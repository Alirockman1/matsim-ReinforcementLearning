package org.matsim.withinday.environment;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.api.core.v01.events.handler.PersonScoreEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.scoring.EventsToActivities;
import org.matsim.core.scoring.EventsToLegs;
import org.matsim.core.scoring.PersonExperiencedActivity;
import org.matsim.core.scoring.PersonExperiencedLeg;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Makes MATSim's own score available <i>during</i> the mobsim, after every completed plan element.
 *
 * <p>Rather than re-implementing the Charypar-Nagel utility function, this class keeps one
 * {@link ScoringFunction} per tracked agent, built by the very {@link ScoringFunctionFactory} the
 * simulation is configured with, and feeds it from the same public {@link EventsToActivities} /
 * {@link EventsToLegs} singletons that MATSim's own {@code ScoringFunctionsForPopulation} uses. The
 * activities and legs handed to it are therefore the <i>experienced</i> ones (real arrival times, real
 * travel times), not the routed estimates in the plan.
 *
 * <p>The instances are ours, which is the whole point: it lets {@link #finishDay} be called at the
 * moment an agent reaches its final activity, so the overnight activity utility and the daily mode
 * constants are available inline instead of only after the mobsim has ended.
 *
 * <p>Timing: {@code QSim.doSimStep} dispatches all events of a sim step before firing
 * {@code MobsimAfterSimStepListener}s, so by the time the within-day listener runs, this tracker has
 * already seen the completed trip's origin activity and all of its legs -- but not the just-started
 * destination activity, which is only scored once it ends. {@link #consumeStepScore} therefore returns
 * exactly "origin activity utility + trip disutility" for the trip that was just completed.
 */
@Singleton
public class MatsimScoreTracker implements ActivityStartEventHandler, PersonMoneyEventHandler,
        PersonScoreEventHandler, PersonStuckEventHandler {

    private static final Logger log = LogManager.getLogger(MatsimScoreTracker.class);

    private final ScoringFunctionFactory scoringFunctionFactory;
    private final Population population;

    private final Map<Id<Person>, ScoringFunction> scoringFunctions = new HashMap<>();
    private final Map<Id<Person>, Double> lastReadScore = new HashMap<>();

    /**
     * Scratch plan per agent, collecting experienced activities and legs so that complete trips can be
     * handed to {@link ScoringFunction#handleTrip}. Mirrors the {@code tripRecords} container of
     * MATSim's {@code ScoringFunctionsForPopulation}: in current MATSim the leg utility lives in
     * {@code CharyparNagelLegScoring}, which is a {@code TripScoring} -- driving only handleLeg() would
     * score no travel at all.
     */
    private final Map<Id<Person>, Plan> tripRecords = new HashMap<>();

    /** Agents whose day has been closed out by {@link #finishDay}; further events are ignored for them. */
    private final Set<Id<Person>> finishedAgents = new HashSet<>();

    @Inject
    public MatsimScoreTracker(ScoringFunctionFactory scoringFunctionFactory, Population population,
                              EventsToActivities eventsToActivities, EventsToLegs eventsToLegs) {
        this.scoringFunctionFactory = scoringFunctionFactory;
        this.population = population;

        eventsToActivities.addActivityHandler(this::handleExperiencedActivity);
        eventsToLegs.addLegHandler(this::handleExperiencedLeg);
    }

    /**
     * Builds a fresh scoring function for every agent to be tracked in the upcoming iteration.
     * Called once per iteration, before the mobsim starts.
     *
     * @param trackedAgents the agents the within-day module actually asks scores for (typically the
     *                      sampled RL agents); everyone else is skipped to avoid allocating a scoring
     *                      function per person in the population.
     */
    public void beginIteration(Collection<Id<Person>> trackedAgents) {
        this.scoringFunctions.clear();
        this.lastReadScore.clear();
        this.tripRecords.clear();
        this.finishedAgents.clear();

        for (Id<Person> agentId : trackedAgents) {
            Person person = this.population.getPersons().get(agentId);
            if (person == null) {
                log.warn("Cannot track score of '{}': not in population.", agentId);
                continue;
            }
            this.scoringFunctions.put(agentId, this.scoringFunctionFactory.createNewScoringFunction(person));
            this.lastReadScore.put(agentId, 0.0);
            this.tripRecords.put(agentId, PopulationUtils.createPlan());
        }

        log.info("Tracking MATSim scores for {} agents.", this.scoringFunctions.size());
    }

    /**
     * Score accumulated since the previous call for this agent, i.e. the utility of the plan elements
     * that MATSim scored in between. Reading it consumes it.
     */
    public double consumeStepScore(Id<Person> agentId) {
        ScoringFunction scoringFunction = this.scoringFunctions.get(agentId);
        if (scoringFunction == null) {
            return 0.0;
        }

        double currentScore = scoringFunction.getScore();
        double previousScore = this.lastReadScore.getOrDefault(agentId, 0.0);
        this.lastReadScore.put(agentId, currentScore);

        return currentScore - previousScore;
    }

    /**
     * Closes the agent's day and returns its full MATSim score.
     *
     * <p>The final activity is synthesized the way {@code EventsToActivities.finish()} does it -- start
     * time set, end time left undefined -- which is what makes {@code SumScoringFunction} route it to
     * {@code handleLastActivity}, wrapping it around with the morning activity. {@link
     * ScoringFunction#finish} is called afterwards for whatever the remaining sub-scorings settle up.
     *
     * @param lastActivity the activity the agent has just arrived at, used as a template.
     * @param arrivalTime  simulation time of that arrival.
     */
    public double finishDay(Id<Person> agentId, Activity lastActivity, double arrivalTime) {
        ScoringFunction scoringFunction = this.scoringFunctions.get(agentId);
        if (scoringFunction == null) {
            return 0.0;
        }

        if (this.finishedAgents.add(agentId)) {
            Activity overnightActivity = PopulationUtils.createActivityFromLinkId(
                    lastActivity.getType(), lastActivity.getLinkId());
            overnightActivity.setFacilityId(lastActivity.getFacilityId());
            if (lastActivity.getCoord() != null) {
                overnightActivity.setCoord(lastActivity.getCoord());
            }
            overnightActivity.setStartTime(arrivalTime);
            // End time deliberately left undefined.

            scoringFunction.handleActivity(overnightActivity);
            scoringFunction.finish();
        }

        double dayScore = scoringFunction.getScore();
        this.lastReadScore.put(agentId, dayScore);

        return dayScore;
    }

    /**
     * Current MATSim score of the agent without consuming or closing anything.
     * Returns {@link Double#NaN} for untracked agents.
     */
    public double getDayScore(Id<Person> agentId) {
        ScoringFunction scoringFunction = this.scoringFunctions.get(agentId);
        return scoringFunction != null ? scoringFunction.getScore() : Double.NaN;
    }

    public boolean isTracked(Id<Person> agentId) {
        return this.scoringFunctions.containsKey(agentId);
    }

    // --- Event plumbing -----------------------------------------------------------------------

    private void handleExperiencedActivity(PersonExperiencedActivity experienced) {
        ScoringFunction scoringFunction = getActiveScoringFunction(experienced.getAgentId());
        if (scoringFunction == null) {
            return;
        }

        scoringFunction.handleActivity(experienced.getActivity());
        this.tripRecords.get(experienced.getAgentId()).addActivity(experienced.getActivity());
    }

    private void handleExperiencedLeg(PersonExperiencedLeg experienced) {
        ScoringFunction scoringFunction = getActiveScoringFunction(experienced.getAgentId());
        if (scoringFunction == null) {
            return;
        }

        scoringFunction.handleLeg(experienced.getLeg());
        this.tripRecords.get(experienced.getAgentId()).addLeg(experienced.getLeg());
    }

    /**
     * Hands the completed trip to the scoring function once the agent arrives at a real activity.
     * Kept in sync with {@code ScoringFunctionsForPopulation.callTripScoring}.
     */
    @Override
    public void handleEvent(ActivityStartEvent event) {
        if (StageActivityTypeIdentifier.isStageActivity(event.getActType())) {
            return;
        }

        ScoringFunction scoringFunction = getActiveScoringFunction(event.getPersonId());
        if (scoringFunction == null) {
            return;
        }

        Plan tripRecord = this.tripRecords.get(event.getPersonId());

        Activity destinationActivity = PopulationUtils.createActivityFromLinkId(event.getActType(), event.getLinkId());
        destinationActivity.setStartTime(event.getTime());
        tripRecord.addActivity(destinationActivity);

        List<Trip> trips = TripStructureUtils.getTrips(tripRecord);
        for (Trip trip : trips) {
            if (trip != null) {
                scoringFunction.handleTrip(trip);
            }
        }

        tripRecord.getPlanElements().clear();
    }

    @Override
    public void handleEvent(PersonMoneyEvent event) {
        ScoringFunction scoringFunction = getActiveScoringFunction(event.getPersonId());
        if (scoringFunction != null) {
            scoringFunction.addMoney(event.getAmount());
        }
    }

    @Override
    public void handleEvent(PersonScoreEvent event) {
        ScoringFunction scoringFunction = getActiveScoringFunction(event.getPersonId());
        if (scoringFunction != null) {
            scoringFunction.addScore(event.getAmount());
        }
    }

    @Override
    public void handleEvent(PersonStuckEvent event) {
        ScoringFunction scoringFunction = getActiveScoringFunction(event.getPersonId());
        if (scoringFunction != null) {
            scoringFunction.agentStuck(event.getTime());
        }
    }

    private ScoringFunction getActiveScoringFunction(Id<Person> agentId) {
        return this.finishedAgents.contains(agentId) ? null : this.scoringFunctions.get(agentId);
    }

    @Override
    public void reset(int iteration) {
        // Nothing to do: beginIteration() rebuilds the state once the RL agents for the iteration are known.
    }
}
