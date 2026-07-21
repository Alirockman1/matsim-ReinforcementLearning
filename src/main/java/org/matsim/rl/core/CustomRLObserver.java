package org.matsim.rl.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.rl.utils.CustomConfigGroup;
import org.matsim.withinday.environment.RealTimeScoringEngine;
import org.matsim.withinday.environment.StateEngine;
import org.matsim.withinday.environment.StateEngine.GridPosition;
import org.matsim.withinday.environment.WithinDayObserver;

import com.google.inject.Inject;

public class CustomRLObserver extends WithinDayObserver {
    private static final NavigableMap<Double, Integer> CUSTOM_TIME_BIN_LOOKUP = new TreeMap<>();
    private final Config config;
    private final String[] tourBasedModes;

    /**
     * Maps high-fidelity continuous clock seconds into discrete temporal partitions.
     * Peak rush hours are allocated tightly, while off-peak intervals are compressed.
     * * @see Stevanovic, A., Stevanovic, J., & Kergaye, C. (2009) "Optimization of 
     * time-of-day traffic signal plans based on dynamic evolution of traffic patterns."
    */
    static {
        CUSTOM_TIME_BIN_LOOKUP.put(4.0, 0); CUSTOM_TIME_BIN_LOOKUP.put(6.0, 1); CUSTOM_TIME_BIN_LOOKUP.put(6.5, 2);
        CUSTOM_TIME_BIN_LOOKUP.put(7.0, 3); CUSTOM_TIME_BIN_LOOKUP.put(7.5, 4); CUSTOM_TIME_BIN_LOOKUP.put(8.0, 5);
        CUSTOM_TIME_BIN_LOOKUP.put(8.5, 6); CUSTOM_TIME_BIN_LOOKUP.put(9.0, 7); CUSTOM_TIME_BIN_LOOKUP.put(10.0, 8);
        CUSTOM_TIME_BIN_LOOKUP.put(12.0, 9); CUSTOM_TIME_BIN_LOOKUP.put(14.0, 10); CUSTOM_TIME_BIN_LOOKUP.put(15.5, 11);
        CUSTOM_TIME_BIN_LOOKUP.put(16.5, 12); CUSTOM_TIME_BIN_LOOKUP.put(17.5, 13); CUSTOM_TIME_BIN_LOOKUP.put(18.5, 14);
        CUSTOM_TIME_BIN_LOOKUP.put(20.0, 15); CUSTOM_TIME_BIN_LOOKUP.put(22.0, 16);
    }

    @Inject private CustomConfigGroup customConfigGroup;
    
    @Inject
    public CustomRLObserver(RealTimeScoringEngine realTimeScoringEngine, Scenario scenario, Logger log) {
        super(realTimeScoringEngine, scenario, log);
        this.config = scenario.getConfig();
        this.tourBasedModes = config.getModules().get("agentModeChoice").getParams().get("tourBasedModes").split("\\s*,\\s*"); 

        StateEngine.setCustomTimeBinLookup(CUSTOM_TIME_BIN_LOOKUP);
    }

