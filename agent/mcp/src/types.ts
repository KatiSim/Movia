export type TerminalMode = "pipe" | "pty";

export type JobStatus =
  | "queued"
  | "running"
  | "completed"
  | "failed"
  | "stopped"
  | "timed_out";

export interface JobMetadata {
  jobId: string;
  command: string;
  cwd: string;
  terminalMode: TerminalMode;
  status: JobStatus;
  pid: number | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  timeoutSeconds: number;
  exitCode: number | null;
  signal: string | null;
  stopRequested: boolean;
  error: string | null;
}

export interface JobPaths {
  jobDir: string;
  metadataPath: string;
  commandPath: string;
  runnerPath: string;
  stdoutPath: string;
  stderrPath: string;
  exitCodePath: string;
}

export interface ReadChunkResult {
  jobId: string;
  stream: "stdout" | "stderr";
  offset: number;
  nextOffset: number;
  returnedBytes: number;
  totalBytes: number;
  eof: boolean;
  content: string;
}
