import { createClient } from "npm:@supabase/supabase-js@2.57.4";
import { decode } from "npm:jpeg-js@0.4.4";

const MAX_REQUEST_BYTES = 4096;
const MAX_MEDIA_BYTES = 8_388_608;
const safeHeaders = { "cache-control": "no-store", "x-content-type-options": "nosniff" };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { ...safeHeaders, "content-type": "application/json" },
});
export const exactKeys = (value: Record<string, unknown>, allowed: string[]) =>
  Object.keys(value).every((key) => allowed.includes(key)) && allowed.every((key) => key in value);

export class RequestInputError extends Error {
  constructor(public readonly statusCode: number, public readonly responseStatus: string) {
    super(responseStatus);
  }
}

export async function readBoundedText(request: Request, limit = MAX_REQUEST_BYTES): Promise<string> {
  const declared = request.headers.get("content-length");
  if (declared !== null) {
    const length = Number(declared);
    if (!Number.isSafeInteger(length) || length < 0) {
      throw new RequestInputError(400, "INVALID_REQUEST");
    }
    if (length > limit) throw new RequestInputError(413, "PAYLOAD_TOO_LARGE");
  }

  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > limit) {
        await reader.cancel("payload too large");
        throw new RequestInputError(413, "PAYLOAD_TOO_LARGE");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new RequestInputError(400, "INVALID_REQUEST");
  }
}

export async function parseRequestInput(request: Request): Promise<Record<string, unknown>> {
  let input: unknown;
  try {
    input = JSON.parse(await readBoundedText(request));
  } catch (error) {
    if (error instanceof RequestInputError) throw error;
    throw new RequestInputError(400, "INVALID_REQUEST");
  }
  if (input === null || typeof input !== "object" || Array.isArray(input)) {
    throw new RequestInputError(400, "INVALID_REQUEST");
  }
  return input as Record<string, unknown>;
}

export function inspectJpeg(bytes: Uint8Array) {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) throw new Error("not jpeg");
  let offset = 2, width = 0, height = 0;
  while (offset + 4 <= bytes.length) {
    if (bytes[offset++] !== 0xff) throw new Error("bad marker");
    const marker = bytes[offset++];
    if (marker === 0xd9 || marker === 0xda) break;
    const length = (bytes[offset] << 8) | bytes[offset + 1];
    if (length < 2 || offset + length > bytes.length) throw new Error("bad segment");
    if (marker === 0xe1) throw new Error("APP1 forbidden");
    if ([0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf].includes(marker)) {
      if (length < 8) throw new Error("short SOF segment");
      height = (bytes[offset + 3] << 8) | bytes[offset + 4];
      width = (bytes[offset + 5] << 8) | bytes[offset + 6];
    }
    offset += length;
  }
  if (width < 1 || height < 1 || width > 2048 || height > 2048) throw new Error("dimensions");
  const decoded = decode(bytes, {
    useTArray: true,
    formatAsRGBA: false,
    tolerantDecoding: false,
    maxResolutionInMP: 5,
    // jpeg-js needs about 75 MiB at the contract maximum of 2048x2048.
    // Keep enough headroom for that valid case while retaining a hard cap.
    maxMemoryUsageInMB: 96,
  });
  if (decoded.width !== width || decoded.height !== height) throw new Error("decode mismatch");
  return { width, height };
}

async function hash(bytes: Uint8Array) {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return [...digest].map((value) => value.toString(16).padStart(2, "0")).join("");
}
const base64 = (hex: string) => btoa(String.fromCharCode(...(hex.match(/../g) ?? []).map((x) => parseInt(x, 16))));

