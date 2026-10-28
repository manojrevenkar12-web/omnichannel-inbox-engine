import { motion } from "framer-motion";
import type { WindowView } from "../../shared/api/types";
import { motion as m } from "../../theme/tokens";
import styles from "./WindowFuse.module.css";
import { fuse } from "./window";

/**
 * The reply window as a burning fuse: a line as long as the time left to answer freely. It turns
 * amber in the last two hours; after that only an approved template may be sent.
 */
export function WindowFuse({ window, now, large = false }: { window: WindowView | null | undefined; now?: number; large?: boolean }) {
  const f = fuse(window, now);
  if (f.kind === "none") return null;
  const tone = f.kind === "open" ? (f.warning ? styles.warning : styles.open) : styles.closed;
  return (
    <div className={`${styles.fuse} ${large ? styles.large : ""} ${tone}`}>
      <div className={styles.track} aria-hidden="true">
        {f.kind === "open" && <motion.span className={styles.burn} initial={false} animate={{ scaleX: f.fraction }} transition={m.swap} />}
      </div>
      <span className={styles.label}>{f.label}</span>
    </div>
  );
}
