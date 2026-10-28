import type { WindowView } from "../../shared/api/types";
import { duration } from "../../shared/format";
import { timing, windowLength } from "../../theme/tokens";

export type Fuse =
  | { kind: "none" } // SMS and email: no window
  | { kind: "open"; remainingMs: number; fraction: number; warning: boolean; label: string }
  | { kind: "closed"; label: string }
  | { kind: "opted-out"; label: string };

/** How much of the reply window is left, as the console shows it under a conversation. */
export function fuse(w: WindowView | null | undefined, now: number = Date.now()): Fuse {
  if (!w) return { kind: "none" };
  if (w.optedOut) return { kind: "opted-out", label: "Opted out of messages" };
  const total = windowLength[w.channel];
  if (!Number.isFinite(total) || !w.closesAt) return w.open ? { kind: "none" } : { kind: "closed", label: "Template only" };
  const remainingMs = Date.parse(w.closesAt) - now;
  if (!w.open || remainingMs <= 0) return { kind: "closed", label: "Window closed: template only" };
  return {
    kind: "open",
    remainingMs,
    fraction: Math.min(1, remainingMs / total),
    warning: remainingMs < timing.fuseWarnMs,
    label: `${duration(remainingMs)} left to reply`,
  };
}
