import os
from datetime import datetime

class SimulationNarrativeLogger:
    def __init__(self, output_dir="scenarios\sioux-falls\modified\output"):
        self.output_dir = output_dir
        if not os.path.exists(self.output_dir):
            os.makedirs(self.output_dir)
            
        # Create a unique filename for this simulation run
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.log_path = os.path.join(self.output_dir, f"simulation_history_{timestamp}.log")

    def log_step(self, agent_id, state_data, action, reward, arrival_data):
        
        pc_time = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        
        with open(self.log_path, 'a') as f:
            f.write(f"{'='*60}\n")
            f.write(f"TIMESTAMP: {pc_time} | AGENT: {agent_id}\n")
            f.write(f"{'-'*60}\n")
            
            # S0: The full state observation
            f.write(f"STATE (S0):\n")
            f.write(f"  - Link ID: {state_data.linkID}\n")
            f.write(f"  - Departure: {state_data.departureTime} ({state_data.departureTimeSeconds}s)\n")
            f.write(f"  - Car Available: {state_data.carAvailability}\n")
            f.write(f"  - Possible Modes: {state_data.possibleModeSet}\n")
            f.write(f"  - Expected Arrival: {state_data.nextActivityArrivalTime}\n")
            
            # A0: The decision
            f.write(f"ACTION (A0): {action}\n")
            
            # R1: The outcome
            f.write(f"REWARD (R1): {reward}\n")
            f.write(f"  - Actual Travel Time: {arrival_data.travelTimeSeconds}s\n")
            f.write(f"  - Distance: {arrival_data.distance}m\n")
            f.write(f"  - Delays: {arrival_data.delaySeconds}s\n")
            f.write(f"  - Transfers: {arrival_data.numberOfTransfers}\n")
            f.write(f"{'='*60}\n\n")
