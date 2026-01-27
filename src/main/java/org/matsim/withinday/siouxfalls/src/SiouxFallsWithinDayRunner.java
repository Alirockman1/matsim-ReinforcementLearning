package org.matsim.withinday.siouxfalls.src;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.mobsim.framework.listeners.MobsimListener;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.withinday.controller.WithinDayModule;
import org.matsim.withinday.siouxfalls.utils.SimulationState;
import org.matsim.withinday.siouxfalls.src.modules.CustomWithinDayModule;

public class SiouxFallsWithinDayRunner{

    public static void main(String[] args) {
		Config config;
		if ( args==null || args.length==0 || args[0]==null ){
			config = ConfigUtils.loadConfig( "scenarios/sioux-falls/modified/input/config.xml" );
		} else {
			config = ConfigUtils.loadConfig( args );
		}

        // Required to initiate the within day module
        config.controller().setRoutingAlgorithmType( ControllerConfigGroup.RoutingAlgorithmType.Dijkstra );

		config.controller().setOverwriteFileSetting( OverwriteFileSetting.deleteDirectoryIfExists );
		config.controller().setLastIteration(0);

        Scenario scenario = ScenarioUtils.loadScenario(config);
        Controler controler = new Controler(scenario);

        // Installing the custom iteration tracker within th eqsim environment
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                // Use bind() to link your listener to the Matsim Controler
                bind(IterationStartsListener.class).toInstance(new IterationStartsListener() {
                    @Override
                    public void notifyIterationStarts(IterationStartsEvent event) {
                        SimulationState.currentIteration = event.getIteration();
                    }
                });
            }
        });
        
        // Overriding the iteration base qsim with the within day module
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                    // Installs the core engine, maps, and travel time logic
                    install(new WithinDayModule());
                    
                    // Tells MATSim to manage the lifecycle of your handler
                    // Using .to(Class) allows @Inject to work!
                    addMobsimListenerBinding().to((Class<? extends MobsimListener>) CustomWithinDayModule.class);
                    addControlerListenerBinding().to(CustomWithinDayModule.class);

                    System.out.println("Within-Day Strategy Linked to Factory Engine!");
                }
        });

        // Start timer
        long start = System.currentTimeMillis();
        controler.run();
        System.out.println("Total Execution Time: " + (System.currentTimeMillis() - start) / 1000.0 + "s");
    }
}
