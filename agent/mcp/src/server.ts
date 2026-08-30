import { mkdir } from "node:fs/promises";

import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { createMcpExpressApp } from "@modelcontextprotocol/sdk/server/express.js";
import type { Request, Response } from "express";

import {
  BASH,
  HOST,
  HOME,
  JOB_ROOT,
  MAX_CHUNK_BYTES,
  MAX_CONCURRENT_JOBS,
  MAX_JOB_TIMEOUT_SECONDS,
  DEFAULT_JOB_TIMEOUT_SECONDS,
  PORT,
  REQUESTED_ROOTS,
  SCRIPT,
  SECRET,
  SERVER_VERSION
} from "./config.js";
import { JobManager } from "./job-manager.js";
import { JobStore } from "./job-store.js";
import { createPathPolicy } from "./paths.js";
import { createToolServer } from "./tools.js";
import { stopBrowserBridgeForShutdown } from "./browser-tools.js";
import { assertDeviceBinding } from "./device-binding.js";

if (SECRET && !/^[A-Za-z0-9_-]{16,128}$/.test(SECRET)) {
  throw new Error(
    "TERMUX_MCP_SECRET должен содержать 16–128 символов A-Z, a-z, 0-9, _ или -"
  );
}

await mkdir(JOB_ROOT, { recursive: true, mode: 0o700 });

const pathPolicy = createPathPolicy(REQUESTED_ROOTS, HOME);
const store = new JobStore(JOB_ROOT);
const manager = new JobManager({
  store,
  pathPolicy,
  bash: BASH,
  scriptPath: SCRIPT,
  maxConcurrentJobs: MAX_CONCURRENT_JOBS,
  defaultTimeoutSeconds: DEFAULT_JOB_TIMEOUT_SECONDS,
  maxTimeoutSeconds: MAX_JOB_TIMEOUT_SECONDS,
  maxChunkBytes: MAX_CHUNK_BYTES
});

const app = createMcpExpressApp({ host: HOST });
const secretRoute = SECRET ? `/mcp/${SECRET}` : null;

// Shizuku is required only for Android shell/UI tools. Termux/files/Gradle
// operations remain available so the server can diagnose and repair the binding.
const SHIZUKU_TOOL_PREFIX = "android_";
const ANDROID_STATUS_TOOL = "android_status";

function requiresShizuku(toolName: string): boolean {
  return toolName.startsWith(SHIZUKU_TOOL_PREFIX) && toolName !== ANDROID_STATUS_TOOL;
}

function requestedToolName(body: unknown): string | null {
  if (!body || typeof body !== "object") return null;
  const value = body as { method?: unknown; params?: { name?: unknown } };
  if (value.method !== "tools/call") return null;
  return typeof value.params?.name === "string" ? value.params.name : null;
}

app.get("/healthz", async (_req, res) => {
  // Do not scan hundreds of durable job metadata files from healthz.
  // The in-memory count is sufficient for watchdog liveness and stays independent
  // of Shizuku and slow/damaged historical job records.
  const runningJobs = manager.activeJobsCount;

  res.status(200).json({
    status: "ok",
    server: "termux-mcp",
    version: SERVER_VERSION,
    pid: process.pid,
    runningJobs,
    // Health is intentionally independent of Shizuku. Use android_status for
    // the separate Android-shell transport diagnostic.
    deviceBinding: {
      status: "not-probed",
      reason: "healthz is independent of Shizuku; use android_status for transport diagnostics"
    },
    secretPathEnabled: Boolean(secretRoute),
    legacyPathEnabled: !secretRoute
  });
});

async function handleMcp(req: Request, res: Response): Promise<void> {
  const toolName = requestedToolName(req.body);
  if (toolName && requiresShizuku(toolName)) {
    try {
      assertDeviceBinding();
    } catch (error) {
      res.status(403).json({
        jsonrpc: "2.0",
        error: {
          code: -32001,
          message: "Jarvis device binding rejected this tool call",
          data: {
            tool: toolName,
            reason: error instanceof Error ? error.message : String(error)
          }
        },
        id: (req.body as { id?: unknown } | undefined)?.id ?? null
      });
      return;
    }
  }

  const server = createToolServer({
    manager,
    pathPolicy,
    jobRoot: JOB_ROOT,
    secretPathEnabled: Boolean(secretRoute)
  });
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: undefined
  });

  try {
    await server.connect(transport);
    res.on("close", () => {
      void transport.close();
      void server.close();
    });
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    console.error("MCP request failed:", error);
    if (!res.headersSent) {
      res.status(500).json({
        jsonrpc: "2.0",
        error: {
          code: -32603,
          message: "Internal MCP server error"
        },
        id: null
      });
    }
  }
}

function methodNotAllowed(_req: Request, res: Response): void {
  res.status(405).json({
    jsonrpc: "2.0",
    error: {
      code: -32000,
      message: "Method not allowed"
    },
    id: null
  });
}

if (!secretRoute) {
  app.post("/mcp", handleMcp);
  app.get("/mcp", methodNotAllowed);
  app.delete("/mcp", methodNotAllowed);
}

if (secretRoute) {
  app.post(secretRoute, handleMcp);
  app.get(secretRoute, methodNotAllowed);
  app.delete(secretRoute, methodNotAllowed);
}

const httpServer = app.listen(PORT, HOST, () => {
  console.log(`Termux MCP v${SERVER_VERSION}: http://${HOST}:${PORT}/mcp`);
  if (secretRoute) {
    console.log("Secret MCP route enabled");
  }
  console.log(`Health: http://${HOST}:${PORT}/healthz`);
  console.log(`Jobs:   ${JOB_ROOT}`);
  console.log(`Roots:  ${REQUESTED_ROOTS.join(", ")}`);
});

let stopping = false;
async function stop(signal: string): Promise<void> {
  if (stopping) return;
  stopping = true;
  console.log(`\nStopping Termux MCP after ${signal}; detached jobs continue...`);
  try {
    await stopBrowserBridgeForShutdown();
  } catch (error) {
    console.error("Browser bridge shutdown failed:", error);
  }
  httpServer.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5_000).unref();
}

process.on("SIGINT", () => void stop("SIGINT"));
process.on("SIGTERM", () => void stop("SIGTERM"));
