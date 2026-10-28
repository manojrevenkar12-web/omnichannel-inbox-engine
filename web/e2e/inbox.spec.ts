import { expect, test, type Page } from "@playwright/test";
import { customerWrites, waId } from "./whatsapp";

// Runs against the real stack: the Quarkus core with PostgreSQL, the Python agent, and a fake
// WhatsApp Cloud API. Customers write through signed webhooks; a teammate works in the browser.

async function signInAs(page: Page, who: "Anna" | "Ben") {
  await page.goto("/");
  await page.getByRole("button", { name: new RegExp(`^${who}`) }).click();
  await expect(page.getByRole("navigation", { name: "Views" })).toBeVisible();
}

async function open(page: Page, contact: string) {
  await page.getByRole("button", { name: "All open", exact: true }).click();
  const row = page.getByRole("button", { name: new RegExp(contact) });
  await expect(row).toBeVisible({ timeout: 30_000 });
  await row.click();
  await expect(page.getByRole("heading", { name: contact })).toBeVisible();
}

test("the AI agent answers a question from the practice's knowledge, once for the whole burst", async ({ page }) => {
  const contact = "Lea Brandt";
  await customerWrites(waId(1), contact, ["Hi", "when are you open on friday?"]);
  await signInAs(page, "Anna");
  await open(page, contact);
  const ai = page.getByRole("list", { name: "Messages" }).getByRole("listitem").filter({ hasText: "AI agent" });
  await expect(ai).toHaveCount(1, { timeout: 30_000 });
  await expect(ai).toContainText("14:00");
  await expect(ai).toContainText(/Sent|Delivered/, { timeout: 15_000 });
});

test("pain goes to a person, who takes it and replies; the reply is delivered", async ({ page }) => {
  const contact = "Jonas Weber";
  await customerWrites(waId(2), contact, ["my tooth hurts a lot since yesterday", "can I come today?"]);
  await signInAs(page, "Anna");
  await open(page, contact);
  await expect(page.getByText(/Handed over: symptoms/)).toBeVisible({ timeout: 30_000 });
  const take = page.getByRole("button", { name: "Take it" });
  if (await take.isVisible()) await take.click();
  await expect(page.getByText(/with you/)).toBeVisible();
  await page.getByLabel("Reply").fill("Please come at 11:30, we will see you today.");
  await page.getByRole("button", { name: "Send" }).click();
  const mine = page.getByRole("list", { name: "Messages" }).getByRole("listitem").filter({ hasText: "Please come at 11:30" });
  await expect(mine).toContainText("Delivered", { timeout: 15_000 });
  await expect(page.getByLabel("Reply")).toHaveValue("");
});

test("after 24 hours only an approved template can be sent", async ({ page }) => {
  const contact = "Sofia Marino";
  await customerWrites(waId(3), contact, ["Danke!"], new Date(Date.now() - 26 * 3600_000));
  await signInAs(page, "Anna");
  await open(page, contact);
  await expect(page.getByLabel("Conversation").getByText("Window closed: template only")).toBeVisible();
  await page.getByLabel("Template").selectOption({ label: "follow_up (en), Utility" });
  await page.getByRole("button", { name: "Send template" }).click();
  await expect(page.getByRole("list", { name: "Messages" }).getByRole("listitem").filter({ hasText: "Template follow_up" })).toContainText(/Sent|Delivered/, { timeout: 15_000 });
});

test("a teammate of another business sees none of it", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: /^Nils/ }).click();
  await page.getByRole("button", { name: "All open", exact: true }).click();
  await expect(page.getByText("No conversations here")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("Jonas Weber")).toHaveCount(0);
});
