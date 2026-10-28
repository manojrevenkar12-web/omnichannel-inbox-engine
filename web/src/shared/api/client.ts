import type {
  ConversationDetail,
  ConversationSummary,
  Me,
  MetricsResponse,
  SendOutcome,
  Teammate,
  Template,
  View,
} from "./types";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: unknown,
  ) {
    super(typeof body === "object" && body && "error" in body ? String((body as { error: unknown }).error) : `HTTP ${status}`);
  }
}

type Fetch = typeof fetch;

/** A thin client over the core's console API. Every call carries the teammate's token. */
export function createClient(token: string, base = "/api", doFetch: Fetch = (...a) => fetch(...a)) {
  async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
    const res = await doFetch(base + path, {
      ...init,
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json", ...(init.headers ?? {}) },
    });
    const text = await res.text();
    const body = text ? JSON.parse(text) : null;
    if (!res.ok) throw new ApiError(res.status, body);
    return body as T;
  }

  return {
    me: () => call<Me>("/me"),
    conversations: (view: View) => call<ConversationSummary[]>(`/conversations?view=${view}`),
    conversation: (id: string) => call<ConversationDetail>(`/conversations/${encodeURIComponent(id)}`),
    team: () => call<Teammate[]>("/team"),
    templates: () => call<Template[]>("/templates"),
    metrics: () => call<MetricsResponse>("/metrics"),
    assign: (id: string, teammateId?: string) =>
      call<null>(`/conversations/${encodeURIComponent(id)}/assign`, { method: "POST", body: JSON.stringify({ teammateId }) }),
    handBack: (id: string) => call<null>(`/conversations/${encodeURIComponent(id)}/handback`, { method: "POST" }),
    resolve: (id: string) => call<null>(`/conversations/${encodeURIComponent(id)}/resolve`, { method: "POST" }),
    /**
     * Send a reply. The idempotency key belongs to the draft, not the request: a retry after a
     * timeout sends the same key, so the customer gets the message once.
     */
    async send(id: string, idempotencyKey: string, body: { text?: string; template?: string; language?: string }): Promise<SendOutcome> {
      try {
        const r = await call<{ messageId: string; replayed: boolean }>(`/conversations/${encodeURIComponent(id)}/messages`, {
          method: "POST",
          headers: { "Idempotency-Key": idempotencyKey },
          body: JSON.stringify(body),
        });
        return { kind: "queued", messageId: r.messageId, replayed: r.replayed };
      } catch (e) {
        if (e instanceof ApiError && e.status === 409) {
          const b = e.body as { error: string; reason?: string };
          if (b.error === "template_required") return { kind: "template_required", reason: b.reason ?? "" };
          return { kind: "refused", reason: b.reason ?? "" };
        }
        throw e;
      }
    },
  };
}

export type Client = ReturnType<typeof createClient>;
