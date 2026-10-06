import assert from "node:assert/strict";
import test from "node:test";
import { OperatorClient, OperatorError, runOperator } from "./starglean.mjs";

const OPERATOR = "047c228c-e80b-4b23-adfa-c7afc5318c19";
const REPORT = "11111111-1111-4111-8111-111111111111";
const ENTRY = "22222222-2222-4222-8222-222222222222";

class FakeClient {
  calls = [];
  userId = OPERATOR;
  listResult = { status: "FOUND", items: [] };
  mutationResult = { status: "REPORT_RESOLVED" };
  async requestOtp(email) { this.calls.push(["otp", email]); }
  async verifyOtp(email, code) { this.calls.push(["verify", email, code]); return { access_token: "memory-token" }; }
  async getUser(token) { this.calls.push(["user", token]); return { id: this.userId }; }
  async staff(token, action, payload) {
    this.calls.push(["staff", token, action, payload]);
    return action.startsWith("list_") ? this.listResult : this.mutationResult;
  }
  async signOutLocal(token) { this.calls.push(["signout", token]); }
}

function fakeIo(answers) {
  const queue = [...answers], output = [];
  return {
    output,
    prompt: async () => queue.shift(),
    promptSecret: async () => queue.shift(),
    print: (value) => output.push(value),
  };
}

test("list authenticates by OTP, verifies exact operator, and redacts content and PII", async () => {
  const client = new FakeClient();
  client.listResult = { status: "FOUND", items: [{ report_id: REPORT, entry_id: ENTRY, category: "spam",
    created_at: "2026-09-27T00:00:00Z", title: "private title", body: "private body", detail: "reporter detail", owner_id: "secret" }] };
  const io = fakeIo(["operator@example.com", "12345678"]);

  const result = await runOperator({ client, io, args: ["list", "reports", "10"] });

  assert.deepEqual(result, { status: "LISTED", count: 1 });
  const printed = io.output.join("\n");
  assert.match(printed, new RegExp(REPORT)); assert.match(printed, /spam/);
  for (const privateValue of ["private title", "private body", "reporter detail", "secret", "memory-token", "12345678"]) {
    assert.equal(printed.includes(privateValue), false);
  }
  assert.deepEqual(client.calls.at(-1), ["signout", "memory-token"]);
});

test("mutation prints exact target and reason but sends nothing without typed confirmation", async () => {
  const client = new FakeClient();
  const io = fakeIo(["operator@example.com", "12345678", "spam-confirmed", "no"]);

  await assert.rejects(() => runOperator({ client, io, args: ["action", REPORT] }), /Confirmation did not match/);

  assert.match(io.output.join("\n"), new RegExp(REPORT));
  assert.match(io.output.join("\n"), /spam-confirmed/);
  assert.equal(client.calls.some((call) => call[0] === "staff"), false);
  assert.deepEqual(client.calls.at(-1), ["signout", "memory-token"]);
});

test("view requires target-specific confirmation and escapes terminal control content", async () => {
  const deniedClient = new FakeClient(), deniedIo = fakeIo(["operator@example.com", "12345678", "VIEW wrong"]);
  await assert.rejects(() => runOperator({ client: deniedClient, io: deniedIo, args: ["view", REPORT] }), /content was not requested/);
  assert.equal(deniedClient.calls.some((call) => call[0] === "staff"), false);
  assert.deepEqual(deniedClient.calls.at(-1), ["signout", "memory-token"]);

  const client = new FakeClient();
  client.listResult = { status: "FOUND", items: [
    { report_id: "33333333-3333-4333-8333-333333333333", entry_id: ENTRY, title: "other private body", body: "other", detail: "other" },
    { report_id: REPORT, entry_id: ENTRY, category: "spam\u001b[31m", created_at: "2026-09-27T00:00:00Z",
      title: "title\u001b]0;owned\u0007", body: "line one\nline two\u202etxt", detail: "detail\u009b31m" },
  ] };
  const io = fakeIo(["operator@example.com", "12345678", `VIEW ${REPORT}`]);
  const result = await runOperator({ client, io, args: ["view", REPORT] });
  const printed = io.output.join("\n");

  assert.deepEqual(result, { status: "VIEWED", reportId: REPORT });
  assert.equal(printed.includes("other private body"), false);
  for (const unsafe of ["\u001b", "\u0007", "\u202e", "\u009b"]) assert.equal(printed.includes(unsafe), false);
  assert.equal(printed.includes("line one\nline two"), false);
  assert.match(printed, /\\\\u001b/); assert.match(printed, /\\\\u000a/); assert.match(printed, /\\\\u202e/);
  assert.deepEqual(client.calls.find((call) => call[0] === "staff"), ["staff", "memory-token", "list_reports", { limit: 50 }]);
  assert.deepEqual(client.calls.at(-1), ["signout", "memory-token"]);
});

