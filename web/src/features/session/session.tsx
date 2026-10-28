import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import { createClient, type Client } from "../../shared/api/client";
import type { Me } from "../../shared/api/types";

const KEY = "inbox.token";

function stored(): string | null {
  try {
    return sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}

function store(token: string | null) {
  try {
    if (token) sessionStorage.setItem(KEY, token);
    else sessionStorage.removeItem(KEY);
  } catch {
    // private mode: the session lasts as long as the tab's memory
  }
}

interface Session {
  client: Client;
  me: Me;
  signOut: () => void;
}

const Ctx = createContext<Session | null>(null);

export function useSession(): Session {
  const s = useContext(Ctx);
  if (!s) throw new Error("useSession outside a signed-in session");
  return s;
}

export function SessionProvider({ me, token, onSignOut, children }: { me: Me; token: string; onSignOut: () => void; children: ReactNode }) {
  const value = useMemo(() => ({ client: createClient(token), me, signOut: onSignOut }), [token, me, onSignOut]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useStoredToken() {
  const [token, setToken] = useState<string | null>(stored);
  return {
    token,
    set: (t: string | null) => {
      store(t);
      setToken(t);
    },
  };
}
