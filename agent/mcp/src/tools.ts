import {
  appendFile,
  mkdir,
  open,
  readFile,
  readdir,
  stat,
  writeFile
} from "node:fs/promises";
import { dirname, resolve } from "node:path";

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import * as z from "zod/v4";

import {
  DEFAULT_JOB_TIMEOUT_SECONDS,
  DEFAULT_RETENTION_HOURS,
  HOME,
  MAX_CHUNK_BYTES,
  MAX_COMMAND_CHARS,
  MAX_CONCURRENT_JOBS,
  MAX_JOB_TIMEOUT_SECONDS,
  MAX_STREAM_BYTES,
  MAX_SYNC_TIMEOUT_SECONDS,
  SERVER_VERSION
} from "./config.js";
import { JobManager } from "./job-manager.js";
import type { PathPolicy } from "./paths.js";
import { registerBrowserTools } from "./browser-tools.js";
import { registerAndroidTools } from "./android-tools.js";
import { registerMoviaTools } from "./movia-tools.js";

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

function durationMs(startedAt: string | null, completedAt: string | null): number | null {
  if (!startedAt) return null;
  const end = completedAt ? Date.parse(completedAt) : Date.now();
  return Math.max(0, end - Date.parse(startedAt));
}

export function createToolServer(options: {
  manager: JobManager;
  pathPolicy: PathPolicy;
  jobRoot: string;
  secretPathEnabled: boolean;
}): McpServer {
  const { manager, pathPolicy } = options;
  const server = new McpServer(
    {
      name: "termux-mcp",
      version: SERVER_VERSION
    },
    {
      capabilities: { logging: {} },
      instructions: [
        "This server operates on the user's Android Termux environment.",
        "Inspect before modifying.",
        "Use read-only tools for diagnosis.",
        "Mutating tools require the literal confirmation specified by their schemas.",
        "A single explicit user instruction may authorize all necessary commands and file changes within that task.",
        "Never execute destructive, credential-exfiltrating, persistence, surveillance, or scope-expanding commands.",
        "Movia native tools use the loopback agent at 127.0.0.1:8899 with a private local bearer token; they do not use UI automation or Shizuku.",
        "The generic movia_action tool remains confirmation-gated; typed Movia tools expose bounded domain operations directly.",
        "Long work should use exec_start and be observed with exec_status, exec_wait, and exec_read.",
        "Report exact commands, exit codes, stdout, and stderr."
      ].join(" ")
    }
  );

  server.registerTool(
    "termux_status",
    {
      title: "Termux status",
      description: "Read-only information about Termux, limits, roots, and durable jobs.",
      inputSchema: {},
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false
      }
    },
    async () => {
      try {
        const roots = await pathPolicy.existingRoots();
        const jobs = await manager.list(500);
        return textResult({
          platform: process.platform,
          architecture: process.arch,
          node: process.version,
          serverVersion: SERVER_VERSION,
          pid: process.pid,
          uptimeSeconds: Math.round(process.uptime()),
          home: HOME,
          cwd: process.cwd(),
          allowedRoots: roots,
          jobRoot: options.jobRoot,
          secretPathEnabled: options.secretPathEnabled,
          jobs: {
            total: jobs.length,
            running: jobs.filter(job => ["queued", "running"].includes(job.status)).length
          },
          limits: {
            maxConcurrentJobs: MAX_CONCURRENT_JOBS,
            defaultJobTimeoutSeconds: DEFAULT_JOB_TIMEOUT_SECONDS,
            maxJobTimeoutSeconds: MAX_JOB_TIMEOUT_SECONDS,
            maxSyncTimeoutSeconds: MAX_SYNC_TIMEOUT_SECONDS,
            maxCommandChars: MAX_COMMAND_CHARS,
            maxChunkBytes: MAX_CHUNK_BYTES,
            maxStreamBytesPolicy: MAX_STREAM_BYTES,
            defaultRetentionHours: DEFAULT_RETENTION_HOURS
          }
        });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "list_directory",
    {
      title: "List Termux directory",
      description: "List files and directories within the permitted filesystem roots.",
      inputSchema: {
        path: z.string().default("~"),
        maxEntries: z.number().int().min(1).max(500).default(200)
      },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false
      }
    },
    async ({ path, maxEntries }) => {
      try {
        const target = await pathPolicy.allowedPath(path, true);
        const entries = await readdir(target, { withFileTypes: true });
        const result = await Promise.all(
          entries.slice(0, maxEntries).map(async entry => {
            const fullPath = resolve(target, entry.name);
            try {
              const info = await stat(fullPath);
              return {
                name: entry.name,
                type: entry.isDirectory()
                  ? "directory"
                  : entry.isFile()
                    ? "file"
                    : entry.isSymbolicLink()
                      ? "symlink"
                      : "other",
                size: info.size,
                modified: info.mtime.toISOString()
              };
            } catch {
              return { name: entry.name, type: "unreadable" };
            }
          })
        );
        return textResult({
          path: target,
          totalEntries: entries.length,
          returnedEntries: result.length,
          truncated: entries.length > result.length,
          entries: result
        });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "read_file",
    {
      title: "Read Termux file",
      description: "Read a UTF-8 file by byte offset within permitted roots.",
      inputSchema: {
        path: z.string(),
        offset: z.number().int().min(0).default(0),
        maxBytes: z.number().int().min(1).max(MAX_CHUNK_BYTES).default(262_144)
      },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false
      }
    },
    async ({ path, offset, maxBytes }) => {
      try {
        const target = await pathPolicy.allowedPath(path, true);
        const info = await stat(target);
        if (!info.isFile()) throw new Error(`Путь не является файлом: ${target}`);
        const start = Math.min(offset, info.size);
        const length = Math.min(maxBytes, Math.max(0, info.size - start));
        const buffer = Buffer.alloc(length);
        if (length > 0) {
          const handle = await open(target, "r");
          try {
            await handle.read(buffer, 0, length, start);
          } finally {
            await handle.close();
          }
        }
        return textResult({
          path: target,
          fileSize: info.size,
          offset: start,
          nextOffset: start + length,
          returnedBytes: length,
          truncated: start + length < info.size,
          content: buffer.toString("utf8")
        });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "write_file",
    {
      title: "Write Termux file",
      description: "Create, overwrite, or append to a file after explicit user authorization.",
      inputSchema: {
        confirm: z.literal("WRITE"),
        path: z.string(),
        content: z.string().max(2_097_152),
        mode: z.enum(["overwrite", "append"]).default("overwrite"),
        createDirectories: z.boolean().default(true)
      },
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: false
      }
    },
    async ({ path, content, mode, createDirectories }) => {
      try {
        const target = await pathPolicy.allowedPath(path, false);
        if (createDirectories) await mkdir(dirname(target), { recursive: true });
        if (mode === "append") await appendFile(target, content, "utf8");
        else await writeFile(target, content, "utf8");
        const info = await stat(target);
        return textResult({
          success: true,
          path: target,
          mode,
          size: info.size,
          modified: info.mtime.toISOString()
        });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "run_command",
    {
      title: "Run Termux command synchronously",
      description: "Compatibility command execution. For long work prefer exec_start. Requires RUN.",
      inputSchema: {
        confirm: z.literal("RUN"),
        command: z.string().min(1).max(MAX_COMMAND_CHARS),
        cwd: z.string().default("~"),
        timeoutSeconds: z.number().int().min(1).max(MAX_SYNC_TIMEOUT_SECONDS).default(30),
        maxOutputBytes: z.number().int().min(1_024).max(MAX_CHUNK_BYTES).default(262_144)
      },
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: true
      }
    },
    async ({ command, cwd, timeoutSeconds, maxOutputBytes }) => {
      try {
        const started = await manager.start({
          command,
          cwd,
          terminalMode: "pipe",
          timeoutSeconds
        });
        let finished = await manager.wait(started.jobId, timeoutSeconds + 2);
        if (["queued", "running"].includes(finished.status)) {
          finished = await manager.stop(started.jobId, 1);
        }
        const stdout = await manager.read(started.jobId, "stdout", 0, maxOutputBytes);
        const remaining = Math.max(1, maxOutputBytes - stdout.returnedBytes);
        const stderr = await manager.read(started.jobId, "stderr", 0, remaining);
        return textResult({
          jobId: started.jobId,
          command,
          cwd: started.cwd,
          exitCode: finished.exitCode,
          signal: finished.signal,
          timedOut: finished.status === "timed_out",
          truncated: !stdout.eof || !stderr.eof,
          durationMs: durationMs(finished.startedAt, finished.completedAt),
          stdout: stdout.content,
          stderr: stderr.content
        });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "exec_start",
    {
      title: "Start durable Termux job",
      description: "Start a long-running disk-backed job and immediately return its jobId. Requires START.",
      inputSchema: {
        confirm: z.literal("START"),
        command: z.string().min(1).max(MAX_COMMAND_CHARS),
        cwd: z.string().default("~"),
        terminalMode: z.enum(["pipe", "pty"]).default("pipe"),
        timeoutSeconds: z.number().int().min(1).max(MAX_JOB_TIMEOUT_SECONDS).default(DEFAULT_JOB_TIMEOUT_SECONDS)
      },
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: true
      }
    },
    async ({ command, cwd, terminalMode, timeoutSeconds }) => {
      try {
        return textResult(await manager.start({ command, cwd, terminalMode, timeoutSeconds }));
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "exec_status",
    {
      title: "Get Termux job status",
      description: "Read current durable job metadata.",
      inputSchema: { jobId: z.string() },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false
      }
    },
    async ({ jobId }) => {
      try {
        return textResult(await manager.status(jobId));
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "exec_wait",
    {
      title: "Wait for Termux job",
      description: "Wait briefly for a job while preserving it if still running.",
      inputSchema: {
        jobId: z.string(),
        waitSeconds: z.number().min(0).max(300).default(30)
      },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false
      }
    },
    async ({ jobId, waitSeconds }) => {
      try {
        return textResult(await manager.wait(jobId, waitSeconds));
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "exec_read",
    {
      title: "Read Termux job output",
      description: "Read stdout or stderr by byte offset without loading the full log.",
      inputSchema: {
        jobId: z.string(),
        stream: z.enum(["stdout", "stderr"]).default("stdout"),
        offset: z.number().int().min(0).default(0),
        maxBytes: z.number().int().min(1).max(MAX_CHUNK_BYTES).default(262_144)
      },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false
      }
    },
    async ({ jobId, stream, offset, maxBytes }) => {
      try {
        return textResult(await manager.read(jobId, stream, offset, maxBytes));
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "exec_input",
    {
      title: "Send input to Termux job",
      description: "Write data to stdin of a job owned by this server process. Requires INPUT.",
      inputSchema: {
        confirm: z.literal("INPUT"),
        jobId: z.string(),
        data: z.string().max(MAX_CHUNK_BYTES)
      },
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: false
      }
    },
    async ({ jobId, data }) => {
      try {
        return textResult(await manager.input(jobId, data));
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "exec_signal",
    {
      title: "Signal Termux job",
      description: "Send an allowed POSIX signal to a job process group. Requires SIGNAL.",
      inputSchema: {
        confirm: z.literal("SIGNAL"),
        jobId: z.string(),
        signal: z.enum(["SIGHUP", "SIGINT", "SIGTERM", "SIGKILL", "SIGUSR1", "SIGUSR2"])
      },
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: false
      }
    },
    async ({ jobId, signal }) => {
      try {
        return textResult(await manager.signal(jobId, signal));
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "exec_stop",
    {
      title: "Stop Termux job",
      description: "Send TERM and then KILL after a grace period. Requires STOP.",
      inputSchema: {
        confirm: z.literal("STOP"),
        jobId: z.string(),
        graceSeconds: z.number().min(0).max(30).default(5)
      },
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: true,
        openWorldHint: false
      }
    },
    async ({ jobId, graceSeconds }) => {
      try {
        return textResult(await manager.stop(jobId, graceSeconds));
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "exec_list",
    {
      title: "List Termux jobs",
      description: "List recent durable jobs newest first.",
      inputSchema: { limit: z.number().int().min(1).max(500).default(100) },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false
      }
    },
    async ({ limit }) => {
      try {
        const jobs = await manager.list(limit);
        return textResult({ jobs, count: jobs.length });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "exec_cleanup",
    {
      title: "Clean completed Termux jobs",
      description: "Delete completed job directories older than a threshold. Running jobs are never removed. Requires CLEANUP.",
      inputSchema: {
        confirm: z.literal("CLEANUP"),
        olderThanHours: z.number().min(0).max(8_760).default(DEFAULT_RETENTION_HOURS),
        limit: z.number().int().min(1).max(500).default(100)
      },
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: true,
        openWorldHint: false
      }
    },
    async ({ olderThanHours, limit }) => {
      try {
        return textResult(await manager.cleanup(olderThanHours, limit));
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  registerBrowserTools(server, pathPolicy);

  registerAndroidTools(server);

  registerMoviaTools(server);

  return server;
}
