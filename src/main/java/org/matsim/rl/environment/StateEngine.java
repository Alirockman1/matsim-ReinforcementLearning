package org.matsim.rl.environment;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.misc.OptionalTime;



public class StateEngine{
    private static final NavigableMap<Double, Integer> TIME_BIN_LOOKUP = new TreeMap<>();
    private static final Map<String, Integer> MODE_AVAILABILITY_MAP = new HashMap<>();
    private static Coord networkCentroid;
    private static double[] environmentMap;
    private static Double maxRadius;
    
    public static int currentIteration = 0;

    /**
     * Maps high-fidelity continuous clock seconds into discrete temporal partitions.
     * Peak rush hours are allocated tightly, while off-peak intervals are compressed.
     * * @see Stevanovic, A., Stevanovic, J., & Kergaye, C. (2009) "Optimization of 
     * time-of-day traffic signal plans based on dynamic evolution of traffic patterns."
    */
    static {
        TIME_BIN_LOOKUP.put(4.0, 0); TIME_BIN_LOOKUP.put(6.0, 1); TIME_BIN_LOOKUP.put(6.5, 2);
        TIME_BIN_LOOKUP.put(7.0, 3); TIME_BIN_LOOKUP.put(7.5, 4); TIME_BIN_LOOKUP.put(8.0, 5);
        TIME_BIN_LOOKUP.put(8.5, 6); TIME_BIN_LOOKUP.put(9.0, 7); TIME_BIN_LOOKUP.put(10.0, 8);
        TIME_BIN_LOOKUP.put(12.0, 9); TIME_BIN_LOOKUP.put(14.0, 10); TIME_BIN_LOOKUP.put(15.5, 11);
        TIME_BIN_LOOKUP.put(16.5, 12); TIME_BIN_LOOKUP.put(17.5, 13); TIME_BIN_LOOKUP.put(18.5, 14);
        TIME_BIN_LOOKUP.put(20.0, 15); TIME_BIN_LOOKUP.put(22.0, 16);
    }

    /**
     * Maps available mode to integer reference values.
    */
    static {
        MODE_AVAILABILITY_MAP.put("none", 0);
        MODE_AVAILABILITY_MAP.put("car", 1);
        MODE_AVAILABILITY_MAP.put("bike", 2);
        MODE_AVAILABILITY_MAP.put("car&bike", 3);
    }

    // SETTER: This method computes the centroid of the network
    public static void setNetworkCentroid(Network network) {
        double[] box = NetworkUtils.getBoundingBox(network.getNodes().values());
        double centerX = (box[0] + box[2]) / 2.0;
        double centerY = (box[1] + box[3]) / 2.0;

        // Centeroid of the graph
        networkCentroid = CoordUtils.createCoord(centerX, centerY);

        // Edge of the graph
        Coord corner = CoordUtils.createCoord(box[0], box[1]);
        maxRadius = CoordUtils.calcEuclideanDistance(networkCentroid, corner);
        environmentMap = box;

    }

    public static Map<String, Object> getRawState(MobsimAgent agent, Trip nextTrip, double departureTimeSeconds) {
        Map<String, Object> rawState = extractRawState(agent, nextTrip, departureTimeSeconds);
        return rawState;
    }

    public static Map<String, Object> discretizeRawState(Map<String, Object> rawState, int grid, String timeDiscretizationMethod) {
        Map<String, Object> discretizeState = discretize(rawState, grid, timeDiscretizationMethod);
        return discretizeState;
    }

    public static String convertToBitStateRepresentation(Map<String, Object> rawState, int grid, String timeDiscretizationMethod){
        Map<String, Object> discretizeState = discretize(rawState, grid, timeDiscretizationMethod);
        String bitStateRepresenation = castDiscreteNumericalToBinarString(discretizeState, grid, timeDiscretizationMethod);

        return bitStateRepresenation;
    }

    public static int getTimeBinSize(String method) {
        switch (method.toLowerCase()) {
            case "demand_based": return TIME_BIN_LOOKUP.size() + 1;
            case "hourly":  return 86400 / 3600;
            case "half_hourly": return 86400 / 1800;
            case "quarter_hourly":  return 86400 / 900;
            default:
                throw new IllegalArgumentException(
                    "Unknown time discretization method: " + method);
        }
    }

    public static int getAssetBinSize(){
        return MODE_AVAILABILITY_MAP.size();
    }