test("confirmed dismiss and restore use only their narrow staff actions", async () => {
  for (const scenario of [
    { args: ["dismiss", REPORT], confirm: `DISMISS ${REPORT}`, rpc: "report_dismiss", key: "report_id", status: "REPORT_RESOLVED" },
    { args: ["restore", ENTRY], confirm: `RESTORE ${ENTRY}`, rpc: "restore", key: "entry_id", status: "REVIEWED" },
  ]) {
    const client = new FakeClient(); client.mutationResult = { status: scenario.status };
    const io = fakeIo(["operator@example.com", "12345678", "operator-review", scenario.confirm]);
    await runOperator({ client, io, args: scenario.args });
    assert.deepEqual(client.calls.find((call) => call[0] === "staff"),
      ["staff", "memory-token", scenario.rpc, { [scenario.key]: scenario.args[1], reason_code: "operator-review" }]);
    assert.deepEqual(client.calls.at(-1), ["signout", "memory-token"]);
  }
});

test("a different authenticated UUID is rejected and the local session is revoked", async () => {
  const client = new FakeClient(); client.userId = crypto.randomUUID();
  const io = fakeIo(["operator@example.com", "12345678"]);
  await assert.rejects(() => runOperator({ client, io, args: ["list", "reports"] }), /not the configured/);
  assert.equal(client.calls.some((call) => call[0] === "staff"), false);
  assert.deepEqual(client.calls.at(-1), ["signout", "memory-token"]);
});

test("HTTP client uses publishable key, bearer token, response bound, and abort deadline", async () => {
  const requests = [];
  const client = new OperatorClient("https://example.supabase.co", "sb_publishable_test_key_123", async (url, init) => {
    requests.push({ url, init });
    return new Response(JSON.stringify({ id: OPERATOR }), { headers: { "content-type": "application/json" } });
  });
  await client.getUser("memory-token");
  assert.equal(requests[0].init.headers.apikey, "sb_publishable_test_key_123");
  assert.equal(requests[0].init.headers.authorization, "Bearer memory-token");
  assert.equal(JSON.stringify(requests[0]).includes("service_role"), false);

  const oversized = new OperatorClient("https://example.supabase.co", "sb_publishable_test_key_123",
    async () => new Response("x".repeat(65 * 1024)));
  await assert.rejects(() => oversized.getUser("token"), /exceeded the operator bound/);

  const largeStaffList = new OperatorClient("https://example.supabase.co", "sb_publishable_test_key_123",
    async () => new Response(JSON.stringify({ status: "FOUND", items: [{ report_id: REPORT, body: "x".repeat(100 * 1024) }] })));
  const list = await largeStaffList.staff("token", "list_reports", { limit: 50 });
  assert.equal(list.items[0].body.length, 100 * 1024);

  let signal;
  const timeout = new OperatorClient("https://example.supabase.co", "sb_publishable_test_key_123", async (_url, init) => {
    signal = init.signal;
    return new Promise((_resolve, reject) => signal.addEventListener("abort", () => reject(signal.reason), { once: true }));
  }, 5);
  await assert.rejects(() => timeout.getUser("token"), /exceeded 20 seconds/);
  assert.equal(signal.aborted, true);
});

test("OTP cannot be supplied through command arguments", async () => {
  const client = new FakeClient(), io = fakeIo([]);
  await assert.rejects(() => runOperator({ client, io, args: ["list", "reports", "10", "12345678"] }), OperatorError);
  assert.deepEqual(client.calls, []);
});
