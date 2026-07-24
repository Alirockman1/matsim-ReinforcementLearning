package org.matsim.withinday.environment;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.project.rl.utils.CustomConfigGroup;

/**
 * WithinDayObserver returns a snapshot of the matsim environment as a state representation.
 * The class is modeled such that custom observers can be built on top
 * while automatically inheriting cached demographic profiling and step-reward scoring.
 */
public abstract class WithinDayObserver {

    public final Map<Id<Person>, RealTimeScoringEngine> agentRewardCalculators = new HashMap<>();
    private final Map<Id<Person>, Map<String, Object>> agentDemographicRegistry = new HashMap<>();
    protected final Logger log;
    protected final Scenario scenario;

    public WithinDayObserver(Scenario scenario, Logger log) {
        this.log = log;
        this.scenario = scenario;
    }

    /**
     * Method to query the environment representation for selected agents.
     * @param agent The MATSim simulation agent being evaluated.
     * @param sim The MATSim simulation engine.
     * @param currentTime The simulation time in which the observer is called up.
     * @param trip The future trip the agent is scheduled to undertake.
     * * @return An array containing the state parameters. 
     */
    public abstract Map<String, Object> observeState(MobsimAgent agent, QSim sim, Trip trip, double currentTime, boolean isNextTripContext);

    /**
     * Method to provide the custom config to other sub-classes.
     */
    protected abstract CustomConfigGroup getCustomConfigGroup();

    /**
     * Get the scoring engine for a specific agent ID if it exists.
     * @param agentId The MATSim person ID.
     * @return The RealTimeScoringEngine instance or null if not yet created.
     */
    public RealTimeScoringEngine getScoringEngine(Id<Person> agentId) {
        return this.agentRewardCalculators.get(agentId);
    }

    /**
     * Convenience overload to get the scoring engine directly from a MobsimAgent.
     * @param agent The MATSim simulation agent.
     * @return The RealTimeScoringEngine instance or null if not yet created.
     */
    public RealTimeScoringEngine getScoringEngine(MobsimAgent agent) {
        return agent != null ? getScoringEngine(agent.getId()) : null;
    }

    /**
     * Gets or creates a RealTimeScoringEngine for the specified agent ID.
     */
    public RealTimeScoringEngine getOrCreateScoringEngine(Id<Person> agentId) {
        return this.agentRewardCalculators.computeIfAbsent(agentId, id -> new RealTimeScoringEngine(this.scenario, this));
    }

    /**
     * Method to extract agent core demographic parameters (saves the parameter for a single iteration)
     * @param agent The MATSim simulation agent being evaluated.
     * * @return A Dictionary containing: agent_id, subpopulation, sex, and age_group.
     */
    public Map<String, Object> getAgentDemographicRecord(MobsimAgent agent) {
        Id<Person> agentId = agent.getId();

        if (agentDemographicRegistry.containsKey(agentId)) {
            return agentDemographicRegistry.get(agentId);
        }

        Person person = scenario.getPopulation().getPersons().get(agentId);
        Map<String, Object> agentProfile = new HashMap<>();

        if (person != null) {
            String subpopulation = (String) person.getAttributes().getAttribute("subpopulation");
            String sex = (String) person.getAttributes().getAttribute("sex");
            Object rawAge = person.getAttributes().getAttribute("age");

            agentProfile.put("agentId", agentId);
            agentProfile.put("subpopulation", subpopulation != null ? subpopulation : "default");
            agentProfile.put("sex", sex != null ? sex.trim().toLowerCase() : "unknown");
            
            // Discretize the raw age attribute using the internal package strategy rules
            agentProfile.put("ageGroup", StateEngine.discretizeAgeAttribute(rawAge));
        } else {
            log.warn("Agent '{}' not found in population.", agentId);
        }

        agentDemographicRegistry.put(agentId, agentProfile);
        return agentProfile;
    }

    /**
     * Method to compile immediate execution step utilities directly with the underlying RealTimeScoringEngine 
     * to compile immediate execution step utilities.
     */
    public RealTimeScoringEngine tripEvaluationMetrics(MobsimAgent agent, double currentTime, Activity activity, 
                                String executedMode, Trip trip, double assetRetrievalTime, 
                                int transferCount, Map<String, Integer> discontinuityPenalties) {
        
        RealTimeScoringEngine rewardCalculator = getOrCreateScoringEngine(agent.getId());

        rewardCalculator.compute(agent, currentTime, activity, executedMode, trip, 
            assetRetrievalTime, transferCount, discontinuityPenalties);

        return rewardCalculator;
    }

    /**
     * Resets internal observation registries and resets every active scoring engine at the start of each iteration.
     */
    public void reset() {
        // 1. Reset individual scoring engines before clearing references
        for (RealTimeScoringEngine engine : this.agentRewardCalculators.values()) {
            if (engine != null) {
                engine.reset();
            }
        }

        // 2. Clear maps for the new iteration
        this.agentDemographicRegistry.clear();
        this.agentRewardCalculators.clear();
    }
}