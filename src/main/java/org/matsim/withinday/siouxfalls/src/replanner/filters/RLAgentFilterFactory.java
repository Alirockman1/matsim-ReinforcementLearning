package org.matsim.withinday.siouxfalls.src.replanner.filters;

import java.util.Collection;
import java.util.Set;
import java.util.HashSet;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.withinday.replanning.identifiers.interfaces.AgentFilterFactory;

public class RLAgentFilterFactory implements AgentFilterFactory {

    private final Set<Id<Person>> agents;
    private final boolean allowAllAgents;

    // Constructor to target specific RL agents
    public RLAgentFilterFactory(Collection<Id<Person>> ids) {
        this.agents = new HashSet<>(ids);
        this.allowAllAgents = false;
    }

    // Constructor for global settings (e.g., allow everyone)
    public RLAgentFilterFactory(boolean allowAllAgents) {
        this.agents = new HashSet<>();
        this.allowAllAgents = allowAllAgents;
    }

    @Override
    public RLAgentFilter createAgentFilter() {
        return new RLAgentFilter(agents, allowAllAgents);
    }
}
