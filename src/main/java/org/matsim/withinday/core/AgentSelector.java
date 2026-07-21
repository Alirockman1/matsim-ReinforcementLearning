package org.matsim.withinday.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.api.core.v01.Id;

public class AgentSelector {

    private final Scenario scenario;
    private final Random randomSeed;
    private final Logger log;

    private final Set<Id<Person>> selectedAgents = new HashSet<>();

    public AgentSelector(Scenario scenario, long seed, Logger log) {
        this.scenario = scenario;
        this.randomSeed = new Random(seed);
        this.log = log;
    }

    /**
     * Samples agents for the current iteration.
     *
     * @param iteration Current MATSim iteration.
     * @param samplingPercentage Value in [0,1]. Ignored if fixedAgentIds is non-empty.
     * @param fixedAgentIds Explicit RL agent IDs (overrides percentage sampling).
     */
    public void sampleAgentsForIteration(int iteration, double samplingPercentage, Collection<String> fixedAgentIds) {

        selectedAgents.clear();

        // Fixed agent list in the config
        if (fixedAgentIds != null && !fixedAgentIds.isEmpty()) {
            for (String agentId : fixedAgentIds) {
                if (agentId == null || agentId.isBlank()) {
                    continue;
                }

                Id<Person> id = Id.createPersonId(agentId.trim());
                Person person = scenario.getPopulation().getPersons().get(id);

                if (person != null && isEligiblePerson(person)){
                    selectedAgents.add(id);
                }else {
                    log.warn("Configured RL agent '{}' not found in population.", agentId);
                }
            }

            log.info("RLAgentSelector: Selected {} fixed RL agents.", selectedAgents.size());
            return;
        }

        // Agent list based on percentage sampling
        if (samplingPercentage >= 1.0) {
            selectedAgents.addAll(scenario.getPopulation().getPersons().keySet());
            log.info("RLAgentSelector: Selected entire population ({} agents).", selectedAgents.size());

            return;
        }else if(samplingPercentage < 1.0 && samplingPercentage > 0){
            List<Person> population = new ArrayList<>(scenario.getPopulation().getPersons().values());
            
            Random random = new Random(iteration);
            Collections.shuffle(population, random);
            int numberToSample = (int) Math.round(population.size() * samplingPercentage);

            for (int i = 0; i < numberToSample; i++) {
                selectedAgents.add(population.get(i).getId());
            }

            log.info("RLAgentSelector: Selected {} RL agents ({}%).", numberToSample, samplingPercentage * 100.0);

            return;
        }else{
            return;
        }
    }

    /**
     * Determines whether a MATSim agent should enter the replanning process 
     * at the current simulation step.
     * 
     * An agent is eligible for RL replanning only if:
     *     The agent was sampled for the current iteration.
     *     The agent has a valid current plan element.
     *     The agent has a future trip available that can be modified.
     * 
     * @param agent The MATSim simulation agent being evaluated.
     **/
    public boolean shouldReplan(MobsimAgent agent) {

        if (!selectedAgents.contains(agent.getId())) {
            return false;
        }

        Integer currentPlanElementIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);
        Plan plan = WithinDayAgentUtils.getModifiablePlan(agent);
        Integer maxPlanElementIndex =plan.getPlanElements().size();
        
        if (currentPlanElementIndex == null || currentPlanElementIndex >=  maxPlanElementIndex - 1) {
            return false;
        }

        return true;
    }

    /**
     * Returns the selected agent list.
     */
    public Set<Id<Person>> getSelectedAgents() {return Collections.unmodifiableSet(selectedAgents);}

    /** 
     * Returns a boolean value based on if an agent ID is in the list.
     */
    public boolean contains(Id<Person> id) {return selectedAgents.contains(id);}

    /** 
     * Reset the agent list.
     */
    public void reset() {selectedAgents.clear();}

    /**
     * Static filtering applied during sampling.
     */
    private boolean isEligiblePerson(Person person) {
        // Can be used for filter through different subpopulations to be excluded
        return true;
    }    
}
