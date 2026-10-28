import type { View } from "../../shared/api/types";

export const VIEWS: { id: View; name: string; hint: string }[] = [
  { id: "mine", name: "Mine", hint: "Assigned to you" },
  { id: "queue", name: "Waiting for someone", hint: "Handed over, nobody online had room" },
  { id: "ai", name: "With the AI agent", hint: "The agent is answering" },
  { id: "all", name: "All open", hint: "Every open conversation" },
  { id: "resolved", name: "Resolved", hint: "Closed conversations" },
];
