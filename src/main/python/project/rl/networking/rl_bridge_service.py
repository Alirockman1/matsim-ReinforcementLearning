import os
import logging
from typing import Dict, Any

from python_matsim_bridge import BaseSimulationBridgeService
from utils.SessionManager import *
from utils.StateUtils import prepare_state, get_chosen_action, update_experience
from core.RLAgent import DecentralizedQLearningAgent as QLearningAgent

logger = logging.getLogger("matsim_bridge")

class ReinforcementLearningBridgeService(BaseSimulationBridgeService):
    """
    Custom Reinforcement Learning Bridge Service implementing Decentralized Q-Learning.
    This class handles RL state initialization, action selection, and policy updates.
    """

    def __init__(self):
        super().__init__()
        self.agent = None
        self.trip_memory: Dict[str, Any] = {}
        self.daily_stats: Dict[str, Any] = {}

    def configure_session(self, config_data: Dict[str, Any]):
        """
        Initializes the Decentralized Q-Learning Agent using parameters 
        passed from MATSim's config.xml.
        """
        with self._lock:
            self.session_config = config_data

            # Parse available transport modes
            mode_string = config_data.get("modes", "")
            mode_list = [m.strip() for m in mode_string.split(",") if m.strip()]

            # Instantiate Q-Learning Agent
            self.agent = QLearningAgent(
                mode_list,
                alpha=config_data.get("alpha"),
                gamma=config_data.get("gamma"),
                initial_epsilon=config_data.get("epsilon"),
                epsilon_decay=config_data.get("epsilonDecay"),
                epsilon_minimum=config_data.get("epsilonMinimum"),
                max_iteration=config_data.get("trainingCutoffIteration")
            )

            model_type = config_data.get("modelType", "Q-Learning")
            model_file = config_data.get("modelFileName")

            # Load existing Q-Table weights if present
            if model_file and os.path.exists(model_file):
                logger.info(f"Loading existing Q-table from: {model_file}")
                self.agent.load_q_table(model_file)

            logger.info("RL Agent successfully initialized.")
            return {"status": "Agent Initialized", "model": model_type}

    def request_decision(self, observation: Any):
        """
        Receives trip observation state, records memory, and selects
        the agent's mode choice via the RL decision policy.
        """
        with self._lock:
            if not self.agent:
                raise RuntimeError("RL Agent has not been initialized.")

            agent_id = observation.agentID
            state = prepare_state(observation, self.trip_memory)
            self.agent.init_state(agent_id, state)

            chosen_mode = get_chosen_action(agent_id, self.agent, self.trip_memory)
            return str(chosen_mode)

    def process_feedback(self, feedback: Any):
        """
        Calculates trip rewards upon trip arrival, updates the Q-table,
        and logs experience.
        """
        with self._lock:
            if not self.agent:
                raise RuntimeError("RL Agent has not been initialized.")

            agent_id = feedback.agentID
            agent_memory = self.trip_memory.get(agent_id, None)

            if not agent_memory:
                raise KeyError(f"No active memory record found for agent ID: '{agent_id}'")

            # Compute step reward and transition state
            reward, next_state, termination = update_experience(feedback, self.agent, self.daily_stats)

            # Update Q-table policy
            self.agent.update_policy(
                agent_id, 
                agent_memory['state'], 
                agent_memory['mode'], 
                reward, 
                next_state, 
                print_tabel=False
            )

            delta_q = float(getattr(self.agent, "delta_q", 0.0))
            return {"status": "Experience Logged", "delta_q": delta_q}

    def checkpoint_state(self):
        """
        Persists the current Q-table to disk.
        """
        with self._lock:
            model_file = self.session_config.get("modelFileName")
            if self.agent and model_file:
                # Ensure target directory exists
                os.makedirs(os.path.dirname(os.path.abspath(model_file)), exist_ok=True)
                self.agent.save_q_table(model_file)
                logger.info(f"Q-Table successfully saved to {model_file}")
                return True
            return False

    def get_service_metrics(self):
        """
        Returns the active Q-Table dictionary for inspection/debugging.
        """
        with self._lock:
            if self.agent and hasattr(self.agent, "q_table"):
                return self.agent.q_table
            return {}