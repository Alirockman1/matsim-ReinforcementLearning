from pydantic import BaseModel
from typing import List, Optional

class ObserverData(BaseModel):
    ''' The observer data structure (Data communicated during departure from an activity)'''

    simulationIteration: int
    agentID:str
    subpopulation: str
    rawStateObservation: dict
    encodedStateString: str
    encodedLatentSpace: list[str]
    possibleModeSet: list[str]

class ArrivalData(BaseModel):
    ''' The observer data structure (Data communicated during arrival at an activity)'''

    agentID:str
    travelTimeSeconds: float
    numberOfTransfers: int
    distance: float
    reward: float
    matsimScore: float
    terminal: bool
    nextEncodedStateString: str
    nextRawStateObservation: dict
    nextEncodedLatentSpace: list[str]
    accumulativeScore: float = 0.0
    accumulativeReward: float = 0.0