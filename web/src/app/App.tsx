import { useCallback, useEffect, useState } from "react";
import { ConversationList } from "../features/conversations/ConversationList";
import { VIEWS } from "../features/conversations/views";
import { Metrics } from "../features/metrics/Metrics";
import { SessionProvider, useSession, useStoredToken } from "../features/session/session";
import { SignIn } from "../features/session/SignIn";
import { Thread } from "../features/thread/Thread";
import { createClient } from "../shared/api/client";
import type { Me, View } from "../shared/api/types";
import { Skeleton } from "../shared/ui/states";
import styles from "./App.module.css";

/** The selected conversation lives in the URL so a link to it can be shared within the team. */
function readHash(): { view: View; id: string | null } {
  const p = new URLSearchParams(window.location.hash.slice(1));
  const view = (VIEWS.find((v) => v.id === p.get("view"))?.id ?? "all") as View;
  return { view, id: p.get("c") };
}

function writeHash(view: View, id: string | null) {
  const p = new URLSearchParams({ view });
  if (id) p.set("c", id);
  window.history.replaceState(null, "", `#${p.toString()}`);
}

export function App() {
  const { token, set } = useStoredToken();
  const [me, setMe] = useState<Me | null>(null);
  const [checking, setChecking] = useState(!!token);

  useEffect(() => {
    if (!token || me) return;
    createClient(token)
      .me()
      .then(setMe)
      .catch(() => set(null))
      .finally(() => setChecking(false));
  }, [token, me, set]);

  const signOut = useCallback(() => {
    set(null);
    setMe(null);
  }, [set]);

  if (checking && !me) return <Skeleton rows={3} label="Checking your session" />;
  if (!token || !me)
    return (
      <SignIn
        onSignedIn={(t, who) => {
          set(t);
          setMe(who);
          setChecking(false);
        }}
      />
    );
  return (
    <SessionProvider me={me} token={token} onSignOut={signOut}>
      <Inbox />
    </SessionProvider>
  );
}

function Inbox() {
  const { me, signOut } = useSession();
  const [{ view, id }, setNav] = useState(readHash);
  const [listKey, setListKey] = useState(0);

  useEffect(() => writeHash(view, id), [view, id]);

  return (
    <div className={`${styles.shell} ${id ? styles.reading : ""}`}>
      <nav className={styles.rail} aria-label="Views">
        <p className={styles.me}>
          <strong>{me.name}</strong>
          <button type="button" className={styles.signOut} onClick={signOut}>
            Sign out
          </button>
        </p>
        <ul className={styles.views}>
          {VIEWS.map((v) => (
            <li key={v.id}>
              <button
                type="button"
                className={v.id === view ? styles.active : undefined}
                aria-current={v.id === view ? "page" : undefined}
                title={v.hint}
                onClick={() => setNav({ view: v.id, id: null })}
              >
                {v.name}
              </button>
            </li>
          ))}
        </ul>
        <div className={styles.numbers}>
          <Metrics />
        </div>
      </nav>
      <ConversationList key={listKey} view={view} selected={id} onSelect={(c) => setNav({ view, id: c })} />
      <Thread id={id} onChanged={() => setListKey((k) => k + 1)} onBack={() => setNav({ view, id: null })} />
    </div>
  );
}
