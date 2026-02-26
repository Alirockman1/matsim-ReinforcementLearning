from pydantic import BaseModel
from typing import List, Optional

class ObserverData(BaseModel):
    agentID:str
    linkID: str
    departureTime: str
    nextActivityArrivalTime: str
    nextActivityArrivalSeconds: float
    departureTimeSeconds: float
    carAvailability: bool
    possibleModeSet: list[str]
    simulationIteration: int

class ArrivalData(BaseModel):
    agentID:str
    travelTimeSeconds: float
    numberOfTransfers: int
    distance: float
    travelDisutility: float
    startDayMode: str
    nextLinkID: str
    nextDepartureTimeSeconds: float
    nextPlannedArrivalTimeSeconds: float
    delaySeconds: float = 0.0