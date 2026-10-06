#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { createInterface } from "node:readline/promises";
import { fileURLToPath } from "node:url";

const OPERATOR_USER_ID = "047c228c-e80b-4b23-adfa-c7afc5318c19";
const MAX_RESPONSE_BYTES = 64 * 1024;
const MAX_STAFF_LIST_BYTES = 2_621_440;
const TIMEOUT_MS = 20_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export class OperatorError extends Error {}

async function boundedBytes(response, limit = MAX_RESPONSE_BYTES) {
  const declared = response.headers.get("content-length");
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > limit)) throw new OperatorError("Response exceeded the operator bound");
  const reader = response.body?.getReader(), chunks = []; let total = 0;
  if (reader) while (true) {
    const { done, value } = await reader.read(); if (done) break;
    total += value.byteLength;
    if (total > limit) { await reader.cancel(); throw new OperatorError("Response exceeded the operator bound"); }
    chunks.push(value);
  }
  const result = new Uint8Array(total); let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.byteLength; }
  return result;
}

export class OperatorClient {
  constructor(url, publishableKey, fetchImpl = fetch, timeoutMs = TIMEOUT_MS) {
    const endpoint = new URL(url);
    if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || endpoint.search || endpoint.hash ||
        (endpoint.pathname !== "/" && endpoint.pathname !== "") || !endpoint.hostname.endsWith(".supabase.co")) {
      throw new OperatorError("Invalid Supabase URL");
    }
    if (!publishableKey?.startsWith("sb_publishable_") || publishableKey.length > 256) throw new OperatorError("Invalid publishable key");
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > TIMEOUT_MS) throw new OperatorError("Invalid timeout");
    this.url = endpoint.toString().replace(/\/$/, ""); this.key = publishableKey; this.fetchImpl = fetchImpl; this.timeoutMs = timeoutMs;
  }

  async request(path, { method = "POST", token, body, maxResponseBytes = MAX_RESPONSE_BYTES } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(new OperatorError("Operator request exceeded 20 seconds")), this.timeoutMs);
    let response, bytes;
    try {
      response = await this.fetchImpl(`${this.url}${path}`, {
        method, signal: controller.signal,
        headers: { apikey: this.key, ...(token ? { authorization: `Bearer ${token}` } : {}),
          ...(body === undefined ? {} : { "content-type": "application/json" }), accept: "application/json" },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      bytes = await boundedBytes(response, maxResponseBytes);
    } finally { clearTimeout(timer); }
    let value = {};
    if (bytes.length) try { value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)); }
    catch { throw new OperatorError(`Server returned non-JSON HTTP ${response.status}`); }
    if (!response.ok) throw new OperatorError(`Server returned HTTP ${response.status}`);
    return value;
  }

  requestOtp(email) { return this.request("/auth/v1/otp", { body: { email, create_user: false } }); }
  verifyOtp(email, code) { return this.request("/auth/v1/verify", { body: { email, token: code, type: "email" } }); }
  getUser(token) { return this.request("/auth/v1/user", { method: "GET", token }); }
  staff(token, action, payload) { return this.request("/rest/v1/rpc/nearby_staff_command", {
    token, body: { action, payload }, maxResponseBytes: action.startsWith("list_") ? MAX_STAFF_LIST_BYTES : MAX_RESPONSE_BYTES,
  }); }
  signOutLocal(token) { return this.request("/auth/v1/logout?scope=local", { token }); }
}

function validateEmail(value) {
  const email = value.trim().toLowerCase();
  if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new OperatorError("Enter a valid operator email");
  return email;
}

function safeItems(kind, value) {
  if (value?.status !== "FOUND" || !Array.isArray(value.items)) throw new OperatorError(`Staff list failed: ${value?.status || "INVALID_RESPONSE"}`);
  return value.items.map((item) => kind === "reports" ? {
    reportId: item.report_id, entryId: item.entry_id, category: item.category, createdAt: item.created_at,
  } : kind === "appeals" ? {
    appealId: item.appeal_id, entryId: item.entry_id, createdAt: item.created_at,
  } : { entryId: item.entry_id, preparedAt: item.prepared_at });
}

function terminalSafe(value) {
  if (typeof value !== "string") return null;
  let result = "";
  for (const character of value) {
    const code = character.codePointAt(0);
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f) ||
        (code >= 0x202a && code <= 0x202e) || (code >= 0x2066 && code <= 0x2069)) {
      result += `\\u${code.toString(16).padStart(4, "0")}`;
    } else result += character;
  }
  return result;
}

function exactReport(value, reportId) {
  if (value?.status !== "FOUND" || !Array.isArray(value.items)) throw new OperatorError(`Staff list failed: ${value?.status || "INVALID_RESPONSE"}`);
  const matches = value.items.filter((item) => item?.report_id === reportId);
  if (matches.length !== 1) throw new OperatorError(matches.length ? "Server returned duplicate report identities" : "Report was not found in the current open queue");
  const item = matches[0];
  return {
    reportId: item.report_id,
    entryId: item.entry_id,
    category: terminalSafe(item.category),
    createdAt: item.created_at,
    title: terminalSafe(item.title),
    body: terminalSafe(item.body),
    reportDetail: terminalSafe(item.detail),
  };
}

