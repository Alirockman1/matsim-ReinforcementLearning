/* *********************************************************************** *
 * project: org.matsim.*
 * NextLegReplanner.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2010 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.withinday.siouxfalls.src.replanner;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;

import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.ActivityEndRescheduler;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.core.utils.timing.TimeInterpretation;

import org.matsim.withinday.mobsim.WithinDayEngine;
import org.matsim.withinday.replanning.replanners.interfaces.WithinDayDuringActivityReplanner;
import org.matsim.withinday.replanning.replanners.interfaces.WithinDayReplanner;
import org.matsim.withinday.utils.EditTrips;

import org.matsim.withinday.siouxfalls.utils.SimulationState;
import org.matsim.withinday.siouxfalls.utils.WithinDayLogger;


public class NextLegModeReplanner extends WithinDayDuringActivityReplanner {

    private final TripRouter tripRouter;
	private final TimeInterpretation timeInterpretation;
	private final WithinDayEngine engine;
	private InternalInterface qsimInternalInterface;
	private final WithinDayLogger logger;

	public NextLegModeReplanner(Id<WithinDayReplanner> id, Scenario scenario, ActivityEndRescheduler internalInterface, WithinDayEngine withinDayEngine, TripRouter tripRouter, TimeInterpretation timeInterpretation) {
		super(id, scenario, internalInterface);
		this.tripRouter = tripRouter;
		this.timeInterpretation = timeInterpretation;
		this.engine = withinDayEngine;

		this.logger = new WithinDayLogger("scenarios\\sioux-falls\\modified\\output");
	}

	@Override
	public boolean doReplanning(MobsimAgent withinDayAgent) {
		
		// 1. Skip ONLY the bus/pt drivers
		if (withinDayAgent instanceof org.matsim.core.mobsim.qsim.pt.TransitDriverAgentImpl) {
			return false; // Only the driver is "discarded" here
		}

		Plan executedPlan = WithinDayAgentUtils.getModifiablePlan(withinDayAgent);

		System.out.println("MAP DETECTED: Agent up for replanning: " + withinDayAgent.getId());

		// If we don't have an executed plan
		if (executedPlan == null) return false;

		// Get the activity currently performed by the agent as well as the subsequent trip.
		Activity currentActivity = (Activity) WithinDayAgentUtils.getCurrentPlanElement(withinDayAgent);
		Trip trip = TripStructureUtils.findTripStartingAtActivity( currentActivity, executedPlan );

		// If there is no trip after the activity.
		if (trip == null) return false;
		
		String routingMode = TripStructureUtils.identifyMainMode(trip.getTripElements());
		OptionalTime departureTime = TripStructureUtils.getDepartureTime(trip);

        int iteration = SimulationState.currentIteration;
		
		// 2. Get the current Simulation Time (Live Clock)
    	double simTime = this.getTime().orElse(0.0);
		
		// 3. Get the AGENT ID
    	Id<Person> agentId = (Id<Person>) withinDayAgent.getId();


        // Log the event
        try {
            logger.logReplanningEvent(iteration, simTime, agentId, routingMode);
        }
        catch(Exception e){
            System.err.println("Failed to log replanning event: " + e.getMessage());
        }

		// Extract the internalInterface from the withinday engine -> Experiemental (only once per replanner instance)
        if (this.qsimInternalInterface == null) {
            try {
                java.lang.reflect.Field field = WithinDayEngine.class.getDeclaredField("internalInterface");
                field.setAccessible(true);
                this.qsimInternalInterface = (InternalInterface) field.get(this.engine);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

		// To replan pt legs, we would need internalInterface of type InternalInterface.class
		new EditTrips( this.tripRouter, scenario, qsimInternalInterface, timeInterpretation ).replanFutureTrip(trip, executedPlan, routingMode, departureTime.seconds() );
		
		return true;
	}


}