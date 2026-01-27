# matsim-ReinforcementLearning
Dynamic Mode Choice: An extension to the matsim withinday module for reinforcement learning choice modeling (In Development)

![Status](https://img.shields.io/badge/Status-In--Development-orange)
![MATSim](https://img.shields.io/badge/Simulation-MATSim-blue)
![Python](https://img.shields.io/badge/RL--Core-Python-green)

This project investigates **Mode Choice Modeling** using Reinforcement Learning (RL) within the **MATSim (Multi-Agent Transport Simulation)** within-day framework. Developed as part of PhD-level research, it aims to replace static discrete choice models with adaptive, behaviorally realistic decision-making policies.

---

## Motivation & Background

Traditional transport models often rely on static preferences. However, real-world travelers adapt to experienced outcomes. This project integrates RL into MATSim’s within-day replanning, allowing agents to:
* **Learn dynamically** from experienced travel times and congestion.
* **Adapt policies** over repeated simulation iterations.
* **Optimize decisions** based on evolving system conditions rather than fixed utility functions.

---

## Reinforcement Learning Formulation

The mode choice problem is modeled as a **Markov Decision Process (MDP)**:

| Component | Description |
| :--- | :--- |
| **Agent** | Individual travelers within the MATSim environment. |
| **State ($s$)** | Contextual data: Travel times, congestion, schedule constraints, and socio-demographics. |
| **Action ($a$)** | Selection of mode: `Car`, `Public Transport`, `Bike`, or `Walk`. |
| **Reward ($r$)** | Feedback based on generalized travel cost, time disutility, and delay penalties. |
| **Policy ($\pi$)** | A learned mapping from states to actions to guide future travel behavior. |

---

## System Architecture

The project utilizes a **Hybrid Java–Python Architecture** to leverage the high-performance simulation core of MATSim and the robust machine learning ecosystem of Python.



### Interaction Workflow:
1. **MATSim (Java)**: Triggers a within-day replanning event and extracts the agent's current state.
2. **API Bridge**: Sends state and reward parameters to the Python environment via Socket/REST.
3. **RL Core (Python)**: Computes the optimal action (mode choice) using the current policy.
4. **MATSim (Java)**: Receives the action and applies it to the agent within the running simulation.

---

## Technical Implementation

### Java–Python Interaction
* **MATSim Environment**: Manages network loading, agent execution, and the extraction of decision-point data.
* **Python RL Environment**: Hosts the RL algorithms (e.g., Q-Learning, PPO, or DQN) and evaluates reward signals.
* **Communication**: Implemented via a lightweight **REST or Socket-based API** to ensure modularity.

### Within-Day Integration
Unlike standard MATSim "day-to-day" replanning, this RL agent is embedded in the **Within-Day Module**, allowing for real-time reconsiderations based on actual network conditions.

---

## Research Objectives
* **Behavioral Realism**: Study how learned policies converge compared to classical models.
* **Performance Analysis**: Observe agent adaptation under heavy congestion.
* **Scalability**: Enable future extensions into multi-agent learning environments.

---

## Development Status
**Current Phase:** `Initial Integration & Testing`

- [x] Theoretical MDP Formulation
- [x] Java–Python API Prototype
- [ ] Finalization of State Representations
- [ ] Large-scale behavioral convergence testing

---

## Contributors
