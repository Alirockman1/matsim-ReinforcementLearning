package org.matsim.project.rl.core;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.withinday.core.WithinDayReplanner;
import org.matsim.withinday.environment.AgentAssetInventory;
import org.matsim.withinday.environment.RealTimeScoringEngine;
import org.matsim.withinday.environment.StateEngine;
import org.matsim.withinday.environment.WithinDayObserver;
import org.matsim.withinday.networking.CommunicationManager;
import org.matsim.withinday.utils.WithinDayAgentExperience;

import com.google.gson.Gson;
import com.google.inject.Inject;

import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.project.rl.utils.CustomConfigGroup;
import org.matsim.project.rl.utils.CustomIterationEndReporting;

/**
 * Reinforcement Learning extension of WithinDayReplanner.
 * Overrides step() to compute step rewards, record experiences, and handle /feedback/score updates with Python.
 */
public class CustomRLReplanner extends WithinDayReplanner {

    protected final Map<Id<Person>, WithinDayAgentExperience> agentExperiences = new HashMap<>();
    protected final CustomConfigGroup customConfigGroup;

    /**
     * Constructor for CustomRLReplanner.
     *
     * @param scenario                   The active MATSim simulation scenario.
     * @param router                     Trip router utility used by EditTrips.
     * @param timeInterpretation         Time interpretation rules for routing.
     * @param customObserver             Observer for extracting environment states and trip scores.
     * @param pythonCommunicationManager HTTP communication manager for external decision models.
     */
    @Inject
    public CustomRLReplanner(Scenario scenario, TripRouter router, TimeInterpretation timeInterpretation,
                              WithinDayObserver customObserver, CommunicationManager pythonCommunicationManager, CustomConfigGroup customConfigGroup) {
        super(scenario, router,timeInterpretation, customObserver, pythonCommunicationManager);
        this.customConfigGroup = customConfigGroup;
    }

    @Override
    public void initializeModel() {
        if (this.customConfigGroup == null) return;

        // System Metadata
        String outputDirectory = scenario.getConfig().controller().getOutputDirectory();
        String absoluteOutputDirectory = new File(outputDirectory).getAbsolutePath();
        File fullPath = new File(absoluteOutputDirectory, customConfigGroup.getModelFileName());

        Map<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("modelType", customConfigGroup.getModelType());
        jsonMap.put("alpha", customConfigGroup.getAlpha());
        jsonMap.put("gamma", customConfigGroup.getGamma());
        jsonMap.put("epsilon", customConfigGroup.getEpsilon());
        jsonMap.put("epsilonDecay", customConfigGroup.getEpsilonDecay());
        jsonMap.put("epsilonMinimum", customConfigGroup.getEpsilonMinimum());
        jsonMap.put("trainingCutoffIteration", customConfigGroup.getTrainingCutoffIteration());
        jsonMap.put("outputDirectory", scenario.getConfig().controller().getOutputDirectory());
        jsonMap.put("modes", customConfigGroup.getModes());
        jsonMap.put("tourBasedModes", customConfigGroup.getTourBasedModes());
        jsonMap.put("outputDirectory", absoluteOutputDirectory);
        jsonMap.put("saveInterval", customConfigGroup.getSaveInterval());
        jsonMap.put("modelFileName", fullPath.getAbsolutePath());

        communicationManager.httpPost(gson.toJson(jsonMap), "/session/configure", 30);

        // Find the network centeroid
        StateEngine.setNetworkCentroid(scenario.getNetwork());
    }