    // GETTER: This method returns the predicted departure time
    public static OptionalTime getPredictedDepartureTime(Activity activity, double arrivalTime){
    
        if (activity.getEndTime().isDefined()) {
            return activity.getEndTime();
        }
        
        if (activity.getMaximumDuration().isDefined()) {
            double maxDuration = activity.getMaximumDuration().seconds();
            return OptionalTime.defined(arrivalTime + maxDuration);
        }

        return OptionalTime.defined(0);
    }


    private static Map<String, Object> extractRawState(MobsimAgent agent, Trip trip, double departureTimeSeconds){

        Map<String, Object> rawObservation = new HashMap<>();
        
        Activity nextActivity = trip.getDestinationActivity();
        Activity currentActivity = trip.getOriginActivity();

        // Departure and Arrival Locations
        Map<String, Object> currentActivityLocation = StateEngine.getActivityLocation(currentActivity);
        Map<String, Object> nextActivityLocation = StateEngine.getActivityLocation(nextActivity);

        double xCurrent = (double) currentActivityLocation.get("x");
        double yCurrent = (double) currentActivityLocation.get("y");
        double xNext = (double) nextActivityLocation.get("x");
        double yNext = (double) nextActivityLocation.get("y");

        // Schedule flexibity -> Does the agent need to abide by a strict plan
        String nextActivityType = nextActivity.getType();

        // Asset spatial positioning
        String assetState = StateEngine.getAssetStateOnHand(agent.getId(), agent.getCurrentLinkId());

        rawObservation.put("departureLocationX", xCurrent);
        rawObservation.put("departureLocationY", yCurrent);
        rawObservation.put("arrivalLocationX", xNext);
        rawObservation.put("arrivalLocationY", yNext);
        rawObservation.put("departureTimeSeconds", departureTimeSeconds);        
        rawObservation.put("targetActivityType", nextActivityType);
        rawObservation.put("assetState", assetState);

        return rawObservation;
    }

    private static Map<String, Object> getActivityLocation(Activity activity){

        Map<String, Object> location = new HashMap<>();
        
        Coord currentActivityLocationCoordinates = activity.getCoord();
        double xPosition = currentActivityLocationCoordinates.getX();
        double yPosition = currentActivityLocationCoordinates.getY();

        location.put("x", xPosition);
        location.put("y", yPosition);
        location.put("environmentBounds", environmentMap);
        location.put("origin", networkCentroid);

        return location;
    }

    private static String getAssetStateOnHand(Id<Person> agentId, Id<Link> currentLinkId) {
        
        Map<String, Integer> penaltyMap = AgentAssetInventory.getModeDiscontinuityPenalty(agentId, currentLinkId);

        boolean carIsOnHand = penaltyMap.getOrDefault("car", 1) == 0;
        boolean bikeIsOnHand = penaltyMap.getOrDefault("bike", 1) == 0;

        if (carIsOnHand && bikeIsOnHand) {
            return "car&bike"; 
        } else if (carIsOnHand) {
            return "car"; 
        } else if (bikeIsOnHand) {
            return "bike"; 
        } else {
            return "none"; 
        }
    }

    private static Map<String, Object> discretize(Map<String, Object> rawState, int gridSize, String timeDiscretizationMethod){
        
        Map<String, Object> discretizedObservation = new HashMap<>();

        // Grid Cells for Departure and Arrival
        GridPosition departurePosition = getGridCell((Double) rawState.get("departureLocationX"), (Double) rawState.get("departureLocationY"), gridSize);
        discretizedObservation.put("departureRow", departurePosition.row());
        discretizedObservation.put("departureColumn", departurePosition.column());

        GridPosition arrivalPosition = getGridCell((Double) rawState.get("arrivalLocationX"), (Double) rawState.get("arrivalLocationY"), gridSize);
        discretizedObservation.put("arrivalRow", arrivalPosition.row());
        discretizedObservation.put("arrivalColumn", arrivalPosition.column());       

        // discretize time based on the method choosen -> uniform or non-uniform
        double timeInSeconds = (double) rawState.get("departureTimeSeconds");
        discretizedObservation.put("departureTimeBin", discretizeContinousTimeInSeconds(timeInSeconds, timeDiscretizationMethod));

        // scheduleFlexibility
        String nextActivity = ((String) rawState.get("targetActivityType"));
        boolean flexibility;
        if(nextActivity.contentEquals("secondary") || nextActivity.contentEquals("leisure") || nextActivity.contentEquals("home")){
            flexibility = true;
        }else{
            flexibility = false;
        }
        discretizedObservation.put("targetActivityIsFlexible", flexibility);

        // asset on hand
        int availableMode = castModeAvailabilityToInteger((String) rawState.get("assetState"));
        discretizedObservation.put("assetOnHandInteger", availableMode);

        return discretizedObservation;
    }

