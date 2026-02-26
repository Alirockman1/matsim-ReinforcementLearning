package org.matsim.withinday.siouxfalls.src.modules;

import java.util.Collection;
import java.util.List;

import org.matsim.withinday.mobsim.MobsimDataProvider;
import org.matsim.withinday.siouxfalls.src.communication_networking.PythonConnectionManager;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.core.utils.misc.Time;

public class RewardHandler implements ActivityStartEventHandler {

    private final Collection<Id<Person>> rlAgents;
    private final MobsimDataProvider mobsimDataProvider;
    private final Scenario scenario;

    // Use Inject so MATSim gives you the ACTIVE QSim and Scenario
    @com.google.inject.Inject
    public RewardHandler(MobsimDataProvider mobsimDataProvider, Scenario scenario, Collection<Id<Person>> rlAgents) {
        this.mobsimDataProvider = mobsimDataProvider;
        this.scenario = scenario;
        this.rlAgents = rlAgents;
    }

    @Override
    public void handleEvent(ActivityStartEvent event) {
        Id<Person> personId = event.getPersonId();

        if (rlAgents.contains(personId)) {

            // Create a mobsim agent based on the the filtered ID
            MobsimAgent agent = mobsimDataProvider.getAgents().get(personId);
            if (agent == null) return;
            
            // Identify the plan being modified
            Plan plan = WithinDayAgentUtils.getModifiablePlan(agent);
            int currentPlanIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);

            // Mode used for the first trip
            List<Trip> trips = (List<Trip>) TripStructureUtils.getTrips(plan);
            
            String startDayMode = "unknown";

            // Get the individual legs for the first trip in the day
            for (Leg leg : trips.getFirst().getLegsOnly()){

                // Exclude interaction modes
                if(!leg.getMode().contains("walk")){
                    
                    startDayMode = leg.getMode();

                    break;
                }
            }
            
            // Extract the main activities
            List<String> mainActivities = TripStructureUtils.getTrips(plan).stream()
                .map(trip -> trip.getDestinationActivity().getType())
                .toList();

            double totalDistance = 0.0;
            double totalTravelTime = 0.0;
            int interactionCount = 0;
            double totalTravelDisutility = 0.0;
            double plannedArrivalTimeSeconds = 0.0;
            double nextDepartureTimeSeconds = -1.0;
            double nextPlannedArrivalTimeSeconds =-1.0;
            String nextLinkID = "terminal";

            if (mainActivities.contains(event.getActType())) {

                System.out.println("The Agent reached the activity start of " + event.getActType());

                // Get the original plan - from the previous iteration plan
                Plan originalPlan = scenario.getPopulation().getPersons().get(personId).getSelectedPlan();
                List<PlanElement> elements = originalPlan.getPlanElements();

                // Loop through the original plans
                for (int i = currentPlanIndex; i < elements.size(); i++) {
                    PlanElement currentPlanElement = elements.get(i);
                    
                    if (currentPlanElement instanceof Activity act && act.getType().equals(event.getActType())) {
                        
                        // Search for the next Activity (not the interaction activities)
                        Activity nextMainAct = null;
                        for (int j = i + 1; j < elements.size(); j++) {
                            if (elements.get(j) instanceof Activity potentialAct) {
                                if (mainActivities.contains(potentialAct.getType())) {
                                    nextMainAct = potentialAct;
                                    System.out.println("Current Activity is " + act.getType());
                                    System.out.println("Next Activity is " + nextMainAct.getType());
                                    break;
                                }
                            }
                        }

                        // Get next state values
                        if (nextMainAct != null) {
                            // NORMAL STATE: Information for the NEXT trip
                            nextDepartureTimeSeconds = act.getEndTime().orElse(0.0);
                            nextLinkID = act.getLinkId().toString();
                            nextPlannedArrivalTimeSeconds = nextMainAct.getStartTime().orElse(0.0);
                        } else {
                            // TERMINAL STATE: Explicit "Exit" signals for Python
                            nextLinkID = "terminal";
                            nextDepartureTimeSeconds = -1.0;
                            nextPlannedArrivalTimeSeconds = -1.0;
                        }
                        
                        break;
                    }
                }

                //  Sum every leg until we hit the PREVIOUS main activity
                for (int i = currentPlanIndex - 1; i >= 0; i--) {
                    PlanElement pe = plan.getPlanElements().get(i);
                    
                    if (pe instanceof Leg leg) {
                        // Simply add the distances and times recorded in the leg elements
                        totalDistance += leg.getRoute().getDistance();
                        totalTravelTime += leg.getTravelTime().orElse(0.0);

                        // calculate the utilities
                        var scoringModeParams = scenario.getConfig().scoring().getModes().get(leg.getMode());

                        if (scoringModeParams != null){
                            double betaTime = scoringModeParams.getMarginalUtilityOfTraveling() / 3600.0;
                            double betaDistance = scoringModeParams.getMarginalUtilityOfDistance();
                            double constant = scoringModeParams.getConstant();

                            double distance = leg.getRoute().getDistance();
                            double time = leg.getTravelTime().orElse(0.0);

                            // Formula: U = (Time * betaT) + (Dist * betaD) + Constant
                            double legDisutility = (time * betaTime) + (distance * betaDistance) + constant;
                            
                            totalTravelDisutility += legDisutility;
                        }

                    } 
                    else if (pe instanceof Activity act) {
                        // If we hit a 'Home' or 'Work' (not an interaction), we've reached the start of the trip
                        if (mainActivities.contains(act.getType())) {
                            break; 
                        }
                        // Count every interaction activity found between destinations
                        if (act.getType().contains("interaction")) {
                            interactionCount++;
                        }
                    }
                }         

                // Forward the observation after reaching the activity to the model
                String pythonResponse = new PythonConnectionManager("127.0.0.1", 5000).sendOnArrival(
                    personId.toString(), 
                    totalTravelTime,
                    (interactionCount/2)-1,
                    totalDistance,
                    totalTravelDisutility,
                    startDayMode,
                    nextLinkID,
                    nextDepartureTimeSeconds,
                    nextPlannedArrivalTimeSeconds
                );
                
                System.out.println(" Policy update for agent - " + personId + " -> " + pythonResponse);
                
            }
        }
    }

    @Override
    public void reset(int iteration) {
        // IMPORTANT: Tell Python to clear its dictionary for the new day
        //new PythonConnectionManager("127.0.0.1", 5000).sendResetSignal();
    }
}