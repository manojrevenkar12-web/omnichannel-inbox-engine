import type { MessageView } from "../../shared/api/types";

const STATUS: Record<string, string> = {
  QUEUED: "Queued",
  ACCEPTED: "Sent to WhatsApp",
  SENT: "Sent",
  DELIVERED: "Delivered",
  READ: "Read",
  FAILED: "Not delivered",
  RECEIVED: "",
};

export function statusLabel(m: MessageView): string {
  if (m.direction === "INBOUND") return "";
  if (m.status === "FAILED") return m.errorTitle ? `Not delivered: ${m.errorTitle}` : "Not delivered";
  return STATUS[m.status] ?? m.status.toLowerCase();
}

export function authorLabel(m: MessageView, contact: string): string {
  switch (m.author) {
    case "CUSTOMER":
      return contact;
    case "AI":
      return "AI agent";
    case "HUMAN":
      return m.authorName ?? "Teammate";
    default:
      return "Inbox";
  }
}
