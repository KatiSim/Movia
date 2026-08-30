import { execFile as execFileCb, spawn, type ChildProcess } from "node:child_process";
import { randomBytes } from "node:crypto";
import { closeSync, openSync } from "node:fs";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { promisify } from "node:util";

import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import * as z from "zod/v4";

import { HOME } from "./config.js";
import type { PathPolicy } from "./paths.js";

const execFile = promisify(execFileCb);
const BRIDGE_HOST = "127.0.0.1";
const BRIDGE_PORT = 8951;
const BRIDGE_URL = `http://${BRIDGE_HOST}:${BRIDGE_PORT}`;
const BRIDGE_DIR = join(HOME, ".local/share/termux-mcp-browser-bridge");
const STATE_DIR = join(HOME, ".local/state/termux-mcp-browser");
const LOG_FILE = join(STATE_DIR, "bridge.log");
const TOKEN_FILE = join(HOME, ".config/termux-mcp/browser-bridge-secret");
const SERVICE_FILE = join(BRIDGE_DIR, "service.mjs");

let bridgeProcess: ChildProcess | null = null;
let startingBridge: Promise<unknown> | null = null;

function textResult(data: unknown) {
  return {
    content: [
      {
        type: "text" as const,
        text: typeof data === "string" ? data : JSON.stringify(data, null, 2)
      }
    ]
  };
}

function errorResult(error: unknown) {
  return {
    isError: true,
    content: [
      {
        type: "text" as const,
        text: error instanceof Error ? error.message : String(error)
      }
    ]
  };
}

async function ensureToken(): Promise<string> {
  await mkdir(join(HOME, ".config/termux-mcp"), { recursive: true, mode: 0o700 });
  try {
    const existing = (await readFile(TOKEN_FILE, "utf8")).trim();
    if (existing.length >= 32) return existing;
  } catch {
    // Create below.
  }
  const value = randomBytes(32).toString("hex");
  await writeFile(TOKEN_FILE, value + "\n", { mode: 0o600 });
  return value;
}

async function readTokenIfPresent(): Promise<string | null> {
  try {
    const value = (await readFile(TOKEN_FILE, "utf8")).trim();
    return value.length >= 32 ? value : null;
  } catch {
    return null;
  }
}

