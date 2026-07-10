import math

class GaussianReward:
    ''' This is formaulated based on the research paper "Transportation Mode Selection Using Reinforcement Learning
    in Simulation of Urban Mobility" '''

    def __init__(self, weight_late, weight_wait=1.0):
        self.weight_late = weight_late
        self.weight_wait = weight_wait

    def calculate_reward(self, travel_time, departure_time, activity_start_time, slack_in_seconds, discretization = 60):

        # Time to arrive at the destination
        arrived_time = departure_time + travel_time
        
        # The measure of lateness / earliness
        time_delay = arrived_time - activity_start_time

        # Observed times
        observed_travel_time = travel_time/discretization
        observed_delay = time_delay/discretization
        sigma = slack_in_seconds/discretization

        # Gaussian reward distribution
        reward = math.exp(-(observed_delay**2) / (2 * (sigma**2)))

        ## Compute the reward

        # HANDLE MIDNIGHT / END-OF-DAY (when activity start time is not a hard definition)
        if activity_start_time <= 0:
            # Return a reward based solely on travel effort (discretized)
            return 0 - (observed_travel_time)
        
        penalty = observed_travel_time

        if  time_delay > slack_in_seconds: # Too late arrival
            penalty += observed_delay * self.weight_late
        elif time_delay < -slack_in_seconds: # Too early arrival
            penalty += abs(observed_delay) * self.weight_wait

        # Modify the reward based on the travel time penalty
        return (reward - (penalty*0.01))

class MatsimScore:
    ''' Reward formulation for individual trip using MATSIM Scoring formulation '''

    def __init__(self, mode, system_metadata):  
        self._metadata = system_metadata
        self._mode = mode
        self._matsim_score = 0.0

    def _base_reward(self, scoring_parameters, travel_time, distance, duration, transfers, waiting_time):
        
        ##--- Activity reward ---##
        # Reward of perfroming an activity
        benefit_per_second = float(scoring_parameters.get('marginalUtilityOfPerforming_s', 0.0))
        activity_reward = duration * benefit_per_second

        ##--- TRAVEL COST ---##
        # MATSIM trip score #
        mode_config = scoring_parameters.get(self._mode, scoring_parameters.get('other', {}))
        
        # Extract the specific penalty values for the mode used
        travel_penalty_per_second = float(mode_config.get('marginalUtilityOfTraveling_util_hr', 0.0)) / 3600.0
        travel_distance_penalty_per_meter = float(mode_config.get('marginalUtilityOfDistance_util_m', 0.0))
        mode_fixed_cost = float(mode_config.get('constant', 0.0))
        
        trip_disutility = (
            mode_fixed_cost + 
            (travel_time * travel_penalty_per_second) + 
            (distance * travel_distance_penalty_per_meter)
        )

        # PUBLIC TRANSIT SPECIFIC COSTS #
        if self._mode == "pt":
            transfer_penalty = float(scoring_parameters.get('utilityOfLineSwitch', 0.0)) * transfers

            # Penalty of waiting at a stop
            waiting_penalty_per_second = float(scoring_parameters.get('marginalUtlOfWaiting_s', 0.0))
            
            # We apply this extra waiting cost only if they used public transit
            pt_waiting_penalty = (waiting_time * waiting_penalty_per_second)

            trip_disutility += (transfer_penalty + pt_waiting_penalty)

        ##--- KAI-NAGEL SCORE ---##
        self._matsim_score = activity_reward + trip_disutility

        return activity_reward, benefit_per_second


    def calculate_reward(self, travel_time, distance, completed_event_duration, number_of_transfers, mode_discontinuity_map, subpopulation, mode_retrieval_time, total_waiting_time=0.0):

        # Individual subpopulation
        scoring_configs = self._metadata.get("scoringParameters", {})
        agent_parameters = scoring_configs.get(subpopulation)

        activity_reward, activity_utility = self._base_reward(agent_parameters, travel_time, distance, completed_event_duration, number_of_transfers, total_waiting_time)

        ## Penalty weights
        weight_discontinuity = self._metadata.get("discontinuityWeight", {})
        weight_retrieval_cost = self._metadata.get("retrievalCostWeight", {})

        ##--- MODE DISCONTINUITY - GHOST MODE ---##
        # Mode discontinuity disutility
        if (mode_discontinuity_map.get(self._mode) == 1):
            legal_modes = [modes for modes, values in mode_discontinuity_map.items() if values == 0]

            # Compute the entire score for all legal modes
            legal_scores = []
            for legal_mode in legal_modes:
                legal_mode_config = agent_parameters.get(legal_mode, agent_parameters.get('other', {}))
                legal_mode_time_penalty = float(legal_mode_config.get('marginalUtilityOfTraveling_util_hr', 0.0)) / 3600.0
                legal_mode_distance_penalty = float(legal_mode_config.get('marginalUtilityOfDistance_util_m', 0.0))
                legal_mode_const = float(legal_mode_config.get('constant', 0.0))
                
                m_disutility = legal_mode_const + (travel_time * legal_mode_time_penalty) + (distance * legal_mode_distance_penalty)
                legal_scores.append(activity_reward + m_disutility)

            # Take the minimum legal score
            min_legal_score = min(legal_scores)

            # Weight_disconinuity_penalty = self._matsim_score + (self._matsim_score - min_legal_score)
            discontinuity_disutility = self._matsim_score + (self._matsim_score - min_legal_score)
        else:
            discontinuity_disutility = 0
        
        mode_discontinuity_penalty = discontinuity_disutility * mode_discontinuity_map.get(self._mode, 0)

        mode_discontinuity_penalty *= weight_discontinuity

        print(f"PYTHON SERVICE: The computed mode discontinuity penalty is: {mode_discontinuity_penalty}")

        ##--- OPPORTUNITY COST OF STRANDED ASSET ---##
        # Equation: OCSA = delta_t * (V_walk * beta_dist + beta_travel_time + 2 * beta_perf)
        # Extract the default mode parameters for the subpopulation
        default_mode_config = agent_parameters.get('walk', agent_parameters.get('other', {}))
        
        beta_dist_default = float(default_mode_config.get('marginalUtilityOfDistance_util_m', 0.0))
        beta_time_default = float(default_mode_config.get('marginalUtilityOfTraveling_util_hr', 0.0)) / 3600.0
        speed_default = self._metadata.get("defaultTeleportedSpeed")
        
        # Compute the disutility of retrieving the assests
        retrieval_asset_cost = ((speed_default * beta_dist_default) + beta_time_default + (2 * activity_utility))

        # Penalty of next day discomfort
        OCSA = mode_retrieval_time * retrieval_asset_cost

        OCSA *= weight_retrieval_cost

        print(f"PYTHON SERVICE: The computed opportunity cost of stranded vehicle penalty is: {OCSA}")

        ##--- TOTAL REWARD ---##
        reward = self._matsim_score - mode_discontinuity_penalty -  OCSA

        return reward
    
    @property
    def matsim_score(self):
        return self._matsim_score


