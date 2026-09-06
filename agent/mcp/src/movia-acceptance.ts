import { execFile } from "node:child_process";
import { homedir } from "node:os";
import { join } from "node:path";

export interface MoviaAcceptanceOptions {
  verbose?: boolean;
  timeoutSeconds?: number;
}

function parseSummary(stdout: string): Record<string, unknown> {
  const trimmed = stdout.trim();
  if (!trimmed) throw new Error("Movia acceptance produced no JSON output");
  const parsed = JSON.parse(trimmed);
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("Movia acceptance output is not a JSON object");
  }
  return parsed as Record<string, unknown>;
}

export async function runMoviaAcceptance(options: MoviaAcceptanceOptions = {}): Promise<Record<string, unknown>> {
  const script = process.env.MOVIA_ACCEPTANCE_SCRIPT || join(homedir(), "projects/movia/acceptance/movia_acceptance.py");
  const timeoutSeconds = Math.min(300, Math.max(30, Math.floor(options.timeoutSeconds ?? 240)));
  const args = [script];
  if (options.verbose) args.push("--verbose");

  return await new Promise<Record<string, unknown>>((resolve, reject) => {
    execFile(
      "python3",
      args,
      {
        timeout: timeoutSeconds * 1_000,
        maxBuffer: 2 * 1024 * 1024,
        env: process.env,
      },
      (error, stdout, stderr) => {
        try {
          const summary = parseSummary(stdout);
          const exitCode = error && typeof (error as NodeJS.ErrnoException).code === "number"
            ? (error as NodeJS.ErrnoException).code
            : error ? 1 : 0;
          resolve({
            ...summary,
            processExitCode: exitCode,
            ...(options.verbose && stderr.trim() ? { logTail: stderr.trim().slice(-8_000) } : {}),
          });
        } catch (parseError) {
          const detail = stderr.trim().slice(-4_000);
          reject(new Error(`Movia acceptance failed to return valid JSON${detail ? `: ${detail}` : ""}`, { cause: parseError }));
        }
      },
    );
  });
}
