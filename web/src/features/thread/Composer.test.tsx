import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SessionProvider } from "../session/session";
import { Composer } from "./Composer";

const me = { teammateId: "tm_anna", name: "Anna", workspaceId: "ws_dental" };
const openWindow = {
  channel: "WHATSAPP" as const,
  open: true,
  closesAt: new Date(Date.now() + 10 * 3600_000).toISOString(),
  aiMayReply: true,
  entryPoint: "ORGANIC",
  optedOut: false,
};

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status });
}

function renderComposer(onSent = vi.fn()) {
  render(
    <SessionProvider me={me} token="demo-anna" onSignOut={() => {}}>
      <Composer conversationId="cv_1" window={openWindow} onSent={onSent} />
    </SessionProvider>,
  );
  return onSent;
}

afterEach(() => vi.unstubAllGlobals());

describe("Composer", () => {
  it("retries a failed send with the same idempotency key, so the customer gets it once", async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new TypeError("network down")).mockResolvedValueOnce(json(202, { messageId: "m1", replayed: false }));
    vi.stubGlobal("fetch", fetch);
    const onSent = renderComposer();
    await userEvent.type(screen.getByLabelText("Reply"), "See you at 9");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Sending again is safe");
    expect(screen.getByLabelText("Reply")).toHaveValue("See you at 9");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(onSent).toHaveBeenCalled());
    const keys = fetch.mock.calls.map(([, init]) => (init as RequestInit & { headers: Record<string, string> }).headers["Idempotency-Key"]);
    expect(keys[0]).toBe(keys[1]);
    expect(screen.getByLabelText("Reply")).toHaveValue("");
  });

  it("switches to approved templates when the window closed before sending", async () => {
    const fetch = vi.fn((url: string) =>
      Promise.resolve(
        url.endsWith("/templates")
          ? json(200, [{ name: "follow_up", language: "en", category: "UTILITY", body: "Hello, following up on your message." }])
          : json(409, { error: "template_required", reason: "the 24-hour window has closed" }),
      ),
    );
    vi.stubGlobal("fetch", fetch);
    renderComposer();
    await userEvent.type(screen.getByLabelText("Reply"), "Are you still there?");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByLabelText("Template")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Choose an approved template");
  });
});
