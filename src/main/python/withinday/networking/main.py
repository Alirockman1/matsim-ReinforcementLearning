import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, Depends, status
from fastapi.responses import PlainTextResponse

from python_matsim_bridge import BaseSimulationBridgeService, load_bridge_service
from Models import ObserverData, ArrivalData

logging.basicConfig(level="INFO", format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("matsim_bridge")

active_service_instance: BaseSimulationBridgeService = load_bridge_service()

def get_bridge_service() -> BaseSimulationBridgeService:
    return active_service_instance

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting MATSim Generic Simulation Bridge API...")
    yield
    logger.info("Persisting session checkpoints before shutdown...")
    active_service_instance.checkpoint_state()

app = FastAPI(
    title="MATSim Simulation Bridge API",
    lifespan=lifespan
)

@app.get("/healthz", status_code=status.HTTP_200_OK)
def health_check():
    return {"status": "ok", "service": "matsim_simulation_bridge"}

@app.post("/session/configure", status_code=status.HTTP_200_OK)
def configure_session(
    config_data: dict, 
    service: BaseSimulationBridgeService = Depends(get_bridge_service)
):
    try:
        return service.configure_session(config_data)
    except Exception as e:
        logger.error(f"Configuration error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/decision/resolve", response_class=PlainTextResponse)
def request_decision(
    observation: ObserverData, 
    service: BaseSimulationBridgeService = Depends(get_bridge_service)
):
    try:
        return service.resolve_agent_decision(observation)
    except Exception as e:
        logger.error(f"Decision resolution failed: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/feedback/execution", status_code=status.HTTP_200_OK)
def process_feedback(
    feedback: ArrivalData, 
    service: BaseSimulationBridgeService = Depends(get_bridge_service)
):
    try:
        return service.process_execution_feedback(feedback)
    except Exception as e:
        logger.error(f"Feedback execution failed: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/session/checkpoint", status_code=status.HTTP_200_OK)
def trigger_checkpoint(service: BaseSimulationBridgeService = Depends(get_bridge_service)):
    if service.checkpoint_state():
        return {"status": "checkpoint_saved"}
    raise HTTPException(status_code=500, detail="Failed to save checkpoint.")

@app.get("/session/metrics", status_code=status.HTTP_200_OK)
def get_metrics(service: BaseSimulationBridgeService = Depends(get_bridge_service)):
    return service.get_service_metrics()