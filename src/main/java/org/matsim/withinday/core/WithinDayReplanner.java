package org.matsim.withinday.core;

import com.google.gson.Gson;

import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.withinday.environment.StateEngine;
import org.matsim.withinday.environment.WithinDayObserver;
import org.matsim.withinday.networking.CommunicationManager;
import org.matsim.withinday.utils.EditTrips;
import org.matsim.withinday.utils.WithinDayAgentExperience;
import org.matsim.core.utils.timing.TimeInterpretation;

/**
 * Generic base class for all within-day replanning strategies.
 * Handles observer integration, state extraction, and network transmission to Python serving models.
 */
public abstract class WithinDayReplanner {

    protected static final Logger log = LogManager.getLogger(WithinDayReplanner.class);

    protected final Scenario scenario;
    protected final TripRouter router;
    protected final TimeInterpretation timeInterpretation;
    protected final WithinDayObserver customObserver;
    protected final CommunicationManager communicationManager;
    protected final Gson gson = new Gson();

    private EditTrips editTrips;

    /**
     * Constructor for WithinDayReplanner.
     *
     * @param scenario                   The active MATSim simulation scenario.
     * @param router                     Trip router utility used by EditTrips.
     * @param timeInterpretation         Time interpretation rules for routing.
     * @param customObserver             Observer for extracting environment states and trip scores.
     * @param pythonCommunicationManager HTTP communication manager for external decision models.
     */
    public WithinDayReplanner(Scenario scenario, TripRouter router, TimeInterpretation timeInterpretation,
                              WithinDayObserver customObserver, CommunicationManager pythonCommunicationManager) {
        this.scenario = scenario;
        this.router = router;
        this.timeInterpretation = timeInterpretation;
        this.customObserver = customObserver;
        this.communicationManager = pythonCommunicationManager;
    }

    /**
     * Method to initialize model configurations or remote endpoint sessions during startup.
     */
    public abstract void initializeModel();

    /**
     * Executes the complete within-day mode replanning workflow for a given agent:
     * <ol>
     * <li>Validates that the agent is currently positioned at an activity.</li>
     * <li>Extracts environmental state observations and demographic attributes.</li>
     * <li>Transmits state payload to the serving decision model via HTTP POST.</li>
     * <li>Updates the agent's active executed plan with the newly assigned trip mode.</li>
     * <li>Registers vehicle assets in QSim if required by the new mode.</li>
     * </ol>
     *
     * @param agent          The MATSim simulation agent undergoing replanning.
     * @param sim            The active queue simulation instance.
     * @param simulationTime Current simulation timestamp in seconds from midnight.
     */
    public void replanNextTrip(MobsimAgent agent, QSim sim, double simulationTime) {
        // Validate that the current plan element is an Activity
        PlanElement currentElement = WithinDayAgentUtils.getCurrentPlanElement(agent);
        if (!(currentElement instanceof Activity)) {
            throw new IllegalStateException(String.format(
                "Expected current plan element for agent '%s' to be an Activity, but found '%s'.",
                agent.getId(), currentElement != null ? currentElement.getClass().getSimpleName() : "null"
            ));
        }

        Activity currentActivity = (Activity) currentElement;
        Plan modifiablePlan = WithinDayAgentUtils.getModifiablePlan(agent);

        // Extract upcoming trip structures
        int currentPlanElementIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);
        Trip unmodifiedNextTrip = EditTrips.findTripAtPlanElementIndex(agent, currentPlanElementIndex + 1);
        Trip nextTripLeg = TripStructureUtils.findTripStartingAtActivity(currentActivity, modifiablePlan);

        if (nextTripLeg == null || unmodifiedNextTrip == null) {
            log.debug("Skipping within-day replanning for agent {}: No upcoming trip found.", agent.getId());
            return;
        }

        // Observe environmental state and demographics
        Map<String, Object> demographics = this.customObserver.getAgentDemographicRecord(agent);
        String agentId = demographics.get("agentId").toString();
        
        log.info("Replanning next trip for agent {} at activity end time {}", agentId, agent.getActivityEndTime());
        
        Map<String, Object> state = this.customObserver.observeState(agent, sim, nextTripLeg, simulationTime, false);
        state.put("simulationIteration", StateEngine.currentIteration);

        if (demographics != null) {
            state.put("subpopulation", demographics.getOrDefault("subpopulation", "default"));
        }

        // 4. Request decision from serving model via HTTP POST
        log.info("COMMUNICATION NET: Transmitting environment state for agent {}", agentId);
        String jsonState = gson.toJson(state);
        String chosenMode = this.communicationManager.httpPost(jsonState, getDecisionEndpoint(), 360);

        // Fallback safety handling
        if (chosenMode == null || chosenMode.trim().isEmpty()) {
            log.error("COMMUNICATION NET: Null/empty response received. Fallback mode 'pedestrian' assigned to agent {}", agentId);
            chosenMode = "pedestrian";
        } else {
            chosenMode = chosenMode.trim();
        }

