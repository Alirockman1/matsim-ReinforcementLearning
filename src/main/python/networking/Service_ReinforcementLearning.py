from http.client import HTTPException
from fastapi import FastAPI, Query
from Models import ObserverData, ArrivalData
from fastapi.responses import PlainTextResponse
import sys
import os

# Custom Modules
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from utils.SessionManager import *
from utils.StateUtils import *
from utils.SimulationNarrativeLogger import SimulationNarrativeLogger
from reinforcement_learning.RLAgent import DecentralizedQLearningAgent as QLearningAgent

# Start the Fast API server
app = FastAPI(title="MATSim_RL_Bridge")

# Persistence Layer
trip_memory, daily_stats, system_metadata = {}, {}, {}
agent = None

@app.get("/health")
async def health_check():
    '''
    Used by the CommunicationManager to verify the Python service
    is ready before starting the simulation iterations.'''

    return {"status": "ready", "service": "MATSim_RL_Bridge"}

@app.post("/initialize")
async def initialize_rl(data: dict):
    '''
    Initializes the RL Agent based on parameters defined in MATSim's
    config.xml. This is called once at the start of the simulation.'''

    global agent, system_metadata
    
    system_metadata = data

    mode_list = [modes.strip() for modes in data.get("modes").split(",")]

    agent = QLearningAgent(
        mode_list,
        alpha=data.get("alpha"),
        gamma=data.get("gamma"),
        initial_epsilon=data.get("epsilon"),
        epsilon_decay=data.get("epsilonDecay"),
        epsilon_minimum=data.get("epsilonMinimum"),
        max_iteration= data.get("trainingCutoffIteration")
        )

    model_type = data.get("modelType")
    model_file = data.get("modelFileName")

    # Load existing knowledge
    if model_file and os.path.exists(model_file):
        agent.load_q_table(model_file)

    return {"status": "Agent Initialized", "model": model_type}

@app.get("/save-model")
async def trigger_save():
    """ Called by MATSim at iteration ends based on saveInterval """

    global agent, system_metadata
    
    if agent and "modelFileName" in system_metadata:
        agent.save_q_table(system_metadata["modelFileName"])
        return {"status": "Table saved to disk"}
    
    return {"status": "Save failed"}

# Send the passenger observation (S) & retrieve the mode (A)
@app.post("/get-action", response_class=PlainTextResponse)
async def get_action(data: ObserverData):
    ''' 
    Merged Endpoint: Receives the observation data,
    updates the agent state, and returns the chosen 
    mode choice immediately for a single trip.
    '''

    global agent

    try:
        # Record observation
        agent_id = data.agentID
        state = prepare_state(data, trip_memory)
        agent.init_state(agent_id, state)

        # Get the RL choice
        chosen_mode = get_chosen_action(data.agentID, agent, trip_memory)

        # Return the mode directly as plain text (e.g., "bike")
        return chosen_mode

    except Exception as e:
        raise HTTPException(status_code=500, detail="Error in recieve state")
    

# Send the state (S_i), and reward (r_i+1) to the agent
@app.post("/send-reward")
async def receive_reward(data: ArrivalData):
    ''' 
    End point to compute the reward and update the decision policy
    based on the data recieved after the agent perfroms the leg '''

    try:
        agent_id = data.agentID
        agent_memory = trip_memory.get(agent_id, None)

        # Get the next simulation step
        reward, next_state, termination = update_experience(data, agent, daily_stats)

        # Learning Update
        agent.update_policy(agent_id, agent_memory['state'], agent_memory['mode'], reward, next_state, print_tabel=False)

        # Log the experience
        # logger = SimulationNarrativeLogger(output_dir = system_metadata["outputDirectory"])
        # logger.log_step(data, agent_memory, reward, agent.q_table)
        # if termination:
        #   finalize_session(data, trip_memory, agent, logger)
        delta_q = float(agent.delta_q)

        return {"status": "Experience Logged", "delta_q": delta_q}
    
    except Exception as e:
        raise HTTPException(status_code=505, detail="Error in recieving reward")

@app.get("/get-qtable")
async def get_local_table():
    ''' 
    End point to retrive the q_table for the specified agent '''

    complete_q_table = agent.q_table

    return complete_q_table