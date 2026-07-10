import os
import subprocess

class WorkerNode:
    def __init__(self, output_directory, config_file_path, matsim_iteration, num_threads, training_iteration, learning_rate, gamma, epsilon_decay, min_epsilon, penalty_weights = [1.0,1.0], java_heap="12g"):
        """
        Initializes the distributed worker tuning node."""

        self._output_dir = output_directory
        self._config_path = config_file_path
        
        self._iteration= matsim_iteration
        self._threads = num_threads
        self._training_iteration = training_iteration
        self._alpha = learning_rate
        self._gamma = gamma
        self._decay= epsilon_decay
        self._min_epsilon = min_epsilon

        self._java_heap = java_heap

        if len(penalty_weights) == 2:
            self._discontinuity_weight = penalty_weights[0]
            self._retrieval_cost_weight = penalty_weights[1]
        elif len(penalty_weights) == 1:
            self._discontinuity_weight = penalty_weights[0]
            self._retrieval_cost_weight = penalty_weights[0]
        else:
            self._discontinuity_weight = 1.0
            self._retrieval_cost_weight = 1.0

    def run(self):
        '''
        Execute the java run script'''

        cmd = [
            "java",
            f"-Xmx{self._java_heap}",
            "-Djava.awt.headless=true",
            "-cp", "/app/simulation.jar",
            "org.matsim.rl.RunExternalModeChoice",
            f"{self._config_path}",
            f"--config:controller.outputDirectory={self._output_dir}",
            f"--config:controller.lastIteration={self._iteration}",
            f"--config:global.numberOfThreads={self._threads}",
            f"--config:agentModeChoice.trainingCutoffIteration={self._training_iteration}",
            f"--config:agentModeChoice.alpha={self._alpha}",
            f"--config:agentModeChoice.gamma={self._gamma}",
            f"--config:agentModeChoice.epsilonDecay={self._decay}",
            f"--config:agentModeChoice.epsilonMinimum={self._min_epsilon}",
            f"--config:agentModeChoice.discontinuityPenalty={self._discontinuity_weight}",
            f"--config:agentModeChoice.retrievalCostPenalty={self._retrieval_cost_weight}"
        ]

        subprocess.run(cmd, check=True, env=os.environ)