package org.matsim.withinday.siouxfalls.src.replanner.filters;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.HashSet;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.withinday.replanning.identifiers.interfaces.AgentFilter;

public class RLAgentFilter implements AgentFilter {

    private final Set<Id<Person>> agentIds;
    private final boolean allowAll;

    // Package-private constructor: intended to be used by the Factory
    /*package*/ RLAgentFilter(Set<Id<Person>> ids, boolean allowAll) {
        this.agentIds = (ids != null) ? ids : new HashSet<>();
        this.allowAll = allowAll;
    }

    @Override
    public void applyAgentFilter(Set<Id<Person>> set, double time) {
        if (allowAll) return; // Quick exit: everyone is allowed

        Iterator<Id<Person>> iter = set.iterator();
        while (iter.hasNext()) {
            Id<Person> id = iter.next();
            // If the individual check returns false, remove from the set
            if (!this.applyAgentFilter(id, time)) {
                iter.remove();
            }
        }
    }

    @Override
    public boolean applyAgentFilter(Id<Person> id, double time) {
        if (allowAll) return true;
        return agentIds.contains(id);
    }

    public Collection<Id<Person>> getIncludedAgents() {
        return Collections.unmodifiableSet(this.agentIds);
    }
}
