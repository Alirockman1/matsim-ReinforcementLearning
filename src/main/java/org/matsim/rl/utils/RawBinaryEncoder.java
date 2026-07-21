package org.matsim.rl.utils;

import java.util.Map;

import org.matsim.rl.environment.StateEngine;

public class RawBinaryEncoder {
    private final double xCurrent, yCurrent, xNext, yNext;
    private final double[] environmentMap;
    private final int departureTimeBin, assetState;
    private final boolean scheduleFlexibility;

    public RawBinaryEncoder(Map<String, Object> currentLocation, Map<String, Object> nextLocation, 
        int timeBin, boolean flexibility, int assets){

        this.xCurrent = (double) currentLocation.get("x");
        this.yCurrent = (double) currentLocation.get("y");
        this.xNext = (double) nextLocation.get("x");
        this.yNext = (double) nextLocation.get("y");
        this.environmentMap = (double[]) currentLocation.get("environmentBounds");
        this.departureTimeBin = timeBin;
        this.scheduleFlexibility = flexibility;
        this.assetState = assets;
    }
    
    public String transform(int gridSize){
        // Calculate how many bits are required for each attribute dynamically
        int locationBitLength = (gridSize * gridSize);
        int timeBitLength = getRequiredBitCount(StateEngine.getTimeBinSize("demand_based") - 1);
        int assetBitLength = getRequiredBitCount(StateEngine.getAssetBinSize() - 1);

        // Transform integer values into binary bit strings with fixed padding sizes
        String departureBitString = getGridBinaryString(this.xCurrent, this.yCurrent, gridSize);
        String arrivalBitString = getGridBinaryString(this.xNext, this.yNext, gridSize);
        String departureTimeBitString = convertToBinaryString(this.departureTimeBin, timeBitLength);
        String assetBitString = convertToBinaryString(this.assetState, assetBitLength);
        
        // 1 bit for boolean flexibility
        String flexibilityBit = this.scheduleFlexibility ? "1" : "0";

        // Merge the compressed bit strings
        StringBuilder stateVector = new StringBuilder();
        stateVector.append(departureBitString)
                   .append(arrivalBitString)
                   .append(departureTimeBitString)
                   .append(flexibilityBit)
                   .append(assetBitString);

        return stateVector.toString();
    }

    private String getGridBinaryString(double x, double y, int gridSize) {
        double width = this.environmentMap[2] - this.environmentMap[0];
        double length = this.environmentMap[3] - this.environmentMap[1];

        double normalizedX = (x - environmentMap[0]) / width;
        double normalizedY = (y - environmentMap[1]) / length;

        int column = (int) (normalizedX * gridSize);
        // Fixed original bug: added missing outer parentheses around casting 
        int row = (int) (normalizedY * gridSize); 

        column = Math.max(0, Math.min(gridSize - 1, column));
        row = Math.max(0, Math.min(gridSize - 1, row));

                int targetIndex = (row * gridSize) + column;
    
        // 5. Build the BitString
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

    private String convertToBinaryString(int value, int bitLength) {
        String binaryStr = Integer.toBinaryString(value);
        
        // If the value is longer than bitLength, truncate it from the front safely
        if (binaryStr.length() > bitLength) {
            return binaryStr.substring(binaryStr.length() - bitLength);
        }
        
        // Pad with leading zeros if it is shorter than required
        StringBuilder sb = new StringBuilder();
        while (sb.length() < (bitLength - binaryStr.length())) {
            sb.append('0');
        }
        sb.append(binaryStr);
        return sb.toString();
    }

    private int getRequiredBitCount(int maxValue) {
        if (maxValue <= 0) return 1;
        return 32 - Integer.numberOfLeadingZeros(maxValue);
    }
}
