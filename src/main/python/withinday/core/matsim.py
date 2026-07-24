import os
from nodes import WorkerNode
from project.rl.parameter_tuning.optimizer import HyperParameterOptimizer

def run_matsim_within_optuna(scenario):

    # --- Worker Initialization ---
    optimizer = HyperParameterOptimizer(
        study_name="matsim_mode_optimization",
        scenario_name=scenario
    )
    
    # Trigger one localized study block assignment run 
    optimizer.run_optuna()

def run_single_instance(scenario):

    params_string = os.environ.get("PARAMS", "")
    replanner_class = os.environ.get("REPLANNER_CLASS", "default")
    observer_class = os.environ.get("OBSERVER_CLASS", "default")

    params_dictionary = {}

    if params_string:
        for item in params_string.split():
            if "=" in item:
                key, value = item.split("=", 1)
                params_dictionary[key] = value
    
    worker = WorkerNode(
        replanner_class=replanner_class,
        observer_class=observer_class,
        output_directory=os.environ.get("MATSIM_OUTPUT_BASE"),
        config_file_path=f"/app/scenarios/{scenario}/input/config.xml",
        matsim_iteration=os.environ.get("MATSIM_ITERATION"),
        num_threads=os.environ.get("NUM_THREADS"),
        training_iteration=os.environ.get("MAX_TRAINING_ITERATION"),
        learning_rate=params_dictionary["alpha"],
        gamma=params_dictionary["gamma"],
        epsilon_decay=params_dictionary["epsilon_decay"],
        min_epsilon=params_dictionary["epsilon_minimum"],
        penalty_weights=[params_dictionary["w_1"], params_dictionary["w_2"]],
        java_heap=os.environ.get("JAVA_HEAP")
    )

    worker.run()

if __name__ == "__main__":
    # Check the objective role env variable
    role = os.environ.get("OBJECTIVE", "").lower().strip()
    scenario = os.environ.get("SCENARIO")
    
    if role == "optimization":
        run_matsim_within_optuna(scenario)
    else:
        run_single_instance(scenario)