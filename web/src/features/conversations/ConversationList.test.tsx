import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SessionProvider } from "../session/session";
import { ConversationList } from "./ConversationList";

const me = { teammateId: "tm_anna", name: "Anna", workspaceId: "ws_dental" };

function show(view: "mine" | "all" = "all") {
  render(
    <SessionProvider me={me} token="demo-anna" onSignOut={() => {}}>
      <ConversationList view={view} selected={null} onSelect={() => {}} />
    </SessionProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("ConversationList", () => {
  it("shows a skeleton while loading", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    show();
    expect(screen.getByRole("status", { name: "Loading conversations" })).toBeInTheDocument();
  });

  it("offers a retry when the core does not answer, and recovers", async () => {
    const fetch = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("down"))
      .mockResolvedValue(new Response(JSON.stringify([]), { status: 200 }));
    vi.stubGlobal("fetch", fetch);
    show("mine");
    await userEvent.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Nothing assigned to you")).toBeInTheDocument();
  });

  it("lists conversations with who has them and how long the window has left", async () => {
    const row = {
      id: "cv_1",
      contact: "Mia Kowalski",
      channels: ["WHATSAPP"],
      status: "OPEN",
      assigneeKind: "QUEUE",
      assigneeId: null,
      assigneeName: null,
      lastMessage: "my tooth hurts",
      lastMessageAt: new Date().toISOString(),
      lastAuthor: "CUSTOMER",
      unansweredSince: new Date().toISOString(),
      window: {
        channel: "WHATSAPP",
        open: true,
        closesAt: new Date(Date.now() + 60 * 60_000).toISOString(),
        aiMayReply: true,
        entryPoint: "ORGANIC",
        optedOut: false,
      },
    };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([row]), { status: 200 })));
    show();
    expect(await screen.findByText("Mia Kowalski")).toBeInTheDocument();
    expect(screen.getAllByText("Waiting for someone").length).toBeGreaterThan(0);
    expect(screen.getByText("1 h left to reply")).toBeInTheDocument();
  });
});
