/** The shapes the inbox core returns (core/src/main/kotlin/inbox/service/Views.kt). */
export type Channel = "WHATSAPP" | "SMS" | "EMAIL" | "INSTAGRAM";
export type AssigneeKind = "NONE" | "AI" | "HUMAN" | "QUEUE";

export interface Me {
  teammateId: string;
  name: string;
  workspaceId: string;
}

export interface WindowView {
  channel: Channel;
  open: boolean;
  closesAt: string | null;
  aiMayReply: boolean;
  entryPoint: string;
  optedOut: boolean;
}

export interface ConversationSummary {
  id: string;
  contact: string;
  channels: Channel[];
  status: "OPEN" | "RESOLVED";
  assigneeKind: AssigneeKind;
  assigneeId: string | null;
  assigneeName: string | null;
  lastMessage: string | null;
  lastMessageAt: string;
  lastAuthor: string | null;
  unansweredSince: string | null;
  window: WindowView | null;
}

export interface StatusView {
  status: string;
  at: string;
  receivedAt: string;
}

export interface MessageView {
  id: string;
  direction: "INBOUND" | "OUTBOUND";
  channel: Channel;
  author: "CUSTOMER" | "AI" | "HUMAN" | "SYSTEM";
  authorName: string | null;
  body: string;
  kind: string;
  template: string | null;
  status: string;
  statusAt: string | null;
  errorCode: string | null;
  errorTitle: string | null;
  billable: boolean;
  chargeCategory: string | null;
  sentAt: string;
  history: StatusView[];
}

export interface ConversationDetail {
  summary: ConversationSummary;
  windows: WindowView[];
  messages: MessageView[];
  handoffReason: string | null;
  handedOffAt: string | null;
  firstHumanResponseAt: string | null;
  aiReplies: number;
  billableMessages: number;
}

export interface Teammate {
  id: string;
  name: string;
  online: boolean;
  capacity: number;
  open: number;
}

export interface Template {
  name: string;
  language: string;
  category: "UTILITY" | "MARKETING" | "AUTHENTICATION";
  body: string;
}

export interface InboxMetrics {
  open: number;
  withAi: number;
  withPeople: number;
  waitingInQueue: number;
  resolved: number;
  handoffs: number;
  medianFirstHumanResponseSeconds: number | null;
  billableMessages: number;
  aiMessages: number;
  aiMessagesPerConversation: number | null;
  failedMessages: number;
}

export interface MetricsResponse {
  inbox: InboxMetrics;
}

export type View = "mine" | "queue" | "ai" | "all" | "resolved";

export type SendOutcome =
  | { kind: "queued"; messageId: string; replayed: boolean }
  | { kind: "template_required"; reason: string }
  | { kind: "refused"; reason: string };
