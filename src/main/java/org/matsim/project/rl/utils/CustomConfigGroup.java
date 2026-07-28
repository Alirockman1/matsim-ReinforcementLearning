package org.matsim.project.rl.utils;

import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.api.core.v01.Id;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;


public class CustomConfigGroup extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "agentModeChoice";
    private static final String WEIGHTS_SET_TYPE = "modelWeights";

    private String modelType;
    private String modelFileName = "";
    private String autoEncoderModel = "";
    private boolean isAllowAllAgents = false;
    private int saveInterval = 1;
    private String agentFilterList = "";
    private double discontinuityPenalty = 1.0;
    private double retrievalCostPenalty = 1.5;
    private double samplingPercentage = 1.0;

    private double alpha;
    private double gamma;
    private double epsilon;
    private double epsilonDecay;
    private double epsilonMinimum;
    private int trainingCutoffIteration;
    private String modes;
    private String tourModesList;

    public CustomConfigGroup() {
        super(GROUP_NAME);
    }

    //--- Custom methods ---//
    
    public Map<String, Double> getModelWeights() { 
        Map<String, Double> weights = new HashMap<>();
        
        weights.put("discontinuityPenalty", this.discontinuityPenalty);
        weights.put("retrievalCostPenalty", this.retrievalCostPenalty);
        
        return weights;
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

    //--- Getters & Setters ---//

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

    @StringGetter("epsilonDecay")
    public double getEpsilonDecay() { return epsilonDecay; }

    @StringSetter("epsilonDecay")
    public void setEpsilonDecay(double epsilonDecay) { this.epsilonDecay = epsilonDecay; }

    @StringGetter("epsilonMinimum")
    public double getEpsilonMinimum() { return epsilonMinimum; }

    @StringSetter("epsilonMinimum")
    public void setEpsilonMinimum(double epsilonMinimum) { this.epsilonMinimum = epsilonMinimum; }

    @StringGetter("trainingCutoffIteration")
    public int getTrainingCutoffIteration() { return trainingCutoffIteration; }

    @StringSetter("trainingCutoffIteration")
    public void setTrainingCutoffIteration(int trainingCutoffIteration) { this.trainingCutoffIteration = trainingCutoffIteration; }

    @StringGetter("agentFilterList")
    public String getAgentFilterList() { return agentFilterList; }

    @StringSetter("agentFilterList")
    public void setAgentFilterList(String agentFilterList) { 
        this.agentFilterList = agentFilterList;
            
            if (agentFilterList == null || agentFilterList.trim().isEmpty()) {
                this.isAllowAllAgents = true;
            } else {
                this.isAllowAllAgents = false;
            }
    }

    @StringGetter("saveInterval")
    public int getSaveInterval() { return saveInterval; }

    @StringSetter("saveInterval")
    public void setSaveInterval(int saveInterval) { this.saveInterval = saveInterval; }

    @StringGetter("allowAllAgents")
    public boolean getIsAllowAllAgents() {
        if (this.agentFilterList == null || this.agentFilterList.trim().isEmpty()) {
            return true;
        }
        return this.isAllowAllAgents;
    }

    @StringSetter("allowAllAgents")
    public void setIsAllowAllAgents(boolean isAllowAllAgents) { this.isAllowAllAgents = isAllowAllAgents; }

    @StringGetter("modes")
    public String getModes() { return modes; }

    @StringSetter("modes")
    public void setModes(String modes) { this.modes = modes; }  

    @StringGetter("tourBasedModes")
    public String getTourBasedModes() { return tourModesList; }

    @StringSetter("tourBasedModes")
    public void setTourBasedModes(String modeList) { this.tourModesList = modeList; }

    @StringGetter("discontinuityPenalty")
    public double getDiscontinuityPenalty() { return discontinuityPenalty; }

    @StringSetter("discontinuityPenalty")
    public void setDiscontinuityPenalty(double discontinuityPenalty) { this.discontinuityPenalty = discontinuityPenalty; }

    @StringGetter("retrievalCostPenalty")
    public double getRetrievalCostPenalty() { return retrievalCostPenalty; }

    @StringSetter("retrievalCostPenalty")
    public void setRetrievalCostPenalty(double retrievalCostPenalty) { this.retrievalCostPenalty = retrievalCostPenalty; }

    @StringGetter("autoEncoderModel")
    public String getEncoderModel() { return autoEncoderModel; }

    @StringSetter("autoEncoderModel")
    public void setEncoderModel(String autoEncoderModel) { this.autoEncoderModel = autoEncoderModel; }

    @StringGetter("samplingPercentage")
    public double getSamplingPercentage() { return samplingPercentage; }

    @StringSetter("samplingPercentage")
    public void setSamplingPercentage(double samplingPercentage) { this.samplingPercentage = samplingPercentage; }
}