        log.info("RL MODE CHOICE: Assigned mode '{}' to agent {}", chosenMode.toUpperCase(), agentId);

        // 5. Update agent's executed plan in memory
        List<? extends PlanElement> newNextTrip = editTrips.replanFutureTrip(unmodifiedNextTrip, modifiablePlan, 
            chosenMode, agent.getActivityEndTime());

        // 6. Spawn vehicle in QSim network if the selected mode requires network vehicle allocation
        if (sim.getScenario().getConfig().qsim().getMainModes().contains(chosenMode)) {
            WithinDayAgentUtils.addVehicleToQSimIfNecessary(newNextTrip, scenario, sim);
        }
    }

    /**
     * Default implementation for processing post-trip simulation step feedback.
     * Can be overridden by concrete subclasses (e.g., RL models) for custom reward and Q-value processing.
     *
     * @param agent          The MATSim simulation agent completing the trip.
     * @param sim            The active queue simulation instance.
     * @param completedTrip  The completed trip leg structure containing performance metrics.
     * @param simulationTime Current simulation timestamp in seconds from midnight.
     * @return Status string response from feedback processing (e.g., "COMPLETED" or raw JSON response).
     */
    public void step(MobsimAgent agent, QSim sim, double simulationTime, boolean rescheduleActivityEndTime) {
        log.debug("Default step handler executed for agent {} at time {}", agent.getId(), simulationTime);
        log.info("Step completed");
        
        if (rescheduleActivityEndTime){
            rescheduleActivityEnd(agent, sim, simulationTime, false);
        }
    }

    /**
     * Reschedules the active activity's end time in the QSim queue.
     * Preserves original duration for flexible activities, or fixed end times for rigid activities.
     *
     * @param agent             The MATSim simulation agent.
     * @param sim               The active queue simulation instance.
     * @param activityStartTime The actual timestamp (in seconds) when the agent started this activity.
     */
    public void rescheduleActivityEnd(MobsimAgent agent, QSim sim, double activityStartTime, boolean isFlexible) {
        PlanElement currentElement = WithinDayAgentUtils.getCurrentPlanElement(agent);
        if (!(currentElement instanceof Activity)) {
            log.warn("Cannot reschedule activity end for agent {}: current element is not an Activity.", agent.getId());
            return;
        }

        Activity currentActivity = (Activity) currentElement;
        int currentElementIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);

        // Fetch original unmodifiable activity from initial selected plan
        Person person = WithinDayAgentUtils.getModifiablePlan(agent).getPerson();
        Plan originalPlan = person.getSelectedPlan();
        Activity originalActivity = (Activity) originalPlan.getPlanElements().get(currentElementIndex);

        double oldEndTime = currentActivity.getEndTime().orElse(-1.0);
        double targetEndTime = oldEndTime;

        if (isFlexible) {
            if (originalActivity.getEndTime().isDefined() && originalActivity.getStartTime().isDefined()) {
                double plannedDuration = originalActivity.getEndTime().seconds() - originalActivity.getStartTime().seconds();
                targetEndTime = activityStartTime + Math.max(0.0, plannedDuration);
            } else if (originalActivity.getMaximumDuration().isDefined()) {
                targetEndTime = activityStartTime + originalActivity.getMaximumDuration().seconds();
            } 
        } else {
            if (originalActivity.getEndTime().isDefined()) {
                targetEndTime = originalActivity.getEndTime().seconds();
            }
        }

        // Apply update in QSim queue
        currentActivity.setEndTime(targetEndTime);
        WithinDayAgentUtils.rescheduleActivityEnd(agent, sim);

        log.info("RESCHEDULE ACTIVITY ({}) : Agent {} end time updated from {}s to {}s (Actual Start: {}s)",
                isFlexible ? "FLEXIBLE" : "FIXED", agent.getId(), oldEndTime, targetEndTime, activityStartTime);
    }

    /**
     * Hook method specifying the HTTP decision endpoint on the Python server.
     * Subclasses override this method to route requests to custom serving models.
     *
     * @return Endpoint URL path string (default is "/decision/mode-choice").
     */
    protected String getDecisionEndpoint() {
        return "/decision/mode-choice";
    }

    /**
     * Method to reset internal states or observer caches at iteration boundaries.
     *
     * @param iteration The index of the iteration currently starting.
     */
    public void reset(IterationEndsEvent event) {
        if (this.customObserver != null) {
            this.customObserver.reset();
        }
    }

    /**
     * Optional accessor for agent experience tracking metrics.
     *
     * @return Map mapping Person IDs to experience records, or null if tracking is disabled.
     */
    public Map<Id<Person>, WithinDayAgentExperience> getAgentExperiences() {
        return null;
    }

    public void initEditTrips() {
        // lazy instantiation of EditTrips
        if (editTrips == null) {
            // internalInterface is null on purpose. This is only needed if current legs are replanned. But we are replacing future trips only (i.e. before activity ends).
            editTrips = new EditTrips(router, scenario, null, timeInterpretation);
        }
    }
}
