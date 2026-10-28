// A stand-in for the WhatsApp Cloud API during the end-to-end test: accepts every send and posts
// "sent" and "delivered" status webhooks back to the core, signed like Meta's.
import { createHmac } from "node:crypto";
import { createServer } from "node:http";

const port = Number(process.env.PORT ?? 9099);
const core = process.env.INBOX_CORE_URL ?? "http://localhost:8080";
const secret = process.env.WHATSAPP_APP_SECRET ?? "dev-app-secret";
let seq = 0;

async function status(phoneNumberId, id, callback, to, s) {
  const body = JSON.stringify({
    object: "whatsapp_business_account",
    entry: [{ id: "WABA", changes: [{ field: "messages", value: {
      messaging_product: "whatsapp",
      metadata: { display_phone_number: "493055501000", phone_number_id: phoneNumberId },
      statuses: [{ id, status: s, timestamp: String(Math.floor(Date.now() / 1000)), recipient_id: to, biz_opaque_callback_data: callback }],
    } }] }],
  });
  const sig = "sha256=" + createHmac("sha256", secret).update(body).digest("hex");
  await fetch(`${core}/webhooks/whatsapp`, { method: "POST", headers: { "Content-Type": "application/json", "X-Hub-Signature-256": sig }, body }).catch(() => {});
}

createServer((req, res) => {
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    if (req.method !== "POST" || !req.url.endsWith("/messages")) {
      res.writeHead(404).end();
      return;
    }
    const msg = JSON.parse(raw);
    const phoneNumberId = req.url.split("/").at(-2);
    const id = `wamid.e2e.out.${++seq}`;
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ messaging_product: "whatsapp", contacts: [{ input: msg.to, wa_id: msg.to }], messages: [{ id }] }));
    setTimeout(() => status(phoneNumberId, id, msg.biz_opaque_callback_data, msg.to, "sent"), 50);
    setTimeout(() => status(phoneNumberId, id, msg.biz_opaque_callback_data, msg.to, "delivered"), 150);
  });
}).listen(port, () => console.log(`fake WhatsApp Cloud API on ${port}`));
