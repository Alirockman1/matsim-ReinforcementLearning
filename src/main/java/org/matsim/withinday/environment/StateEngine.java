package org.matsim.withinday.environment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.misc.OptionalTime;



public class StateEngine{
    private static NavigableMap<Double, Integer> TIME_BIN_LOOKUP = new TreeMap<>();
    private static Map<Set<String>, Integer> MODE_AVAILABILITY_MAP = new HashMap<>();
    private static Coord networkCentroid;
    private static double[] environmentMap;
    private static Double maxRadius;
    public static int currentIteration = 0;

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

    public static void setCustomTimeBinLookup(NavigableMap<Double, Integer> lookup) {
        TIME_BIN_LOOKUP = new TreeMap<>(lookup);
    }

    public static void setModeAvailabilityLookup(String[] tourBasedModes) {
        MODE_AVAILABILITY_MAP = buildModeAvailabilityLookup(tourBasedModes);
    }

    public static Map<String, Object> getActivityLocation(Activity activity){

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

    public static int getAssetStateOnHand(Id<Person> agentId, Id<Link> currentLinkId, String[] tourBasedModes) {
        
        Map<String, Integer> penaltyMap = AgentAssetInventory.getModeDiscontinuityPenalty(agentId, currentLinkId);

        Set<String> availableAssetModes = new HashSet<>();
        for (String mode : tourBasedModes) {
            if (penaltyMap.getOrDefault(mode, 1) == 0) {
                availableAssetModes.add(mode);
            }
        }

        return MODE_AVAILABILITY_MAP.getOrDefault(availableAssetModes, 0);
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

    public static int discretizeTimeFromContinous(double timeInSeconds, String method) {
        if ("demand_based".equalsIgnoreCase(method)) {
            if (TIME_BIN_LOOKUP == null || TIME_BIN_LOOKUP.isEmpty()) return 0;
            double timeInHours = (timeInSeconds % 86400) / 3600.0;
            Map.Entry<Double, Integer> entry = TIME_BIN_LOOKUP.higherEntry(timeInHours);
            return (entry != null) ? entry.getValue() : TIME_BIN_LOOKUP.size();
        }

        // Divide by the actual step size length in seconds to get a valid 0-indexed bin
        switch (method.toLowerCase()) {
            case "hourly":         return (int) ((timeInSeconds % 86400) / 3600);
            case "half_hourly":    return (int) ((timeInSeconds % 86400) / 1800);
            case "quarter_hourly": return (int) ((timeInSeconds % 86400) / 900);
            default:               return (int) ((timeInSeconds % 86400) / 3600);
        }
    }

    public static GridPosition discretizePositionFromContinuous(double xCoordinate, double yCoordinate, int gridSize) {
        int gridShape = gridSize * gridSize;
        double width = environmentMap[2] - environmentMap[0];
        double length = environmentMap[3] - environmentMap[1];

        double normalizeX = (xCoordinate - environmentMap[0]) / width;
        double normalizeY = (yCoordinate - environmentMap[1]) / length;
        
        int gridColumn = Math.max(0, Math.min(gridSize - 1, (int) (normalizeX * gridSize)));
        int gridRow = Math.max(0, Math.min(gridSize - 1, (int) (normalizeY * gridSize)));
        int cellIndex = (gridRow * gridSize) + gridColumn;

        return new GridPosition(gridColumn, gridRow, cellIndex, gridShape);
    }

    public static int getBitWidth(int maxValue) {
        if (maxValue <= 1) return 1;
        return Integer.SIZE - Integer.numberOfLeadingZeros(maxValue - 1);
    }

    public static int[] convertToBitStateRepresentation(int rawState, int bitWidth) {
        int[] bits = new int[bitWidth];

        for (int i = 0; i < bitWidth; i++) {
            bits[bitWidth - 1 - i] = (rawState >> i) & 1;
        }

        return bits;
    }

    private static Map<Set<String>, Integer> buildModeAvailabilityLookup(String[] tourBasedModes) {
        Map<Set<String>, Integer> lookup = new HashMap<>();

        int combinationCount = 1 << tourBasedModes.length;

        for (int mask = 0; mask < combinationCount; mask++) {
            Set<String> availableModes = new HashSet<>();

            for (int bit = 0; bit < tourBasedModes.length; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    availableModes.add(tourBasedModes[bit]);
                }
            }

            lookup.put(availableModes, mask);
        }

        return lookup;
    }

    /**
     * Classify continuous age numbers into discrete cohorts.
     * @param agentAge the age of the filtered person.
     */
    public static int discretizeAgeAttribute(Object agentAge) {
        if (agentAge == null) return 0;
        
        int ageValue;
        if (agentAge instanceof Number number) {
            ageValue = number.intValue();
        } else {
            try {
                ageValue = Integer.parseInt(agentAge.toString().trim());
            } catch (NumberFormatException e) {
                return 0; // Fallback placeholder
            }
        }

        if (ageValue < 18)  return 1; // Cohort 1: Minors
        if (ageValue <= 25) return 2; // Cohort 2: Young Adults
        if (ageValue <= 45) return 3; // Cohort 3: Mid-Career Adults
        if (ageValue <= 65) return 4; // Cohort 4: Senior Career Adults
        return 5;                     // Cohort 5: Retirement
    }

    public record GridPosition(int column, int row, int cellIndex, int gridShape) {}
}
