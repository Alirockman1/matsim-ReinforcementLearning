package org.matsim.withinday.siouxfalls.utils;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;

import java.util.*;

public class PopulationSampling {

    public static void main(String[] args) {
        String inputFile = "E:\\MyFolder\\Upskill\\MatSIM\\matsim-Basic\\scenarios\\sioux-falls\\modified\\input\\population.xml.gz";
        String outputFile = "E:\\MyFolder\\Upskill\\MatSIM\\matsim-Basic\\scenarios\\sioux-falls\\modified\\input\\population_10p.xml.gz";
        
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenario).readFile(inputFile);
        Population population = scenario.getPopulation();
        
        Random random = new Random(42); 
        
        // 1. Initial 10% Sampling
        List<Person> sampledPersons = new ArrayList<>();
        for (Person person : population.getPersons().values()) {
            if (random.nextDouble() <= 0.10) sampledPersons.add(person);
        }

        int targetSize = sampledPersons.size();
        int targetNetworkWalkers = (int) (targetSize * 0.05); // 5% Target for physical sidewalks
        int currentNetworkWalkers = 0;

        System.out.println("Processing " + targetSize + " agents...");

        for (Person person : sampledPersons) {
            Plan plan = person.getSelectedPlan();
            String mainMode = getPlanMainMode(plan);

            // 2. Assign Car Availability (79.3% threshold)
            // We set carAvail to 'always' for the vast majority as requested
            double carAvailDice = random.nextDouble();
            String carAvail = (carAvailDice <= 0.793) ? "always" : "never";
            person.getAttributes().putAttribute("carAvail", carAvail);

            // 3. Logic for Walkers
            if ("walk".equals(mainMode)) {
                if (currentNetworkWalkers < targetNetworkWalkers) {
                    // Fill the 5% quota for network_walk
                    applyNewMode(plan, "network_walk");
                    currentNetworkWalkers++;
                } else {
                    // If 5% quota is full, convert these walkers to CAR users
                    applyNewMode(plan, "car");
                    // Ensure they have a car available since we just gave them one
                    person.getAttributes().putAttribute("carAvail", "always");
                }
            } 
            
            // 4. Convert Short-Distance Car users to Bike
            else if ("car".equals(mainMode)) {
                double distance = calculateCommuteDistance(plan);
                // If commute < 3km, convert a subset to bike
                if (distance < 3000 && random.nextDouble() < 0.20) {
                    applyNewMode(plan, "bike");
                }
            }
            
            // 5. PT/transit_walk users remain untouched.
        }

        // Finalize Population Object
        population.getPersons().clear();
        for (Person p : sampledPersons) {
            population.addPerson(p);
        }

        // 6. Calculate and Print Final Shares
        printReport(population);

        new PopulationWriter(population).write(outputFile);
    }

    private static String getPlanMainMode(Plan plan) {
        boolean usesBus = false;
        String fallbackMode = "unknown";

        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Leg) {
                Leg leg = (Leg) pe;
                String m = leg.getMode();
                if (m.equals("pt") || m.equals("bus")) usesBus = true;
                
                // Prioritize finding the 'heavy' mode in the chain
                if (!m.contains("interaction") && !m.equals("transit_walk") && !m.equals("walk")) {
                    fallbackMode = m;
                }
            }
        }
        if (usesBus) return "pt";
        if (fallbackMode.equals("unknown")) {
            // Check for pure walkers
            for (PlanElement pe : plan.getPlanElements()) {
                if (pe instanceof Leg) {
                    String m = ((Leg) pe).getMode();
                    if (m.equals("walk") || m.equals("network_walk")) return m;
                }
            }
            return "transit_walk";
        }
        return fallbackMode;
    }

    private static void applyNewMode(Plan plan, String mode) {
        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Leg) {
                Leg leg = (Leg) pe;
                leg.setMode(mode);
                leg.setRoute(null); // Clear route to force re-routing on network
            }
        }
    }

    private static double calculateCommuteDistance(Plan plan) {
        Activity home = null;
        Activity work = null;
        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity) {
                Activity act = (Activity) pe;
                if (act.getType().equals("home")) home = act;
                if (act.getType().equals("work")) work = act;
            }
        }
        if (home != null && work != null) {
            return CoordUtils.calcEuclideanDistance(home.getCoord(), work.getCoord());
        }
        return 99999;
    }

    private static void printReport(Population population) {
        Map<String, Integer> counts = new HashMap<>();
        for (Person p : population.getPersons().values()) {
            String mode = getPlanMainMode(p.getSelectedPlan());
            counts.put(mode, counts.getOrDefault(mode, 0) + 1);
        }
        System.out.println("\n--- FINAL SCENARIO SHARES ---");
        int total = population.getPersons().size();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            double pct = (entry.getValue() * 100.0) / total;
            System.out.printf("Mode: %-15s | Count: %-5d | Share: %.2f%%\n", entry.getKey(), entry.getValue(), pct);
        }
        System.out.println("-------------------------------\n");
    }
}
