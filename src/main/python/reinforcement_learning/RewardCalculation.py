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


