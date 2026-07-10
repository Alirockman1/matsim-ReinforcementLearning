package org.matsim.rl.core;

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

import org.matsim.withinday.utils.EditTrips;

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

// Custom classes
import static org.matsim.rl.core.RunExternalModeChoice.REINFORCEMENT_MODE;

import org.matsim.rl.environment.AgentAssetInventory;
import org.matsim.rl.environment.RealTimeScoringEngine;
import org.matsim.rl.environment.StateEngine;
import org.matsim.rl.networking.CommunicationManager;
import org.matsim.rl.utils.IterationEndReportingUtils;
import org.matsim.rl.utils.RLConfigGroup;
import org.matsim.rl.utils.RawBinaryEncoder;

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

        Config config = scenario.getConfig();
        RLConfigGroup rlConfig = ConfigUtils.addOrGetModule(config, RLConfigGroup.class);
        ScoringConfigGroup scoring = config.scoring();

        if (rlConfig != null) {
            Map<String, Object> jsonMap = new HashMap<>();

            // RL Nodel
            jsonMap.put("modelType", rlConfig.getModelType());

            // RL Hyperparameters
            jsonMap.put("alpha", rlConfig.getAlpha());
            jsonMap.put("gamma", rlConfig.getGamma());
            jsonMap.put("epsilon", rlConfig.getEpsilon());
            jsonMap.put("epsilonDecay", rlConfig.getEpsilonDecay());
            jsonMap.put("epsilonMinimum", rlConfig.getEpsilonMinimum());
            jsonMap.put("trainingCutoffIteration", rlConfig.getTrainingCutoffIteration());

            // System Metadata
            String outputDirectory = scenario.getConfig().controller().getOutputDirectory();
            String absoluteOutputDirectory = new java.io.File(outputDirectory).getAbsolutePath();
            jsonMap.put("outputDirectory", absoluteOutputDirectory);
            
            File fullPath = new File(absoluteOutputDirectory, rlConfig.getModelFileName());
            
            jsonMap.put("saveInterval", rlConfig.getSaveInterval());
            jsonMap.put("modelFileName", fullPath.getAbsolutePath());

            // RL Mode Choice
            jsonMap.put("modes", rlConfig.getModes());
            jsonMap.put("tourBasedModes", rlConfig.getTourBasedModes());

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

            //  Initialize the encoder model
            URL context = config.getContext(); 
            String encoderModelPath = rlConfig.getEncoderModel();
            URL absoluteModelUrl = ConfigGroup.getInputFileURL(context, encoderModelPath);

            String finalModelPath;
            try {
                finalModelPath = Paths.get(absoluteModelUrl.toURI()).toAbsolutePath().toString();
            } catch (URISyntaxException e) {
                log.error("Failed to convert model URL to a valid URI path: " + e.getMessage(), e);
                finalModelPath = encoderModelPath;
            }
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
        if (!isAgentForReplanning(agent)){
            return;
        }

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
		Map<String, Object> state = agentObservation(agent, nextTripLeg, sim, simulationTime);
        state.put("simulationIteration", StateEngine.currentIteration);

        // Individual subpopulation
        String agentSubpopulation = (String) scenario.getPopulation().getPersons().get(agentId).getAttributes().getAttribute("subpopulation");

        if (agentSubpopulation == null){
            agentSubpopulation = "null";
        }
        state.put("subpopulation", agentSubpopulation);

        log.info("COMMUNICATION NET: Environment recorded for agent (" + agentId.toString() + ")");

        // POST REQUEST: Send observation through the communication net
        String jsonState = gson.toJson(state);
        String newMode = pythonCommunicationManager.httpPost(jsonState, "get-action", 360);
        
        if (newMode == null){
            // Set default mode incase communication breaks down
            log.error( "COMMUNICATION NET: Default mode is asssigned for the agent ("+ agentId.toString() + ")\n");
            newMode = "pedestrian";
        }

        log.info("RL MODE CHOICE: " + newMode.toUpperCase() + " is asssigned for the agent (" + agentId.toString() + ")");

        this.agentExperiencedModes.computeIfAbsent(agentId, id -> new ArrayList<>()).add(newMode);
            
        // Route next trip
        List<? extends PlanElement> newNextTrip = editTrips.replanFutureTrip(oldNextTrip, WithinDayAgentUtils.getModifiablePlan(agent), newMode, agent.getActivityEndTime());

        if (!sim.getScenario().getConfig().qsim().getMainModes().contains(newMode)) {
            // the new mode is not a main mode, so we don't need to add a vehicle to the simulation.
            return;
        }

        WithinDayAgentUtils.addVehicleToQSimIfNecessary(newNextTrip, scenario, sim);
    }

    private Map<String, Object> agentObservation(MobsimAgent agent, Trip nextTrip, QSim sim, double departureTimeSeconds){

        String agentIdString = agent.getId().toString();

        // Get the raw state parameters from the live environment
        Map<String, Object> rawStateSpaceMap = StateEngine.getRawState(agent, nextTrip, departureTimeSeconds);

        // Discretize the raw state
        Map<String, Object> discreteStateSpaceMap = StateEngine.discretizeRawState(rawStateSpaceMap, 8, "demand_based");

        // Convert to raw binary encoded state representation (Optional)
        String rawBinaryEncodedString = StateEngine.convertToBitStateRepresentation(rawStateSpaceMap, 8, "demand_based");

        //--- Auto Encoded States ---//
        String encodedLatentSpace = rawBinaryEncodedString;  

        // Mode set for the agent
        List<String> availableModes= new ArrayList<>(scenario.getConfig().scoring().getAllModes());

        availableModes.removeIf(mode -> 
            mode.equalsIgnoreCase("ride") || 
            mode.equalsIgnoreCase("other") || 
            mode.equalsIgnoreCase("rl") ||
            mode.equalsIgnoreCase("walk")
        );

        Map<String, Object> observation = new HashMap<>();
        observation.put("agentID", agentIdString);
        observation.put("encodedStateString", rawBinaryEncodedString);
        observation.put("encodedLatentSpace", encodedLatentSpace);
        observation.put("possibleModeSet", availableModes);
        observation.put("rawStateObservation", rawStateSpaceMap);

        return observation;
    }

    private void computeRewardAndNextState(MobsimAgent agent, double currentTime, QSim sim){
        
        // Check if an RL agent is selected
        if (!isAgentForReplanning(agent)){
            return;
        }

        RealTimeScoringEngine rewardCalculator = agentRewardCalculators.computeIfAbsent(agent.getId(), id -> new RealTimeScoringEngine(this.scenario, this.log));

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
            
            // Identify the main mode (ignoring the 'walk' legs usually used for access)
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
        //System.out.println("The previous mode location at " + previousLinkId.toString() + " is: " + ModeUtils.getModeLocation(agentId));

        // --- UPDATING INVENTORY FOR New Location ---
        // Get all modes (including tour based modes - i.e resources)
        Id<Link> currentLinkId = agent.getCurrentLinkId();
        List<String> allModes = Arrays.asList(scenario.getConfig().getModules().get("agentModeChoice").getParams().get("modes").split("\\s*,\\s*"));
        List<String> tourBasedModes = Arrays.asList(scenario.getConfig().getModules().get("agentModeChoice").getParams().get("tourBasedModes").split("\\s*,\\s*")); 

        AgentAssetInventory.updateModeLocation(agentId, currentLinkId, previousLinkId, currentModeUsed, allModes, tourBasedModes, modeDiscontinuityPenaltyMap);

        //System.out.println("The current mode location at " + currentLinkId.toString() + " is: " + ModeUtils.getModeLocation(agentId));

        // --- COMPUTE STEP-WISE REWARD ---
        int currentTripIndex = trips.indexOf(completedTrip);
        boolean isTour = AgentAssetInventory.getIsTourBased(agent.getId());
        double modeRetrievalTime = 0.0;

        if (isTour) {
            modeRetrievalTime = AgentAssetInventory.getModeRetrievalTimes(agent, this.scenario, currentTripIndex, tourBasedModes, this.log);
        }

        rewardCalculator.compute(agent, currentTime, previousActivity, currentModeUsed, completedTrip, modeRetrievalTime, numberOfTransfers, modeDiscontinuityPenaltyMap);
        double currentStepMatsimScore = rewardCalculator.getCurrentStepTripScore();
        double currentStepReward = rewardCalculator.getCurrentStepReward();

        // --- NEXT STATE DATA ---
        Map<String, Object> nextRawObservation;
        String nextBitString;
        List<String> nextEncodedLatentSpace;

        Map<String, Object> nextState = new HashMap<>();

        if (currentTripIndex < trips.size() - 1) {
            Trip nextTripLeg = trips.get(currentTripIndex + 1);

            double nextDepartureTimeSeconds = StateEngine.getPredictedDepartureTime(completedTrip.getDestinationActivity(), currentTime).seconds();

            nextState = agentObservation(agent, nextTripLeg, sim, nextDepartureTimeSeconds);
            nextState.put("isTerminal", false);

            nextRawObservation = (Map<String, Object>) nextState.get("rawStateObservation");
            nextBitString = (String) nextState.get("encodedStateString");
            nextEncodedLatentSpace = (List<String>) nextState.get("encodedLatentSpace");

        }else{
            nextState.put("isTerminal", true);

            nextRawObservation = new HashMap<>();
            nextBitString = "TERMINAL";
            nextEncodedLatentSpace = new ArrayList<>();

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
        jsonMap.put("terminal",nextState.get("isTerminal"));
        jsonMap.put("nextEncodedStateString", nextBitString);
        jsonMap.put("nextRawStateObservation", nextRawObservation);
        jsonMap.put("nextEncodedLatentSpace", nextEncodedLatentSpace);

        if ((boolean) nextState.get("isTerminal")){

            jsonMap.put("accumulativeScore", rewardCalculator.getAccumulatedDayScore());
            jsonMap.put("accumulativeReward", rewardCalculator.getAccumulatedDayReward());
        }

        // Step info
        String jsonStep = new Gson().toJson(jsonMap);

        //log.info("COMMUNICATION NET: Sending Reward to Python: " + jsonStep);

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

    // Method to check if the agent should be up for replanning
    private boolean isAgentForReplanning(MobsimAgent agent){

        // Check if the agent is already stored in the list
        if (this.activeRLAgents.contains(agent.getId())) {
            return true;
        }

        // Check if the new agent should be part of the filter list
        // Exclude PT driver or system agent.
        Person person = this.scenario.getPopulation().getPersons().get(agent.getId());
        if (person == null) return false;

        Integer currentPlanElementIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);
        Plan plan = WithinDayAgentUtils.getModifiablePlan(agent);
        Integer maxPlanElementIndex =plan.getPlanElements().size();
        
        if (currentPlanElementIndex == null || currentPlanElementIndex >=  maxPlanElementIndex - 1) {
            return false;
        }

        try {
            TripStructureUtils.Trip nextTrip = EditTrips.findTripAtPlanElementIndex(agent, currentPlanElementIndex + 1);

            if (nextTrip != null) {
                String mode = TripStructureUtils.identifyMainMode(nextTrip.getTripElements());
                if (REINFORCEMENT_MODE.equals(mode)) {
                    if (!this.activeRLAgents.contains(agent.getId())) {
                        this.activeRLAgents.add(agent.getId());
                        return true;
                    }
                }
            }
        } catch (IndexOutOfBoundsException e) {
            return false;
        }

        return false;
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
