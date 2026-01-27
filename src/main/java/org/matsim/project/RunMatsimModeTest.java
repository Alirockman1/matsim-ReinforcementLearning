/* *********************************************************************** *
 * project: org.matsim.*												   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
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
package org.matsim.project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.RoutingConfigGroup.TeleportedModeParams;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * @author nagel
 *
 */
public class RunMatsimModeTest{

	public static void main(String[] args) {

		Config config;
		if ( args==null || args.length==0 || args[0]==null ){
			config = ConfigUtils.loadConfig( "scenarios/equil/config.xml" );
		} else {
			config = ConfigUtils.loadConfig( args );
		}

		config.controller().setOverwriteFileSetting( OverwriteFileSetting.deleteDirectoryIfExists );
		config.controller().setLastIteration(20);

		// ****** Anable network mode types *****
		{
			Collection<String> modes = new ArrayList<>();
			modes.add(TransportMode.car);
			modes.add(TransportMode.motorcycle);
			modes.add(TransportMode.pt);

			// 1. Define Network Modes in routing
			config.routing().setNetworkModes( modes );

			// 2. Define Network Modes in Qsim
			config.qsim().setMainModes( modes );
		}

		// ****** Set parameters for mode types *****	
		//{
			
		//}
		
		Scenario scenario = ScenarioUtils.loadScenario(config) ;

		{// Add the network modes to the allowed modes for each link
			for(Link link : scenario.getNetwork().getLinks().values() ){

				Set<String> modes = new HashSet<>();
				modes.add(TransportMode.car);
				modes.add(TransportMode.motorcycle);
				modes.add(TransportMode.pt);
				link.setAllowedModes(modes);

			};
			
		}
		
		Controler controler = new Controler( scenario ) ;
		
		// possibly modify controler here

//		controler.addOverridingModule( new OTFVisLiveModule() ) ;

//		controler.addOverridingModule( new SimWrapperModule() );
		
		// ---
		
		controler.run();
	}
	
}
