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

public class SiouxFallsWithinDayRunner{

    public static Process pythonProcess;

    public static void main(String[] args) {
        
        // Start the Python Service
        startPythonService();

        // Config the matsim within day module
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

        long start = System.currentTimeMillis();
        // Run the matsim simulation
        try{
            controler.run();
        }finally{
            stopPythonService();
        }
        
        System.out.println("Total Execution Time: " + (System.currentTimeMillis() - start) / 1000.0 + "s");
    }

    private static void startPythonService() {
        try {
            System.out.println("Starting Python Uvicorn Service...");

            // Base location
            String projectRoot = System.getProperty("user.dir");

            // Location of the networking module
            File serverPath = new File(projectRoot, "src/main/python/networking");

            // The virtual environment path
            String virtualEnvironmentPython = new File(serverPath, ".myenv/Scripts/python.exe").getAbsolutePath();

            // code to be run in the background
            ProcessBuilder processBuilder = new ProcessBuilder(
                "cmd.exe", "/c", 
                "start", "cmd.exe", "/k",
                "\"" + virtualEnvironmentPython + "\" -m uvicorn Service:app --host 127.0.0.1 --port 5000 --reload"
            );
            
            // Strat the service
            processBuilder.directory(serverPath);
            pythonProcess = processBuilder.start();
            
            // Give Python 5 seconds to initialize before MATSim hits the network
            Thread.sleep(5000); 
            System.out.println("Python Service should be live.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void stopPythonService() {
        if (pythonProcess != null) {
            System.out.println("Closing Python Service...");
            // On Windows, uvicorn spawns child processes, so we force kill the tree
            try {
                Runtime.getRuntime().exec("taskkill /F /T /PID " + pythonProcess.pid());

                Thread.sleep(1000);
            } catch (Exception e) {
                pythonProcess.destroyForcibly();
            }
        }
    }
}
