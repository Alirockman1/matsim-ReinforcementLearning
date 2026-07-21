package org.matsim.withinday.core;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.rl.core.CustomRLObserver;
import org.matsim.withinday.environment.RealTimeScoringEngine;
import org.matsim.withinday.environment.WithinDayObserver;
import org.matsim.withinday.networking.CommunicationManager;
import org.matsim.withinday.trafficmonitoring.WithinDayTravelTime;
import org.matsim.rl.utils.CustomConfigGroup;

import java.io.File;
import java.util.Set;

public class RunExternalModeChoice {
    public static final String REINFORCEMENT_MODE = "rl";

    static void main(String[] args) {
        // Path to the config file
        String configPath;
        
        if (args == null || args.length == 0 || args[0] == null) {
            configPath = "scenarios/sioux-falls/input/config.xml";
        } else {
            configPath = args[0];
        }
        
        // Load the config
        CustomConfigGroup customGroupModule = new CustomConfigGroup();
        Config config = ConfigUtils.loadConfig(configPath, customGroupModule);
        File secondaryParamsFile = new File(new File(configPath).getParentFile(), customGroupModule.getModelFileName());
        ConfigUtils.loadConfig(secondaryParamsFile.getAbsolutePath(), customGroupModule);

        // HPC commmand line interface
        if (args != null && args.length > 1) {
            String[] overrides = new String[args.length - 1];
            System.arraycopy(args, 1, overrides, 0, overrides.length);
            ConfigUtils.applyCommandline(config, overrides);
        }

        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

        // disable on purpose; otherwise the router checks the new "rl" mode, which is only a dummy mode.
        config.routing().setNetworkRouteConsistencyCheck(RoutingConfigGroup.NetworkRouteConsistencyCheck.disable);

        // add walk scoring parameters, because the default router adds access and egress legs with mode walk.
        config.scoring().addModeParams(new ScoringConfigGroup.ModeParams("walk"));

        // reset replanning method assuming that the RL method does all the replanning within the simulation.
        config.replanning().clearStrategySettings();
        ReplanningConfigGroup.StrategySettings keepLast = new ReplanningConfigGroup.StrategySettings()
                .setStrategyName(DefaultPlanStrategiesModule.DefaultSelector.KeepLastSelected)
                .setWeight(1.0);
        config.replanning().addStrategySettings(keepLast);

        config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);

        // Disable iteration based writes
        config.controller().setWritePlansInterval(0);       
        config.controller().setWriteEventsInterval(0);      
        config.controller().setWriteSnapshotsInterval(0);   

        // Disable postprocessing
        config.controller().setCreateGraphs(false);         
        config.controller().setDumpDataAtEnd(false);

        Scenario scenario = ScenarioUtils.loadScenario(config);
        Controller controller = ControllerUtils.createController(scenario);

        // Dont we need to have all modes in the simulation to simulate real time congestion?? - ALI
        //final WithinDayTravelTime travelTime = new WithinDayTravelTime(controller.getScenario(), Set.of(REINFORCEMENT_MODE));
        final WithinDayTravelTime travelTime = new WithinDayTravelTime(controller.getScenario(), Set.of(REINFORCEMENT_MODE, TransportMode.car));

        controller.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                // bind the inter platform communication manager
                this.bind(CommunicationManager.class).asEagerSingleton();
                // Allow the communication manager to interact with matsim listeners
                this.addControllerListenerBinding().to(CommunicationManager.class);

                // bind the custom observer class
                this.bind(WithinDayObserver.class).to(CustomRLObserver.class).asEagerSingleton();

                // bind the withinday travel time in order to be able to use it in the mode choice listener
                this.bind(TravelTime.class).toInstance(travelTime);
                this.addEventHandlerBinding().toInstance(travelTime);
                this.addMobsimListenerBinding().toInstance(travelTime);

                // bind the RLModeChoiceListener for mode replanning
                this.bind(RLModeChoiceListener.class).asEagerSingleton();
                // add the RL mode choice listener to the mobsim listeners
                this.addMobsimListenerBinding().to(RLModeChoiceListener.class);
                // add RL mode choice listener as event handler to listen for activity starts
                this.addEventHandlerBinding().to(RLModeChoiceListener.class);
                // Allow the RL mode choice to interact with matsim listeners
                this.addControllerListenerBinding().to(RLModeChoiceListener.class);
            }
        });
        controller.run();
    }
}
