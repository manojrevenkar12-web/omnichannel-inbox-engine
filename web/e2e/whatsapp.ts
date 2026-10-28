import { createHmac } from "node:crypto";

/** Signed WhatsApp Cloud API webhooks, as Meta posts them to the core. */
export const CORE = process.env.INBOX_CORE_URL ?? "http://localhost:8080";
const SECRET = process.env.WHATSAPP_APP_SECRET ?? "dev-app-secret";
export const DENTAL_NUMBER_ID = "109000000000001";

export function sign(body: string): string {
  return "sha256=" + createHmac("sha256", SECRET).update(body).digest("hex");
}

export async function customerWrites(from: string, name: string, texts: string[], at: Date = new Date()): Promise<void> {
  const messages = texts.map((text, i) => ({
    from,
    id: `wamid.e2e.${from}.${at.getTime()}.${i}`,
    timestamp: String(Math.floor(at.getTime() / 1000) + i),
    type: "text",
    text: { body: text },
  }));
  const body = JSON.stringify({
    object: "whatsapp_business_account",
    entry: [
      {
        id: "WABA",
        changes: [
          {
            field: "messages",
            value: {
              messaging_product: "whatsapp",
              metadata: { display_phone_number: "493055501000", phone_number_id: DENTAL_NUMBER_ID },
              contacts: [{ wa_id: from, profile: { name } }],
              messages,
            },
          },
        ],
      },
    ],
  });
  const res = await fetch(`${CORE}/webhooks/whatsapp`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Hub-Signature-256": sign(body) },
    body,
  });
  if (res.status !== 200) throw new Error(`webhook answered ${res.status}`);
}

/** A WhatsApp id unique to this run. */
export function waId(n: number): string {
  return "4915" + String((Date.now() % 1e8) * 10 + n).padStart(9, "0");
}
