package org.matsim.withinday.siouxfalls.src.replanner;

import java.util.Collection;

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

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.ActivityEndRescheduler;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.mobsim.qsim.interfaces.MobsimVehicle;
import org.matsim.core.mobsim.qsim.qnetsimengine.QVehicleImpl;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.withinday.mobsim.WithinDayEngine;
import org.matsim.withinday.replanning.replanners.interfaces.WithinDayDuringActivityReplanner;
import org.matsim.withinday.replanning.replanners.interfaces.WithinDayReplanner;
import org.matsim.withinday.utils.EditTrips;
import org.matsim.withinday.siouxfalls.src.communication_networking.PythonConnectionManager;
import org.matsim.withinday.siouxfalls.utils.AgentObservation;
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
		Id<Link> linkId = currentActivity.getLinkId();
		Trip trip = TripStructureUtils.findTripStartingAtActivity( currentActivity, executedPlan );

		// If there is no trip after the activity.
		if (trip == null) return false;

		// Get the current iteration
        int iteration = SimulationState.currentIteration;
		
		// Get the current Simulation Time (Live Clock)
    	double simTime = this.getTime().orElse(0.0);
		
		// Get the AGENT ID
    	Id<Person> agentId = (Id<Person>) withinDayAgent.getId();

		// Start Observer class //
		Person person = this.scenario.getPopulation().getPersons().get(agentId);
		
		String json = new AgentObservation().prepareAgentObservation(scenario, withinDayAgent, currentActivity, trip, person);
		System.out.println(json);
		String routingMode = new PythonConnectionManager("127.0.0.1",5000).getMode(json);
		System.out.println(routingMode);
		
		// Get the departure time of the agent
		OptionalTime departureTime = TripStructureUtils.getDepartureTime(trip);

        // Log the events of the within day replanning
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

		// Ensure a vehicle of the routing mode exists
		prepareVehicleForNetworkMode(agentId, routingMode, linkId);

		// To replan pt legs, we would need internalInterface of type InternalInterface.class
		new EditTrips( this.tripRouter, scenario, qsimInternalInterface, timeInterpretation ).replanFutureTrip(trip, executedPlan, routingMode, departureTime.seconds() );
		
		return true;
	}

	private void prepareVehicleForNetworkMode(Id<Person> agentId, String mode, Id<Link> linkId) {
		
		// Get the matsim engine
		QSim qsim = (QSim) qsimInternalInterface.getMobsim();
		
		// All network modes mentioned on the route
		Collection<String> routingNetworkModes = this.scenario.getConfig().routing().getNetworkModes();

		// If a teleported or pt mode
		if (!routingNetworkModes.contains(mode)) {
			return; 
		}

		// Create a vehicle ID
		Id<Vehicle> vehicleId = Id.createVehicleId(agentId.toString() + "_" + mode);

		// Add the vehicle ID if it is not already present
		if (!qsim.getVehicles().containsKey(vehicleId)) {
			VehicleType vehicleType = scenario.getVehicles().getVehicleTypes().get(Id.create(mode, VehicleType.class));
			
			Vehicle vehicle = VehicleUtils.getFactory().createVehicle(vehicleId, vehicleType);

			MobsimVehicle mobsimVehicle = new QVehicleImpl(vehicle);

			qsim.addParkedVehicle(mobsimVehicle, linkId);
		}
	}

}