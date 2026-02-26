package org.matsim.withinday.siouxfalls.src.modules.configer_modules;

import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.api.core.v01.Id;

import java.util.Collection;
import java.util.HashSet;


public class RLConfigGroup extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "agentModeChoice";

    private String modelType;
    private String modelFileName;
    private double alpha;
    private double gamma;
    private double epsilon;
    private int trainingCutoffIteration;
    private String agentFilterList;
    private int saveInterval;

    public RLConfigGroup() {
        super(GROUP_NAME);
    }

    public Collection<Id<Person>> getAgentIdsAsCollection() {
        Collection<Id<Person>> ids = new HashSet<>();
        
        // Get the original string
        String rawList = this.agentFilterList; 

        if (rawList != null && !rawList.isEmpty()) {
            // Split by comma
            String[] parts = rawList.split(",");
            for (String part : parts) {
                // Trim whitespace and create the ID
                String cleanId = part.trim();
                if (!cleanId.isEmpty()) {
                    ids.add(Id.createPersonId(cleanId));
                }
            }
        }
        return ids;
    }

    @StringGetter("modelType")
    public String getModelType() { return modelType; }

    @StringSetter("modelType")
    public void setModelType(String modelType) { this.modelType = modelType; }

    @StringGetter("modelFileName")
    public String getModelFileName() { return modelFileName; }

    @StringSetter("modelFileName")
    public void setModelFileName(String modelFileName) { this.modelFileName = modelFileName; }

    @StringGetter("alpha")
    public double getAlpha() { return alpha; }

    @StringSetter("alpha")
    public void setAlpha(double alpha) { this.alpha = alpha; }

    @StringGetter("gamma")
    public double getGamma() { return gamma; }

    @StringSetter("gamma")
    public void setGamma(double gamma) { this.gamma = gamma; }

    @StringGetter("epsilon")
    public double getEpsilon() { return epsilon; }

    @StringSetter("epsilon")
    public void setEpsilon(double epsilon) { this.epsilon = epsilon; }

    @StringGetter("trainingCutoffIteration")
    public int getTrainingCutoffIteration() { return trainingCutoffIteration; }

    @StringSetter("trainingCutoffIteration")
    public void setTrainingCutoffIteration(int trainingCutoffIteration) { this.trainingCutoffIteration = trainingCutoffIteration; }

    @StringGetter("agentFilterList")
    public String getAgentFilterList() { return agentFilterList; }

    @StringSetter("agentFilterList")
    public void setAgentFilterList(String agentFilterList) { this.agentFilterList = agentFilterList; }

    @StringGetter("saveInterval")
    public int getSaveInterval() { return saveInterval; }

    @StringSetter("saveInterval")
    public void setSaveInterval(int saveInterval) { this.saveInterval = saveInterval; }
}