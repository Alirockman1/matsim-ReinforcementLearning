package org.matsim.withinday.siouxfalls.src.communication_networking;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PythonConnectionManager {

    private HttpClient client;
    private String decisionUrl;
    private String arrivalUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public PythonConnectionManager(String host, int port){
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.decisionUrl = "http://" + host + ":" + port + "/ModeChoice";
        this.arrivalUrl = "http://" + host + ":" + port + "/Arrival";
    }

    // Forward the agent json file to the RL Model 
    public String getMode(String json) {
        
        try {

            HttpRequest request = HttpRequest.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .uri(URI.create(this.decisionUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            // This returns the JSON string from Python: {"mode_choice": "bike"}
            return extractMode(response.body());


        } catch (Exception e) {
            return "error_fallback_mode";
        }
    }

    private String extractMode(String responseBody){
        try {
            JsonNode node = mapper.readTree(responseBody);
            return node.get("mode_choice").asText();
        } catch (Exception e) {
            return "101";
        }
    }

    public void sendArrival(String agentId, double travelTimeSeconds,
        int numberOfTransfer, double distance, double travelDisutility, String startDayMode) {

        try {
            // 1. Build the JSON string with our agreed variables
            String json = """
                {
                    "agentID": "%s",
                    "travelTimeSeconds": %.1f,
                    "numberOfTransfers": %d,
                    "distance": %.2f,
                    "travelDisutility": %.2f,
                    "startDayMode": "%s"

                }
                """.formatted(agentId, travelTimeSeconds, numberOfTransfer, distance, travelDisutility, startDayMode);

            System.out.println(json);

            // 2. Build the Request
            HttpRequest request = HttpRequest.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .uri(URI.create(this.arrivalUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            // 3. Send asynchronously (recommended for events so they don't lag the Mobsim)
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::statusCode)
                .thenAccept(status -> {
                    if (status != 200) {
                        System.err.println("Failed to send arrival for agent " + agentId + ". Status: " + status);
                    }
                });

        } catch (Exception e) {
            System.err.println("Error in sendArrival: " + e.getMessage());
        }
    }
}