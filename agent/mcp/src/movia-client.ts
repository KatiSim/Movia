import { execFile as execFileCallback } from "node:child_process";
import { randomBytes } from "node:crypto";
import { chmod, mkdir, readFile, writeFile } from "node:fs/promises";
import http from "node:http";
import { resolve } from "node:path";
import { promisify } from "node:util";

import {
  HOME,
  MOVIA_BOOTSTRAP_ATTEMPTS,
  MOVIA_BOOTSTRAP_COMMAND_TIMEOUT_MS,
  MOVIA_BOOTSTRAP_HEALTH_ATTEMPTS,
  MOVIA_BOOTSTRAP_HEALTH_TIMEOUT_MS,
  MOVIA_BOOTSTRAP_RETRY_DELAY_MS,
  MOVIA_DEFAULT_TIMEOUT_MS,
  MOVIA_HOST,
  MOVIA_MAX_REQUEST_BYTES,
  MOVIA_MAX_RESPONSE_BYTES,
  MOVIA_MAX_TIMEOUT_MS,
  MOVIA_PORT
} from "./config.js";

const execFile = promisify(execFileCallback);
const BASE_PATH = "/agent/v1";
const TOKEN_DIR = resolve(HOME, ".config/movia-agent");
const TOKEN_FILE = resolve(TOKEN_DIR, "token");
const TERMUX_AM = "/system/bin/am";
const BOOTSTRAP_COMPONENT = "app.movia.android/.agent.AgentBootstrapReceiver";
const BOOTSTRAP_ACTION = "app.movia.android.agent.BOOTSTRAP";
const TOKEN_PATTERN = /^[0-9a-f]{64}$/;
const SENSITIVE_KEY_PATTERN = /token|authorization|cookie|password|secret|api[-_]?key/i;
const TOKEN_TEXT_PATTERN = /\b[0-9a-f]{64}\b/gi;
const BEARER_TEXT_PATTERN = /(\bBearer\s+)[^\s,]+/gi;

let tokenPromise: Promise<string> | undefined;
let bootstrapPromise: Promise<void> | undefined;

export class MoviaBridgeError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly retryable = false
  ) {
    super(redactText(message));
    this.name = "MoviaBridgeError";
  }
}

export class MoviaHttpError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly payload: unknown,
    message: string
  ) {
    super(redactText(message));
    this.name = "MoviaHttpError";
  }
}

function redactText(value: string, secret?: string): string {
  let result = value;
  if (secret) result = result.split(secret).join("[REDACTED]");
  return result
    .replace(BEARER_TEXT_PATTERN, "$1[REDACTED]")
    .replace(TOKEN_TEXT_PATTERN, "[REDACTED]");
}

export function sanitizeMoviaValue(value: unknown, secret?: string, depth = 0): unknown {
  if (depth > 12) return "[TRUNCATED]";
  if (typeof value === "string") return redactText(value, secret);
  if (value === null || typeof value !== "object") return value;
  if (Array.isArray(value)) {
    return value.map(item => sanitizeMoviaValue(item, secret, depth + 1));
  }

  const result: Record<string, unknown> = {};
  for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
    result[key] = SENSITIVE_KEY_PATTERN.test(key)
      ? "[REDACTED]"
      : sanitizeMoviaValue(item, secret, depth + 1);
  }
  return result;
}

function safeErrorCode(error: unknown): string | undefined {
  const code = (error as { code?: unknown } | undefined)?.code;
  return typeof code === "string" ? code : undefined;
}

function transportError(error: unknown): MoviaBridgeError {
  const code = safeErrorCode(error);
  if (code === "ETIMEDOUT" || code === "ESOCKETTIMEDOUT") {
    return new MoviaBridgeError("MOVIA_TIMEOUT", "Movia bridge request timed out", true);
  }
  if (code === "ECONNREFUSED") {
    return new MoviaBridgeError("MOVIA_UNAVAILABLE", "Movia bridge is unavailable", true);
  }
  if (code === "ECONNRESET" || code === "EPIPE" || code === "EHOSTUNREACH" || code === "EAI_AGAIN") {
    return new MoviaBridgeError("MOVIA_CONNECTION_FAILED", "Movia bridge connection failed", true);
  }
  return new MoviaBridgeError("MOVIA_NETWORK_ERROR", "Movia bridge request failed", true);
}

async function readExistingToken(): Promise<string | undefined> {
  try {
    const existing = (await readFile(TOKEN_FILE, "utf8")).trim().toLowerCase();
    if (!TOKEN_PATTERN.test(existing)) return undefined;
    await chmod(TOKEN_FILE, 0o600).catch(() => undefined);
    return existing;
  } catch {
    return undefined;
  }
}