async function requestBridge(path: string, options: {
  method?: "GET" | "POST";
  body?: unknown;
  requireRunning?: boolean;
  timeoutMs?: number;
} = {}): Promise<any> {
  if (options.requireRunning !== false) await ensureBridge();
  const token = options.requireRunning === false ? await readTokenIfPresent() : await ensureToken();
  if (!token) throw new Error("Browser bridge secret is not initialized");
  const response = await fetch(`${BRIDGE_URL}${path}`, {
    method: options.method ?? "GET",
    headers: {
      "x-termux-browser-token": token,
      ...(options.body === undefined ? {} : { "content-type": "application/json" })
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: AbortSignal.timeout(options.timeoutMs ?? 120_000)
  });
  const data = await response.json().catch(() => null) as any;
  if (!response.ok || !data?.ok) {
    const message = data?.error?.message || `Browser bridge HTTP ${response.status}`;
    throw new Error(String(message));
  }
  return data.result;
}

async function serviceHealth(): Promise<any | null> {
  const token = await readTokenIfPresent();
  if (!token) return null;
  try {
    const response = await fetch(`${BRIDGE_URL}/healthz`, {
      headers: { "x-termux-browser-token": token },
      signal: AbortSignal.timeout(2_000)
    });
    if (!response.ok) return null;
    const data = await response.json() as any;
    return data?.ok ? data.result : null;
  } catch {
    return null;
  }
}

async function preflight(): Promise<Record<string, string | boolean | null>> {
  const script = [
    "set -eu",
    `cd '${BRIDGE_DIR.replaceAll("'", "'\\''")}'`,
    ". /etc/os-release",
    "printf 'debian=%s\\n' \"$PRETTY_NAME\"",
    "printf 'debian_version=%s\\n' \"$VERSION_ID\"",
    "printf 'arch=%s\\n' \"$(uname -m)\"",
    "printf 'node_path=%s\\n' \"$(command -v node)\"",
    "printf 'node=%s\\n' \"$(node -v)\"",
    "printf 'npm=%s\\n' \"$(npm -v)\"",
    "printf 'playwright=%s\\n' \"$(node -p \"require('./node_modules/playwright/package.json').version\")\"",
    "node --input-type=module -e \"import {chromium} from 'playwright'; import {existsSync} from 'node:fs'; const system='/usr/bin/chromium'; const p=existsSync(system) ? system : chromium.executablePath(); console.log('chromium_executable='+p); console.log('chromium_executable_exists='+existsSync(p)); console.log('headed_display='+(process.env.DISPLAY||''))\""
  ].join("; ");
  try {
    const { stdout } = await execFile("proot-distro", ["login", "debian", "--", "/bin/bash", "-lc", script], {
      timeout: 30_000,
      maxBuffer: 256 * 1024
    });
    const parsed: Record<string, string | boolean | null> = {};
    for (const line of stdout.split(/\r?\n/)) {
      const index = line.indexOf("=");
      if (index > 0) parsed[line.slice(0, index)] = line.slice(index + 1);
    }
    if (parsed.chromium_executable_exists === "true") parsed.chromium_executable_exists = true;
    else if (parsed.chromium_executable_exists === "false") parsed.chromium_executable_exists = false;
    return parsed;
  } catch (error) {
    return {
      available: false,
      error: error instanceof Error ? error.message : String(error)
    };
  }
}

async function readLogTail(maxBytes = 8_192): Promise<string> {
  try {
    const info = await stat(LOG_FILE);
    const start = Math.max(0, info.size - maxBytes);
    const data = await readFile(LOG_FILE);
    return data.subarray(start).toString("utf8").slice(-maxBytes);
  } catch {
    return "";
  }
}

async function startBridgeProcess(): Promise<any> {
  const healthy = await serviceHealth();
  if (healthy) return healthy;
  if (startingBridge) return startingBridge;

  startingBridge = (async () => {
    await ensureToken();
    await mkdir(STATE_DIR, { recursive: true, mode: 0o700 });
    const serviceInfo = await stat(SERVICE_FILE).catch(() => null);
    if (!serviceInfo?.isFile()) throw new Error(`Browser bridge service is missing: ${SERVICE_FILE}`);

    const logFd = openSync(LOG_FILE, "a", 0o600);
    const display = process.env.TERMUX_BROWSER_DISPLAY || ":1";
    const command = `export DISPLAY='${display.replaceAll("'", "'\\''")}'; cd '${BRIDGE_DIR.replaceAll("'", "'\\''")}' && exec /usr/bin/node service.mjs`;
    const child = spawn("proot-distro", ["login", "debian", "--shared-tmp", "--", "/bin/bash", "-lc", command], {
      stdio: ["ignore", logFd, logFd],
      env: { ...process.env, TERMUX_BROWSER_BRIDGE_PORT: String(BRIDGE_PORT), TERMUX_BROWSER_DISPLAY: display }
    });
    closeSync(logFd);
    bridgeProcess = child;
    child.once("exit", () => {
      if (bridgeProcess === child) bridgeProcess = null;
    });

    for (let index = 0; index < 100; index += 1) {
      const status = await serviceHealth();
      if (status) return status;
      if (child.exitCode !== null) break;
      await new Promise(resolve => setTimeout(resolve, 200));
    }

    if (child.exitCode === null) child.kill("SIGTERM");
    const tail = await readLogTail();
    throw new Error(`Browser bridge did not become healthy.${tail ? ` Log tail:\n${tail}` : ""}`);
  })().finally(() => {
    startingBridge = null;
  });

  return startingBridge;
}

async function ensureBridge(): Promise<any> {
  return (await serviceHealth()) ?? startBridgeProcess();
}

async function rpc(action: string, args: Record<string, unknown> = {}): Promise<any> {
  return requestBridge("/rpc", {
    method: "POST",
    body: { action, args },
    timeoutMs: 130_000
  });
}

export async function stopBrowserBridgeForShutdown(): Promise<void> {
  const token = await readTokenIfPresent();
  if (token && await serviceHealth()) {
    try {
      await fetch(`${BRIDGE_URL}/shutdown`, {
        method: "POST",
        headers: { "x-termux-browser-token": token },
        signal: AbortSignal.timeout(5_000)
      });
    } catch {
      // Fall through to process signal.
    }
  }
  if (bridgeProcess?.exitCode === null) bridgeProcess.kill("SIGTERM");
  bridgeProcess = null;
}

const locatorSchema = z.object({
  role: z.string().min(1).optional(),
  name: z.string().optional(),
  label: z.string().min(1).optional(),
  placeholder: z.string().min(1).optional(),
  text: z.string().min(1).optional(),
  testId: z.string().min(1).optional(),
  css: z.string().min(1).optional(),
  xpath: z.string().min(1).optional(),
  exact: z.boolean().default(false),
  nth: z.number().int().min(0).max(10_000).optional()
}).refine(value => Boolean(value.role || value.label || value.placeholder || value.text || value.testId || value.css || value.xpath), {
  message: "locator requires role, label, placeholder, text, testId, css, or xpath"
});

const tabIdSchema = z.string().regex(/^tab-[0-9]+$/).optional();
const timeoutSchema = z.number().int().min(100).max(120_000).default(10_000);

export function registerBrowserTools(server: McpServer, pathPolicy: PathPolicy): void {
  server.registerTool(
    "browser_status",
    {
      title: "Browser status",
      description: "Read-only status of the local Debian/Playwright/Chromium browser bridge.",
      inputSchema: {},
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
    },
    async () => {
      try {
        const [service, environment] = await Promise.all([serviceHealth(), preflight()]);
        return textResult({
          serviceRunning: Boolean(service),
          service,
          environment,
          bridgeHost: BRIDGE_HOST,
          bridgePort: BRIDGE_PORT,
          publicBridge: false,
          logFile: LOG_FILE
        });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_start",
    {
      title: "Start browser",
      description: "Start the localhost-only Debian browser bridge and persistent headed Playwright Chromium context on Termux:X11 display :1.",
      inputSchema: {},
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false }
    },
    async () => {
      try {
        await startBridgeProcess();
        return textResult(await rpc("start"));
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_stop",
    {
      title: "Stop browser",
      description: "Close the persistent Chromium context and stop the local browser bridge.",
      inputSchema: {},
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false }
    },
    async () => {
      try {
        if (await serviceHealth()) await rpc("stopBrowser");
        await stopBrowserBridgeForShutdown();
        return textResult({ stopped: true });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_navigate",
    {
      title: "Navigate browser",
      description: "Navigate the active or specified tab to an HTTP(S) URL.",
      inputSchema: {
        url: z.string().url(),
        tabId: tabIdSchema,
        waitUntil: z.enum(["commit", "domcontentloaded", "load", "networkidle"]).default("domcontentloaded"),
        timeoutMs: z.number().int().min(100).max(120_000).default(30_000)
      },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("navigate", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_snapshot",
    {
      title: "Browser accessibility/DOM snapshot",
      description: "Return URL, title, relevant visible text, interactive elements with roles/names, and recent page errors. Input values and cookies are not exposed.",
      inputSchema: {
        tabId: tabIdSchema,
        maxTextChars: z.number().int().min(1_000).max(50_000).default(20_000),
        maxElements: z.number().int().min(1).max(500).default(300)
      },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("snapshot", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_click",
    {
      title: "Click browser element",
      description: "Click a unique element. Prefer role/name locators. Potential irreversible final actions are blocked unless confirmFinalAction is FINAL_ACTION after explicit user authorization.",
      inputSchema: {
        locator: locatorSchema,
        tabId: tabIdSchema,
        timeoutMs: timeoutSchema,
        confirmFinalAction: z.literal("FINAL_ACTION").optional()
      },
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true }
    },
    async ({ confirmFinalAction, ...input }) => {
      try {
        return textResult(await rpc("click", { ...input, allowFinalAction: confirmFinalAction === "FINAL_ACTION" }));
      } catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_fill",
    {
      title: "Fill browser field",
      description: "Replace the value of a unique editable element. Sensitive identity, authentication, birth-date, and banking fields are blocked for manual user entry.",
      inputSchema: { locator: locatorSchema, value: z.string().max(200_000), tabId: tabIdSchema, timeoutMs: timeoutSchema },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("fill", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_type",
    {
      title: "Type into browser field",
      description: "Type characters sequentially into a unique editable element. Sensitive identity, authentication, birth-date, and banking fields are blocked for manual user entry.",
      inputSchema: {
        locator: locatorSchema,
        value: z.string().max(200_000),
        delayMs: z.number().int().min(0).max(1_000).default(0),
        tabId: tabIdSchema
      },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("type", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_select",
    {
      title: "Select browser option",
      description: "Select one or more option values in a unique select element.",
      inputSchema: { locator: locatorSchema, values: z.array(z.string()).min(1).max(100), tabId: tabIdSchema },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("select", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_check",
    {
      title: "Check browser checkbox/radio",
      description: "Check a unique checkbox or radio element.",
      inputSchema: { locator: locatorSchema, tabId: tabIdSchema, timeoutMs: timeoutSchema },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("check", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_uncheck",
    {
      title: "Uncheck browser checkbox",
      description: "Uncheck a unique checkbox element.",
      inputSchema: { locator: locatorSchema, tabId: tabIdSchema, timeoutMs: timeoutSchema },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("uncheck", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_press",
    {
      title: "Press browser key",
      description: "Press a keyboard key on a unique element or the active page.",
      inputSchema: { key: z.string().min(1).max(100), locator: locatorSchema.optional(), tabId: tabIdSchema, timeoutMs: timeoutSchema },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("press", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_wait",
    {
      title: "Wait in browser",
      description: "Wait for a locator or text to reach a state, or wait for a bounded timeout.",
      inputSchema: {
        locator: locatorSchema.optional(),
        text: z.string().min(1).optional(),
        exact: z.boolean().default(false),
        state: z.enum(["attached", "detached", "visible", "hidden"]).default("visible"),
        timeoutMs: z.number().int().min(0).max(120_000).default(10_000),
        tabId: tabIdSchema
      },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("wait", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_get_text",
    {
      title: "Get browser text",
      description: "Read inner text from a unique element.",
      inputSchema: { locator: locatorSchema, tabId: tabIdSchema, timeoutMs: timeoutSchema },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("getText", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_get_attribute",
    {
      title: "Get browser attribute",
      description: "Read an element attribute. Password values and explicitly sensitive attributes are blocked.",
      inputSchema: { locator: locatorSchema, name: z.string().min(1).max(200), tabId: tabIdSchema, timeoutMs: timeoutSchema },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("getAttribute", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_screenshot",
    {
      title: "Take browser screenshot",
      description: "Save a PNG screenshot to the private Termux browser data directory and return its path.",
      inputSchema: { fullPage: z.boolean().default(false), tabId: tabIdSchema },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: false, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("screenshot", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_upload",
    {
      title: "Upload browser file",
      description: "Set files on a file input. The file must exist within the Termux MCP allowed roots.",
      inputSchema: { locator: locatorSchema, filePath: z.string().min(1), tabId: tabIdSchema, timeoutMs: timeoutSchema },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async ({ filePath, ...input }) => {
      try {
        const allowed = await pathPolicy.allowedPath(filePath, true);
        const info = await stat(allowed);
        if (!info.isFile()) throw new Error(`Upload path is not a file: ${allowed}`);
        return textResult(await rpc("upload", { ...input, filePath: allowed }));
      } catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_tabs",
    {
      title: "List browser tabs",
      description: "List current tabs with stable tab IDs, titles, URLs, and active state.",
      inputSchema: {},
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async () => {
      try { return textResult(await rpc("tabs")); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_new_tab",
    {
      title: "Open browser tab",
      description: "Open a new tab, optionally navigating to an HTTP(S) URL.",
      inputSchema: { url: z.string().url().optional(), timeoutMs: z.number().int().min(100).max(120_000).default(30_000) },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("newTab", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_switch_tab",
    {
      title: "Switch browser tab",
      description: "Make a tab the active tab by stable tab ID.",
      inputSchema: { tabId: z.string().regex(/^tab-[0-9]+$/) },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("switchTab", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  server.registerTool(
    "browser_close_tab",
    {
      title: "Close browser tab",
      description: "Close the specified or active tab.",
      inputSchema: { tabId: tabIdSchema },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true }
    },
    async input => {
      try { return textResult(await rpc("closeTab", input)); }
      catch (error) { return errorResult(error); }
    }
  );

  for (const [name, title, action] of [
    ["browser_back", "Browser back", "back"],
    ["browser_forward", "Browser forward", "forward"],
    ["browser_reload", "Reload browser page", "reload"]
  ] as const) {
    server.registerTool(
      name,
      {
        title,
        description: `${title} in the active or specified tab.`,
        inputSchema: { tabId: tabIdSchema, timeoutMs: z.number().int().min(100).max(120_000).default(30_000) },
        annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true }
      },
      async input => {
        try { return textResult(await rpc(action, input)); }
        catch (error) { return errorResult(error); }
      }
    );
  }

  server.registerTool(
    "browser_downloads",
    {
      title: "List browser downloads",
      description: "List browser downloads saved in the private Termux browser download directory.",
      inputSchema: {},
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true }
    },
    async () => {
      try { return textResult(await rpc("downloads")); }
      catch (error) { return errorResult(error); }
    }
  );
}
