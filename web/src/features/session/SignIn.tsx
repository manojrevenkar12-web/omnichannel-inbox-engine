import { motion } from "framer-motion";
import { useState, type FormEvent } from "react";
import { ApiError, createClient } from "../../shared/api/client";
import type { Me } from "../../shared/api/types";
import { motion as m } from "../../theme/tokens";
import styles from "./SignIn.module.css";

const DEMO = [
  { token: "demo-anna", who: "Anna", where: "Praxis Dr. Lindqvist" },
  { token: "demo-ben", who: "Ben", where: "Praxis Dr. Lindqvist" },
  { token: "demo-nils", who: "Nils", where: "Nordlicht Bikes" },
];

export function SignIn({ onSignedIn }: { onSignedIn: (token: string, me: Me) => void }) {
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function signIn(t: string) {
    setBusy(true);
    setError(null);
    try {
      const me = await createClient(t).me();
      onSignedIn(t, me);
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 401
          ? "That token does not belong to anyone on a team. Check it and try again."
          : "The inbox did not answer. Check that the core is running, then try again.",
      );
    } finally {
      setBusy(false);
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (token.trim()) void signIn(token.trim());
  }

  return (
    <main className={styles.page}>
      <motion.section className={styles.card} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={m.enter}>
        <h1 className={styles.title}>Sign in to the inbox</h1>
        <p className={styles.lead}>Every customer message from WhatsApp, SMS, email and Instagram, in one place, with an AI agent that hands over to you.</p>
        <form onSubmit={submit} className={styles.form}>
          <label htmlFor="token">Your access token</label>
          <input id="token" value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" spellCheck={false} />
          <button type="submit" disabled={busy || !token.trim()}>
            {busy ? "Signing in" : "Sign in"}
          </button>
        </form>
        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
        <div className={styles.demo}>
          <p>Or try a demo teammate:</p>
          <ul>
            {DEMO.map((d) => (
              <li key={d.token}>
                <button type="button" onClick={() => void signIn(d.token)} disabled={busy}>
                  <strong>{d.who}</strong>
                  <span>{d.where}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      </motion.section>
    </main>
  );
}