async function loadOrCreateToken(): Promise<string> {
  try {
    await mkdir(TOKEN_DIR, { recursive: true, mode: 0o700 });
    await chmod(TOKEN_DIR, 0o700).catch(() => undefined);

    const existing = await readExistingToken();
    if (existing) return existing;

    const token = randomBytes(32).toString("hex");
    try {
      await writeFile(TOKEN_FILE, `${token}\n`, {
        encoding: "utf8",
        flag: "wx",
        mode: 0o600
      });
    } catch (error) {
      if (safeErrorCode(error) !== "EEXIST") throw error;
      const raced = await readExistingToken();
      if (raced) return raced;
      await writeFile(TOKEN_FILE, `${token}\n`, {
        encoding: "utf8",
        flag: "w",
        mode: 0o600
      });
    }
    await chmod(TOKEN_FILE, 0o600).catch(() => undefined);
    return token;
  } catch {
    throw new MoviaBridgeError("MOVIA_TOKEN_UNAVAILABLE", "Movia bridge token is unavailable");
  }
}

async function ensureToken(): Promise<string> {
  if (!tokenPromise) {
    tokenPromise = loadOrCreateToken().catch(error => {
      tokenPromise = undefined;
      throw error;
    });
  }
  return tokenPromise;
}

function clampTimeout(timeoutMs: number | undefined): number {
  const requested = Number.isFinite(timeoutMs) ? timeoutMs ?? MOVIA_DEFAULT_TIMEOUT_MS : MOVIA_DEFAULT_TIMEOUT_MS;
  return Math.max(100, Math.min(Math.floor(requested), MOVIA_MAX_TIMEOUT_MS));
}

function boundedJsonBody(bodyValue: unknown): string | null {
  if (bodyValue === undefined) return null;
  let body: string;
  try {
    body = JSON.stringify(bodyValue);
  } catch {
    throw new MoviaBridgeError("MOVIA_INVALID_REQUEST", "Movia request body is not JSON serializable");
  }
  if (Buffer.byteLength(body, "utf8") > MOVIA_MAX_REQUEST_BYTES) {
    throw new MoviaBridgeError("MOVIA_REQUEST_TOO_LARGE", "Movia request body exceeded its size limit");
  }
  return body;
}

function httpJson(
  token: string,
  path: string,
  options: { method?: "GET" | "POST"; body?: unknown; timeoutMs?: number } = {}
): Promise<unknown> {
  const method = options.method ?? "GET";
  const body = boundedJsonBody(options.body);
  const timeoutMs = clampTimeout(options.timeoutMs);

  return new Promise((resolvePromise, rejectPromise) => {
    let settled = false;
    let timer: NodeJS.Timeout | undefined;

    const settleResolve = (value: unknown) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      resolvePromise(value);
    };
    const settleReject = (error: unknown) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      rejectPromise(error);
    };

    const request = http.request(
      {
        host: MOVIA_HOST,
        port: MOVIA_PORT,
        path: `${BASE_PATH}${path}`,
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: "application/json",
          ...(body === null
            ? {}
            : {
                "Content-Type": "application/json; charset=utf-8",
                "Content-Length": Buffer.byteLength(body, "utf8")
              })
        }
      },
      response => {
        const contentLength = response.headers["content-length"];
        if (typeof contentLength === "string" && Number(contentLength) > MOVIA_MAX_RESPONSE_BYTES) {
          response.destroy();
          request.destroy();
          settleReject(new MoviaBridgeError("MOVIA_RESPONSE_TOO_LARGE", "Movia bridge response exceeded its size limit"));
          return;
        }

        const chunks: Buffer[] = [];
        let total = 0;
        let tooLarge = false;
        response.on("data", (chunk: Buffer | string) => {
          if (tooLarge || settled) return;
          const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
          total += buffer.length;
          if (total > MOVIA_MAX_RESPONSE_BYTES) {
            tooLarge = true;
            response.destroy();
            request.destroy();
            settleReject(new MoviaBridgeError("MOVIA_RESPONSE_TOO_LARGE", "Movia bridge response exceeded its size limit"));
            return;
          }
          chunks.push(buffer);
        });
        response.on("aborted", () => {
          settleReject(new MoviaBridgeError("MOVIA_CONNECTION_FAILED", "Movia bridge response was interrupted", true));
        });
        response.on("error", error => settleReject(transportError(error)));
        response.on("end", () => {
          if (tooLarge || settled) return;
          const raw = Buffer.concat(chunks).toString("utf8");
          let payload: unknown = {};
          if (raw) {
            try {
              payload = JSON.parse(raw);
            } catch {
              payload = redactText(raw);
            }
          }

          const status = response.statusCode ?? 0;
          if (status < 200 || status >= 300) {
            settleReject(
              new MoviaHttpError(
                status,
                sanitizeMoviaValue(payload, token),
                `Movia bridge returned HTTP ${status}`
              )
            );
            return;
          }
          settleResolve(sanitizeMoviaValue(payload, token));
        });
      }
    );

    request.on("error", error => settleReject(transportError(error)));
    request.setTimeout(timeoutMs, () => {
      request.destroy();
      settleReject(new MoviaBridgeError("MOVIA_TIMEOUT", "Movia bridge request timed out", true));
    });
    timer = setTimeout(() => {
      request.destroy();
      settleReject(new MoviaBridgeError("MOVIA_TIMEOUT", "Movia bridge request timed out", true));
    }, timeoutMs);

    if (body !== null) request.write(body);
    request.end();
  });
}

