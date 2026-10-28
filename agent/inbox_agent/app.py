"""HTTP surface of the agent: one endpoint the inbox service calls per turn."""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI
from pydantic import BaseModel, Field

from inbox_agent.graph import build, current_turn


class TurnMessage(BaseModel):
    author: str
    text: str
    at: str | None = None


class TurnIn(BaseModel):
    conversation_id: str
    workspace_id: str
    channel: str = "whatsapp"
    window_open: bool = True
    ai_replies_so_far: int = 0
    messages: list[TurnMessage] = Field(default_factory=list)


class TurnOut(BaseModel):
    action: str
    text: str | None = None
    reason: str
    confidence: float
    sources: list[str] = Field(default_factory=list)
    model: str | None = None


def create_app(graph: Any | None = None) -> FastAPI:
    app = FastAPI(title="inbox agent")
    g = graph or build()

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/v1/turn", response_model=TurnOut)
    def turn(body: TurnIn) -> TurnOut:
        text = current_turn([m.model_dump() for m in body.messages])
        if not text:
            return TurnOut(action="no_reply", reason="no customer message to answer", confidence=1.0)
        s = g.invoke({"workspace": body.workspace_id, "window_open": body.window_open, "turn": text})
        return TurnOut(
            action=s.get("action", "handoff"),
            text=s.get("text"),
            reason=s.get("reason", ""),
            confidence=float(s.get("confidence", 0.0)),
            sources=list(s.get("cited", [])),
            model=s.get("model"),
        )

    return app


app = create_app()
