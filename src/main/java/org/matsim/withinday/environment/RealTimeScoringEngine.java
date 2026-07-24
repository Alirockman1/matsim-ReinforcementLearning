package org.matsim.withinday.environment;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ScoringParameterSet;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.project.rl.utils.CustomConfigGroup;

import com.google.inject.Inject;

import org.matsim.api.core.v01.population.Activity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RealTimeScoringEngine {

    Scenario localScenario;
    private static final Logger localLogger = LogManager.getLogger(RealTimeScoringEngine.class);
    private final WithinDayObserver localObserver;

    private final Set<String> allModesUsedToday = new HashSet<>();
    private final Set<String> allActivityTypes = new HashSet<>();
    private String lastActiveMode = null;
    private double accumulatedDayScore = 0.0;
    private double accumulatedDayReward = 0.0;

    private double currentStepTripScore;
    private double currentStepTravelDisutility;
    private double currentStepActivityReward;
    private double currentStepDiscontinuityPenalty;
    private double currentStepAbandonedModePenalty;
    private double currentStepReward;

    @Inject
    public RealTimeScoringEngine(Scenario scenario, WithinDayObserver observer){
        this.localScenario = scenario;
        this.localObserver = observer;
    }

    private void modeDiscontinuityPenalty(Map<String, Integer> modeDiscontinuityPenaltyMap, ScoringParameterSet populationScoringParameters,
                                            List<Leg> legsInTrip, double constraintsPenalty){
        
        double modeDiscontinuityPenalty = 0.0;
        String currentMode = this.lastActiveMode;
        double activityPerformingReward = this.currentStepActivityReward;
        double tripScore = this.currentStepTripScore;


        if (modeDiscontinuityPenaltyMap.containsKey(currentMode) && modeDiscontinuityPenaltyMap.get(currentMode) == 1) {
            
            double minLegalScore = Double.MAX_VALUE;
            boolean foundLegalMode = false;

            // Evaluate all valid choices
            for (Map.Entry<String, Integer> entry : modeDiscontinuityPenaltyMap.entrySet()) {
                String legalModeOption = entry.getKey();
                int isNotAvailable = entry.getValue();

                if (isNotAvailable == 0) {
                    ModeParams legalModeParams = populationScoringParameters.getModes().get(legalModeOption);
                    
                    if (legalModeParams != null) {
                        double legalModeTimePenaltyPerSec = legalModeParams.getMarginalUtilityOfTraveling() / 3600.0;
                        double legalModeDistancePenalty = legalModeParams.getMarginalUtilityOfDistance();
                        double legalModeConst = legalModeParams.getConstant();
                        
                        // Calculate hypothetical travel cost for this legal choice
                        double prospectiveTripDisutility = 0.0;
                        for (Leg leg : legsInTrip) {
                            prospectiveTripDisutility += (leg.getTravelTime().orElse(0.0) * legalModeTimePenaltyPerSec) + 
                                                         (leg.getRoute().getDistance() * legalModeDistancePenalty) + 
                                                         legalModeConst;
                        }

                        // Combine tracking elements for the legal choice total utility
                        double prospectiveTotalScore = activityPerformingReward + constraintsPenalty + prospectiveTripDisutility;

                        // Retain the highest utility (closest to 0 / maximum behavioral choice)
                        if (prospectiveTotalScore < minLegalScore) {
                            minLegalScore = prospectiveTotalScore;
                            foundLegalMode = true;
                        }
                    }
                }
            }

            // 3. Subtract based on the utility earned using the worst legal mode
            if (foundLegalMode) {
                modeDiscontinuityPenalty = tripScore + (tripScore - minLegalScore);
            }
        }

        this.currentStepDiscontinuityPenalty = Math.abs(modeDiscontinuityPenalty);
    }

    private void abandonedModePenalty(MobsimAgent agent, double modeRetrievalTime, ScoringParameterSet populationScoringParameters, double betaPerformingPerSeconds) {
        
        // If retrieval time is 0.0, the asset isn't stranded, so there is no penalty
        if (modeRetrievalTime == 0.0) {
            this.currentStepAbandonedModePenalty = 0.0;
            return;
        }

        // Extract default 'walk' mode configurations from the subpopulation parameters
        ModeParams walkParams = populationScoringParameters.getModes().get("walk");
        
        double betaDistanceDefaultMode = 0.0;
        double betaTimeDefaultMode = 0.0;
        
        if (walkParams != null) {
            betaDistanceDefaultMode = walkParams.getMarginalUtilityOfDistance();
            betaTimeDefaultMode = walkParams.getMarginalUtilityOfTraveling() / 3600.0;
        }

        // Fetch the default teleported speed (e.g., walk speed in m/s)
        double teleportedSpeed = this.localScenario.getConfig().routing().getTeleportedModeParams().get("walk").getTeleportedModeSpeed();

        // Compute the per-second disutility of retrieving the asset
        // Equation: (V_walk * beta_dist + beta_travel_time + 2 * beta_perf)
        double retrievalCostPerSecond = (teleportedSpeed * betaDistanceDefaultMode) + betaTimeDefaultMode + (2.0 * betaPerformingPerSeconds);

        double abandonedModePenalty = modeRetrievalTime * retrievalCostPerSecond;

        this.currentStepAbandonedModePenalty = abandonedModePenalty;
    }

    private void logResults(MobsimAgent agent){
        
        String agentId = agent.getId().toString();

        localLogger.info(String.format("REWARD: Agent %s | Trip Penalty: %.4f | Activity Reward: %.4f | Total Trip Score: %.4f", 
            agentId, this.currentStepTravelDisutility, this.currentStepActivityReward, this.currentStepTripScore));
        localLogger.info(String.format("REWARD: Agent %s | Mode Discountinuity Penalty: %.4f", agentId, this.currentStepDiscontinuityPenalty));
        localLogger.info(String.format("REWARD: Agent %s | Mode Tour Penalty: %.4f", agentId, this.currentStepAbandonedModePenalty));
        localLogger.info(String.format("REWARD: Agent %s | Step Reward: %.4f", agentId, this.currentStepReward));      

    }

    public void compute(MobsimAgent agent, double currentTime, Activity activity, String mode, Trip trip, double modeRetrievalTime, int numberOfTransfers, Map<String, Integer> modeDiscontinuityPenaltyMap){

        this.currentStepTripScore = 0.0;
        this.currentStepTravelDisutility = 0.0;
        this.currentStepActivityReward = 0.0;
        this.currentStepDiscontinuityPenalty = 0.0;
        this.currentStepAbandonedModePenalty = 0.0;
        this.currentStepReward = 0.0;

        this.allModesUsedToday.add(mode);
        this.lastActiveMode = mode;
        
        String activityType = activity.getType();
        Map<String, Object> profile = this.localObserver.getAgentDemographicRecord(agent);
        String subpopulation = (String) profile.getOrDefault("subpopulation", "default");

        // 1. Completed activity reward
        ScoringConfigGroup scoringConfig = this.localScenario.getConfig().scoring();
        ScoringParameterSet populationScoringParameters = scoringConfig.getScoringParameters(subpopulation);
        if (populationScoringParameters == null) {
            populationScoringParameters = scoringConfig.getScoringParametersPerSubpopulation().get(null);
        }
        
        // Look up specific parameters for this activity type from the set
        ActivityParams activityParameters = populationScoringParameters.getActivityParams(activity.getType());
        if (activityParameters == null) return;

        double departureTime = trip.getLegsOnly().get(0).getDepartureTime().orElse(currentTime);
        double arrivalTime = activity.getStartTime().orElse(0.0);
        double activityDurationSeconds = Math.max(0, departureTime - arrivalTime);

        double betaPerformingPerSeconds = populationScoringParameters.getPerforming_utils_hr()/3600.0;

        double timeTypical = activityParameters.getTypicalDuration().seconds();
        double timeZero = timeTypical * Math.exp(-1.0);

        if (activityDurationSeconds > timeZero) {
            this.currentStepActivityReward = betaPerformingPerSeconds * timeTypical * Math.log(activityDurationSeconds / timeZero);
        }

        /*
        this.localLogger.error(String.format(
            "MATSIM UNIT CHECK -> Agent: %s | Duration (sec): %.2f | timeTypical (parsed): %.2f | timeZero: %.6f | betaPerSec: %.6f",
            agent.getId().toString(), activityDurationSeconds, timeTypical, timeZero, betaPerformingPerSeconds
        ));*/

        // 2. Compute opportunity penalties
        double constraintsPenalty = 0.0;
        CustomConfigGroup customConfigGroup = this.localObserver.getCustomConfigGroup();

        // Late Arrival Penalty
        if (activityParameters.getLatestStartTime().isDefined() && arrivalTime > activityParameters.getLatestStartTime().seconds()) {

            if (!allActivityTypes.contains(activityType)) {

                double lateArrivalSeconds = arrivalTime - activityParameters.getLatestStartTime().seconds();
                double lateArrivalUtilityPerSecond = populationScoringParameters.getLateArrival_utils_hr() / 3600.0;
                constraintsPenalty += lateArrivalUtilityPerSecond * lateArrivalSeconds;

            }
        }

        // Early Departure Penalty
        if (activityParameters.getEarliestEndTime().isDefined() && departureTime < activityParameters.getEarliestEndTime().seconds()) {
            double earlyArrivalSeconds = activityParameters.getEarliestEndTime().seconds() - departureTime;
            double earlyArrivalUtilityPerSecond = populationScoringParameters.getEarlyDeparture_utils_hr() / 3600.0;
            constraintsPenalty += earlyArrivalUtilityPerSecond * earlyArrivalSeconds;
        }

        // Short Duration Penalty
        if (activityParameters.getMinimalDuration().isDefined() && activityDurationSeconds < activityParameters.getMinimalDuration().seconds()) {
            double shortStayTimeSeconds = activityParameters.getMinimalDuration().seconds() - activityDurationSeconds;
            double earlyArrivalUtilityPerSecond = populationScoringParameters.getEarlyDeparture_utils_hr() / 3600.0;
            constraintsPenalty += earlyArrivalUtilityPerSecond * shortStayTimeSeconds;
        }

        // 3. Trip travel cost
        List<Leg> legsInTrip = trip.getLegsOnly();
        Set<String> modesChargedInThisTrip = new HashSet<>();

        double betaUtilityOfMoney = populationScoringParameters.getMarginalUtilityOfMoney();
        double betaWaitingPerSecond = populationScoringParameters.getMarginalUtlOfWaiting_utils_hr() / 3600.0;
        double utilityOfLineSwitch = populationScoringParameters.getUtilityOfLineSwitch();
        
        for (Leg leg : legsInTrip) {
            String legMode = leg.getMode();
            ModeParams modeParams = populationScoringParameters.getModes().get(legMode);
            double legDurationDistance = leg.getRoute().getDistance();

            if (legMode.equals("pt-waiting") || legMode.equals("wait")) {
                this.currentStepTravelDisutility += legDurationDistance * betaWaitingPerSecond;
                continue; // Skip the rest of the loop for this leg, as it has no distance/constant
            }
            
            if (modeParams != null) {
                double betaTravelTimePerSecond = modeParams.getMarginalUtilityOfTraveling() / 3600.0;
                double betaTravelDistance = modeParams.getMarginalUtilityOfDistance();
                double monetaryDistanceCostRate = modeParams.getMonetaryDistanceRate();
                double combinedDistanceBeta = betaTravelDistance + (monetaryDistanceCostRate * betaUtilityOfMoney);

                double distanceCost = legDurationDistance * combinedDistanceBeta;

                double travelTimeCost = leg.getTravelTime().orElse(0.0) * betaTravelTimePerSecond;

                double modeConstantCost = 0.0;
                if (!modesChargedInThisTrip.contains(legMode)) {
                    modeConstantCost = modeParams.getConstant();
                    modesChargedInThisTrip.add(legMode);
                }

                this.currentStepTravelDisutility += distanceCost + travelTimeCost + modeConstantCost;
            }
        }

        // Apply the Line Switch (Transfer) Penalty
        if (numberOfTransfers > 0) {
            this.currentStepTravelDisutility += numberOfTransfers * utilityOfLineSwitch;
        }

        // Total Step Score - kai Nagel
        this.currentStepTripScore = this.currentStepActivityReward + constraintsPenalty + this.currentStepTravelDisutility;
        this.accumulatedDayScore += this.currentStepTripScore;

        // Mode discontinuity penalty
        modeDiscontinuityPenalty(modeDiscontinuityPenaltyMap, populationScoringParameters, legsInTrip, constraintsPenalty);
        
        // Mode retrieval penalty
        abandonedModePenalty(agent, modeRetrievalTime, populationScoringParameters, betaPerformingPerSeconds);

        // Get penalty weights
        double discontinuityWeight = customConfigGroup.getModelWeights().get("discontinuityPenalty");
        double retrievalCostWeight = customConfigGroup.getModelWeights().get("retrievalCostPenalty");

        this.currentStepReward = this.currentStepTripScore - (discontinuityWeight * this.currentStepDiscontinuityPenalty) - (retrievalCostWeight * this.currentStepAbandonedModePenalty);
        this.accumulatedDayReward += this.currentStepReward; 

        logResults(agent);

        allActivityTypes.add(activityType);
    }

    public void computeDayEndScore(MobsimAgent agent, List<Trip> trips, Activity endOfDayActivity) {
        double endOfDayPerformingUtility = 0.0;
        double dailyModeConstantsBonusOrPenalty = 0.0;

        String subpopulation = (String) localScenario.getPopulation().getPersons().get(agent.getId()).getAttributes().getAttribute("subpopulation");

        if (subpopulation == null){
            subpopulation = "null";
        }

        if (!trips.isEmpty()) {
            Trip firstTrip = trips.get(0);
            Activity firstActivity = firstTrip.getOriginActivity();

            double finalActivityArrivalTime = endOfDayActivity.getStartTime().orElse(86400.0);
            double endOfDayDuration = 0.0;

            boolean isWrappedActivity = firstActivity.getType().equalsIgnoreCase(endOfDayActivity.getType()) &&
                    firstActivity.getFacilityId() == endOfDayActivity.getFacilityId() &&
                    firstActivity.getCoord().equals(endOfDayActivity.getCoord());

            if (isWrappedActivity) {
                double firstActivityDepartureTime = firstActivity.getEndTime().orElse(0.0);
                endOfDayDuration = 86400.0 - finalActivityArrivalTime;
                //endOfDayDuration = (86400.0 - finalActivityArrivalTime) + firstActivityDepartureTime;
            } else {
                endOfDayDuration = 86400.0 - finalActivityArrivalTime;
            }

            ScoringParameterSet populationScoringParameters = this.localScenario.getConfig().scoring().getScoringParameters(subpopulation);
            
            if (populationScoringParameters != null) {
                double betaPerformingPerSec = populationScoringParameters.getPerforming_utils_hr() / 3600.0;
                ActivityParams activityParameters = populationScoringParameters.getActivityParams(endOfDayActivity.getType());

                double timeTypical = activityParameters.getTypicalDuration().seconds();
                double timeZero = timeTypical * Math.exp(-1.0);

                if (endOfDayDuration > timeZero) {
                    endOfDayPerformingUtility = betaPerformingPerSec * timeTypical * Math.log(endOfDayDuration / timeZero);
                }

                Set<String> uniqueModesUsed = new HashSet<>();
                for (Trip trip : trips) {
                    for (Leg leg : trip.getLegsOnly()) {
                        uniqueModesUsed.add(leg.getMode());
                    }
                }

                double betaMoney = populationScoringParameters.getMarginalUtilityOfMoney();

                for (String usedMode : uniqueModesUsed) {
                    ModeParams modeParams = populationScoringParameters.getModes().get(usedMode);
                    if (modeParams != null) {
                        // Flat structural daily utility change (e.g. general car ownership hassle)
                        dailyModeConstantsBonusOrPenalty += modeParams.getDailyUtilityConstant();

                        // Flat financial daily penalty (e.g. daily parking fee or transit pass) scaled by money utility
                        dailyModeConstantsBonusOrPenalty += (modeParams.getDailyMonetaryConstant() * betaMoney);
                    }
                }
            }
        }

        double dayEndScore = endOfDayPerformingUtility + dailyModeConstantsBonusOrPenalty;

        this.accumulatedDayScore += dayEndScore;
        this.accumulatedDayReward += dayEndScore;
    }

    public void reset() {
        // Clear history trackers
        this.allModesUsedToday.clear();
        this.allActivityTypes.clear();
        this.lastActiveMode = null;
        
        // Reset daily cumulative trackers
        this.accumulatedDayScore = 0.0;
        this.accumulatedDayReward = 0.0;

        // Reset step cache variables
        this.currentStepTripScore = 0.0;
        this.currentStepTravelDisutility = 0.0;
        this.currentStepActivityReward = 0.0;
        this.currentStepDiscontinuityPenalty = 0.0;
        this.currentStepAbandonedModePenalty = 0.0;
        this.currentStepReward = 0.0;
    }

    // GET step-wise rewards
    public double getCurrentStepTripScore() { return this.currentStepTripScore; }
    public double getCurrentStepDiscontinuityPenalty() { return this.currentStepDiscontinuityPenalty; }
    public double getCurrentStepAbandonedModePenalty() { return this.currentStepAbandonedModePenalty; }
    public double getCurrentStepReward() { return this.currentStepReward; }

    // GET cumalative rewards
    public double getAccumulatedDayScore() { return this.accumulatedDayScore; }
    public double getAccumulatedDayReward() { return this.accumulatedDayReward; }

}