    private static GridPosition getGridCell(double xCoordinate, double yCoordinate, int gridSize) {
        double width = environmentMap[2] - environmentMap[0];
        double length = environmentMap[3] - environmentMap[1];

        double normalizeX = (xCoordinate - environmentMap[0]) / width;
        double normalizeY = (yCoordinate - environmentMap[1]) / length;
        
        int gridColumn = Math.max(0, Math.min(gridSize - 1, (int) (normalizeX * gridSize)));
        int gridRow = Math.max(0, Math.min(gridSize - 1, (int) (normalizeY * gridSize)));
        int cellIndex = (gridRow * gridSize) + gridColumn;

        return new GridPosition(gridColumn, gridRow, cellIndex);
    }

    private static int discretizeContinousTimeInSeconds(double timeInSeconds, String method) {
        switch (method.toLowerCase()) {
            case "demandBased":    
                double timeInHours = (timeInSeconds % 86400) / 3600.0;
                Map.Entry<Double, Integer> binEntry = TIME_BIN_LOOKUP.higherEntry(timeInHours);
                return (binEntry != null) ? binEntry.getValue() : (TIME_BIN_LOOKUP.size());
            case "hourly":         return (int) (timeInSeconds / 3600);
            case "half_hourly":    return (int) (timeInSeconds / 1800);
            case "quarter_hourly": return (int) (timeInSeconds / 900);
            default:               return (int) (timeInSeconds / 3600);
        }
    }

    private static int castModeAvailabilityToInteger(String assetOnHand){
        if (assetOnHand == null) return 0;
        return MODE_AVAILABILITY_MAP.getOrDefault(assetOnHand.trim().toLowerCase(), 0);
    }

    private static String castDiscreteNumericalToBinarString(Map<String, Object> discretizeState, int gridSize, String method){

        int departureRow = (int) discretizeState.get("departureRow");
        int departureColumn = (int) discretizeState.get("departureColumn");
        int arrivalRow = (int) discretizeState.get("arrivalRow");
        int arrivalColumn = (int) discretizeState.get("arrivalColumn");
        int departureTimeBin = (int) discretizeState.get("departureTimeBin");
        boolean isFlexible = (boolean) discretizeState.get("targetActivityIsFlexible");
        int assetOnHandState = (int) discretizeState.get("assetOnHandInteger");

        // Calculate how many bits are required for each attribute dynamically
        int timeBitLength = getRequiredBitCount(getTimeBinSize(method) - 1);
        int assetBitLength = getRequiredBitCount(getAssetBinSize() - 1);

        // Transform integer values into binary bit strings with fixed padding sizes
        String departureBitString = getGridBinaryString(departureRow, departureColumn, gridSize);
        String arrivalBitString = getGridBinaryString(arrivalRow, arrivalColumn, gridSize);
        String departureTimeBitString = convertToBinaryString(departureTimeBin, timeBitLength);
        String assetBitString = convertToBinaryString(assetOnHandState, assetBitLength);
        
        // 1 bit for boolean flexibility
        String flexibilityBit = isFlexible ? "1" : "0";

        // Merge the compressed bit strings
        StringBuilder stateVector = new StringBuilder();
        stateVector.append(departureBitString)
                   .append(arrivalBitString)
                   .append(departureTimeBitString)
                   .append(flexibilityBit)
                   .append(assetBitString);

        return stateVector.toString();
    }

    private static int getRequiredBitCount(int maxValue) {
        if (maxValue <= 0) return 1;
        return 32 - Integer.numberOfLeadingZeros(maxValue);
    }

    private static String getGridBinaryString(int row, int column, int gridSize) {
        int targetIndex = (row * gridSize) + column;

        StringBuilder sb = new StringBuilder(gridSize * gridSize);
        for (int i = 0; i < (gridSize * gridSize); i++) {
            if (i == targetIndex) {
                sb.append('1');
            } else {
                sb.append('0');
            }
        }

        return sb.toString();
    }

    private static String convertToBinaryString(int value, int bitLength) {
        String binaryStr = Integer.toBinaryString(value);
        
        if (binaryStr.length() > bitLength) {return binaryStr.substring(binaryStr.length() - bitLength);}
        
        StringBuilder sb = new StringBuilder();
        while (sb.length() < (bitLength - binaryStr.length())) {
            sb.append('0');
        }
        sb.append(binaryStr);
        return sb.toString();
    }

    public record GridPosition(int column, int row, int cellIndex) {}
}
