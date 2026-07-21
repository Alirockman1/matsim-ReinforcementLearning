import os
from datetime import datetime
from pathlib import Path
import csv

class SimulationNarrativeLogger:
    def __init__(self, output_dir):
        self.output_directory = Path(output_dir)
        self.iteration_directory = self.output_directory / "ITERS"

        if not os.path.exists(self.iteration_directory):
            os.makedirs(self.iteration_directory)
    
    def save_to_csv(self, iteration, epsilon, agent_id, mode_history, matsim_score, total_reward):
        
        csv_file_path = self.output_directory / "mode_choice_results.csv"
        
        file_exists = os.path.isfile(csv_file_path)
        
        # Calculate how many modes we have
        num_modes = len(mode_history)

        # Create dictionary to save
        row_data = {
            'Iteration': iteration,
            'Epsilon': epsilon,
            'Agent ID': agent_id,
            'Kai-nagel Utility Score': f"{matsim_score:.2f}",
            'Total Reward': f"{total_reward:.2f}"
        }
        
        for i, mode in enumerate(mode_history):
            row_data[f"Mode {i+1}"] = mode

        fieldnames = ['Iteration', 'Epsilon', 'Agent ID'] + [f"Mode {i+1}" for i in range(5)] + ['Kai-nagel Utility Score','Total Reward']
        
        with open(csv_file_path, mode='a', newline='') as f:
 
            writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction='ignore')
            
            if not file_exists:
                writer.writeheader()
                
            writer.writerow(row_data)

    def log_day_summary(self, agent_id, total_reward, iteration, q_table):
        """
        Logs the final accumulated results for an agent's entire day.
        """
        iteration_directory = self.iteration_directory / f"it.{iteration}"
        iteration_directory.mkdir(parents=True, exist_ok=True)
        
        log_path = os.path.join(iteration_directory, f"simulation_history.log")

        with open(log_path, 'a', encoding="utf-8") as f:
            f.write(f"\n")
            f.write(f"TOTAL DAY REWARD for {agent_id}: {total_reward:.4f}\n")
            f.write(f"{'='*112}\n")

    def log_step(self, data, memory, reward, q_table):
        
        agent_id = data.agentID
        state = memory['state'] 
        action = memory['mode']
        iteration = memory['full_data'].simulationIteration

        iteration_directory = self.iteration_directory / f"it.{iteration}"
        iteration_directory.mkdir(parents=True, exist_ok=True)

        log_path = os.path.join(iteration_directory, f"simulation_history.log")
        
        pc_time = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        asset_map = {
            0: "NONE",
            1: "CAR",
            2: "BIKE",
            3: "CAR+BIKE"
        }


        asset_string = asset_map.get(memory['full_data'].assetAvailability)
        
        with open(log_path, 'a', encoding="utf-8") as f:
            f.write(f"\n{'='*112}\n")
            f.write(f"TIMESTAMP: {pc_time} | AGENT: {agent_id} | SUBPOPULATION: {memory['population']}\n")
            f.write(f"{'-'*112}\n")
            
            # S0: The full state observation
            f.write(f"STATE (S0):\n")
            f.write(f"  - Departure Link ID: {memory['full_data'].departureLinkID}\n")
            f.write(f"  - Departure Coordinates: ({memory['full_data'].departurePositionX}, {memory['full_data'].departurePositionY})\n")
            f.write(f"  - Departure: {memory['full_data'].departureTime} ({memory['full_data'].departureTimeBin}s)\n")
            f.write(f"  - Arrival Link ID: {memory['full_data'].arrivalLinkID}\n")
            f.write(f"  - Desired Arrival Coordinates: ({memory['full_data'].arrivalPositionX}, {memory['full_data'].arrivalPositionY})\n")
            f.write(f"  - Asset Available: {asset_string}\n")
            f.write(f"  - Possible Modes: {memory['full_data'].possibleModeSet}\n")
            
            # A0: The decision
            f.write(f"ACTION (A0): {action}\n")
            
            # R1: The outcome
            f.write(f"REWARD (R1): {reward}\n")
            f.write(f"  - Actual Travel Time: {data.travelTimeSeconds}s\n")
            f.write(f"  - Distance: {data.distance}m\n")
            f.write(f"  - Delays: {data.delaySeconds}s\n")
            f.write(f"  - Transfers: {data.numberOfTransfers}\n")
            f.write("\n")

            self.log_Qtable(q_table, f)

    def log_Qtable(self, q_tables, f):
        width = 128 

        for agent_id, q_table in q_tables.items():
            
            if not q_table:
                        continue
            
            f.write(f"\n{'=' * width}\n")
            f.write(f"{' ' * 45} PERSONAL Q-TABLE: {agent_id} {' ' * 45}\n")
            f.write(f"{'=' * width}\n")
        
            # Headers aligned with the 140-character width
            header = (f"{'ORIGIN (X,Y)':<18} | {'TARGET (X,Y)':<18} | {'DEP BIN':<8} | "
                    f"{'FLEXIBILITY':<12} | {'ASSETS':<8} || "
                    f"{'CAR':<8} | {'PT':<8} | {'BIKE':<8} | {'WALK':<8}\n")
            f.write(header)
            f.write("-" * width + "\n")

            # Sort the table items for a consistent log structure (by Departure Bin)
            # Assuming state: (xj, yj, xn, yn, tau, sigma, asset)
            sorted_table = sorted(q_table.items(), key=lambda x: (x[0][4], x[0][0]))

            for state, actions in sorted_table:
                # Unpack the 8-item state tuple
                xj, yj, xn, yn, tau, sigma, asset = state
                
                # Format spatial coordinates for precision
                orig_str = f"{xj:>8.1f},{yj:<8.1f}"
                dest_str = f"{xn:>8.1f},{yn:<8.1f}"

                # Map categories to readable labels for the narrative log
                flex_label = "FLEXIBLE" if sigma == 1 else "CONSTRAINED"
                asset_label = {0: "NONE", 1: "CAR", 2: "BIKE", 3: "CAR+BIKE"}.get(asset, str(asset))
                
                # Construct the row
                row = (f"{orig_str:<18} | "     # Origin
                    f"{dest_str:<18} | "        # Target
                    f"{str(tau):<8} | "         # Departure Bin
                    f"{flex_label:<12} | "      # Schedule Flexibility
                    f"{asset_label:<8} || "     # Asset Availability
                    f"{actions.get('car', 0.0):>8.2f} | "
                    f"{actions.get('pt', 0.0):>8.2f} | "
                    f"{actions.get('bike', 0.0):>8.2f} | "
                    f"{actions.get('pedestrian', 0.0):>8.2f}\n")
                
                f.write(row)
            
            f.write(f"{'=' * width}\n\n")
