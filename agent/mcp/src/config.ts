import { homedir } from "node:os";
import { resolve } from "node:path";

export const HOME = homedir();
export const BASH = "/data/data/com.termux/files/usr/bin/bash";
export const SCRIPT = "/data/data/com.termux/files/usr/bin/script";

export const SERVER_VERSION = "2.4.0";
export const HOST = process.env.TERMUX_MCP_HOST ?? "127.0.0.1";
export const PORT = Number(process.env.TERMUX_MCP_PORT ?? "8940");
export const SECRET = (process.env.TERMUX_MCP_SECRET ?? "").trim();

// Movia is intentionally a fixed loopback dependency. It is bootstrapped
// headlessly through its local receiver and never through UI automation or
// Shizuku.
export const MOVIA_HOST = "127.0.0.1";
export const MOVIA_PORT = 8899;
export const MOVIA_DEFAULT_TIMEOUT_MS = 15_000;
export const MOVIA_MAX_TIMEOUT_MS = 60_000;
export const MOVIA_MAX_REQUEST_BYTES = 256 * 1024;
export const MOVIA_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
export const MOVIA_BOOTSTRAP_ATTEMPTS = 3;
export const MOVIA_BOOTSTRAP_COMMAND_TIMEOUT_MS = 5_000;
export const MOVIA_BOOTSTRAP_HEALTH_ATTEMPTS = 12;
export const MOVIA_BOOTSTRAP_HEALTH_TIMEOUT_MS = 750;
export const MOVIA_BOOTSTRAP_RETRY_DELAY_MS = 150;

export const JOB_ROOT = resolve(
  process.env.TERMUX_MCP_JOB_ROOT ?? `${HOME}/.termux-mcp/jobs`
);

export const REQUESTED_ROOTS = (
  process.env.TERMUX_MCP_ROOTS ?? `${HOME}:/storage/emulated/0`
)
  .split(":")
  .map(value => value.trim())
  .filter(Boolean);

export const MAX_COMMAND_CHARS = 1_048_576;
export const MAX_CHUNK_BYTES = 1_048_576;
export const MAX_SYNC_TIMEOUT_SECONDS = 600;
export const DEFAULT_JOB_TIMEOUT_SECONDS = 86_400;
export const MAX_JOB_TIMEOUT_SECONDS = 604_800;
export const MAX_CONCURRENT_JOBS = 8;
export const DEFAULT_RETENTION_HOURS = 72;
export const MAX_STREAM_BYTES = 536_870_912;
