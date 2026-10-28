import { motion } from "framer-motion";
import { useSession } from "../session/session";
import { useResource } from "../../shared/hooks/useResource";
import type { ConversationSummary, View } from "../../shared/api/types";
import { channelName, since } from "../../shared/format";
import { EmptyState, ErrorState, Skeleton } from "../../shared/ui/states";
import { motion as m } from "../../theme/tokens";
import { VIEWS } from "./views";
import { WindowFuse } from "./WindowFuse";
import styles from "./ConversationList.module.css";

function who(c: ConversationSummary): string {
  if (c.assigneeKind === "AI") return "AI agent";
  if (c.assigneeKind === "HUMAN") return c.assigneeName ?? "A teammate";
  if (c.assigneeKind === "QUEUE") return "Waiting for someone";
  return "Unassigned";
}

export function ConversationList({ view, selected, onSelect }: { view: View; selected: string | null; onSelect: (id: string) => void }) {
  const { client } = useSession();
  const res = useResource(() => client.conversations(view), [view, client]);
  const name = VIEWS.find((v) => v.id === view)?.name ?? "Conversations";

  return (
    <section className={styles.list} aria-label={name}>
      <header className={styles.head}>
        <h2>{name}</h2>
        {res.state === "ready" && <span className={styles.count}>{res.data.length}</span>}
      </header>
      {res.state === "loading" && <Skeleton label="Loading conversations" />}
      {res.state === "error" && (
        <ErrorState title="Conversations did not load" detail="The inbox core did not answer. Your replies are safe; nothing was sent." onRetry={res.retry} />
      )}
      {res.state === "ready" && res.data.length === 0 && (
        <EmptyState title={view === "mine" ? "Nothing assigned to you" : "No conversations here"}>
          {view === "mine" ? "When the AI agent hands a customer over to you, they appear here." : "New messages arrive here as customers write."}
        </EmptyState>
      )}
      {res.state === "ready" && res.data.length > 0 && (
        <ol className={styles.rows}>
          {res.data.map((c, i) => (
            <motion.li
              key={c.id}
              initial={{ opacity: 0, y: m.rowRise }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ ...m.enter, delay: Math.min(i, 12) * m.rowStagger }}
            >
              <button
                type="button"
                className={`${styles.row} ${selected === c.id ? styles.selected : ""}`}
                aria-current={selected === c.id ? "true" : undefined}
                onClick={() => onSelect(c.id)}
              >
                <span className={styles.top}>
                  <span className={styles.contact}>{c.contact}</span>
                  <time dateTime={c.lastMessageAt} className={styles.time}>
                    {since(c.lastMessageAt)}
                  </time>
                </span>
                <span className={styles.preview}>{c.lastMessage ?? "No messages yet"}</span>
                <span className={styles.meta}>
                  <span>{c.channels.map((ch) => channelName[ch] ?? ch).join(", ")}</span>
                  <span className={c.assigneeKind === "QUEUE" ? styles.waiting : undefined}>{who(c)}</span>
                  {c.unansweredSince && <span className={styles.unanswered}>Unanswered for {since(c.unansweredSince)}</span>}
                </span>
                <WindowFuse window={c.window} />
              </button>
            </motion.li>
          ))}
        </ol>
      )}
    </section>
  );
}
