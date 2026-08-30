import { randomUUID } from "node:crypto";
import {
  mkdir,
  open,
  readFile,
  readdir,
  rename,
  rm,
  stat,
  writeFile
} from "node:fs/promises";
import { join, resolve } from "node:path";

import type {
  JobMetadata,
  JobPaths,
  ReadChunkResult
} from "./types.js";

const JOB_ID_PATTERN = /^job_[0-9]{8}_[0-9]{6}_[a-f0-9]{8}$/;

export class JobStore {
  constructor(readonly root: string) {}

  pathsFor(jobId: string): JobPaths {
    if (!JOB_ID_PATTERN.test(jobId)) {
      throw new Error(`Некорректный jobId: ${jobId}`);
    }

    const jobDir = resolve(this.root, jobId);
    return {
      jobDir,
      metadataPath: join(jobDir, "metadata.json"),
      commandPath: join(jobDir, "command.sh"),
      runnerPath: join(jobDir, "runner.sh"),
      stdoutPath: join(jobDir, "stdout.log"),
      stderrPath: join(jobDir, "stderr.log"),
      exitCodePath: join(jobDir, "exit-code")
    };
  }

  async createLayout(metadata: JobMetadata): Promise<JobPaths> {
    const paths = this.pathsFor(metadata.jobId);
    await mkdir(paths.jobDir, { recursive: false });
    await Promise.all([
      writeFile(paths.commandPath, "", { encoding: "utf8", mode: 0o700 }),
      writeFile(paths.runnerPath, "", { encoding: "utf8", mode: 0o700 }),
      writeFile(paths.stdoutPath, "", "utf8"),
      writeFile(paths.stderrPath, "", "utf8")
    ]);
    await this.writeMetadata(metadata);
    return paths;
  }

  async writeMetadata(metadata: JobMetadata): Promise<void> {
    const paths = this.pathsFor(metadata.jobId);
    await mkdir(paths.jobDir, { recursive: true });
    const tempPath = `${paths.metadataPath}.tmp-${process.pid}-${randomUUID()}`;
    try {
      await writeFile(tempPath, `${JSON.stringify(metadata, null, 2)}\n`, {
        encoding: "utf8",
        mode: 0o600
      });
      await rename(tempPath, paths.metadataPath);
    } finally {
      await rm(tempPath, { force: true }).catch(() => undefined);
    }
  }

  async readMetadata(jobId: string): Promise<JobMetadata> {
    const paths = this.pathsFor(jobId);
    try {
      const raw = await readFile(paths.metadataPath, "utf8");
      return JSON.parse(raw) as JobMetadata;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") {
        throw new Error(`Задание не найдено: ${jobId}`);
      }
      throw error;
    }
  }

  async listMetadata(limit = 100): Promise<JobMetadata[]> {
    await mkdir(this.root, { recursive: true });
    const entries = await readdir(this.root, { withFileTypes: true });
    const jobs: JobMetadata[] = [];

    for (const entry of entries) {
      if (!entry.isDirectory() || !JOB_ID_PATTERN.test(entry.name)) continue;
      try {
        jobs.push(await this.readMetadata(entry.name));
      } catch {
        // Ignore incomplete or corrupt job directories in list output.
      }
    }

    jobs.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    return jobs.slice(0, Math.max(0, limit));
  }

  async readChunk(
    jobId: string,
    stream: "stdout" | "stderr",
    offset: number,
    maxBytes: number
  ): Promise<ReadChunkResult> {
    if (!Number.isSafeInteger(offset) || offset < 0) {
      throw new Error("offset должен быть неотрицательным целым числом");
    }
    if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) {
      throw new Error("maxBytes должен быть положительным целым числом");
    }

    const paths = this.pathsFor(jobId);
    await this.readMetadata(jobId);
    const path = stream === "stdout" ? paths.stdoutPath : paths.stderrPath;
    const info = await stat(path);
    const start = Math.min(offset, info.size);
    const length = Math.min(maxBytes, Math.max(0, info.size - start));
    const buffer = Buffer.alloc(length);

    if (length > 0) {
      const handle = await open(path, "r");
      try {
        await handle.read(buffer, 0, length, start);
      } finally {
        await handle.close();
      }
    }

    const nextOffset = start + length;
    return {
      jobId,
      stream,
      offset: start,
      nextOffset,
      returnedBytes: length,
      totalBytes: info.size,
      eof: nextOffset >= info.size,
      content: buffer.toString("utf8")
    };
  }

  async reconcile(jobId: string): Promise<JobMetadata> {
    const metadata = await this.readMetadata(jobId);
    if (!["queued", "running"].includes(metadata.status)) return metadata;

    const paths = this.pathsFor(jobId);
    try {
      const raw = (await readFile(paths.exitCodePath, "utf8")).trim();
      if (!/^-?\d+$/.test(raw)) return metadata;
      const exitCode = Number(raw);
      const updated: JobMetadata = {
        ...metadata,
        status: metadata.stopRequested
          ? "stopped"
          : exitCode === 0
            ? "completed"
            : "failed",
        exitCode,
        completedAt: metadata.completedAt ?? new Date().toISOString()
      };
      await this.writeMetadata(updated);
      return updated;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return metadata;
      throw error;
    }
  }

  async removeJob(jobId: string): Promise<void> {
    const metadata = await this.reconcile(jobId);
    if (["queued", "running"].includes(metadata.status)) {
      throw new Error(`Нельзя удалить работающее задание: ${jobId}`);
    }
    await rm(this.pathsFor(jobId).jobDir, { recursive: true, force: false });
  }
}
