import { encode } from "npm:jpeg-js@0.4.4";
import {
  exactKeys,
  inspectJpeg,
  parseRequestInput,
  readBoundedText,
  RequestInputError,
} from "./index.ts";

function assert(condition: unknown, message = "assertion failed"): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals(actual: unknown, expected: unknown): void {
  if (!Object.is(actual, expected)) {
    throw new Error(`expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

function assertThrows(fn: () => unknown, pattern?: RegExp): void {
  try {
    fn();
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (pattern && !pattern.test(message)) throw error;
    return;
  }
  throw new Error("expected function to throw");
}

async function assertInputError(
  promise: Promise<unknown>,
  statusCode: number,
  responseStatus: string,
): Promise<void> {
  try {
    await promise;
  } catch (error) {
    assert(error instanceof RequestInputError, "expected RequestInputError");
    assertEquals(error.statusCode, statusCode);
    assertEquals(error.responseStatus, responseStatus);
    return;
  }
  throw new Error("expected promise to reject");
}

function androidCompatibleJpeg(width = 2, height = 2): Uint8Array {
  // The Android client re-encodes to baseline JPEG. jpeg-js produces the same
  // marker family and, importantly, no EXIF/APP1 segment.
  const rgba = new Uint8Array(width * height * 4);
  for (let index = 0; index < rgba.length; index += 4) {
    rgba[index] = 127;
    rgba[index + 1] = 63;
    rgba[index + 2] = 191;
    rgba[index + 3] = 255;
  }
  return encode({ data: rgba, width, height }, 85).data;
}

function findSof(bytes: Uint8Array): number {
  for (let offset = 2; offset + 8 < bytes.length;) {
    assertEquals(bytes[offset], 0xff);
    const marker = bytes[offset + 1];
    if (marker === 0xda || marker === 0xd9) break;
    const length = (bytes[offset + 2] << 8) | bytes[offset + 3];
    if ([0xc0, 0xc1, 0xc2].includes(marker)) return offset;
    offset += 2 + length;
  }
  throw new Error("SOF not found");
}

Deno.test("inspectJpeg accepts a complete Android-compatible JPEG", () => {
  assertEquals(inspectJpeg(androidCompatibleJpeg()).width, 2);
  assertEquals(inspectJpeg(androidCompatibleJpeg()).height, 2);
});

Deno.test("inspectJpeg memory cap still accepts the 2048x2048 contract maximum", () => {
  const dimensions = inspectJpeg(androidCompatibleJpeg(2048, 2048));
  assertEquals(dimensions.width, 2048);
  assertEquals(dimensions.height, 2048);
});

Deno.test("inspectJpeg rejects truncated JPEG data", () => {
  const jpeg = androidCompatibleJpeg();
  assertThrows(() => inspectJpeg(jpeg.slice(0, jpeg.length - 8)));
});

Deno.test("inspectJpeg rejects EXIF APP1 before decoding", () => {
  const jpeg = androidCompatibleJpeg();
  const withApp1 = new Uint8Array(jpeg.length + 6);
  withApp1.set([0xff, 0xd8, 0xff, 0xe1, 0x00, 0x04, 0x00, 0x00]);
  withApp1.set(jpeg.slice(2), 8);
  assertThrows(() => inspectJpeg(withApp1), /APP1 forbidden/);
});

Deno.test("inspectJpeg rejects malformed and undersized SOF segments", () => {
  assertThrows(() => inspectJpeg(new Uint8Array([0xff, 0xd8, 0xff, 0xc0, 0x00, 0x07, 0, 0, 0, 0, 0])));
  assertThrows(() => inspectJpeg(new Uint8Array([0xff, 0xd8, 0x01, 0x02, 0x03, 0x04])), /bad marker/);
});

Deno.test("inspectJpeg rejects dimensions above the 2048 pixel contract", () => {
  const jpeg = androidCompatibleJpeg().slice();
  const sof = findSof(jpeg);
  jpeg[sof + 7] = 0x08;
  jpeg[sof + 8] = 0x01; // width 2049
  assertThrows(() => inspectJpeg(jpeg), /dimensions/);
});

Deno.test("readBoundedText rejects an oversized Content-Length without reading", async () => {
  const request = new Request("https://example.invalid", {
    method: "POST",
    headers: { "content-length": "4097" },
    body: "{}",
  });
  await assertInputError(readBoundedText(request), 413, "PAYLOAD_TOO_LARGE");
});

Deno.test("readBoundedText bounds a streamed body without Content-Length", async () => {
  const request = new Request("https://example.invalid", {
    method: "POST",
    body: new ReadableStream({
      start(controller) {
        controller.enqueue(new Uint8Array(4096));
        controller.enqueue(new Uint8Array([1]));
        controller.close();
      },
    }),
  });
  await assertInputError(readBoundedText(request), 413, "PAYLOAD_TOO_LARGE");
});

Deno.test("parseRequestInput rejects null and invalid UTF-8", async () => {
  await assertInputError(
    parseRequestInput(new Request("https://example.invalid", { method: "POST", body: "null" })),
    400,
    "INVALID_REQUEST",
  );
  await assertInputError(
    parseRequestInput(new Request("https://example.invalid", {
      method: "POST",
      body: new Uint8Array([0xc3, 0x28]),
    })),
    400,
    "INVALID_REQUEST",
  );
});

Deno.test("exactKeys rejects missing and unexpected request fields", () => {
  assert(exactKeys({ action: "read", media_id: "id" }, ["action", "media_id"]));
  assert(!exactKeys({ action: "read" }, ["action", "media_id"]));
  assert(!exactKeys({ action: "read", media_id: "id", object_path: "private" }, ["action", "media_id"]));
});
