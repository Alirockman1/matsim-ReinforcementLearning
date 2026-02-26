from fastapi import FastAPI
from pydantic import BaseModel
import uvicorn
import random
import os
import csv

class ObserverData(BaseModel):
    agentID:str
    linkID: str
    departureTime: str
    nextActivityArrivalTime: str
    nextActivityArrivalSeconds: float
    departureTimeSeconds: float
    carAvailability: bool
    possibleModeSet: list[str]

class ArrivalData(BaseModel):
    agentID:str
    travelTimeSeconds: float
    numberOfTransfers: int
    distance: float
    travelDisutility: float
    startDayMode: str
    delaySeconds: float = 0.0

class ModeData(BaseModel):
    mode_choice:str

class RLModeChoice:

    def __init__(self, host="127.0.0.1", port=5000):
        self._app = FastAPI(title="MATSim_RL_Bridge")
        self._host = host
        self._port = port
        self._file = "networkingLog.csv"

        self._agent_memory = {}
        self._reward = {}

        self.prepare_csv()
        self.setup_routes()

    @property
    def app(self):
        return self._app
    
    def prepare_csv(self):
        with open(self._file, 'w', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(["agentID", "linkID", "departureTime", "plannedArrivalTime", "travelDistance", "carAvailability",  "mode",  "delayedArrivalSeconds", "travelTimeSeconds", "numberOfTransfers", "travelDisutility", "startDayMode" ])
    
    def setup_routes(self):
        @self._app.post("/ModeChoice", response_model=ModeData)
        def receive_environment(data: ObserverData):
            print(f"\n Received the envivornment data for Agent: {data.agentID} \n")

            mode = self.get_mode(data)

            self._agent_memory[data.agentID] = {
                "data": data,
                "mode": mode
            }

            return ModeData(mode_choice=mode)
        
        @self._app.post("/Arrival")
        async def receive_arrival(data: ArrivalData):
            print(f"\n Beginning reward computation for Agent: {data.agentID} \n")

            agent_id = data.agentID

            plannedArrival = self._agent_memory[agent_id]["data"].nextActivityArrivalSeconds
            actualArrival = self._agent_memory[agent_id]["data"].departureTimeSeconds + data.travelTimeSeconds

            data.delaySeconds = actualArrival - plannedArrival

            self._reward[data.agentID] ={
                "data": data
            }
            
            self.log_to_csv(agent_id)
            return {"status": "recorded"}

    def get_mode(self, data: ObserverData):
        print(f"\n Retriveing transportation mode for Agent: {data.agentID} \n")

        MODE_SOLUTION_SPACE = data.possibleModeSet

        if not data.carAvailability:
            if 'car' in MODE_SOLUTION_SPACE:
                        MODE_SOLUTION_SPACE.remove('car')
        
        # The RL model will be called here
        # return random.choice(MODE_SOLUTION_SPACE)
        return 'bike'
    

    def log_to_csv(self, agent_id):
        # Observed Data:
            # Start Link (at departure event)
            # Current time (at departure event)
            # Planned next activity start time (at departure event) !!!
            # Travel Distance (at next activity arrival)  ????
            # Departure time traffic severity (1,0,-1) (at departure event) !!!
            # Car availablity (at departure event)
            # Tour start mode (at next activity arrival)
        
        # Computed for RL loss:
            # Travel Time (at next activity arrival)
            # Time to arrival - desired time to arrival (at next activity arrival)
            # Number of transfers (at next activity arrival) --- (number of interactions/2)-1
            # Travel economical disutility (at next activity arrival)
        
        if agent_id in self._agent_memory and agent_id in self._reward:
            agent_reward = self._reward.pop(agent_id)
            agent_memory = self._agent_memory.pop(agent_id)

            with open(self._file, 'a', newline='') as f:
                    writer = csv.writer(f)
                    writer.writerow([
                        agent_memory["data"].agentID,
                        agent_memory["data"].linkID,
                        agent_memory["data"].departureTime,
                        agent_memory["data"].nextActivityArrivalTime,
                        agent_reward["data"].distance,
                        agent_memory["data"].carAvailability,
                        agent_memory["mode"],
                        agent_reward["data"].delaySeconds,
                        agent_reward["data"].travelTimeSeconds,
                        agent_reward["data"].numberOfTransfers,
                        agent_reward["data"].travelDisutility,
                        agent_reward["data"].startDayMode

                    ])

            print(f"Logged trip for {agent_reward["data"].agentID} in memory")

# Script to run in terminal to start the server
# cd E:\MyFolder\Upskill\ApplicationProcessingInterface\FastAPI\.myenv\Scripts
# ./activate
# cd E:\MyFolder\Upskill\ApplicationProcessingInterface\FastAPI
# python -m uvicorn Service:app --port 5000 --reload

# Create the instance
service_instance = RLModeChoice(host="127.0.0.1", port=5000)
app = service_instance.app