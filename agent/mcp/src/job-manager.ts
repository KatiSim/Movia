import { spawn, type ChildProcess } from "node:child_process";
import { randomBytes } from "node:crypto";
import { access, open, stat, writeFile } from "node:fs/promises";
import { constants as fsConstants } from "node:fs";

import {
  DEFAULT_JOB_TIMEOUT_SECONDS,
  MAX_CHUNK_BYTES,
  MAX_CONCURRENT_JOBS,
  MAX_JOB_TIMEOUT_SECONDS
} from "./config.js";
import { JobStore } from "./job-store.js";
import type { PathPolicy } from "./paths.js";
import { shellQuote, validateCommand } from "./security.js";
import type {
  JobMetadata,
  ReadChunkResult,
  TerminalMode
} from "./types.js";

interface ActiveJob {
  child: ChildProcess;
  timer: NodeJS.Timeout;
  timedOut: boolean;
}

export interface JobManagerOptions {
  store: JobStore;
  pathPolicy: PathPolicy;
  bash: string;
  scriptPath: string;
  maxConcurrentJobs?: number;
  defaultTimeoutSeconds?: number;
  maxTimeoutSeconds?: number;
  maxChunkBytes?: number;
}

export interface StartJobOptions {
  command: string;
  cwd: string;
  terminalMode?: TerminalMode;
  timeoutSeconds?: number;
}

const ALLOWED_SIGNALS = new Set<NodeJS.Signals>([
  "SIGHUP",
  "SIGINT",
  "SIGTERM",
  "SIGKILL",
  "SIGUSR1",
  "SIGUSR2"
]);

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function makeJobId(now = new Date()): string {
  const stamp = now
    .toISOString()
    .replace(/[-:]/g, "")
    .replace("T", "_")
    .slice(0, 15);
  return `job_${stamp}_${randomBytes(4).toString("hex")}`;
}

function processAlive(pid: number | null): boolean {
  if (!pid || pid < 1) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === "EPERM";
  }
}

export class JobManager {
  private readonly active = new Map<string, ActiveJob>();
  private startQueue: Promise<void> = Promise.resolve();
  private readonly maxConcurrentJobs: number;
  private readonly defaultTimeoutSeconds: number;
  private readonly maxTimeoutSeconds: number;
  private readonly maxChunkBytes: number;

  constructor(private readonly options: JobManagerOptions) {
    this.maxConcurrentJobs =
      options.maxConcurrentJobs ?? MAX_CONCURRENT_JOBS;
    this.defaultTimeoutSeconds =
      options.defaultTimeoutSeconds ?? DEFAULT_JOB_TIMEOUT_SECONDS;
    this.maxTimeoutSeconds =
      options.maxTimeoutSeconds ?? MAX_JOB_TIMEOUT_SECONDS;
    this.maxChunkBytes = options.maxChunkBytes ?? MAX_CHUNK_BYTES;
  }

  get store(): JobStore {
    return this.options.store;
  }

  get activeJobsCount(): number {
    return this.active.size;
  }

  async start(input: StartJobOptions): Promise<JobMetadata> {
    const previous = this.startQueue;
    let release!: () => void;
    this.startQueue = new Promise<void>(resolve => { release = resolve; });
    await previous;
    try {
      return await this.startAtomically(input);
    } finally {
      release();
    }
  }

