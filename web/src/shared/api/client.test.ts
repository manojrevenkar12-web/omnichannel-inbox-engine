import { ApiError, createClient } from "./client";

function respond(status: number, body: unknown) {
  return Promise.resolve(new Response(body === null ? "" : JSON.stringify(body), { status }));
}

describe("console API client", () => {
  it("sends the token and the draft's idempotency key", async () => {
    const f = vi.fn().mockReturnValue(respond(202, { messageId: "msg_1", replayed: false }));
    const c = createClient("demo-anna", "/api", f);
    const r = await c.send("cv_1", "key-1", { text: "Hello" });
    expect(r).toEqual({ kind: "queued", messageId: "msg_1", replayed: false });
    const [url, init] = f.mock.calls[0];
    expect(url).toBe("/api/conversations/cv_1/messages");
    expect(init.headers).toMatchObject({ Authorization: "Bearer demo-anna", "Idempotency-Key": "key-1" });
  });

  it("turns a closed window into an outcome, not an error", async () => {
    const f = vi.fn().mockReturnValue(respond(409, { error: "template_required", reason: "window closed" }));
    expect(await createClient("t", "/api", f).send("cv", "k", { text: "x" })).toEqual({ kind: "template_required", reason: "window closed" });
  });

  it("raises other failures with their status", async () => {
    const f = vi.fn().mockReturnValue(respond(401, { error: "unknown or missing token" }));
    await expect(createClient("t", "/api", f).me()).rejects.toEqual(new ApiError(401, { error: "unknown or missing token" }));
  });
});