export async function handleRequest(request: Request): Promise<Response> {
  try {
    if (request.method !== "POST") return json({ status: "METHOD_NOT_ALLOWED" }, 405);
    const bearer = request.headers.get("authorization") ?? "";
    if (!bearer.startsWith("Bearer ")) return json({ status: "AUTH_REQUIRED" }, 401);
    const input = await parseRequestInput(request);
    const url = Deno.env.get("SUPABASE_URL");
    const anon = Deno.env.get("SUPABASE_ANON_KEY");
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !anon || !service) throw new Error("missing server configuration");
    const user = createClient(url, anon, { global: { headers: { Authorization: bearer } } });
    const admin = createClient(url, service, { auth: { persistSession: false } });
    if (!(await user.auth.getUser()).data.user) return json({ status: "AUTH_REQUIRED" }, 401);

    if (input.action === "finalize") {
      if (!exactKeys(input, ["action", "entry_id", "media_id"])) return json({ status: "INVALID_REQUEST" }, 400);
      const upload = (await user.rpc("nearby_public_command", { action: "media_upload",
        payload: { entry_id: input.entry_id, media_id: input.media_id } })).data;
      if (upload?.status === "MEDIA_ALREADY_VALIDATED") return json(upload);
      if (upload?.status !== "UPLOAD_READY") return json(upload, 409);
      const downloaded = await admin.storage.from(upload.bucket).download(upload.object_path);
      if (downloaded.error || !downloaded.data) return json({ status: "OBJECT_NOT_FOUND" }, 409);
      if (downloaded.data.size < 1 || downloaded.data.size > MAX_MEDIA_BYTES || downloaded.data.size !== upload.bytes)
        return json({ status: "VALIDATION_REJECTED" }, 422);
      const bytes = new Uint8Array(await downloaded.data.arrayBuffer());
      let dimensions: { width: number; height: number };
      try { dimensions = inspectJpeg(bytes); }
      catch { return json({ status: "VALIDATION_REJECTED" }, 422); }
      const actualHash = await hash(bytes);
      if (bytes.length !== upload.bytes || actualHash !== upload.sha256 || upload.content_type !== "image/jpeg")
        return json({ status: "VALIDATION_REJECTED" }, 422);
      const fresh = (await user.rpc("nearby_public_command", { action: "media_upload",
        payload: { entry_id: input.entry_id, media_id: input.media_id } })).data;
      // A concurrent identical finalizer may have completed while this request
      // was validating. That is confirmed success, not a deletion failure.
      if (fresh?.status === "MEDIA_ALREADY_VALIDATED") return json(fresh);
      if (fresh?.status !== "UPLOAD_READY") return json({ status: "DELETE_IN_PROGRESS" }, 409);
      const validated = await admin.rpc("nearby_media_validation", { media_id: input.media_id,
        content_type: "image/jpeg", byte_count: bytes.length, width: dimensions.width,
        height: dimensions.height, sha256: actualHash, exif_absent: true });
      if (validated.error) throw validated.error;
      return json(validated.data);
    }

    if (input.action === "read") {
      if (!exactKeys(input, ["action", "media_id"])) return json({ status: "INVALID_REQUEST" }, 400);
      const grant = (await user.rpc("nearby_public_command", { action: "media_access",
        payload: { media_id: input.media_id } })).data;
      if (grant?.status !== "MEDIA_AUTHORIZED") return json(grant, 403);
      const path = (await admin.rpc("nearby_service_media_path", { ticket_id: grant.ticket_id })).data;
      if (!path) return json({ status: "MEDIA_NOT_AUTHORIZED" }, 403);
      const downloaded = await admin.storage.from("nearby-private-photos").download(path);
      if (downloaded.error || !downloaded.data) return json({ status: "OBJECT_NOT_FOUND" }, 404);
      if (downloaded.data.size < 1 || downloaded.data.size > MAX_MEDIA_BYTES)
        return json({ status: "VALIDATION_REJECTED" }, 422);
      const bytes = new Uint8Array(await downloaded.data.arrayBuffer());
      const actualHash = await hash(bytes);
      // Recheck after the potentially slow Storage read and digest, immediately before disclosure.
      const recheck = (await user.rpc("nearby_public_command", { action: "media_access_check",
        payload: { ticket_id: grant.ticket_id } })).data;
      if (recheck?.status !== "MEDIA_AUTHORIZED") return json(recheck, 403);
      return new Response(bytes, { headers: { ...safeHeaders, "content-type": "image/jpeg",
        "content-length": String(bytes.length), "digest": `sha-256=${base64(actualHash)}` } });
    }

    if (input.action === "delete_entry" || input.action === "delete_account") {
      if (!exactKeys(input, input.action === "delete_entry" ? ["action", "entry_id"] : ["action"]))
        return json({ status: "INVALID_REQUEST" }, 400);
      const entry = input.action === "delete_entry";
      const begin = (await user.rpc("nearby_public_command", { action: entry ? "delete_entry" : "account_delete",
        payload: entry ? { entry_id: input.entry_id } : { confirmation: "DELETE" } })).data;
      if (entry && begin?.status === "ENTRY_DELETED") return json(begin);
      if (begin?.status !== "DELETE_PREPARED") return json(begin, 409);
      const inventory = await admin.rpc("nearby_service_delete", { action: "paths", operation_id: begin.operation_id });
      if (inventory.error) throw inventory.error;
      if (inventory.data.paths.length) {
        const removed = await admin.storage.from(inventory.data.bucket).remove(inventory.data.paths);
        if (removed.error) return json({ status: "DELETE_RETRY_REQUIRED", operation_id: begin.operation_id }, 503);
      }
      const done = await admin.rpc("nearby_service_delete", { action: "finalize", operation_id: begin.operation_id });
      if (done.error) throw done.error;
      return json(done.data);
    }
    return json({ status: "UNSUPPORTED_ACTION" }, 400);
  } catch (error) {
    if (error instanceof RequestInputError) return json({ status: error.responseStatus }, error.statusCode);
    // Unexpected storage/RPC/configuration errors do not prove a bad request.
    // Keep the client retry journal and never include upstream details/tokens.
    return json({ status: "SERVICE_UNAVAILABLE" }, 503);
  }
}

if (import.meta.main) Deno.serve(handleRequest);