    @Override
    public Map<String, Object> observeState(MobsimAgent agent, QSim sim, Trip trip, double timeInSeconds, boolean isNextTripContext) {
        Map<String, Object> observation = new HashMap<>();
        Map<String, Object> rawStateSpace = new HashMap<>();
        int[] rawBitStateSpace;
        double departureTimeSeconds = timeInSeconds;

        Plan executedPlan = WithinDayAgentUtils.getModifiablePlan(agent);
        List<Trip> allTrips = TripStructureUtils.getTrips(executedPlan);
        int targetTripIndex = allTrips.indexOf(trip);

        if (isNextTripContext) {
            targetTripIndex += 1;
            departureTimeSeconds = StateEngine.getPredictedDepartureTime(trip.getDestinationActivity(), timeInSeconds).seconds();
        }

        boolean isEndOfDay = (targetTripIndex >= allTrips.size());

        // Mode set for the agent
        List<String> availableModes = getFilteredAvailableModes();

        // Current activity location
        Activity currentActivity = trip.getOriginActivity();
        Map<String, Object> currentActivityLocation = StateEngine.getActivityLocation(currentActivity);
        double xCurrent = (double) currentActivityLocation.get("x");
        double yCurrent = (double) currentActivityLocation.get("y");

        // Asset spatial positioning
        int assetState = StateEngine.getAssetStateOnHand(agent.getId(), agent.getCurrentLinkId(), tourBasedModes);

        if(!isEndOfDay){

            Trip targetTrip = allTrips.get(targetTripIndex);

            rawStateSpace = buildActiveTripState(targetTrip, xCurrent, yCurrent, departureTimeSeconds, assetState);

            // Discretize continous variables
            int discreteTimeBin = StateEngine.discretizeTimeFromContinous(departureTimeSeconds, "demand_based");
            GridPosition currentDiscretePosition = StateEngine.discretizePositionFromContinuous(xCurrent, yCurrent, 8);
            GridPosition nextDiscretePosition = StateEngine.discretizePositionFromContinuous((double) rawStateSpace.get("scheduledActivityLocationX"), 
                                                                                            (double) rawStateSpace.get("scheduledActivityLocationY"),
                                                                                            8);

            // Bit state representation
            int[] timeBits = StateEngine.convertToBitStateRepresentation(discreteTimeBin,StateEngine.getTimeBinSize("demand_based"));
            int[] assetBits = StateEngine.convertToBitStateRepresentation(assetState,StateEngine.getAssetBinSize());
            int[] currentPositionBits = StateEngine.convertToBitStateRepresentation(currentDiscretePosition.cellIndex(),currentDiscretePosition.gridShape());
            int[] nextPositionBits = StateEngine.convertToBitStateRepresentation(nextDiscretePosition.cellIndex(),nextDiscretePosition.gridShape());

            int totalLength = currentPositionBits.length + nextPositionBits.length + timeBits.length + 1 + assetBits.length;
            IntBuffer stateBuffer = ByteBuffer.allocate(totalLength * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
            stateBuffer.put(currentPositionBits);
            stateBuffer.put(nextPositionBits);
            stateBuffer.put(timeBits);
            stateBuffer.put((int) rawStateSpace.get("scheduledActivityFlexibility"));
            stateBuffer.put(assetBits);

            rawBitStateSpace = stateBuffer.array();

            // Compressed Latent bit state

        }else{

            rawStateSpace = buildTerminalState(xCurrent, yCurrent, assetState);

            // Discretize continous variables
            GridPosition currentDiscretePosition = StateEngine.discretizePositionFromContinuous(xCurrent, yCurrent, 8);

            // Bit state representation
            int[] assetBits = StateEngine.convertToBitStateRepresentation(assetState,StateEngine.getAssetBinSize());
            int[] currentPositionBits = StateEngine.convertToBitStateRepresentation(currentDiscretePosition.cellIndex(),currentDiscretePosition.gridShape());

            int totalLength = currentPositionBits.length + assetBits.length;
            IntBuffer stateBuffer = ByteBuffer.allocate(totalLength * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
            stateBuffer.put(currentPositionBits);
            stateBuffer.put(assetBits);

            rawBitStateSpace = stateBuffer.array();

        }

        observation.put("endOfDayFlag", isEndOfDay);
        observation.put("agentID", agent.getId().toString());
        observation.put("rawBitState", rawBitStateSpace);
        observation.put("possibleModeSet", availableModes);
        observation.put("rawStateObservation", rawStateSpace);
        
        return observation;
    }

    @Override
    protected CustomConfigGroup getCustomConfigGroup() {
        return this.customConfigGroup;
    }

    /**
     * Compiles continuous variables for an active intra-day trip request.
     */
    private Map<String, Object> buildActiveTripState(Trip trip, double xCurrent, double yCurrent, double timeInSeconds, int assetState) {
        
        // Next scheduled activity location
        Map<String, Object> state = new HashMap<>();
        Activity nextActivity = trip.getDestinationActivity();
        Map<String, Object> nextLocation = StateEngine.getActivityLocation(nextActivity);

        state.put("currentActivityLocationX", xCurrent);
        state.put("currentActivityLocationY", yCurrent);
        state.put("scheduledActivityLocationX", nextLocation.get("x"));
        state.put("scheduledActivityLocationY", nextLocation.get("y"));
        state.put("departureTimeSeconds", timeInSeconds);        
        state.put("scheduledActivityFlexibility", isActivityFlexible(nextActivity) ? 1 : 0);
        state.put("assetState", assetState);
        return state;
    }

    /**
     * Compiles continuous variables for terminal end-of-day scenarios.
     */
    private Map<String, Object> buildTerminalState(double xCurrent, double yCurrent, int assetState) {
        Map<String, Object> state = new HashMap<>();
        state.put("currentActivityLocationX", xCurrent);
        state.put("currentActivityLocationY", yCurrent);
        state.put("assetState", assetState);
        return state;
    }

    /**
     * Utility method evaluating plan flexibility properties.
     */
    private boolean isActivityFlexible(Activity activity) {
        String type = activity.getType();
        return type.contentEquals("secondary") || type.contentEquals("leisure") || type.contentEquals("home");
    }

    /**
     * Isolates acceptable alternative option configurations.
     */
    private List<String> getFilteredAvailableModes() {
        List<String> modes = new ArrayList<>(this.config.scoring().getAllModes());
        modes.removeIf(mode -> 
            mode.equalsIgnoreCase("ride") || 
            mode.equalsIgnoreCase("other") || 
            mode.equalsIgnoreCase("rl") ||
            mode.equalsIgnoreCase("walk")
        );
        return modes;
    }


}
