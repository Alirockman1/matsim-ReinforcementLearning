package org.matsim.withinday.core;

import java.io.File;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.rl.core.CustomRLObserver;
import org.matsim.project.rl.core.CustomRLReplanner;
import org.matsim.project.rl.utils.CustomConfigGroup;
import org.matsim.withinday.environment.WithinDayObserver;
import org.matsim.withinday.networking.CommunicationManager;
import org.matsim.withinday.networking.UnixSocketCommunicationManager;
import org.matsim.withinday.trafficmonitoring.WithinDayTravelTime;
import org.matsim.withinday.utils.WithinDayConfigGroup;

public class RunExternalModeChoice {
    private static final Logger log = LogManager.getLogger(RunExternalModeChoice.class);
    public static final String REINFORCEMENT_MODE = "rl";

    public static void main(String[] args) {
        String configPath = (args != null && args.length > 0 && args[0] != null) 
                ? args[0] 
                : "scenarios/sioux-falls/input/config.xml";

        // 1. Load Configurations
        CustomConfigGroup customGroupModule = new CustomConfigGroup();
        WithinDayConfigGroup withindayModule = new WithinDayConfigGroup();
        Config config = ConfigUtils.loadConfig(configPath, customGroupModule, withindayModule);

        File secondaryParamsFile = new File(new File(configPath).getParentFile(), customGroupModule.getModelFileName());
        if (secondaryParamsFile.exists()) {
            ConfigUtils.loadConfig(secondaryParamsFile.getAbsolutePath(), customGroupModule);
        }

        // Apply HPC command-line parameter overrides
        if (args != null && args.length > 1) {
            String[] overrides = new String[args.length - 1];
            System.arraycopy(args, 1, overrides, 0, overrides.length);
            ConfigUtils.applyCommandline(config, overrides);
        }

        // 2. Configure Output Directory and Suppress Unnecessary File Generation
        config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setWritePlansInterval(config.controller().getLastIteration());
        config.controller().setWriteEventsInterval(config.controller().getLastIteration());
        config.controller().setWriteSnapshotsInterval(0);
        config.controller().setCreateGraphsInterval(1);
        config.controller().setDumpDataAtEnd(false);

        // SUPPRESS MODESTATS & COVERAGE CHARTS (modeChoiceCoverage, ph_modestats, pkm_modestats)
        // ASK DANIEL !!!
        
        // 3. MATSim Framework Settings
        config.routing().setNetworkRouteConsistencyCheck(RoutingConfigGroup.NetworkRouteConsistencyCheck.disable);
        config.scoring().addModeParams(new ScoringConfigGroup.ModeParams("walk"));
        config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);

        // Reset replanning strategies to pure 'KeepLastSelected' (RL handles replanning within-day)
        config.replanning().clearStrategySettings();
        config.replanning().addStrategySettings(
            new ReplanningConfigGroup.StrategySettings()
                .setStrategyName(DefaultPlanStrategiesModule.DefaultSelector.KeepLastSelected)
                .setWeight(1.0)
        );

        // 4. Load Scenario & Create Controller
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Controller controller = ControllerUtils.createController(scenario);
        
        final String replannerClass = (withindayModule != null) ? withindayModule.getParams().get("replanner") : null;
        final String observerClass = (withindayModule != null) ? withindayModule.getParams().get("observer") : null;

        System.out.println(replannerClass);
        System.out.println(observerClass);

        // 5. Register Guice Bindings
        controller.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                // Inter-Platform Communication Manager
                bind(CommunicationManager.class).to(UnixSocketCommunicationManager.class).asEagerSingleton();
                addControllerListenerBinding().to(UnixSocketCommunicationManager.class);

                // Observer & Replanner
                bindDynamicClass(WithinDayObserver.class, observerClass, CustomRLObserver.class);
                bindDynamicClass(WithinDayReplanner.class, replannerClass, CustomRLReplanner.class);

                // Within-Day Travel Time (Tracks 'car' and 'rl' modes)
                WithinDayTravelTime travelTime = new WithinDayTravelTime(scenario, Set.of(REINFORCEMENT_MODE, TransportMode.car));
                bind(TravelTime.class).toInstance(travelTime);
                addEventHandlerBinding().toInstance(travelTime);
                addMobsimListenerBinding().toInstance(travelTime);

                // Within-Day Mode Choice Listener
                bind(WithinDayModeChoiceListener.class).asEagerSingleton();
                addMobsimListenerBinding().to(WithinDayModeChoiceListener.class);
                addEventHandlerBinding().to(WithinDayModeChoiceListener.class);
                addControllerListenerBinding().to(WithinDayModeChoiceListener.class);
            }

            /**
             * Dynamically binds a class name String to a Guice target interface with type checking and fallback.
             */
            @SuppressWarnings("unchecked")
            private <T> void bindDynamicClass(Class<T> targetInterface, String className, Class<? extends T> defaultClass) {
                if (className == null || className.isBlank() || className.equalsIgnoreCase("default")) {
                    log.info("[WITHINDAY BINDING] No custom input provided for {}. Using DEFAULT class: {}", 
                                    targetInterface.getSimpleName(), defaultClass.getName());
                    bind(targetInterface).to(defaultClass).asEagerSingleton();
                    return;
                }

                try {
                    Class<?> clazz;
                    try {
                        clazz = Class.forName(className);
                    } catch (ClassNotFoundException e) {
                        String defaultPackage = defaultClass.getPackageName();
                        clazz = Class.forName(defaultPackage + "." + className);
                    }

                    if (!targetInterface.isAssignableFrom(clazz)) {
                        throw new IllegalArgumentException(String.format(
                            "Configured class '%s' does not implement required interface '%s'", 
                            className, targetInterface.getName()
                        ));
                    }

                    log.info("[WITHINDAY BINDING] Resolved CUSTOM input for {}: {}", 
                                    targetInterface.getSimpleName(), clazz.getName());
                    bind(targetInterface).to((Class<? extends T>) clazz).asEagerSingleton();

                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Could not find dynamic class: " + className, e);
                }
            }
        });

        // 6. Execute Simulation
        controller.run();
    }

    /**
     * Internal helper class to disable unused analysis modules dynamically.
     */
    private static class ConfigGroupSuppressor extends org.matsim.core.config.ReflectiveConfigGroup {
        public ConfigGroupSuppressor(String name) {
            super(name);
        }
    }
}