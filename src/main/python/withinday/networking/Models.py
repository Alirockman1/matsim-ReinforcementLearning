from dataclasses import Field

from pydantic import BaseModel
from typing import List, Optional

class ObserverData(BaseModel):
    ''' The observer data structure (Data communicated during departure from an activity)'''

    simulationIteration: int
    agentID:str
    subpopulation: str
    rawStateObservation: dict
    rawBitStateRepresentation: List[int]
    possibleModeSet: list[str]

    endOfDayFlag: Optional[bool] = False
    encodedStateString: Optional[str] = None
    encodedLatentSpace: Optional[List[str]] = None

class ArrivalData(BaseModel):
    ''' The observer data structure (Data communicated during arrival at an activity)'''

    agentID:str
    travelTimeSeconds: float
    numberOfTransfers: int
    distance: float
    reward: float
    matsimScore: float

    isTerminal: bool = Field(alias="isTerminal")
    nextRawStateObservation: Optional[dict] = None
    nextRawBitStateRepresentation: List[int]
    nextEncodedStateString: Optional[str] = None
    nextEncodedLatentSpace: Optional[List[str]] = None
    accumulativeScore: float = 0.0
    accumulativeReward: float = 0.0