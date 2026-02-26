from fastapi import FastAPI, Query
from Models import ObserverData, ArrivalData
import sys
import os

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from utils.SimulationNarrativeLogger import SimulationNarrativeLogger
from reinforcement_learning.RewardCalculation import *
from reinforcement_learning.rl_agent import QLearningAgent

import random

app = FastAPI(title="MATSim_RL_Bridge")

# Global variables to persist settings across the simulation
trip_memory = {}
rl_settings = {}
agent = None

@app.post("/initialize")
async def initialize_rl(data: dict):
    '''
    Initializes the RL Agent based on parameters defined in MATSim's config.xml.
    This is called once at the start of the simulation.'''

    global agent, rl_settings
    rl_settings = data

    model_type = data.get("modelType")
    model_file = data.get("modelFileName")
    alpha = data.get("alpha", 0.1)
    gamma = data.get("gamma", 0.9)
    epsilon = data.get("epsilon", 0.1)

    # Initialize RL Model
    if model_type == "QLearning":
        agent = QLearningAgent(alpha=alpha, gamma=gamma, epsilon=epsilon)
    else:
        # Fallback to Q Learning
        agent = QLearningAgent(alpha=alpha, gamma=gamma, epsilon=epsilon)

    # Load existing knowledge
    if model_file and os.path.exists(model_file):
        agent.load_q_table(model_file)

    return {"status": "Agent Initialized", "model": model_type}

@app.get("/save-agent")
async def trigger_save():
    """ Called by MATSim at iteration ends based on saveInterval """

    global agent, rl_settings
    
    if agent and "modelFileName" in rl_settings:
        agent.save_q_table(rl_settings["modelFileName"])
        return {"status": "Table saved to disk"}
    
    return {"status": "Save failed"}

# Retrieve the passenger observation (S)
@app.post("/send-state")
async def receive_state(data: ObserverData):
    """ Record observations before trip commences """

    print(data.carAvailability)

    # Map car availability matrix
    if data.carAvailability:
        carAvailability = 1
    else:
        carAvailability = 0

        if 'car' in data.possibleModeSet:
            data.possibleModeSet.remove('car')

    # Create the time buckets of 15 minutes
    departureTime = (data.departureTimeSeconds // 900) * 900
    desiredArrivalTime = (data.nextActivityArrivalSeconds // 900) * 900

    # Create a state tuple
    state = (data.linkID, departureTime, desiredArrivalTime, carAvailability)
    
    # Initialize the state
    agent.init_state(state)

    trip_memory[data.agentID] = {
        "state": state,
        "available_modes": data.possibleModeSet,
        "full_data": data
    }

    print(f"\n### User: Environment successfully recorded for the agent {data.agentID} \n")

    return {"status": "Observation Recorded"}

# Retrieve the mode as defined by the reinforcement learning algorithm
@app.get("/get-action")
async def get_action(agentID: str = Query(...)):
    """ Step 2: Provide an action """
    
    agent_memory = trip_memory.get(agentID)
    
    # Safety fallback
    if not agent_memory:
        return {"mode_choice": "bike"}

    chosen_mode = agent.choose_action(agent_memory['state'], agent_memory['available_modes'], agent_memory['full_data'].simulationIteration)

    # chosen_mode = random.choice(agent_memory["available_modes"])

    print(f"\n### RL: {chosen_mode} is chosen for the agent {agentID} \n")

    trip_memory[agentID]["mode"] = chosen_mode
    
    return {"mode_choice": chosen_mode}

# Post the parameters for the reward
# Send the state (S_i), action (A_i) and reward (r_i+1) to the agent
@app.post("/send-reward")
async def receive_reward(data: ArrivalData):
    """ Step 3: Receive outcome and log result """
    agent_memory = trip_memory.pop(data.agentID, None)

    if agent_memory:
        memory = agent_memory['full_data']
        mode = agent_memory['mode']
        state = agent_memory['state']

        # Reward calculation module
        reward_calculator = GaussianReward(weight_late=1.5)
        reward = reward_calculator.calculate_reward(data.travelTimeSeconds,
                                                    memory.departureTimeSeconds, 
                                                    memory.nextActivityArrivalSeconds, 
                                                    slack_in_seconds = 300)
        
        print(f"\n### User: The computed reward for the agent {data.agentID} is: {reward} \n")

        # Log the experience - time stamp in the output file directory soo everytime is new
        logger = SimulationNarrativeLogger()
        logger.log_step(data.agentID, memory, mode, reward, data)


        if data.nextLinkID == 'terminal':
            next_state = None
            print(f"### User: Agent {data.agentID} has reached the end of their day.")
        else:
            # Create the next state tuple
            # Create the time buckets of 15 minutes
            next_departure = (data.nextDepartureTimeSeconds // 900) * 900
            next_arrival = (data.nextPlannedArrivalTimeSeconds // 900) * 900
            next_state = (data.nextLinkID, next_departure, next_arrival, state[3])
                    
            # Ensure the next state is initialized in the Q-table
            agent.init_state(next_state)
            print(f"### User: Next state for agent {data.agentID}: {next_state} \n")

        # Perform the BELLMAN update (learn)
        if memory.simulationIteration < rl_settings.get('trainingCutoffIteration', 100):
            agent.update_policy(state, mode, reward, next_state)

        return {"status": "Experience Logged", "reward": reward}
            
    return {"status": "Error: Agent context lost"}