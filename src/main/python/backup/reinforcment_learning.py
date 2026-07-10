from fastapi import FastAPI, Query
from Models import ObserverData, ArrivalData
from fastapi.exceptions import RequestValidationError
from starlette.responses import JSONResponse
import sys
import os

# Custom Modules
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from main.python.utils.StateUtils import *
from utils.SimulationNarrativeLogger import SimulationNarrativeLogger
from main.python.backup.RewardCalculation import *
from main.python.reinforcement_learning.RLAgent import QLearningAgent

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
    Initializes the RL Agent based on parameters defined in MATSim's config.xml.
    This is called once at the start of the simulation.'''

    global agent, system_metadata
    
    system_metadata = data

    mode_list = [modes.strip() for modes in data.get("modes").split(",")]

    agent = QLearningAgent(
        mode_list,
        alpha=data.get("alpha", 0.1),
        gamma=data.get("gamma", 0.9),
        epsilon=data.get("epsilon", 0.1),
        epsilon_decay=data.get("epsilonDecay", 0.99),
        max_iteration= data.get("trainingCutoffIteration", 100)
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

# Retrieve the passenger observation (S)
@app.post("/send-state")
async def receive_state(data: ObserverData):
    """ Record observations before trip commences """
    global agent

    # Initialize the state
    agent.init_state(state)
    
    departure_time = create_time_buckets(data.departureTimeSeconds)

    # Create a state tuple
    state = (data.departureLinkID, data.arrivalLinkID, departure_time, data.carAvailability)

    # Check if any previous modes were selected
    existing_modes = [] 
    
    if data.agentID in trip_memory:
        # Check for 'mode_choice_history' specifically
        existing_modes = trip_memory[data.agentID].get("mode_choice_history", [])

    trip_memory[data.agentID] = {
        "state": state,
        "available_modes": data.possibleModeSet,
        "full_data": data,
        "mode_choice_history": existing_modes 
    }

    print(f"PYTHON SERVICE: Environment successfully recorded for the agent {data.agentID}: {state}.")

    return {"status": "Observation Recorded"}


# Retrieve the mode as defined by the reinforcement learning algorithm
@app.get("/get-action")
async def get_action(agentID: str = Query(...)):
    """ Step 2: Provide an action """
    
    agent_memory = trip_memory.get(agentID)
    
    # Safety fallback
    if not agent_memory:
        # Fall back to default mode
        chosen_mode = "pedestrian"
    else:
        #chosen_mode = "bike"
        chosen_mode = agent.choose_action(agent_memory['state'], agent_memory['available_modes'], agent_memory['full_data'].simulationIteration)

    print(f"PYTHON SERVICE: {chosen_mode} is chosen for the agent {agentID}.")

    trip_memory[agentID]["mode"] = chosen_mode

    if "mode_choice_history" not in trip_memory[agentID]:
        trip_memory[agentID]["mode_choice_history"] = []
        
    trip_memory[agentID]["mode_choice_history"].append(chosen_mode)

    return {"mode_choice": chosen_mode}

# Post the parameters for the reward
# Send the state (S_i), action (A_i) and reward (r_i+1) to the agent
@app.post("/send-reward")
async def receive_reward(data: ArrivalData):
    """ Step 3: Receive outcome and log result """

    global system_metadata

    WEIGHT_TOUR_PENALTY = 100

    agent_memory = trip_memory.get(data.agentID, None)

    if agent_memory:
        memory = agent_memory['full_data']
        mode = agent_memory['mode']
        state = agent_memory['state']

        # Reward calculation module
        #reward_calculator_gaussian = GaussianReward(weight_late=1.5)
        #reward_gaussian = reward_calculator_gaussian.calculate_reward(data.travelTimeSeconds,
        #                                            memory.departureTimeSeconds, 
        #                                            memory.nextActivityArrivalSeconds, 
        #                                            slack_in_seconds = 300)
        
        score_object = MatsimScore(mode, system_metadata, WEIGHT_TOUR_PENALTY)
        reward_episode = score_object.calculate_reward(data.travelTimeSeconds, data.distance, data.activityDurationSeconds, 
                                               data.numberOfTransfers, data.modeDiscontinuityPenalty, data.subpopulation, 
                                               data.modeRetrivalTime)
        matsim_score_episode = score_object.matsim_score

        print(f"PYTHON SERVICE: The computed MATSIM segment reward for the agent {data.agentID} is: {matsim_score_episode}")
        
        print(f"PYTHON SERVICE: The computed reward for the agent {data.agentID} is: {reward_episode}.")

        # Accumulate the reward and matsim_score for the day
        if data.agentID not in daily_stats:
            daily_stats[data.agentID] = {"reward": 0.0, "matsim_score": 0.0}
        
        daily_stats[data.agentID]["reward"] += reward_episode
        daily_stats[data.agentID]["matsim_score"] += matsim_score_episode

        if data.nextDepartureLinkID == 'terminal':
            next_state = None
            print(f"PYTHON SERVICE: Agent {data.agentID} has reached the end of their day.")
        else:
            # Create the next state tuple
            # Create the time buckets of 5 minutes
            next_departure = create_time_buckets(data.nextDepartureTimeSeconds)
            next_state = (data.nextDepartureLinkID, data.nextArrivalLinkID, next_departure, state[3])
                    
            # Ensure the next state is initialized in the Q-table
            agent.init_state(next_state)
            print(f"PYTHON SERVICE: Next state for agent {data.agentID}: {next_state}.")

        # Perform the BELLMAN update (learn)
        agent.update_policy(state, mode, reward_episode, memory.simulationIteration, next_state)

        # Log the experience
        logger = SimulationNarrativeLogger()
        logger.log_step(data.agentID, memory, mode, reward_episode, data, memory.simulationIteration, agent.q_table)

        if data.nextDepartureLinkID == 'terminal':
            final_data = trip_memory.pop(data.agentID) 
            agent_stats = daily_stats.pop(data.agentID, {"reward": 0.0, "matsim_score": 0.0})

            total_reward = agent_stats["reward"]
            total_matsim_score = agent_stats["matsim_score"]

            mode_history = final_data.get('mode_choice_history', [mode])

            logger.log_day_summary(
                agent_id=data.agentID,
                total_reward=total_reward,
                iteration=memory.simulationIteration,
                q_table=agent.q_table
            )

            # Save to csv for better post-processing
            logger.save_to_csv(
                iteration=memory.simulationIteration,
                epsilon=system_metadata.get("gamma", 0.9),
                agent_id=data.agentID,
                mode_history=mode_history,
                matsim_score=total_matsim_score,
                total_reward=total_reward
            )
        

        return {"status": "Experience Logged", "reward": reward_episode}
            
    return {"status": "Error: Agent context lost"}

def create_time_buckets(nextDepartureTimeSeconds, minutes = 5):
    
    seconds = minutes*60;

    next_departure = (nextDepartureTimeSeconds // seconds) * seconds

    return next_departure