  private async startAtomically(input: StartJobOptions): Promise<JobMetadata> {
    validateCommand(input.command);
    const cwd = await this.options.pathPolicy.allowedPath(input.cwd, true);
    const cwdInfo = await stat(cwd);
    if (!cwdInfo.isDirectory()) {
      throw new Error(`Рабочий путь не является каталогом: ${cwd}`);
    }

    const terminalMode = input.terminalMode ?? "pipe";
    if (terminalMode === "pty") {
      try {
        await access(this.options.scriptPath, fsConstants.X_OK);
      } catch {
        throw new Error(
          `PTY недоступен: не найден исполняемый script(1): ${this.options.scriptPath}`
        );
      }
    }

    const timeoutSeconds = input.timeoutSeconds ?? this.defaultTimeoutSeconds;
    if (
      !Number.isInteger(timeoutSeconds) ||
      timeoutSeconds < 1 ||
      timeoutSeconds > this.maxTimeoutSeconds
    ) {
      throw new Error(
        `timeoutSeconds должен быть от 1 до ${this.maxTimeoutSeconds}`
      );
    }

    const running = await this.runningJobsCount();
    if (running >= this.maxConcurrentJobs) {
      throw new Error(
        `Достигнут лимит одновременных заданий: ${this.maxConcurrentJobs}`
      );
    }

    let jobId = makeJobId();
    while (true) {
      try {
        await this.options.store.readMetadata(jobId);
        jobId = makeJobId();
      } catch (error) {
        if ((error as Error).message.includes("не найдено")) break;
        throw error;
      }
    }

    const now = new Date().toISOString();
    let metadata: JobMetadata = {
      jobId,
      command: input.command,
      cwd,
      terminalMode,
      status: "queued",
      pid: null,
      createdAt: now,
      startedAt: null,
      completedAt: null,
      timeoutSeconds,
      exitCode: null,
      signal: null,
      stopRequested: false,
      error: null
    };

    const paths = await this.options.store.createLayout(metadata);
    await writeFile(
      paths.commandPath,
      `#!${this.options.bash}\n${input.command}\n`,
      { encoding: "utf8", mode: 0o700 }
    );

    const commandInvocation =
      terminalMode === "pty"
        ? `${shellQuote(this.options.scriptPath)} -qefc ${shellQuote(
            `${shellQuote(this.options.bash)} ${shellQuote(paths.commandPath)}`
          )} /dev/null`
        : `${shellQuote(this.options.bash)} ${shellQuote(paths.commandPath)}`;

    const runner = [
      `#!${this.options.bash}`,
      "set +e",
      commandInvocation,
      "ec=$?",
      `tmp=${shellQuote(`${paths.exitCodePath}.tmp`)}.$$`,
      'printf "%s\\n" "$ec" > "$tmp"',
      `mv -f "$tmp" ${shellQuote(paths.exitCodePath)}`,
      'exit "$ec"',
      ""
    ].join("\n");
    await writeFile(paths.runnerPath, runner, {
      encoding: "utf8",
      mode: 0o700
    });

    const stdoutHandle = await open(paths.stdoutPath, "a");
    const stderrHandle = await open(paths.stderrPath, "a");
    let child: ChildProcess;

    try {
      child = spawn(this.options.bash, [paths.runnerPath], {
        cwd,
        detached: true,
        env: {
          ...process.env,
          HOME: this.options.pathPolicy.expandHome("~"),
          TERM: terminalMode === "pty" ? "xterm-256color" : "dumb"
        },
        stdio: ["pipe", stdoutHandle.fd, stderrHandle.fd]
      });
    } finally {
      await stdoutHandle.close();
      await stderrHandle.close();
    }

    if (!child.pid) {
      throw new Error("Не удалось получить PID запущенного задания");
    }

    let readyResolve: (() => void) | undefined;
    const ready = new Promise<void>(resolve => {
      readyResolve = resolve;
    });

    child.once("error", error => {
      void ready.then(() => this.finalize(jobId, null, null, error));
    });
    child.once("close", (exitCode, signal) => {
      void ready.then(() => this.finalize(jobId, exitCode, signal, null));
    });

    const timer = setTimeout(() => {
      void this.timeout(jobId);
    }, timeoutSeconds * 1000);
    timer.unref();

    this.active.set(jobId, {
      child,
      timer,
      timedOut: false
    });

    metadata = {
      ...metadata,
      status: "running",
      pid: child.pid,
      startedAt: new Date().toISOString()
    };
    await this.options.store.writeMetadata(metadata);
    readyResolve?.();
    child.unref();

    if (child.exitCode !== null || child.signalCode !== null) {
      void this.finalize(jobId, child.exitCode, child.signalCode, null);
    }

    return metadata;
  }

  async status(jobId: string): Promise<JobMetadata> {
    let metadata = await this.options.store.reconcile(jobId);
    if (!["queued", "running"].includes(metadata.status)) return metadata;

    if (processAlive(metadata.pid)) return metadata;

    await sleep(25);
    metadata = await this.options.store.reconcile(jobId);
    if (!["queued", "running"].includes(metadata.status)) return metadata;

    const updated: JobMetadata = {
      ...metadata,
      status: metadata.stopRequested ? "stopped" : "failed",
      completedAt: new Date().toISOString(),
      error: metadata.error ?? "Процесс завершился без файла exit-code"
    };
    await this.options.store.writeMetadata(updated);
    return updated;
  }

  async wait(jobId: string, waitSeconds: number): Promise<JobMetadata> {
    if (!Number.isFinite(waitSeconds) || waitSeconds < 0) {
      throw new Error("waitSeconds должен быть неотрицательным числом");
    }

    const deadline = Date.now() + waitSeconds * 1000;
    let metadata = await this.status(jobId);
    while (
      ["queued", "running"].includes(metadata.status) &&
      Date.now() < deadline
    ) {
      await sleep(Math.min(50, Math.max(1, deadline - Date.now())));
      metadata = await this.status(jobId);
    }
    return metadata;
  }

  async read(
    jobId: string,
    stream: "stdout" | "stderr",
    offset: number,
    maxBytes: number
  ): Promise<ReadChunkResult> {
    return this.options.store.readChunk(
      jobId,
      stream,
      offset,
      Math.min(maxBytes, this.maxChunkBytes)
    );
  }

