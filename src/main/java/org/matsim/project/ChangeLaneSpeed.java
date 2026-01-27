package org.matsim.project;

import org.matsim.core.network.NetworkUtils;

public class ChangeLaneSpeed {

    public static void main(String[] args) {
        
        // Load the network file
        var network = NetworkUtils.readNetwork("scenarios/equil/network.xml");

        // Perform lane speed modifications
        network.getLinks().values().forEach(link -> {
            for (int i = 3; i <= 10; i++){

                if (link.getId().toString().equals(String.valueOf(i))){
                    link.setFreespeed(10.0);
                }

            }
         });

        // Save the modified network to a new file
        NetworkUtils.writeNetwork(network, "scenarios/equil/modified_network.xml");

        };
     }

