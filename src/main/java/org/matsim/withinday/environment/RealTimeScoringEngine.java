package org.matsim.withinday.environment;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.core.config.groups.RoutingConfigGroup.TeleportedModeParams;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.scoring.functions.ModeUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.project.rl.utils.CustomConfigGroup;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Composes the reinforcement-learning reward of a single agent.
 *
 * <p>The utility of the executed plan is <i>not</i> computed here: it comes straight from MATSim's own
 * scoring via {@link MatsimScoreTracker}, so activity utility, opening times, late-arrival and
 * early-departure penalties, travel disutility, line switches and daily mode constants are whatever the
 * configured {@code ScoringFunctionFactory} says they are.
 *
 * <p>What remains here are the two terms MATSim has no notion of, both specific to the within-day mode
 * choice problem: the penalty for choosing a mode whose vehicle is not at the agent's current location
 * ({@link #modeDiscontinuityPenalty}) and the cost of walking back to retrieve a stranded vehicle
 * ({@link #abandonedModePenalty}). Their parameters are read from MATSim's parsed
 * {@link ScoringParameters}, which are already per-second and resolved for the agent's subpopulation.
 */
public class RealTimeScoringEngine {

    private static final Logger localLogger = LogManager.getLogger(RealTimeScoringEngine.class);

    private final Scenario localScenario;
    private final WithinDayObserver localObserver;
    private final ScoringParameters scoringParameters;

    private final Set<String> allModesUsedToday = new HashSet<>();
    private String lastActiveMode = null;
    private double accumulatedDayScore = 0.0;
    private double accumulatedDayReward = 0.0;

    private double currentStepTripScore;
    private double currentStepDiscontinuityPenalty;
    private double currentStepAbandonedModePenalty;
    private double currentStepReward;

    public RealTimeScoringEngine(Scenario scenario, WithinDayObserver observer, ScoringParameters scoringParameters) {
        this.localScenario = scenario;
        this.localObserver = observer;
        this.scoringParameters = scoringParameters;
    }

    /**
     * Folds one completed trip into the agent's reward.
     *
     * @param matsimStepScore           utility MATSim accumulated since the previous trip, i.e. the
     *                                  origin activity plus the trip that was just completed.
     * @param mode                      main mode the agent actually used.
     * @param trip                      the completed trip, used for the counterfactual mode comparison.
     * @param modeRetrievalTime         seconds needed to fetch a vehicle left behind elsewhere, 0 if none.
     * @param modeDiscontinuityPenaltyMap per mode: 1 if the mode was not available at the origin, else 0.
     */
    public void compute(MobsimAgent agent, double matsimStepScore, String mode, Trip trip,
                        double modeRetrievalTime, Map<String, Integer> modeDiscontinuityPenaltyMap) {

        this.currentStepDiscontinuityPenalty = 0.0;
        this.currentStepAbandonedModePenalty = 0.0;
        this.currentStepReward = 0.0;

        this.allModesUsedToday.add(mode);
        this.lastActiveMode = mode;

        this.currentStepTripScore = matsimStepScore;
        this.accumulatedDayScore += matsimStepScore;

        modeDiscontinuityPenalty(modeDiscontinuityPenaltyMap, trip.getLegsOnly());
        abandonedModePenalty(modeRetrievalTime);

        CustomConfigGroup customConfigGroup = this.localObserver.getCustomConfigGroup();
        double discontinuityWeight = customConfigGroup.getModelWeights().get("discontinuityPenalty");
        double retrievalCostWeight = customConfigGroup.getModelWeights().get("retrievalCostPenalty");

        this.currentStepReward = this.currentStepTripScore
                - (discontinuityWeight * this.currentStepDiscontinuityPenalty)
                - (retrievalCostWeight * this.currentStepAbandonedModePenalty);
        this.accumulatedDayReward += this.currentStepReward;

        logResults(agent);
    }

    /**
     * Closes the day with the agent's final MATSim score, which additionally contains the overnight
     * activity utility and the daily mode constants. The difference to what was already accounted for
     * per trip carries no RL penalty of its own, so it enters the reward unchanged.
     */
    public void finalizeDay(double matsimDayScore) {
        double dayEndScore = matsimDayScore - this.accumulatedDayScore;

        this.accumulatedDayScore = matsimDayScore;
        this.accumulatedDayReward += dayEndScore;
    }

    /**
     * Penalizes using a mode whose vehicle was not parked at the trip's origin.
     *
     * <p>The reference point is the worst legal alternative: the same trip priced with each mode that
     * <i>was</i> available, keeping the least attractive one. The activity part of the step score is
     * identical for every alternative and cancels out, so only the travel disutility is compared.
     */
    private void modeDiscontinuityPenalty(Map<String, Integer> modeDiscontinuityPenaltyMap, List<Leg> legsInTrip) {

        double modeDiscontinuityPenalty = 0.0;
        Integer usedModeUnavailable = modeDiscontinuityPenaltyMap.get(this.lastActiveMode);

        if (usedModeUnavailable != null && usedModeUnavailable == 1) {

            double worstLegalTripDisutility = Double.MAX_VALUE;
            boolean foundLegalMode = false;

            for (Map.Entry<String, Integer> entry : modeDiscontinuityPenaltyMap.entrySet()) {
                if (entry.getValue() != 0) {
                    continue;
                }

                ModeUtilityParameters legalModeParams = this.scoringParameters.modeParams.get(entry.getKey());
                if (legalModeParams == null) {
                    continue;
                }

                double prospectiveTripDisutility = tripDisutility(legsInTrip, legalModeParams);

                if (prospectiveTripDisutility < worstLegalTripDisutility) {
                    worstLegalTripDisutility = prospectiveTripDisutility;
                    foundLegalMode = true;
                }
            }

            if (foundLegalMode) {
                ModeUtilityParameters usedModeParams = this.scoringParameters.modeParams.get(this.lastActiveMode);
                double actualTripDisutility = usedModeParams != null ? tripDisutility(legsInTrip, usedModeParams) : 0.0;

                modeDiscontinuityPenalty = this.currentStepTripScore + (actualTripDisutility - worstLegalTripDisutility);
            }
        }

        this.currentStepDiscontinuityPenalty = Math.abs(modeDiscontinuityPenalty);
    }

    /**
     * Travel disutility of the given legs if the whole trip were made with one mode. Follows
     * {@code CharyparNagelLegScoring}: time and distance per leg, mode constant once per trip.
     */
    private double tripDisutility(List<Leg> legsInTrip, ModeUtilityParameters modeParams) {
        double distanceBeta = modeParams.marginalUtilityOfDistance_m
                + (modeParams.monetaryDistanceCostRate * this.scoringParameters.marginalUtilityOfMoney);

        double tripDisutility = modeParams.constant;

        for (Leg leg : legsInTrip) {
            tripDisutility += leg.getTravelTime().orElse(0.0) * modeParams.marginalUtilityOfTraveling_s;

            if (leg.getRoute() != null) {
                double distance = leg.getRoute().getDistance();
                if (!Double.isNaN(distance)) {
                    tripDisutility += distance * distanceBeta;
                }
            }
        }

        return tripDisutility;
    }

    /**
     * Cost of walking back to a vehicle the agent left at an earlier location. Per retrieval second:
     * the walk distance and time disutility plus twice the foregone activity utility (the time is lost
     * both here and at the destination).
     */
    private void abandonedModePenalty(double modeRetrievalTime) {

        // A retrieval time of 0.0 means the vehicle is not stranded, so there is nothing to pay for.
        if (modeRetrievalTime == 0.0) {
            this.currentStepAbandonedModePenalty = 0.0;
            return;
        }

        ModeUtilityParameters walkParams = this.scoringParameters.modeParams.get(TransportMode.walk);

        double betaDistanceDefaultMode = walkParams != null ? walkParams.marginalUtilityOfDistance_m : 0.0;
        double betaTimeDefaultMode = walkParams != null ? walkParams.marginalUtilityOfTraveling_s : 0.0;

        TeleportedModeParams walkTeleportParams =
                this.localScenario.getConfig().routing().getTeleportedModeParams().get(TransportMode.walk);
        double teleportedSpeed = (walkTeleportParams != null && walkTeleportParams.getTeleportedModeSpeed() != null)
                ? walkTeleportParams.getTeleportedModeSpeed()
                : 0.0;

        double retrievalCostPerSecond = (teleportedSpeed * betaDistanceDefaultMode)
                + betaTimeDefaultMode
                + (2.0 * this.scoringParameters.marginalUtilityOfPerforming_s);

        this.currentStepAbandonedModePenalty = modeRetrievalTime * retrievalCostPerSecond;
    }

    private void logResults(MobsimAgent agent) {

        String agentId = agent.getId().toString();

        localLogger.info(String.format("REWARD: Agent %s | MATSim Step Score: %.4f", agentId, this.currentStepTripScore));
        localLogger.info(String.format("REWARD: Agent %s | Mode Discountinuity Penalty: %.4f", agentId, this.currentStepDiscontinuityPenalty));
        localLogger.info(String.format("REWARD: Agent %s | Mode Tour Penalty: %.4f", agentId, this.currentStepAbandonedModePenalty));
        localLogger.info(String.format("REWARD: Agent %s | Step Reward: %.4f", agentId, this.currentStepReward));
    }

    public void reset() {
        // Clear history trackers
        this.allModesUsedToday.clear();
        this.lastActiveMode = null;

        // Reset daily cumulative trackers
        this.accumulatedDayScore = 0.0;
        this.accumulatedDayReward = 0.0;

        // Reset step cache variables
        this.currentStepTripScore = 0.0;
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