    /**
     * Overridden step method calculating post-trip MATSim scores, step rewards, and posting feedback to Python.
     *
     * @param agent          The MATSim agent completing the trip.
     * @param sim            The active queue simulation instance.
     * @param completedTrip  The completed trip leg structure.
     * @param simulationTime Current simulation timestamp in seconds.
     * @return Raw JSON response string from the Python feedback endpoint.
     */
    @Override
    public void step(MobsimAgent agent, QSim sim, double simulationTime, boolean rescheduleActivityEndTime) {
        Map<String, Object> demographics = this.customObserver.getAgentDemographicRecord(agent);
        Id<Person> agentId = (Id<Person>) demographics.get("agentId");

        // The live plan and corresponding trips
        Plan executedPlan = WithinDayAgentUtils.getModifiablePlan(agent);
        List<Trip> trips = TripStructureUtils.getTrips(executedPlan);

        Trip completedTrip = null;
        for (Trip trip : trips) {
            // Match the trip whose destination is the current activity the agent just started
            if (trip.getDestinationActivity().equals(WithinDayAgentUtils.getCurrentPlanElement(agent))) {
                completedTrip = trip;
                break;
            }
        }

        if (completedTrip == null) return;

        // --- REWARD PARAMETER COMPUTATION ---
        double totalTripDistance = 0;
        double totalTripTravelTime = 0;
        int mainModeLegCount = 0;
        String currentModeUsed = "unknown";
        double stepDeltaQ = 0.0;
        Activity previousActivity = completedTrip.getOriginActivity();;       
            
        // Trip matrics (Penalty for traveling)
        List<Leg> legsInTrip = completedTrip.getLegsOnly();

        for (Leg leg : legsInTrip) {
            totalTripDistance += leg.getRoute().getDistance();
            totalTripTravelTime += leg.getTravelTime().orElse(0.0);
            
            if (!leg.getMode().contains("walk")) {
                mainModeLegCount++;
                currentModeUsed = leg.getMode();
            }
        }

        int numberOfTransfers = Math.max(0, mainModeLegCount - 1);

        // Spatial penalty (check if mode is available at location)
        Id<Link> previousLinkId = previousActivity.getLinkId();
        Map<String, Integer> modeDiscontinuityPenaltyMap = AgentAssetInventory.getModeDiscontinuityPenalty(agentId, previousLinkId);

        //Map<String, Id<Link>> previousInventorySnapshot = new HashMap<>(ModeUtils.getModeLocation(agentId));
        System.out.println("The previous mode location at " + previousLinkId.toString() + " is: " + AgentAssetInventory.getModeLocation(agentId));

        // --- UPDATING INVENTORY FOR New Location ---
        // Get all modes (including tour based modes - i.e resources)
        Id<Link> currentLinkId = agent.getCurrentLinkId();
        List<String> allModes = Arrays.asList(scenario.getConfig().getModules().get("agentModeChoice").getParams().get("modes").split("\\s*,\\s*"));
        List<String> tourBasedModes = Arrays.asList(scenario.getConfig().getModules().get("agentModeChoice").getParams().get("tourBasedModes").split("\\s*,\\s*")); 

        AgentAssetInventory.updateModeLocation(agentId, currentLinkId, previousLinkId, currentModeUsed, allModes, tourBasedModes, modeDiscontinuityPenaltyMap);

        System.out.println("The current mode location at " + currentLinkId.toString() + " is: " + AgentAssetInventory.getModeLocation(agentId));

        // --- COMPUTE STEP-WISE REWARD ---
        int currentTripIndex = trips.indexOf(completedTrip);
        boolean isTour = AgentAssetInventory.getIsTourBased(agent.getId());
        double modeRetrievalTime = 0.0;

        if (isTour) {
            modeRetrievalTime = AgentAssetInventory.getModeRetrievalTimes(agent, this.scenario, currentTripIndex, tourBasedModes, this.log);
        }

        RealTimeScoringEngine rewardCalculator = this.customObserver.tripEvaluationMetrics(
            agent, simulationTime, previousActivity, currentModeUsed, completedTrip, modeRetrievalTime, numberOfTransfers, modeDiscontinuityPenaltyMap
        );

        WithinDayAgentExperience experience = this.agentExperiences.computeIfAbsent(
            agentId, 
            id -> new WithinDayAgentExperience(id, this.customObserver.getOrCreateScoringEngine(id))
        );

        double currentStepMatsimScore = rewardCalculator.getCurrentStepTripScore();
        double currentStepReward = rewardCalculator.getCurrentStepReward();

        // --- NEXT STATE DATA ---
        Map<String, Object> nextState = this.customObserver.observeState(agent, sim, completedTrip, simulationTime, true);
        
        if ((boolean) nextState.get("endOfDayFlag")){
            rewardCalculator.computeDayEndScore(agent, trips, completedTrip.getDestinationActivity());
            log.info("The end of the day score for " + agentId.toString() + " is: " + rewardCalculator.getAccumulatedDayScore());

            experience.finalizeDay(
                rewardCalculator.getAccumulatedDayReward(), 
                rewardCalculator.getAccumulatedDayScore()
            );
        }

        // REWARD MAP
        Map<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("agentID", agentId.toString());
        jsonMap.put("travelTimeSeconds", totalTripTravelTime);
        jsonMap.put("numberOfTransfers", numberOfTransfers);
        jsonMap.put("distance", totalTripDistance);
        jsonMap.put("reward", currentStepReward);
        jsonMap.put("matsimScore", currentStepMatsimScore);
        jsonMap.put("isTerminal", (boolean) nextState.get("endOfDayFlag"));
        jsonMap.put("nextRawBitStateRepresentation", nextState.get("rawBitStateRepresentation"));

        if ((boolean) nextState.get("endOfDayFlag")){
            jsonMap.put("accumulativeScore", rewardCalculator.getAccumulatedDayScore());
            jsonMap.put("accumulativeReward", rewardCalculator.getAccumulatedDayReward());
        }

        // Step info
        String jsonStep = new Gson().toJson(jsonMap);

        String response = communicationManager.httpPost(jsonStep, "/feedback/score", 360);

        if (response != null && !response.isEmpty()) {
            try {
                Map<String, Object> responseMap = gson.fromJson(response, HashMap.class);
                
                if (responseMap != null && responseMap.containsKey("delta_q")) {
                    stepDeltaQ = ((Number) responseMap.get("delta_q")).doubleValue();
                } else if (responseMap != null && responseMap.containsKey("deltaQ")) { 
                    // Fallback check in case key is camelCase
                    stepDeltaQ = ((Number) responseMap.get("deltaQ")).doubleValue();
                } else {
                    log.warn("COMMUNICATION NET: 'delta_q' missing in response for agent " + agentId + ". Response was: " + response);
                }
            } catch (Exception ex) {
                log.error("Failed to parse delta_q metrics response for agent " + agentId + ": " + ex.getMessage());
                stepDeltaQ = -1.0;
            }
        } else {
            log.error("COMMUNICATION NET: Null or empty response received from /feedback/score for agent " + agentId);
        }

        // ALWAYS record the trip step so experiencedModes, rewards, scores, and deltaQ stay synchronized!
        experience.recordTrip(currentModeUsed, currentStepReward, currentStepMatsimScore, stepDeltaQ);
    }

    @Override
    public void reset(IterationEndsEvent event) {
        int iteration = event.getIteration();

        // Write output for each iteration in a csv file
        if (this.agentExperiences != null && !this.agentExperiences.isEmpty()) {
            CustomIterationEndReporting.writeAgentStatsCsv(event, this.agentExperiences);
        }
        super.reset(event);
        this.agentExperiences.clear();
        AgentAssetInventory.reset();
        log.info("RL PLANNER: Agent experiences cleared for iteration {}", iteration);
    }

    @Override
    public Map<Id<Person>, WithinDayAgentExperience> getAgentExperiences() {
        return this.agentExperiences;
    }
}
