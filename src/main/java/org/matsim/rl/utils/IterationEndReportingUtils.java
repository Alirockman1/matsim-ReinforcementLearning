package org.matsim.rl.utils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.rl.environment.RealTimeScoringEngine;

public class IterationEndReportingUtils {

    public static void writeAgentStatsCsv(IterationEndsEvent event, Map<Id<Person>, RealTimeScoringEngine> agentRewardCalculators, Map<Id<Person>, List<String>> agentExperiencedModes,
        Map<Id<Person>, Double> agentAccumulatedDeltaQ) {
        
        String outputDirectory = event.getServices().getConfig().controller().getOutputDirectory();

        for (Id<Person> agentId : agentExperiencedModes.keySet()) {
            Person person = event.getServices().getScenario().getPopulation().getPersons().get(agentId);
        
            if (person != null && person.getSelectedPlan() != null) {
                int iteration = event.getIteration();

                System.out.println(agentAccumulatedDeltaQ);
                
                double deltaQ = agentAccumulatedDeltaQ.get(agentId);

                // Fetch the custom score from the mapping pipeline
                double reward = 0.0;
                double customMatsimScore = 0.0;
                RealTimeScoringEngine agentReward = agentRewardCalculators.get(agentId);
                
                if (agentReward != null) {
                    reward = agentReward.getAccumulatedDayReward();
                    customMatsimScore = agentReward.getAccumulatedDayScore();
                }
                
                // Extract clean representation of mode chains
                String modesChain = extractModesChain(agentExperiencedModes.get(agentId));

                // Save data row
                saveRowToCsv(outputDirectory, iteration, agentId.toString(), modesChain, reward, customMatsimScore, deltaQ);
            }
        }
    }

    private static String extractModesChain(List<String> experiencedModes) {
        StringBuilder modesUsed = new StringBuilder();

        for (String mode : experiencedModes) {
            modesUsed.append(mode).append(";");
        }

        return modesUsed.toString();
    }

    private static void saveRowToCsv(String directory, int iteration, String id, String modes, 
                                     double reward, double customMatsimScore, double deltaQ) {
        
        String filePath = directory + File.separator + "agent_tracking.csv";

        try (FileWriter fw = new FileWriter(filePath, true)) {
            // Write standard headers if the file is fresh
            if (new java.io.File(filePath).length() == 0) {
                fw.write("iteration,agentid,mainmodes,reward,matsim_score,delta_q\n");
            }

            // Append data row
            fw.write(iteration + "," + id + "," + modes + "," + reward + "," + customMatsimScore + "," + deltaQ + "\n");
        } catch (IOException e) {
            System.err.println("Error");
        }
    }
    
}