function parseCommand(args) {
  if (args[0] === "list" && ["reports", "pending", "appeals"].includes(args[1]) && args.length <= 3) {
    const limit = args[2] === undefined ? 5 : Number(args[2]);
    if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw new OperatorError("List limit must be 1..50");
    return { type: "list", kind: args[1], limit };
  }
  if (args[0] === "view" && args.length === 2 && UUID.test(args[1])) {
    return { type: "view", targetId: args[1].toLowerCase() };
  }
  if (["action", "dismiss", "restore"].includes(args[0]) && args.length === 2 && UUID.test(args[1])) {
    return { type: "mutation", operation: args[0], targetId: args[1].toLowerCase() };
  }
  throw new OperatorError("Usage: starglean.mjs list reports|pending|appeals [limit] | view REPORT_ID | action|dismiss REPORT_ID | restore ENTRY_ID");
}

export async function runOperator({ client, io, args }) {
  const command = parseCommand(args);
  const email = validateEmail(await io.prompt("Operator email: "));
  await client.requestOtp(email);
  const code = await io.promptSecret("8-digit email code: ");
  if (!/^\d{8}$/.test(code)) throw new OperatorError("The email code must contain exactly 8 digits");
  const verified = await client.verifyOtp(email, code);
  const token = verified?.access_token;
  if (!token) throw new OperatorError("OTP verification did not return a session");
  try {
    const user = await client.getUser(token);
    if (user?.id !== OPERATOR_USER_ID) throw new OperatorError("This account is not the configured Starglean operator");
    if (command.type === "list") {
      const action = { reports: "list_reports", pending: "list_pending", appeals: "list_appeals" }[command.kind];
      const items = safeItems(command.kind, await client.staff(token, action, { limit: command.limit }));
      io.print(JSON.stringify({ status: "FOUND", kind: command.kind, items }, null, 2));
      return { status: "LISTED", count: items.length };
    }
    if (command.type === "view") {
      const expected = `VIEW ${command.targetId}`;
      if ((await io.prompt(`Type '${expected}' to display report content in this terminal: `)).trim() !== expected) {
        throw new OperatorError("Confirmation did not match; report content was not requested");
      }
      const report = exactReport(await client.staff(token, "list_reports", { limit: 50 }), command.targetId);
      io.print(JSON.stringify({ status: "REPORT_VIEW", report }, null, 2));
      return { status: "VIEWED", reportId: command.targetId };
    }
    const reason = (await io.prompt("Reason code (1-80 characters; avoid personal information): ")).trim();
    if (reason.length < 1 || reason.length > 80) throw new OperatorError("Reason code must contain 1..80 characters");
    const rpcAction = { action: "report_action", dismiss: "report_dismiss", restore: "restore" }[command.operation];
    const idKey = command.operation === "restore" ? "entry_id" : "report_id";
    io.print(JSON.stringify({ operation: command.operation, target: { [idKey]: command.targetId }, reason }));
    const expected = `${command.operation.toUpperCase()} ${command.targetId}`;
    if ((await io.prompt(`Type '${expected}' to confirm: `)).trim() !== expected) throw new OperatorError("Confirmation did not match; no mutation was sent");
    const result = await client.staff(token, rpcAction, { [idKey]: command.targetId, reason_code: reason });
    const allowed = command.operation === "restore" ? "REVIEWED" : "REPORT_RESOLVED";
    if (result?.status !== allowed) throw new OperatorError(`Mutation was not confirmed: ${result?.status || "INVALID_RESPONSE"}`);
    io.print(JSON.stringify({ status: result.status, operation: command.operation, targetId: command.targetId }));
    return { status: result.status };
  } finally {
    try { await client.signOutLocal(token); }
    catch { io.print("WARNING: this CLI could not revoke its current-device session; no token was persisted."); }
  }
}

function publicConfig() {
  const text = readFileSync(new URL("../../supabase/client-config.properties", import.meta.url), "utf8");
  return Object.fromEntries(text.split(/\r?\n/).filter((line) => line && !line.startsWith("#"))
    .map((line) => line.split(/=(.*)/s).slice(0, 2)));
}

async function hiddenQuestion(prompt) {
  if (!process.stdin.isTTY || !process.stdout.isTTY || typeof process.stdin.setRawMode !== "function") {
    throw new OperatorError("OTP entry requires an interactive terminal");
  }
  process.stdout.write(prompt); process.stdin.setRawMode(true); process.stdin.resume();
  return new Promise((resolve, reject) => {
    let value = "";
    const onData = (chunk) => {
      for (const byte of chunk) {
        if (byte === 3) { cleanup(); reject(new OperatorError("Cancelled")); return; }
        if (byte === 13 || byte === 10) { cleanup(); process.stdout.write("\n"); resolve(value); return; }
        if ((byte === 8 || byte === 127) && value.length) value = value.slice(0, -1);
        else if (byte >= 48 && byte <= 57 && value.length < 8) value += String.fromCharCode(byte);
      }
    };
    const cleanup = () => { process.stdin.off("data", onData); process.stdin.setRawMode(false); process.stdin.pause(); };
    process.stdin.on("data", onData);
  });
}

async function main() {
  const config = publicConfig();
  const plainQuestion = async (text) => {
    const readline = createInterface({ input: process.stdin, output: process.stdout });
    try { return await readline.question(text); } finally { readline.close(); }
  };
  const io = { prompt: plainQuestion, promptSecret: hiddenQuestion, print: (text) => process.stdout.write(`${text}\n`) };
  await runOperator({ client: new OperatorClient(config.url, config.publishableKey), io, args: process.argv.slice(2) });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((error) => { process.stderr.write(`${error instanceof Error ? error.message : "Operator command failed"}\n`); process.exitCode = 1; });
}
