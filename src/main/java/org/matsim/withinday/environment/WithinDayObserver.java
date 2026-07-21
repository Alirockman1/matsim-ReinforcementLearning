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
import org.matsim.rl.utils.CustomConfigGroup;

/**
 * WithinDayObserver returns a snapshot of the matsim environment as a state representation.
 * The class is modeled such that custom observers can be built on top.
 * while automatically inheriting cached demographic profiling and step-reward scoring.
 */
public abstract class WithinDayObserver {

    protected final RealTimeScoringEngine realTimeScoringEngine;
    public final Map<Id<Person>, RealTimeScoringEngine> agentRewardCalculators = new HashMap<>();
    private final Map<Id<Person>, Map<String, Object>> agentDemographicRegistry = new HashMap<>();
    protected final Logger log;
    protected final Scenario scenario;

    public WithinDayObserver(RealTimeScoringEngine realTimeScoringEngine, Scenario scenario, Logger log) {
        this.realTimeScoringEngine = realTimeScoringEngine;
        this.log = log;
        this.scenario = scenario;
    }

    /**
     * Method to query the environment representation for selected agents.
     * @param agent The MATSim simulation agent being evaluated.
     * @param sim The MATSim simulation engine.
     * @param currentTime The simulation time in which the observer is called up.
     * @param trip The future trip the agent is scheduled to undertake.
     * 
     * * @return An array containing the state parameters. 
     */
    public abstract Map<String, Object> observeState(MobsimAgent agent, QSim sim, Trip trip, double currentTime, boolean isNextTripContext);

    /**
     * Method to provide the custom config to other sub-classes.
     */
    protected abstract CustomConfigGroup getCustomConfigGroup();

    /**
     * Method to extract agent core demographic parameters (saves the parameter for a single iteration)
     * @param agent The MATSim simulation agent being evaluated.
     * @param sim The MATSim simulation engine.
     * 
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

            agentProfile.put("agent_id", agentId.toString());
            agentProfile.put("subpopulation", subpopulation != null ? subpopulation : "default");
            agentProfile.put("sex", sex != null ? sex.trim().toLowerCase() : "unknown");
            
            // Discretize the raw age attribute using the internal package strategy rules
            agentProfile.put("age_group", StateEngine.discretizeAgeAttribute(rawAge));
        } else {
            log.warn("Agent '{}' not found in population.", agentId);
        }

        agentDemographicRegistry.put(agentId, agentProfile);
        return agentProfile;
    }

    /**
     * Method to compile immediate execution step utilities directly with the underlying RealTimeScoringEngine 
     * to compile immediate execution step utilities.
     * @param agent The MATSim simulation agent being evaluated.
     * @param currentTime The simulation time in which the observer is called up.
     * @param sim The MATSim simulation engine.
     * @param currentTime The simulation time in which the observer is called up.
     * @param trip The future trip the agent is scheduled to undertake.
     */
    public RealTimeScoringEngine tripEvaluationMetrics(MobsimAgent agent, double currentTime, Activity activity, 
                                String executedMode, Trip trip, double assetRetrievalTime, 
                                int transferCount, Map<String, Integer> discontinuityPenalties) {
        
        RealTimeScoringEngine rewardCalculator = agentRewardCalculators.computeIfAbsent(agent.getId(), id -> new RealTimeScoringEngine(this.scenario, this.log, this));

        rewardCalculator.compute(agent, currentTime, activity, executedMode, trip, 
            assetRetrievalTime, transferCount, discontinuityPenalties);

        return rewardCalculator;
    }

    /**
     * Reset the store libraries at the start of each new iteration.
     */
    public void resetObservationRegistries() {
        this.agentDemographicRegistry.clear();
        this.agentRewardCalculators.clear();
    }
}