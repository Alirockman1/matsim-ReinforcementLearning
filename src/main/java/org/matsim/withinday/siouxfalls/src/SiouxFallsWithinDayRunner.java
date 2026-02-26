package org.matsim.withinday.siouxfalls.src;

import java.io.File;

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
import org.matsim.withinday.siouxfalls.src.modules.RLStartupListener;
import org.matsim.withinday.siouxfalls.src.modules.configer_modules.RLConfigGroup;
import org.matsim.withinday.siouxfalls.src.communication_networking.PythonService;

public class SiouxFallsWithinDayRunner{

    public static void main(String[] args) {

        // Start the Python Service
        PythonService pythonService = new PythonService();
        Process process = pythonService.start();

        // Config the matsim within day module
		Config config = ConfigUtils.createConfig();

        config.addModule(new RLConfigGroup());
        
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

        
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                // 1. Core Within-Day Engine
                install(new WithinDayModule());

                // 2. RL Life Cycle (Handles Startup and Iteration Starts)
                addControlerListenerBinding().to(RLStartupListener.class);

                // 3. Custom Within-Day Logic & Mobsim Listeners
                addControlerListenerBinding().to(CustomWithinDayModule.class);
                //addMobsimListenerBinding().to((Class<? extends MobsimListener>) CustomWithinDayModule.class);

                System.out.println("Within-Day Strategy Linked to Factory Engine!");
                }
        });

        long start = System.currentTimeMillis();
        // Run the matsim simulation
        try{
            controler.run();
        }finally{
            pythonService.stop(process);
        }
        
        System.out.println("Total Execution Time: " + (System.currentTimeMillis() - start) / 1000.0 + "s");
    }
}
