import { AnimatePresence, motion } from "framer-motion";
import { useState, type FormEvent } from "react";
import { useSession } from "../session/session";
import { useResource } from "../../shared/hooks/useResource";
import type { Template, WindowView } from "../../shared/api/types";
import { motion as m } from "../../theme/tokens";
import { fuse } from "../conversations/window";
import styles from "./Composer.module.css";

function newKey(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `k-${Date.now()}-${Math.random()}`;
}

const CATEGORY: Record<Template["category"], string> = {
  UTILITY: "Utility",
  MARKETING: "Marketing, always billed",
  AUTHENTICATION: "Authentication",
};

/**
 * Reply box. A free-form reply while the window is open; once it has closed, only an approved
 * template. The draft keeps one idempotency key until it is sent, so retrying after a timeout
 * never sends the customer the same message twice.
 */
export function Composer({ conversationId, window, onSent }: { conversationId: string; window: WindowView | null; onSent: () => void }) {
  const { client } = useSession();
  const [text, setText] = useState("");
  const [key, setKey] = useState(newKey);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [forceTemplate, setForceTemplate] = useState(false);
  const closed = forceTemplate || fuse(window).kind === "closed";

  async function send(body: { text?: string; template?: string; language?: string }) {
    setBusy(true);
    setProblem(null);
    try {
      const r = await client.send(conversationId, key, body);
      if (r.kind === "queued") {
        setText("");
        setKey(newKey());
        onSent();
      } else if (r.kind === "template_required") {
        setForceTemplate(true);
        setProblem("The reply window closed before this was sent. Choose an approved template instead.");
      } else {
        setProblem(`Not sent: ${r.reason}`);
      }
    } catch {
      setProblem("Not sent yet: the inbox did not answer. Sending again is safe; the customer gets it once.");
    } finally {
      setBusy(false);
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (text.trim() && !busy) void send({ text: text.trim() });
  }

  return (
    <div className={styles.composer}>
      <AnimatePresence mode="wait" initial={false}>
        {closed ? (
          <motion.div key="template" initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={m.swap}>
            <TemplatePicker busy={busy} onSend={(t) => void send({ template: t.name, language: t.language })} />
          </motion.div>
        ) : (
          <motion.form key="text" onSubmit={submit} initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={m.swap}>
            <label htmlFor="reply" className={styles.label}>
              Reply
            </label>
            <div className={styles.row}>
              <textarea
                id="reply"
                rows={2}
                value={text}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) submit(e);
                }}
                placeholder="Write to the customer"
              />
              <button type="submit" disabled={busy || !text.trim()}>
                {busy ? "Sending" : "Send"}
              </button>
            </div>
          </motion.form>
        )}
      </AnimatePresence>
      {problem && (
        <p className={styles.problem} role="alert">
          {problem}
        </p>
      )}
    </div>
  );
}

function TemplatePicker({ busy, onSend }: { busy: boolean; onSend: (t: Template) => void }) {
  const { client } = useSession();
  const res = useResource(() => client.templates(), [client], null);
  const [chosen, setChosen] = useState<string>("");

  if (res.state === "loading") return <p className={styles.note}>Loading templates</p>;
  if (res.state === "error")
    return (
      <p className={styles.problem} role="alert">
        Templates did not load.{" "}
        <button type="button" className={styles.link} onClick={res.retry}>
          Try again
        </button>
      </p>
    );
  const pick = res.data.find((t) => `${t.name}:${t.language}` === chosen);
  return (
    <div className={styles.templates}>
      <p className={styles.note}>WhatsApp only allows an approved template after the 24-hour window. The customer's reply opens it again.</p>
      {res.data.length === 0 ? (
        <p className={styles.note}>This workspace has no approved templates yet.</p>
      ) : (
        <div className={styles.row}>
          <select aria-label="Template" value={chosen} onChange={(e) => setChosen(e.target.value)}>
            <option value="">Choose a template</option>
            {res.data.map((t) => (
              <option key={`${t.name}:${t.language}`} value={`${t.name}:${t.language}`}>
                {t.name} ({t.language}), {CATEGORY[t.category]}
              </option>
            ))}
          </select>
          <button type="button" disabled={busy || !pick} onClick={() => pick && onSend(pick)}>
            {busy ? "Sending" : "Send template"}
          </button>
        </div>
      )}
      {pick && <p className={styles.preview}>{pick.body}</p>}
    </div>
  );
}
