import { execFile as execFileCb, spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { access, mkdir, readFile, stat, unlink, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { promisify } from "node:util";

import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import * as z from "zod/v4";

import { HOME } from "./config.js";

const execFile = promisify(execFileCb);
const TRANSPORT = "shizuku-rish";
const RISH = join(HOME, "shizuku_tmp/rish");
const RISH_DEX = join(HOME, "shizuku_tmp/rish_shizuku.dex");
const RISH_APPLICATION_ID = "com.termux";
const ANDROID_DATA_DIR = join(HOME, ".local/share/termux-mcp-android");
const ANDROID_SCREENSHOT_DIR = join(ANDROID_DATA_DIR, "screenshots");
const MAX_TEXT_OUTPUT = 4 * 1024 * 1024;
const MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024;
const MAX_STDERR_BYTES = 256 * 1024;
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const PNG_IEND = Buffer.from([
  0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82
]);
const PORTRAIT_LOCK_COMMAND = [
  "settings put system accelerometer_rotation 0",
  "settings put system user_rotation 0",
  "cmd window fixed-to-user-rotation enabled",
  "cmd window user-rotation lock 0"
].join("\n");

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

function assertLocalSerial(serial?: string): void {
  if (!serial) return;
  if (!/^[A-Za-z0-9._:-]{1,200}$/.test(serial)) throw new Error("Invalid Android serial");
  throw new Error("serial is unsupported in local Shizuku mode; omit serial to control this phone");
}

function rishEnvironment(): NodeJS.ProcessEnv {
  return {
    ...process.env,
    RISH_APPLICATION_ID,
    RISH_DISH_PATH: RISH_DEX
  };
}

async function isAccessible(path: string, mode: number): Promise<boolean> {
  try {
    await access(path, mode);
    return true;
  } catch {
    return false;
  }
}

async function ensureRishFiles(): Promise<void> {
  if (!(await isAccessible(RISH, constants.X_OK))) {
    throw new Error(`rish executable is missing or not executable: ${RISH}`);
  }
  if (!(await isAccessible(RISH_DEX, constants.R_OK))) {
    throw new Error(`rish Shizuku dex is missing or unreadable: ${RISH_DEX}`);
  }
}

function rishFailureMessage(error: any): string {
  const stderr = typeof error?.stderr === "string" ? error.stderr.trim() : "";
  const stdout = typeof error?.stdout === "string" ? error.stdout.trim() : "";
  const raw = stderr || stdout || error?.message || String(error);
  const detail = String(raw).slice(0, 4000);
  const lower = detail.toLowerCase();

  if (error?.killed || error?.code === "ETIMEDOUT") {
    return "rish/Shizuku command timed out; verify Shizuku is running, Termux remains authorized, and battery restrictions are not suspending either app";
  }
  if (
    lower.includes("permission denied") ||
    lower.includes("not granted") ||
    lower.includes("authorization") ||
    lower.includes("unauthorized")
  ) {
    return `Shizuku authorization is unavailable: ${detail}`;
  }
  if (
    lower.includes("shizuku") ||
    lower.includes("binder") ||
    lower.includes("service not found") ||
    lower.includes("connection refused")
  ) {
    return `Shizuku is not reachable: ${detail}`;
  }
  return `Android command failed through rish/Shizuku: ${detail}`;
}

async function runRishText(
  command: string,
  timeoutMs = 15_000,
  maxBuffer = MAX_TEXT_OUTPUT
): Promise<{ stdout: string; stderr: string }> {
  await ensureRishFiles();
  try {
    const result = await execFile(RISH, ["-c", command], {
      timeout: timeoutMs,
      maxBuffer,
      encoding: "utf8",
      env: rishEnvironment()
    });
    return {
      stdout: `${result.stdout}${result.stderr}`,
      stderr: ""
    };
  } catch (error: any) {
    throw new Error(rishFailureMessage(error));
  }
}

async function enforcePortraitMode(): Promise<void> {
  await runRishText(PORTRAIT_LOCK_COMMAND, 10_000, 256 * 1024);
}

async function runRishBinary(
  command: string,
  timeoutMs = 20_000,
  maxBytes = MAX_SCREENSHOT_BYTES
): Promise<Buffer> {
  await ensureRishFiles();
  return new Promise((resolve, reject) => {
    const child = spawn(RISH, ["-c", command], {
      stdio: ["ignore", "pipe", "pipe"],
      env: rishEnvironment()
    });
    const stdout: Buffer[] = [];
    const stderr: Buffer[] = [];
    let stdoutBytes = 0;
    let stderrBytes = 0;
    let settled = false;

    const finish = (fn: () => void): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      fn();
    };

    const timer = setTimeout(() => {
      child.kill("SIGTERM");
      finish(() => reject(new Error("rish/Shizuku binary command timed out; verify Shizuku is running and Termux remains authorized")));
    }, timeoutMs);
    timer.unref();

    child.stdout?.on("data", (chunk: Buffer) => {
      stdoutBytes += chunk.length;
      if (stdoutBytes > maxBytes) {
        child.kill("SIGTERM");
        finish(() => reject(new Error(`rish/Shizuku binary output exceeded ${maxBytes} bytes`)));
        return;
      }
      stdout.push(Buffer.from(chunk));
    });

    child.stderr?.on("data", (chunk: Buffer) => {
      if (stderrBytes >= MAX_STDERR_BYTES) return;
      const remaining = MAX_STDERR_BYTES - stderrBytes;
      const kept = Buffer.from(chunk).subarray(0, remaining);
      stderrBytes += kept.length;
      stderr.push(kept);
    });

    child.once("error", error => {
      finish(() => reject(new Error(rishFailureMessage(error))));
    });

    child.once("close", code => {
      finish(() => {
        if (code !== 0) {
          const detail = Buffer.concat(stderr).toString("utf8").trim().slice(0, 4000);
          reject(new Error(rishFailureMessage({ message: `exit ${code}`, stderr: detail })));
          return;
        }
        const stdoutData = Buffer.concat(stdout);
        const stderrData = Buffer.concat(stderr);
        const candidates = [
          stdoutData,
          stderrData,
          Buffer.concat([stdoutData, stderrData])
        ];
        const pngCandidate = candidates.find(candidate => {
          const start = candidate.indexOf(PNG_SIGNATURE);
          return start >= 0;
        });
        if (pngCandidate) {
          const start = pngCandidate.indexOf(PNG_SIGNATURE);
          resolve(pngCandidate.subarray(start));
        } else {
          resolve(stdoutData);
        }
      });
    });
  });
}

function shellQuote(value: string): string {
  return `'${value.replaceAll("'", `'\\''`)}'`;
}

function parseCurrentComponent(output: string): string | null {
  const patterns = [
    /mResumedActivity:\s+ActivityRecord\{[^}]*\s([A-Za-z0-9._$]+\/[A-Za-z0-9._$]+)\s+t\d+/,
    /topResumedActivity=.*?ActivityRecord\{[^}]*\s([A-Za-z0-9._$]+\/[A-Za-z0-9._$]+)\s+t\d+/,
    /ResumedActivity[^\n]*?\s([A-Za-z0-9._$]+\/[A-Za-z0-9._$]+)(?:\s|\})/
  ];
  for (const pattern of patterns) {
    const match = output.match(pattern);
    if (match?.[1]) return match[1];
  }
  return null;
}

const serialSchema = z.string().regex(/^[A-Za-z0-9._:-]{1,200}$/).optional();

export function registerAndroidTools(server: McpServer): void {
  server.registerTool(
    "android_status",
    {
      title: "Android Shizuku/rish status",
      description: "Read the local Shizuku/rish transport status for Android shell control. ADB pairing is not required.",
      inputSchema: {},
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
    },
    async () => {
      const rishInstalled = await isAccessible(RISH, constants.X_OK);
      const dexInstalled = await isAccessible(RISH_DEX, constants.R_OK);
      let shizukuShellReachable = false;
      let identity: string | null = null;
      let uid: string | null = null;
      let user: string | null = null;
      let deviceModel: string | null = null;
      let wmSize: string | null = null;
      let diagnostic: string | null = null;

      if (rishInstalled && dexInstalled) {
        try {
          const [idResult, userResult, modelResult, wmResult] = await Promise.all([
            runRishText("id", 10_000, 256 * 1024),
            runRishText("whoami", 10_000, 256 * 1024),
            runRishText("getprop ro.product.model", 10_000, 256 * 1024),
            runRishText("wm size", 10_000, 256 * 1024)
          ]);
          identity = idResult.stdout.trim() || null;
          uid = identity?.match(/uid=\d+\([^)]+\)/)?.[0] || null;
          user = userResult.stdout.trim() || null;
          deviceModel = modelResult.stdout.trim() || null;
          wmSize = wmResult.stdout.split(/\r?\n/).map(line => line.trim()).find(Boolean) || null;
          shizukuShellReachable = uid === "uid=2000(shell)" && user === "shell";
          if (!shizukuShellReachable) diagnostic = "rish responded but did not return the expected Android shell identity";
        } catch (error) {
          diagnostic = error instanceof Error ? error.message : String(error);
        }
      } else if (!rishInstalled) {
        diagnostic = `rish executable is missing or not executable: ${RISH}`;
      } else {
        diagnostic = `rish Shizuku dex is missing or unreadable: ${RISH_DEX}`;
      }

      return textResult({
        transport: TRANSPORT,
        rishInstalled,
        dexInstalled,
        shizukuShellReachable,
        uid,
        identity,
        user,
        deviceModel,
        wmSize,
        adbPairingRequired: false,
        diagnostic
      });
    }
  );

  server.registerTool(
    "android_screenshot",
    {
      title: "Android screenshot",
      description: "Capture the local Android display through Shizuku/rish and save a private PNG in Termux.",
      inputSchema: { serial: serialSchema },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: false, openWorldHint: false }
    },
    async ({ serial }) => {
      const remotePath = `/storage/emulated/0/.termux_mcp_capture_${randomUUID()}.png`;
      try {
        assertLocalSerial(serial);
        await mkdir(ANDROID_SCREENSHOT_DIR, { recursive: true, mode: 0o700 });

        // Avoid piping PNG through rish stdout/stderr: some Android/rish builds
        // split binary output between both descriptors and truncate one stream.
        await runRishText(`screencap -p > ${shellQuote(remotePath)}`, 20_000, 256 * 1024);
        const raw = await readFile(remotePath);
        const start = raw.indexOf(PNG_SIGNATURE);
        const end = start >= 0 ? raw.indexOf(PNG_IEND, start) : -1;
        if (start < 0 || end < 0) {
          throw new Error("rish/Shizuku screencap did not return a complete PNG");
        }
        const data = raw.subarray(start, end + PNG_IEND.length);
        const path = join(
          ANDROID_SCREENSHOT_DIR,
          `${new Date().toISOString().replace(/[:.]/g, "-")}-${randomUUID().slice(0, 8)}.png`
        );
        await writeFile(path, data, { mode: 0o600 });
        const info = await stat(path);
        return textResult({ transport: TRANSPORT, serial: null, path, bytes: info.size });
      } catch (error) {
        return errorResult(error);
      } finally {
        await unlink(remotePath).catch(() => {});
      }
    }
  );

  server.registerTool(
    "android_dump_ui",
    {
      title: "Android UI hierarchy",
      description: "Dump the current native Android UIAutomator hierarchy through local Shizuku/rish. Only this operation's unique temporary XML is removed.",
      inputSchema: { serial: serialSchema, compressed: z.boolean().default(true) },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: false, openWorldHint: false }
    },
    async ({ serial, compressed }) => {
      const remote = `/data/local/tmp/termux_mcp_ui_${randomUUID().replaceAll("-", "")}.xml`;
      try {
        assertLocalSerial(serial);
        const dumpCommand = `uiautomator dump${compressed ? " --compressed" : ""} ${remote}`;
        await runRishText(dumpCommand, 20_000, 256 * 1024);
        const { stdout } = await runRishText(`cat ${remote}`, 20_000, MAX_TEXT_OUTPUT);
        const xmlStart = stdout.indexOf("<?xml");
        const hierarchyStart = stdout.indexOf("<hierarchy");
        const start = xmlStart >= 0 ? xmlStart : hierarchyStart;
        const closing = "</hierarchy>";
        const endStart = stdout.lastIndexOf(closing);
        if (start < 0 || endStart < start) {
          throw new Error("UIAutomator returned malformed or empty hierarchy XML");
        }
        const xml = stdout.slice(start, endStart + closing.length);
        const bytes = Buffer.byteLength(xml);
        if (!/<hierarchy\b/.test(xml)) {
          throw new Error("UIAutomator returned malformed or empty hierarchy XML");
        }
        return textResult({ transport: TRANSPORT, serial: null, xml, bytes });
      } catch (error) {
        return errorResult(error);
      } finally {
        await runRishText(`rm -f ${remote}`, 5_000, 256 * 1024).catch(() => {});
      }
    }
  );

  server.registerTool(
    "android_current_activity",
    {
      title: "Android current activity",
      description: "Read the currently resumed local Android activity through Shizuku/rish.",
      inputSchema: { serial: serialSchema },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
    },
    async ({ serial }) => {
      try {
        assertLocalSerial(serial);
        const { stdout } = await runRishText("dumpsys activity activities", 15_000, MAX_TEXT_OUTPUT);
        let component = parseCurrentComponent(stdout);
        if (!component) {
          const window = await runRishText("dumpsys window windows", 15_000, MAX_TEXT_OUTPUT);
          component =
            window.stdout.match(/mCurrentFocus=Window\{[^}]*\s([A-Za-z0-9._$]+\/[A-Za-z0-9._$]+)\}/)?.[1] ||
            window.stdout.match(/mFocusedApp=.*?ActivityRecord\{[^}]*\s([A-Za-z0-9._$]+\/[A-Za-z0-9._$]+)\s/)?.[1] ||
            null;
        }
        return textResult({
          transport: TRANSPORT,
          serial: null,
          activity: component,
          component,
          packageName: component?.split("/", 1)[0] || null
        });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "android_tap",
    {
      title: "Tap Android screen",
      description: "Tap local Android screen coordinates through Shizuku/rish. Requires ANDROID_UI confirmation from an explicit user instruction.",
      inputSchema: {
        confirm: z.literal("ANDROID_UI"),
        x: z.number().int().min(0).max(20_000),
        y: z.number().int().min(0).max(20_000),
        serial: serialSchema
      },
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false }
    },
    async ({ x, y, serial }) => {
      try {
        assertLocalSerial(serial);
        await runRishText(`input tap ${x} ${y}`, 10_000, 256 * 1024);
        return textResult({ transport: TRANSPORT, serial: null, tapped: { x, y } });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "android_swipe",
    {
      title: "Swipe Android screen",
      description: "Swipe between local Android screen coordinates through Shizuku/rish. Requires ANDROID_UI confirmation from an explicit user instruction.",
      inputSchema: {
        confirm: z.literal("ANDROID_UI"),
        x1: z.number().int().min(0).max(20_000),
        y1: z.number().int().min(0).max(20_000),
        x2: z.number().int().min(0).max(20_000),
        y2: z.number().int().min(0).max(20_000),
        durationMs: z.number().int().min(1).max(10_000).default(300),
        serial: serialSchema
      },
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false }
    },
    async ({ x1, y1, x2, y2, durationMs, serial }) => {
      try {
        assertLocalSerial(serial);
        await runRishText(`input swipe ${x1} ${y1} ${x2} ${y2} ${durationMs}`, 15_000, 256 * 1024);
        return textResult({ transport: TRANSPORT, serial: null, swiped: { x1, y1, x2, y2, durationMs } });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "android_input_text",
    {
      title: "Type Android text",
      description: "Type text through Android's standard input text command over Shizuku/rish. Unicode behavior depends on Android/IME. Requires ANDROID_UI confirmation.",
      inputSchema: {
        confirm: z.literal("ANDROID_UI"),
        text: z.string().max(20_000),
        serial: serialSchema
      },
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false }
    },
    async ({ text, serial }) => {
      try {
        assertLocalSerial(serial);
        if (/[\u0000\r\n]/.test(text)) {
          throw new Error("android_input_text does not accept NUL/newlines; use android_press_key for Enter");
        }
        const encoded = text.replaceAll("%", "\\%").replaceAll(" ", "%s");
        await runRishText(`input text ${shellQuote(encoded)}`, 20_000, 256 * 1024);
        return textResult({
          transport: TRANSPORT,
          serial: null,
          typedCharacters: [...text].length,
          unicodeLimitation: "Android input text Unicode behavior depends on the active IME/system"
        });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "android_press_key",
    {
      title: "Press Android key",
      description: "Send a local Android keyevent through Shizuku/rish. Requires ANDROID_UI confirmation.",
      inputSchema: {
        confirm: z.literal("ANDROID_UI"),
        key: z.union([z.number().int().min(0).max(500), z.string().regex(/^KEYCODE_[A-Z0-9_]+$/)]),
        serial: serialSchema
      },
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false }
    },
    async ({ key, serial }) => {
      try {
        assertLocalSerial(serial);
        await runRishText(`input keyevent ${String(key)}`, 10_000, 256 * 1024);
        return textResult({ transport: TRANSPORT, serial: null, key });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "android_launch_app",
    {
      title: "Launch Android app",
      description: "Launch a local installed app by validated package name through Shizuku/rish using the standard launcher category. Requires ANDROID_UI confirmation.",
      inputSchema: {
        confirm: z.literal("ANDROID_UI"),
        packageName: z.string().regex(/^[A-Za-z0-9._]{1,255}$/),
        serial: serialSchema
      },
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false }
    },
    async ({ packageName, serial }) => {
      try {
        assertLocalSerial(serial);
        await enforcePortraitMode();
        const { stdout, stderr } = await runRishText(
          `component="$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${packageName} | tail -n 1)"
case "$component" in
  */*) am start -W -n "$component" ;;
  *) printf "Error: no launcher activity resolved for ${packageName}\\n" ;;
esac`,
          20_000,
          512 * 1024
        );
        await enforcePortraitMode();
        const output = `${stdout}${stderr ? `\n${stderr}` : ""}`.trim();
        return textResult({
          transport: TRANSPORT,
          serial: null,
          packageName,
          launched: !/No activities found|Error:|Exception/i.test(output),
          output: output.slice(0, 2000)
        });
      } catch (error) {
        return errorResult(error);
      }
    }
  );
}
