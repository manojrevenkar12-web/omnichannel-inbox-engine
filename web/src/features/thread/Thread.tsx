import { AnimatePresence, motion } from "framer-motion";
import { useEffect, useRef, useState } from "react";
import { useSession } from "../session/session";
import { useResource } from "../../shared/hooks/useResource";
import type { ConversationDetail, MessageView } from "../../shared/api/types";
import { channelName, clock, since } from "../../shared/format";
import { EmptyState, ErrorState, Skeleton } from "../../shared/ui/states";
import { motion as m } from "../../theme/tokens";
import { WindowFuse } from "../conversations/WindowFuse";
import { Composer } from "./Composer";
import { authorLabel, statusLabel } from "./labels";
import styles from "./Thread.module.css";

export function Thread({ id, onChanged, onBack }: { id: string | null; onChanged: () => void; onBack: () => void }) {
  if (!id)
    return (
      <section className={styles.thread} aria-label="Conversation">
        <EmptyState title="Choose a conversation">Pick one from the list to read it and reply.</EmptyState>
      </section>
    );
  return <Loaded key={id} id={id} onChanged={onChanged} onBack={onBack} />;
}

function Loaded({ id, onChanged, onBack }: { id: string; onChanged: () => void; onBack: () => void }) {
  const { client } = useSession();
  const res = useResource(() => client.conversation(id), [id, client]);
  const refresh = () => {
    if (res.state === "ready") res.refresh();
    onChanged();
  };

  return (
    <section className={styles.thread} aria-label="Conversation">
      <button type="button" className={styles.back} onClick={onBack}>
        Back to the list
      </button>
      {res.state === "loading" && <Skeleton rows={5} label="Loading the conversation" />}
      {res.state === "error" && <ErrorState title="This conversation did not load" detail={res.error.message} onRetry={res.retry} />}
      {res.state === "ready" && <Body d={res.data} onChanged={refresh} />}
    </section>
  );
}

function Body({ d, onChanged }: { d: ConversationDetail; onChanged: () => void }) {
  const { client, me } = useSession();
  const [acting, setActing] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const s = d.summary;
  const window = d.windows[0] ?? s.window;
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => {
    end.current?.scrollIntoView({ block: "end" });
  }, [d.messages.length]);

  async function act(name: string, f: () => Promise<unknown>) {
    setActing(name);
    setProblem(null);
    try {
      await f();
      onChanged();
    } catch {
      setProblem("That did not go through. Try again.");
    } finally {
      setActing(null);
    }
  }

  const mine = s.assigneeKind === "HUMAN" && s.assigneeId === me.teammateId;
  const open = s.status === "OPEN";

  return (
    <>
      <header className={styles.head}>
        <div className={styles.who}>
          <h2>{s.contact}</h2>
          <p>
            {s.channels.map((c) => channelName[c] ?? c).join(", ")}
            {s.assigneeKind === "AI" && ", answered by the AI agent"}
            {s.assigneeKind === "HUMAN" && `, with ${mine ? "you" : (s.assigneeName ?? "a teammate")}`}
            {s.assigneeKind === "QUEUE" && ", waiting for someone to take it"}
            {!open && ", resolved"}
          </p>
          {d.handoffReason && s.assigneeKind !== "AI" && <p className={styles.reason}>Handed over: {d.handoffReason}</p>}
        </div>
        {open && (
          <div className={styles.actions}>
            {!mine && (
              <button type="button" disabled={!!acting} onClick={() => void act("take", () => client.assign(s.id))}>
                Take it
              </button>
            )}
            {s.assigneeKind !== "AI" && (
              <button type="button" disabled={!!acting} onClick={() => void act("ai", () => client.handBack(s.id))}>
                Hand back to the AI
              </button>
            )}
            <button type="button" disabled={!!acting} onClick={() => void act("resolve", () => client.resolve(s.id))}>
              Resolve
            </button>
          </div>
        )}
      </header>
      {open && window && (
        <div className={styles.window}>
          <WindowFuse window={window} large />
        </div>
      )}
      {problem && (
        <p className={styles.problem} role="alert">
          {problem}
        </p>
      )}
      <ol className={styles.messages} aria-label="Messages">
        <AnimatePresence initial={false}>
          {d.messages.map((msg) => (
            <Message key={msg.id} m={msg} contact={s.contact} />
          ))}
        </AnimatePresence>
        <div ref={end} />
      </ol>
      {open && <Composer conversationId={s.id} window={window} onSent={onChanged} />}
      <footer className={styles.facts}>
        <span>{d.aiReplies} AI replies</span>
        <span>{d.billableMessages} billable messages</span>
        {d.firstHumanResponseAt && d.handedOffAt && <span>First answer from a person {since(d.handedOffAt, Date.parse(d.firstHumanResponseAt))} after handover</span>}
      </footer>
    </>
  );
}

function Message({ m: msg, contact }: { m: MessageView; contact: string }) {
  const inbound = msg.direction === "INBOUND";
  const status = statusLabel(msg);
  return (
    <motion.li
      layout="position"
      className={`${styles.message} ${inbound ? styles.inbound : styles.outbound} ${msg.author === "AI" ? styles.ai : ""}`}
      initial={{ opacity: 0, y: m.rowRise }}
      animate={{ opacity: 1, y: 0 }}
      transition={m.enter}
    >
      <span className={styles.author}>{authorLabel(msg, contact)}</span>
      <p className={styles.body}>{msg.template ? `Template ${msg.template}: ${msg.body}` : msg.body}</p>
      <span className={styles.stamp}>
        <time dateTime={msg.sentAt}>{clock(msg.sentAt)}</time>
        {status && <span className={msg.status === "FAILED" ? styles.failed : undefined}>{status}</span>}
        {msg.billable && <span>Billed</span>}
      </span>
    </motion.li>
  );
}
