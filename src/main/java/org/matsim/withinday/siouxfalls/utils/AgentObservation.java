package org.matsim.withinday.siouxfalls.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.StageActivityHandling;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.utils.misc.Time;
import org.matsim.withinday.siouxfalls.src.communication_networking.PythonConnectionManager;

public class AgentObservation {

    public void sendAgentObservation(Scenario scenario, MobsimAgent agent, Activity currentActivity, Trip nextTrip, Person person, int iteration) {
        
        Id<Person> agentID = person.getId();
        String linkID = currentActivity.getLinkId().toString();
        
        // Departure time from current activity
        double departureTime = TripStructureUtils.getDepartureTime(nextTrip).orElse(0.0);

        String formattedDepartureTime = Time.writeTime(departureTime);

        // planned start time of the next activity 
        Plan executedPlan = WithinDayAgentUtils.getModifiablePlan(agent);
        List<Activity> activityList = TripStructureUtils.getActivities(executedPlan, StageActivityHandling.ExcludeStageActivities);

        String nextActivityArrivalTime = "00:00:00";
        double nextActivityArrivalTimeSeconds = 0.0;

        for(int i = 0;  i < activityList.size(); i++){

            if (activityList.get(i) == currentActivity){
                nextActivityArrivalTime = Time.writeTime(activityList.get(i+1).getStartTime().orElse(0.0));

                if (nextActivityArrivalTime == "0:00:00") nextActivityArrivalTime = "24:00:00";

                nextActivityArrivalTimeSeconds = Time.parseTime(nextActivityArrivalTime);

                break;
            }
        }

        // Car Availability (Check Person attributes)
        String carAvailAttr = (String) person.getAttributes().getAttribute("car_avail");
        boolean carAvailability = "always".equals(carAvailAttr);

        // Mode set for the agent
        Collection<String> configModes = scenario.getConfig().scoring().getAllModes();

        List<String> modes = new ArrayList<>(configModes);

        modes.removeIf(mode -> 
            mode.equalsIgnoreCase("ride") || 
            mode.equalsIgnoreCase("other") || 
            mode.equalsIgnoreCase("walk")
        );

        // Send the data tot the connection manager
        String pythonResponse = new PythonConnectionManager("127.0.0.1", 5000).sendOnDeparture(iteration, agentID.toString(), linkID, formattedDepartureTime, 
                            nextActivityArrivalTime, nextActivityArrivalTimeSeconds, departureTime, carAvailability, modes);

        
        System.out.println("Observation of state for agent - " + agentID.toString() + ": " + pythonResponse);
    }

}
