import { useSession } from "../session/session";
import { useResource } from "../../shared/hooks/useResource";
import { duration } from "../../shared/format";
import styles from "./Metrics.module.css";

/** The few numbers a team lead looks at during the day. */
export function Metrics() {
  const { client } = useSession();
  const res = useResource(() => client.metrics(), [client]);
  if (res.state === "loading") return <p className={styles.quiet}>Loading today's numbers</p>;
  if (res.state === "error")
    return (
      <p className={styles.quiet}>
        Numbers unavailable.{" "}
        <button type="button" className={styles.link} onClick={res.retry}>
          Try again
        </button>
      </p>
    );
  const i = res.data.inbox;
  const rows: [string, string][] = [
    ["Open", String(i.open)],
    ["With the AI agent", String(i.withAi)],
    ["With people", String(i.withPeople)],
    ["Waiting for someone", String(i.waitingInQueue)],
    ["First answer after handover", i.medianFirstHumanResponseSeconds == null ? "no handovers yet" : duration(i.medianFirstHumanResponseSeconds * 1000)],
    ["Billable messages", String(i.billableMessages)],
    ["Not delivered", String(i.failedMessages)],
  ];
  return (
    <dl className={styles.metrics} aria-label="Inbox numbers">
      {rows.map(([k, v]) => (
        <div key={k} className={k === "Waiting for someone" && i.waitingInQueue > 0 ? styles.alert : undefined}>
          <dt>{k}</dt>
          <dd>{v}</dd>
        </div>
      ))}
    </dl>
  );
}
