package org.matsim.withinday.siouxfalls.src.modules;

import java.util.Map;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.withinday.siouxfalls.utils.WithinDayLogger;
import org.matsim.withinday.mobsim.MobsimDataProvider;
import org.matsim.withinday.mobsim.WithinDayEngine;
import org.matsim.withinday.replanning.identifiers.ActivityEndIdentifierFactory;
import org.matsim.withinday.replanning.identifiers.filter.ProbabilityFilterFactory;
import org.matsim.withinday.replanning.identifiers.interfaces.AgentFilterFactory;
import org.matsim.withinday.replanning.identifiers.interfaces.DuringActivityAgentSelector;
import org.matsim.withinday.replanning.identifiers.tools.ActivityReplanningMap;
import org.matsim.withinday.replanning.identifiers.tools.LinkReplanningMap;
import org.matsim.withinday.replanning.replanners.NextLegReplannerFactory;
import org.matsim.withinday.replanning.replanners.interfaces.WithinDayDuringActivityReplannerFactory;
import org.matsim.withinday.siouxfalls.src.replanner.NextLegModeReplannerFactory;
import org.matsim.withinday.siouxfalls.src.replanner.filters.RLAgentFilterFactory;

public class CustomWithinDayModule implements StartupListener{

	@Inject private Scenario scenario;
	@Inject private WithinDayEngine withinDayEngine;
	@Inject private Provider<TripRouter> tripRouterProvider;
	@Inject private ActivityReplanningMap activityReplanningMap;
	@Inject private EventsManager eventsManager;
	@Inject private LeastCostPathCalculatorFactory pathCalculatorFactory;
	@Inject private Map<String,TravelDisutilityFactory> travelDisutilityFactories;
	@Inject private Map<String,TravelTime> travelTimes;

    @Override
	public void notifyStartup(StartupEvent event) {
		
		this.initReplanners();
	}

	private void initReplanners( ) {
		// Add the activityMap to the events handler
		this.eventsManager.addHandler(this.activityReplanningMap);

		// Define the Agent Identifier factory 
		ActivityEndIdentifierFactory activityEndIdentifierFactory = new ActivityEndIdentifierFactory(this.activityReplanningMap);

		// Probability based filter (optional)
		ProbabilityFilterFactory duringActivityProbabilityFilterFactory = new ProbabilityFilterFactory(1.0);
		activityEndIdentifierFactory.addAgentFilterFactory(duringActivityProbabilityFilterFactory);

		// Specify persons to be filtered
        Collection<Id<Person>> ids = new HashSet<>();
        ids.add(Id.createPersonId("10434_2"));

        // Custom agent filter
		AgentFilterFactory agentFilter = new RLAgentFilterFactory(ids);
        // identifier.addAgentFilter(new RLAgentFilter(ids));
		activityEndIdentifierFactory.addAgentFilterFactory(agentFilter);

		// Create a new identifier
		DuringActivityAgentSelector activityEndIdentifier = activityEndIdentifierFactory.createIdentifier();

		// Create a planner
		WithinDayDuringActivityReplannerFactory duringActivityReplannerFactory = new NextLegModeReplannerFactory(this.scenario, this.withinDayEngine, (com.google.inject.Provider<TripRouter>) this.tripRouterProvider, TimeInterpretation.create(scenario.getConfig()));

		// Add identifier to the planner factory
		duringActivityReplannerFactory.addIdentifier(activityEndIdentifier);

		// Add the factory to the engine
		this.withinDayEngine.addDuringActivityReplannerFactory(duringActivityReplannerFactory);

		// Pnly perform suring activity replanning
		this.withinDayEngine.doDuringActivityReplanning(true);
		
		// this.duringActivityReplannerFactory = new NextLegReplannerFactory(this.scenario, this.withinDayEngine, this.tripRouterProvider, TimeInterpretation.create(scenario.getConfig()));
		// this.duringActivityReplannerFactory.addIdentifier(this.duringActivityIdentifier);
		// this.withinDayEngine.addDuringActivityReplannerFactory(this.duringActivityReplannerFactory);
	}

}
