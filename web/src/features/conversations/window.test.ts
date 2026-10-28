import { fuse } from "./window";

const now = Date.parse("2026-10-28T10:00:00Z");
const w = (channel: "WHATSAPP" | "SMS" | "INSTAGRAM", closesInMs: number | null, open = true) => ({
  channel,
  open,
  closesAt: closesInMs === null ? null : new Date(now + closesInMs).toISOString(),
  aiMayReply: open,
  entryPoint: "ORGANIC",
  optedOut: false,
});

describe("reply window fuse", () => {
  it("has no fuse for channels without a window", () => {
    expect(fuse(w("SMS", null), now).kind).toBe("none");
    expect(fuse(null, now).kind).toBe("none");
  });

  it("burns down over 24 hours on WhatsApp and warns in the last two", () => {
    const half = fuse(w("WHATSAPP", 12 * 3600_000), now);
    expect(half).toMatchObject({ kind: "open", warning: false, label: "12 h left to reply" });
    expect(half.kind === "open" && half.fraction).toBeCloseTo(0.5);
    expect(fuse(w("WHATSAPP", 90 * 60_000), now)).toMatchObject({ kind: "open", warning: true, label: "1 h 30 min left to reply" });
  });

  it("measures Instagram against seven days", () => {
    const f = fuse(w("INSTAGRAM", 3.5 * 24 * 3600_000), now);
    expect(f.kind === "open" && f.fraction).toBeCloseTo(0.5);
  });

  it("is closed when the time has run out, even if the server still said open", () => {
    expect(fuse(w("WHATSAPP", -1000), now).kind).toBe("closed");
    expect(fuse(w("WHATSAPP", 5000, false), now).kind).toBe("closed");
  });

  it("says when a customer opted out", () => {
    expect(fuse({ ...w("SMS", null), optedOut: true }, now).kind).toBe("opted-out");
  });
});
