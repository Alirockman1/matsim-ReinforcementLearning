package org.matsim.withinday.siouxfalls.src.communication_networking;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PythonConnectionManager {

    private final HttpClient client;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public PythonConnectionManager(String host, int port) {
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.baseUrl = "http://" + host + ":" + port;
    }

    public void reset(String json) {
        try {

            HttpRequest request = HttpRequest.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .uri(URI.create(baseUrl + "/initialize"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

            if (response.statusCode() == 422) {
                // This will print the exact field Pydantic is unhappy with
                System.out.println("Python Validation Error: " + response.body());
            }

        } catch (Exception e) {
            System.err.println("Error resetting environment: " + e.getMessage());
        }
    }

    public String sendOnDeparture(int iteration, String agentID, String linkID, String departureTimeStr, 
                            String arrivalTimeStr, double arrivalSecs, double departureSecs, 
                            boolean carAvail, List<String> modes) {
        try {
            // 1. Create the JSON inside the method
            String allModes = modes.stream().map(m -> "\"" + m + "\"")
            .collect(java.util.stream.Collectors.joining(", ", "[", "]"));

            String json = """
                {
                    "agentID": "%s",
                    "linkID": "%s",
                    "departureTime": "%s",
                    "nextActivityArrivalTime": "%s",
                    "nextActivityArrivalSeconds": %.1f,
                    "departureTimeSeconds": %.1f,
                    "carAvailability": %b,
                    "possibleModeSet": %s,
                    "simulationIteration": %d
                }
                """.formatted(
                    agentID, linkID, departureTimeStr, arrivalTimeStr, 
                    arrivalSecs, departureSecs, carAvail, allModes, iteration);
            
            System.out.println(" ");
            System.out.println(json);
            System.out.println(" ");

            // 2. Build and send the Request
            HttpRequest request = HttpRequest.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .uri(URI.create(baseUrl + "/send-state"))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            // Async prevents MATSim from lagging during the network call
            HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

            if (response.statusCode() == 422) {
                 // This will print the exact field Pydantic is unhappy with
                System.out.println("Python Validation Error: " + response.body());
            }

            return response.body();

        } catch (Exception e) {
            System.err.println("Error in sendState: " + e.getMessage());
            return "{\"status\": \"Error\", \"message\": \"" + e.getMessage() + "\"}";
        }
    }

    public String getModeChoice(String agentId) {
        
        String mode;

        try {
            // URL format: http://localhost:5000/get-action?agentID=person_1
            String url = baseUrl + "/get-action?agentID=" + agentId;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();
            
            JsonNode node = mapper.readTree(response.body());
            mode =  node.get("mode_choice").asText();

            System.out.println( "\n" + mode + " is asssigned for the agent ("+ agentId + ")\n");

            return mode;

        } catch (Exception e) {
            System.err.println("Error getting mode for " + agentId + ": " + e.getMessage());
            mode = "bike";
            
            System.out.println( "\nDefault mode is asssigned for the agent ("+ agentId + ")\n");
            
            return mode; 
        }
    }

    public String sendOnArrival(String agentId, double travelTime, int transfers, 
                                double distance, double disutility, String mode, String nextLinkID, double nextDepartureTimeSeconds,
                                double nextPlannedArrivalTimeSeconds) {
        try {
            // Using a Map or formatted String to build JSON
            String json = String.format("""
                {
                    "agentID": "%s",
                    "travelTimeSeconds": %.1f,
                    "numberOfTransfers": %d,
                    "distance": %.2f,
                    "travelDisutility": %.2f,
                    "startDayMode": "%s",
                    "nextLinkID": "%s",
                    "nextDepartureTimeSeconds": %.1f,
                    "nextPlannedArrivalTimeSeconds": %.1f
                }
                """, agentId, travelTime, transfers, distance, disutility, mode, nextLinkID, nextDepartureTimeSeconds, nextPlannedArrivalTimeSeconds);

            System.out.println(" ");
            System.out.println(json);
            System.out.println(" ");

            HttpRequest request = HttpRequest.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .uri(URI.create(baseUrl + "/send-reward"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

            if (response.statusCode() == 422) {
                // This will print the exact field Pydantic is unhappy with
                System.out.println("Python Validation Error: " + response.body());
            }

            return response.body();

        } catch (Exception e) {
            System.err.println("Error sending reward: " + e.getMessage());
            return "{\"status\": \"Error\", \"message\": \"" + e.getMessage() + "\"}";
        }
    }

}