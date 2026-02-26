package org.matsim.withinday.siouxfalls.src.modules;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.withinday.siouxfalls.src.communication_networking.PythonConnectionManager;
import org.matsim.withinday.siouxfalls.src.modules.configer_modules.RLConfigGroup;
import org.matsim.withinday.siouxfalls.utils.SimulationState;

import com.google.gson.Gson;
import com.google.inject.Inject;

public class RLStartupListener implements StartupListener, IterationStartsListener {

    private final Scenario scenario;

    @Inject
    public RLStartupListener(Scenario scenario) {
        this.scenario = scenario;
    }

    @SuppressWarnings("null")
    @Override
    public void notifyStartup(StartupEvent event) {

        RLConfigGroup rlConfig = ConfigUtils.addOrGetModule(scenario.getConfig(), RLConfigGroup.class);

        String outputDirectory = scenario.getConfig().controller().getOutputDirectory();

        File fullPath = new File(outputDirectory, rlConfig.getModelFileName());
        String absoluteModelPath = fullPath.getAbsolutePath();

        if (rlConfig != null) {
            Map<String, Object> jsonMap = new HashMap<>();
            jsonMap.put("modelType", rlConfig.getModelType());
            jsonMap.put("alpha", rlConfig.getAlpha());
            jsonMap.put("gamma", rlConfig.getGamma());
            jsonMap.put("epsilon", rlConfig.getEpsilon());
            jsonMap.put("trainingCutoffIteration", rlConfig.getTrainingCutoffIteration());
            jsonMap.put("saveInterval", rlConfig.getSaveInterval());
            jsonMap.put("modelFileName", absoluteModelPath);

            Gson gson = new Gson();
            String jsonString = gson.toJson(jsonMap);

            // Send via Connection Manager
            new PythonConnectionManager("127.0.0.1", 5000).reset(jsonString);
        }
    }

    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        SimulationState.currentIteration = event.getIteration();
    }
}
