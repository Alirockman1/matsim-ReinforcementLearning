import random
import numpy as np
import pickle
import os

class QLearningAgent:
    def __init__(self, alpha=0.1, gamma=0.9, epsilon=0.9):
        self._alpha = alpha      # Learning rate
        self._gamma = gamma      # Discount factor
        self._epsilon = epsilon  # Epsilon-greedy factor
        self._epsilon_min = 0.1
        self._epsilon_decay = 0.98
        self._model_file = "model.pkl"
        
        # Initialize an empty Q-Table
        self._q_table = {}
        
        self.modes = ["car", "pt", "bike", "pedestrian"]

    @property
    def q_table(self):
        return self._q_table
    
    @property
    def modes(self):
        return self._modes

    @modes.setter
    def modes(self, modes_list):
        self._modes = modes_list

    @property
    def file_path(self):
        return self._file_path

    @file_path.setter
    def file_path(self, path):
        self._file_path = path

    def init_state(self, state_tuple):
        ''' Adds a new 'row' to the table if we haven't seen this link/time combo '''

        if state_tuple not in self._q_table:
            # Zero - initialize all possible modes
            self._q_table[state_tuple] = {mode: 0.0 for mode in self.modes}

    def save_q_table(self):
        ''' Saves the Q-table to a file using pickle '''

        try:
            with open(self._file_path, 'wb') as f:
                pickle.dump(self._q_table, f)
            print(f"### RL: Q-Table successfully saved to {self._file_path}")
        except Exception as e:
            print(f"### RL: Failed to save Q-Table: {e} ###")

    def load_q_table(self, load_file_path):
        """ Loads a Q-table from a file """

        if os.path.exists(load_file_path):
            try:
                with open(load_file_path, 'rb') as f:
                    self._q_table = pickle.load(f)

                print(f"### RL: Q-Table loaded from {load_file_path}. Knowledge Size: {len(self._q_table)} states")

            except Exception as e:
                print(f"### RL: Failed to load Q-Table: {e}")
        else:
            print(f"### RL: No existing model file found at {load_file_path}. Starting fresh.")

    def choose_action(self, state_tuple, available_modes, iteration, is_training=True):
        ''' Epsilon-Greedy Selection For Modes Available ''' 
        
        epsilon = max(self._epsilon_min, self._epsilon * (self._epsilon_decay ** iteration))

        # Exploration (Random)
        if random.random() < epsilon and is_training:
            action = random.choice(available_modes)
        # Exploitation
        else:
            state_policy = self._q_table[state_tuple]
            # Filter policy for only modes available for the agent
            filtered_policy = {mode: state_policy[mode] for mode in available_modes}
            action = max(filtered_policy, key=filtered_policy.get)

        return action

    def update_policy(self, state, action, reward, next_state=None):
        ''' 
        The Bellman Equation Update 
        Q(s, a) = Q(s, a) + alpha * [reward + gamma * max(Q(s', a')) - Q(s, a)] '''
        
        # Get current Q(s,a)
        current_reward = self._q_table[state][action]
        
        if next_state is None:
            target = reward # No furture update required
        # Get Max Q(s', a') for the next state
        else:
            future_reward_value = max(self._q_table[next_state].values())
            # Discount the future reward to the current reward
            target = reward + self._gamma * future_reward_value

        # Update Rule: Q(s,a) = Q(s,a) + alpha * [Reward + gamma * MaxQ(s') - Q(s,a)]
        self._q_table[state][action] += self._alpha * (target - current_reward)

        print(self._q_table)