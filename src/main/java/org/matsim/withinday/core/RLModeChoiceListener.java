package org.matsim.withinday.core;

import com.google.gson.Gson;
import com.google.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ScoringParameterSet;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.events.MobsimAfterSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimAfterSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.core.router.TripStructureUtils.StageActivityHandling;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.core.utils.misc.Time;
import org.matsim.withinday.environment.AgentAssetInventory;
import org.matsim.withinday.environment.RealTimeScoringEngine;
import org.matsim.withinday.environment.StateEngine;
import org.matsim.withinday.environment.WithinDayObserver;
import org.matsim.withinday.networking.CommunicationManager;
import org.matsim.withinday.utils.EditTrips;
import org.matsim.withinday.utils.IterationEndReportingUtils;

import org.matsim.rl.utils.CustomConfigGroup;

import static org.matsim.withinday.core.RunExternalModeChoice.REINFORCEMENT_MODE;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RLModeChoiceListener implements StartupListener, IterationStartsListener, IterationEndsListener, MobsimBeforeSimStepListener, MobsimAfterSimStepListener, ActivityStartEventHandler {
    private static final Logger log = LogManager.getLogger(RLModeChoiceListener.class);
    private final List<Id<Person>> activeRLAgents = new ArrayList<>();
    private final Map<Id<Person>, RealTimeScoringEngine> agentRewardCalculators = new HashMap<>();
    private final Map<Id<Person>, List<String>> agentExperiencedModes = new HashMap<>();
    private final Map<Id<Person>, Double> agentAccumulatedDeltaQ = new HashMap<>();

    @Inject
    TripRouter router;

    @Inject
    Scenario scenario;

    @Inject
    TimeInterpretation timeInterpretation;

    @Inject
    TravelTime travelTime;

    @Inject
    CommunicationManager pythonCommunicationManager;

    @Inject
    WithinDayObserver customRLObserver;

    @Inject
    CustomConfigGroup customConfigGroup;

    private AgentSelector agentSelector;
    private EditTrips editTrips;
    private Gson gson = new Gson();

    // bookkeeping for the activity start time of each agent, so that we can identify the agents that started their activity in the current step.
    private Map<Id<Person>, Double> actStartTimeByAgent = new HashMap<>();

    @Override
    public double priority() {
        return 10.0; 
    }

    // initialize the custom RL module in the config File
    @SuppressWarnings("null")
    @Override
    public void notifyStartup(StartupEvent event) {

        this.agentSelector = new AgentSelector(scenario, 42L, log);

        Config config = scenario.getConfig();
        ScoringConfigGroup scoring = config.scoring();

        // Move these to CustomRLReplanner.initiate()
        if (this.customConfigGroup != null) {
            Map<String, Object> jsonMap = new HashMap<>();

            // RL Nodel
            jsonMap.put("modelType", customConfigGroup.getModelType());

            // RL Hyperparameters
            jsonMap.put("alpha", customConfigGroup.getAlpha());
            jsonMap.put("gamma", customConfigGroup.getGamma());
            jsonMap.put("epsilon", customConfigGroup.getEpsilon());
            jsonMap.put("epsilonDecay", customConfigGroup.getEpsilonDecay());
            jsonMap.put("epsilonMinimum", customConfigGroup.getEpsilonMinimum());
            jsonMap.put("trainingCutoffIteration", customConfigGroup.getTrainingCutoffIteration());

            // System Metadata
            String outputDirectory = scenario.getConfig().controller().getOutputDirectory();
            String absoluteOutputDirectory = new File(outputDirectory).getAbsolutePath();
            jsonMap.put("outputDirectory", absoluteOutputDirectory);
            
            File fullPath = new File(absoluteOutputDirectory, customConfigGroup.getModelFileName());
            
            jsonMap.put("saveInterval", customConfigGroup.getSaveInterval());
            jsonMap.put("modelFileName", fullPath.getAbsolutePath());

            // RL Mode Choice
            jsonMap.put("modes", customConfigGroup.getModes());
            jsonMap.put("tourBasedModes", customConfigGroup.getTourBasedModes());

            // Global Scoring Parameters for subpopulation (The Core of Nagel Scoring)
            Map<String, Object> scoringParameterAllPopulation = new HashMap<>();

            for (Map.Entry<String, ScoringParameterSet> entry : scoring.getScoringParametersPerSubpopulation().entrySet()) {
                String subpopulationName = entry.getKey();
                ScoringParameterSet scoringParameterSet = entry.getValue();

                // We still build a custom map per subpopulation to avoid GSON circularity errors
                Map<String, Object> populationScoringParameterSet = new HashMap<>();
                
                // Global parameters
                populationScoringParameterSet.put("marginalUtilityOfPerforming_s", scoringParameterSet.getPerforming_utils_hr()/3600);
                populationScoringParameterSet.put("marginalUtilityOfMoney", scoringParameterSet.getMarginalUtilityOfMoney());
                populationScoringParameterSet.put("utilityOfLineSwitch", scoringParameterSet.getUtilityOfLineSwitch());
                populationScoringParameterSet.put("marginalUtlOfWaiting_s", scoringParameterSet.getMarginalUtlOfWaiting_utils_hr()/3600);

                // Mode related parameter
                for (ModeParams modeParameter : scoringParameterSet.getModes().values()) {
                    populationScoringParameterSet.put(modeParameter.getMode(), modeParameter.getParams());
                }

                scoringParameterAllPopulation.put(subpopulationName, populationScoringParameterSet);
            }

            // Now add it to your main JSON
            jsonMap.put("scoringParameters", scoringParameterAllPopulation);

            // Default velocity
            jsonMap.put("defaultTeleportedSpeed", scenario.getConfig().routing().getTeleportedModeSpeeds().get("walk"));

            String jsonString = gson.toJson(jsonMap);

            String reset = "initialize";

            // Send via Connection Manager
            pythonCommunicationManager.httpPost(jsonString, reset, 30);

            // Find the network centeroid
            StateEngine.setNetworkCentroid(scenario.getNetwork());

            /*//  Initialize the encoder model
            URL context = config.getContext(); 
            String encoderModelPath = customConfigGroup.getEncoderModel();
            URL absoluteModelUrl = ConfigGroup.getInputFileURL(context, encoderModelPath);

            String finalModelPath;
            try {
                finalModelPath = Paths.get(absoluteModelUrl.toURI()).toAbsolutePath().toString();
            } catch (URISyntaxException e) {
                log.error("Failed to convert model URL to a valid URI path: " + e.getMessage(), e);
                finalModelPath = encoderModelPath;
            }*/
        }
    }

    // Iteration start listener
    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        StateEngine.currentIteration = event.getIteration();

        // Reset the environment
        AgentAssetInventory.reset();
        this.activeRLAgents.clear();
        this.agentRewardCalculators.clear();
        this.agentExperiencedModes.clear();
        this.agentAccumulatedDeltaQ.clear();

        // Sample agents from the population
        double samplingPercentage = this.customConfigGroup.getSamplingPercentage();

        Collection<String> fixedAgentIds = new ArrayList<>();
        if (this.customConfigGroup!= null && this.customConfigGroup.getAgentFilterList() != null) {
            fixedAgentIds = Arrays.asList(this.customConfigGroup.getAgentFilterList().split("\\s*,\\s*"));
        }

        this.agentSelector.sampleAgentsForIteration(event.getIteration(), samplingPercentage, fixedAgentIds);
        
        // Initialize the tour based modes
        String[] tourBasedModes = scenario.getConfig().getModules().get("agentModeChoice").getParams().get("tourBasedModes").split("\\s*,\\s*");
        StateEngine.setModeAvailabilityLookup(tourBasedModes);

        // Start tagging each agents mode geo location [CHANGE TO ONLY THE FILTED AGENTS]
        log.info("Initializing Mode Location Tagging for all RL agents at start of iteration {}", event.getIteration());
        AgentAssetInventory.initializeModeLocationTagging(this.scenario, this.log);
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event){
        IterationEndReportingUtils.writeAgentStatsCsv(event, this.agentRewardCalculators, this.agentExperiencedModes, this.agentAccumulatedDeltaQ);

        // Get the q_table for the agent
        try {
            String qTable = pythonCommunicationManager.httpGet("get-qtable", 360);
        }catch (Exception e) {
            log.error("COMMUNICATION NET: Failed retriveing Q-Table. " + e.getMessage());
        }
    }

    @Override
    public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent e) {
        initEditTrips();

        // Pick all agents that end their activity
        QSim sim = (QSim) e.getQueueSimulation();
        sim.getAgents().values().stream()
                // filter all agents who are performing an activity and whose activity is going to end in this time step.
                .filter(p -> p.getState() == MobsimAgent.State.ACTIVITY)
                .filter(p -> p.getActivityEndTime() == e.getSimulationTime())
                .forEach(p -> {
                    replanNextTrip(p, sim, e.getSimulationTime());
                });
    }

    @Override
    public void notifyMobsimAfterSimStep(MobsimAfterSimStepEvent e) {
        initEditTrips();

        // Pick all agents that started their activity in the current step
        QSim sim = (QSim) e.getQueueSimulation();
        sim.getAgents().values().stream()
                // filter all agents who are performing an activity and whose activity was started in this time step.
                // interaction activities are automatically filtered out, because act start, act end and leg start happen
                // in the same time step => the state of such an agent is LEG after the sim step.
                .filter(p -> p.getState() == MobsimAgent.State.ACTIVITY)
                .filter(p -> actStartTimeByAgent.containsKey(p.getId()))
                .filter(p -> actStartTimeByAgent.get(p.getId()) == e.getSimulationTime())
                .forEach(p -> {
                    computeRewardAndNextState(p, e.getSimulationTime(), sim);
                    rescheduleActivityEnd(p, sim, e.getSimulationTime());
                });
    }

    @Override
    public void handleEvent(ActivityStartEvent event) {
        actStartTimeByAgent.put(event.getPersonId(), event.getTime());
    }

    @Override
    public void reset(int iteration) {
        actStartTimeByAgent.clear();
    }

    private void replanNextTrip(MobsimAgent agent, QSim sim, double simulationTime) {

        // Check if an RL agent is selected
        if (!this.agentSelector.contains(agent.getId())) return;

        if (!(WithinDayAgentUtils.getCurrentPlanElement(agent) instanceof Activity)) {
            throw new RuntimeException("For replanning the next trip, we expect the current plan element to be an activity, but it is not. Agent: " + agent.getId());
        }

        Integer currentPlanElementIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);
        TripStructureUtils.Trip oldNextTrip = EditTrips.findTripAtPlanElementIndex(agent, currentPlanElementIndex + 1);

        log.info("Replaning next trip for agent {} at time {}", agent.getId(), agent.getActivityEndTime());

        // Get next trip from current activity
        Activity currentActivity = (Activity) WithinDayAgentUtils.getCurrentPlanElement(agent);
        Trip nextTripLeg = TripStructureUtils.findTripStartingAtActivity( currentActivity, WithinDayAgentUtils.getModifiablePlan(agent) );

        // End the modifier is there is no trip after current activity. -> Might not be needed
		if (nextTripLeg == null) return;

		// Get the AGENT ID
    	Id<Person> agentId = agent.getId();       

        // Get observation of the state
		Map<String, Object> state = this.customRLObserver.observeState(agent, sim, nextTripLeg, simulationTime, false);
        System.out.println("The state at activity end event is:" + state);
        state.put("simulationIteration", StateEngine.currentIteration);

        // Individual subpopulation
        Map<String, Object> demographics = this.customRLObserver.getAgentDemographicRecord(agent);
        state.put("subpopulation", demographics.getOrDefault("subpopulation", "default"));

        // Transfer package
        log.info("COMMUNICATION NET: Environment recorded for agent (" + agentId.toString() + ")");
        String jsonState = gson.toJson(state);
        String newMode = pythonCommunicationManager.httpPost(jsonState, "get-action", 360);
        System.out.println("The mode assigned by the backend algorithm is: " + newMode);
        
        if (newMode == null){
            // Set default mode incase communication breaks down
            log.error( "COMMUNICATION NET: Default mode is asssigned for the agent ("+ agentId.toString() + ")\n");
            newMode = "pedestrian";
        }

        log.info("RL MODE CHOICE: " + newMode.toUpperCase() + " is asssigned for the agent (" + agentId.toString() + ")");

        // SHIFT TO THE PLANNER
        this.agentExperiencedModes.computeIfAbsent(agentId, id -> new ArrayList<>()).add(newMode);
            
        // Route next trip
        List<? extends PlanElement> newNextTrip = editTrips.replanFutureTrip(oldNextTrip, WithinDayAgentUtils.getModifiablePlan(agent), newMode, agent.getActivityEndTime());

        // the new mode is not a main mode, so we don't need to add a vehicle to the simulation.
        if (!sim.getScenario().getConfig().qsim().getMainModes().contains(newMode)) return;

        WithinDayAgentUtils.addVehicleToQSimIfNecessary(newNextTrip, scenario, sim);
    }

    private void computeRewardAndNextState(MobsimAgent agent, double simulationTime, QSim sim){
        
        // Check if an RL agent is selected
        if (!this.agentSelector.contains(agent.getId())) return;

        // Get the agent which reached the activity destination
        Id<Person> agentId = agent.getId();

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

        RealTimeScoringEngine rewardCalculator = this.customRLObserver.tripEvaluationMetrics(
            agent, simulationTime, previousActivity, currentModeUsed, completedTrip, modeRetrievalTime, numberOfTransfers, modeDiscontinuityPenaltyMap
        );

        double currentStepMatsimScore = rewardCalculator.getCurrentStepTripScore();
        double currentStepReward = rewardCalculator.getCurrentStepReward();

        // --- NEXT STATE DATA ---
        Map<String, Object> nextState = this.customRLObserver.observeState(agent, sim, completedTrip, simulationTime, true);

        if ((boolean) nextState.get("endOfDayFlag")){
            rewardCalculator.computeDayEndScore(agent, trips, completedTrip.getDestinationActivity());
            log.info("The end of the day score for " + agentId.toString() + " is: " + rewardCalculator.getAccumulatedDayScore());
        }

        // REWARD MAP
        Map<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("agentID", agentId.toString());
        jsonMap.put("travelTimeSeconds", totalTripTravelTime);
        jsonMap.put("numberOfTransfers", numberOfTransfers);
        jsonMap.put("distance", totalTripDistance);
        jsonMap.put("reward", currentStepReward);
        jsonMap.put("matsimScore", currentStepMatsimScore);

        // NEXT STATE MAP
        jsonMap.put("isTerminal", (boolean) nextState.get("endOfDayFlag"));
        jsonMap.put("nextRawBitStateRepresentation", nextState.get("rawBitState"));
        //jsonMap.put("nextEncodedLatentSpace", nextEncodedLatentSpace);

        if ((boolean) nextState.get("endOfDayFlag")){
            jsonMap.put("accumulativeScore", rewardCalculator.getAccumulatedDayScore());
            jsonMap.put("accumulativeReward", rewardCalculator.getAccumulatedDayReward());
        }

        // Step info
        String jsonStep = new Gson().toJson(jsonMap);

        log.info("COMMUNICATION NET: Sending Reward to Python: " + jsonStep);

        // SEND DATA TO PYTHON //
        String stepResponse = pythonCommunicationManager.httpPost(jsonStep, "send-reward", 360);

        System.out.println(stepResponse);

        if (stepResponse != null && !stepResponse.isEmpty()) {
            try {
                Map<String, Object> responseMap = gson.fromJson(stepResponse, HashMap.class);
                
                if (responseMap.containsKey("delta_q")) {
                    double stepDeltaQ = ((Number) responseMap.get("delta_q")).doubleValue();
                    
                    this.agentAccumulatedDeltaQ.merge(agentId, stepDeltaQ, Double::sum);
                }
            } catch (Exception ex) {
                log.error("Failed to parse delta_q metrics response for agent " + agentId + ": " + ex.getMessage());
            }
        }
    }
    
    // Method to adjust the activity end time if needed.
    private void rescheduleActivityEnd(MobsimAgent agent, QSim sim, double now) {
        Activity currentActivity = (Activity) WithinDayAgentUtils.getCurrentPlanElement(agent); // might be used in the RL algorithm to decide about the new end time.
        double newEndTime = 0.;  // calculated by your RL algorithm
        //EditPlans.rescheduleCurrentActivityEndtime(agent, newEndTime, sim);
        return;
    }

    private void initEditTrips() {
        // lazy instantiation of EditTrips
        if (editTrips == null) {
            // internalInterface is null on purpose. This is only needed if current legs are replanned. But we are replacing future trips only (i.e. before activity ends).
            editTrips = new EditTrips(router, scenario, null, timeInterpretation);
        }
    }

}