function delay(milliseconds: number): Promise<void> {
  return new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds));
}

async function runBootstrap(token: string): Promise<void> {
  let lastFailure: MoviaBridgeError | undefined;

  for (let attempt = 0; attempt < MOVIA_BOOTSTRAP_ATTEMPTS; attempt += 1) {
    try {
      await execFile(
        TERMUX_AM,
        [
          "broadcast",
          "--user",
          "0",
          "-f",
          "0x20",
          "-n",
          BOOTSTRAP_COMPONENT,
          "-a",
          BOOTSTRAP_ACTION
        ],
        {
          timeout: MOVIA_BOOTSTRAP_COMMAND_TIMEOUT_MS,
          maxBuffer: 64 * 1024,
          env: process.env
        }
      );
    } catch {
      lastFailure = new MoviaBridgeError("MOVIA_BOOTSTRAP_FAILED", "Movia headless bootstrap command failed", true);
    }

    for (let healthAttempt = 0; healthAttempt < MOVIA_BOOTSTRAP_HEALTH_ATTEMPTS; healthAttempt += 1) {
      try {
        await httpJson(token, "/health", { timeoutMs: MOVIA_BOOTSTRAP_HEALTH_TIMEOUT_MS });
        return;
      } catch {
        lastFailure = new MoviaBridgeError("MOVIA_BOOTSTRAP_UNAVAILABLE", "Movia headless bridge is not ready", true);
        await delay(MOVIA_BOOTSTRAP_RETRY_DELAY_MS);
      }
    }
  }

  throw lastFailure ?? new MoviaBridgeError("MOVIA_BOOTSTRAP_UNAVAILABLE", "Movia headless bridge is not ready", true);
}

function bootstrapWithRetry(token: string): Promise<void> {
  if (!bootstrapPromise) {
    bootstrapPromise = runBootstrap(token).finally(() => {
      bootstrapPromise = undefined;
    });
  }
  return bootstrapPromise;
}

function shouldBootstrap(error: unknown): boolean {
  if (error instanceof MoviaHttpError) {
    return error.statusCode === 401 || error.statusCode === 502 || error.statusCode === 503 || error.statusCode === 504;
  }
  const code = safeErrorCode(error);
  return (
    code === "ECONNREFUSED" ||
    code === "ECONNRESET" ||
    code === "ETIMEDOUT" ||
    code === "EHOSTUNREACH" ||
    code === "EAI_AGAIN" ||
    code === "MOVIA_TIMEOUT" ||
    code === "MOVIA_UNAVAILABLE" ||
    code === "MOVIA_CONNECTION_FAILED"
  );
}

export async function moviaRequest(
  path: string,
  options: { method?: "GET" | "POST"; body?: unknown; timeoutMs?: number } = {}
): Promise<unknown> {
  const token = await ensureToken();
  let lastError: unknown;

  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      return await httpJson(token, path, options);
    } catch (error) {
      lastError = error;
      if (!shouldBootstrap(error) || attempt === 1) throw error;
      await bootstrapWithRetry(token);
    }
  }

  throw lastError ?? new MoviaBridgeError("MOVIA_REQUEST_FAILED", "Movia bridge request failed");
}

export async function moviaAction(
  action: string,
  args: Record<string, unknown> = {},
  requestId?: string
): Promise<unknown> {
  if (!action || action.length > 200) {
    throw new MoviaBridgeError("MOVIA_INVALID_ACTION", "Movia action ID is invalid");
  }
  return moviaRequest("/action", {
    method: "POST",
    timeoutMs: 30_000,
    body: {
      action,
      arguments: args,
      ...(requestId ? { requestId } : {})
    }
  });
}