  async input(
    jobId: string,
    data: string
  ): Promise<{ jobId: string; acceptedBytes: number }> {
    const metadata = await this.status(jobId);
    if (metadata.status !== "running") {
      throw new Error(`Задание не работает: ${jobId} (${metadata.status})`);
    }

    const active = this.active.get(jobId);
    const stdin = active?.child.stdin;
    if (!active || !stdin || stdin.destroyed || !stdin.writable) {
      throw new Error(
        "stdin недоступен: задание запущено другим экземпляром сервера или поток уже закрыт"
      );
    }

    await new Promise<void>((resolve, reject) => {
      stdin.write(data, error => {
        if (error) reject(error);
        else resolve();
      });
    });

    return {
      jobId,
      acceptedBytes: Buffer.byteLength(data)
    };
  }

  async signal(
    jobId: string,
    signal: NodeJS.Signals
  ): Promise<{ jobId: string; pid: number; signal: NodeJS.Signals }> {
    if (!ALLOWED_SIGNALS.has(signal)) {
      throw new Error(`Сигнал не разрешён: ${signal}`);
    }
    const metadata = await this.status(jobId);
    if (metadata.status !== "running" || !metadata.pid) {
      throw new Error(`Задание не работает: ${jobId} (${metadata.status})`);
    }

    this.killProcessGroup(metadata.pid, signal);
    return { jobId, pid: metadata.pid, signal };
  }

  async stop(jobId: string, graceSeconds = 5): Promise<JobMetadata> {
    let metadata = await this.status(jobId);
    if (metadata.status !== "running") return metadata;
    metadata = { ...metadata, stopRequested: true };
    await this.options.store.writeMetadata(metadata);

    if (metadata.pid) this.killProcessGroup(metadata.pid, "SIGTERM");
    metadata = await this.wait(jobId, graceSeconds);
    if (metadata.status === "running" && metadata.pid) {
      this.killProcessGroup(metadata.pid, "SIGKILL");
      metadata = await this.wait(jobId, 2);
    }
    return metadata;
  }

  async list(limit = 100): Promise<JobMetadata[]> {
    const jobs = await this.options.store.listMetadata(limit);
    return Promise.all(jobs.map(job => this.status(job.jobId)));
  }

  async cleanup(
    olderThanHours: number,
    limit = 100
  ): Promise<{ removed: string[]; skipped: string[] }> {
    if (!Number.isFinite(olderThanHours) || olderThanHours < 0) {
      throw new Error("olderThanHours должен быть неотрицательным числом");
    }
    const cutoff = Date.now() - olderThanHours * 3_600_000;
    const jobs = await this.list(limit);
    const removed: string[] = [];
    const skipped: string[] = [];

    for (const job of jobs) {
      const completedAt = job.completedAt
        ? Date.parse(job.completedAt)
        : Number.POSITIVE_INFINITY;
      if (
        ["queued", "running"].includes(job.status) ||
        completedAt > cutoff
      ) {
        skipped.push(job.jobId);
        continue;
      }
      await this.options.store.removeJob(job.jobId);
      removed.push(job.jobId);
    }

    return { removed, skipped };
  }

  private async runningJobsCount(): Promise<number> {
    const jobs = await this.options.store.listMetadata(10_000);
    let count = 0;
    for (const job of jobs) {
      const current = await this.status(job.jobId);
      if (["queued", "running"].includes(current.status)) count += 1;
    }
    return count;
  }

  private killProcessGroup(pid: number, signal: NodeJS.Signals): void {
    try {
      process.kill(-pid, signal);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ESRCH") throw error;
      process.kill(pid, signal);
    }
  }

  private async timeout(jobId: string): Promise<void> {
    const active = this.active.get(jobId);
    if (!active) return;
    active.timedOut = true;
    const metadata = await this.options.store.readMetadata(jobId);
    await this.options.store.writeMetadata({
      ...metadata,
      error: `Превышен тайм-аут ${metadata.timeoutSeconds} секунд`
    });
    if (metadata.pid && processAlive(metadata.pid)) {
      this.killProcessGroup(metadata.pid, "SIGTERM");
      setTimeout(() => {
        if (metadata.pid && processAlive(metadata.pid)) {
          try {
            this.killProcessGroup(metadata.pid, "SIGKILL");
          } catch {
            // Process may have exited between the liveness check and signal.
          }
        }
      }, 1_000).unref();
    }
  }

  private async finalize(
    jobId: string,
    exitCode: number | null,
    signal: NodeJS.Signals | null,
    error: Error | null
  ): Promise<void> {
    const active = this.active.get(jobId);
    if (active) clearTimeout(active.timer);
    this.active.delete(jobId);

    let metadata: JobMetadata;
    try {
      metadata = await this.options.store.readMetadata(jobId);
    } catch {
      return;
    }

    if (!["queued", "running"].includes(metadata.status)) return;
    const status = active?.timedOut
      ? "timed_out"
      : metadata.stopRequested
        ? "stopped"
        : exitCode === 0
          ? "completed"
          : "failed";

    await this.options.store.writeMetadata({
      ...metadata,
      status,
      exitCode,
      signal,
      completedAt: new Date().toISOString(),
      error: error?.message ?? metadata.error
    });
  }
}
