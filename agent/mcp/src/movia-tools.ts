import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import * as z from "zod/v4";

import {
  MoviaBridgeError,
  MoviaHttpError,
  moviaAction,
  moviaRequest,
  sanitizeMoviaValue
} from "./movia-client.js";

const ACTIONS = {
  catalogQuery: ["catalog.query"],
  catalogSearch: ["catalog.search"],
  peopleSearch: ["people.search"],
  mediaDetails: ["media.details"],
  play: ["media.play"],
  pause: ["player.pause"],
  seek: ["player.seek"],
  streamSelect: ["player.selectStream"],
  qualitySelect: ["player.selectQuality"],
  voiceSelect: ["player.selectVoice"],
  myList: ["library.snapshot"],
  myListAdd: ["library.setMyList"],
  myListRemove: ["library.setMyList"],
  downloadEnqueue: ["downloads.enqueue"],
  downloadStatus: ["downloads.status"],
  downloadDelete: ["downloads.delete"],
  settingsSet: ["settings.set"]
} as const;

const MAX_ERROR_DETAIL_CHARS = 8_192;
const TERMINAL_OPERATION_STATES = new Set([
  "completed",
  "complete",
  "success",
  "succeeded",
  "failed",
  "failure",
  "error",
  "cancelled",
  "canceled",
  "rejected"
]);

function jsonText(data: unknown): string {
  try {
    return JSON.stringify(data, null, 2) ?? "null";
  } catch {
    return JSON.stringify({ error: "MOVIA_RESULT_NOT_SERIALIZABLE" }, null, 2);
  }
}

function jsonResult(data: unknown) {
  const safeData = sanitizeMoviaValue(data);
  const structuredContent =
    safeData !== null && typeof safeData === "object" && !Array.isArray(safeData)
      ? (safeData as Record<string, unknown>)
      : { value: safeData };
  return {
    content: [
      {
        type: "text" as const,
        text: jsonText(safeData)
      }
    ],
    structuredContent
  };
}

function boundedDiagnostic(data: unknown): unknown {
  const safeData = sanitizeMoviaValue(data);
  const encoded = jsonText(safeData);
  if (encoded.length <= MAX_ERROR_DETAIL_CHARS) return safeData;
  return {
    truncated: true,
    preview: encoded.slice(0, MAX_ERROR_DETAIL_CHARS)
  };
}

function safeError(error: unknown) {
  let details: unknown;
  let errorCode = "MOVIA_BRIDGE_ERROR";
  let message = "Movia bridge request failed";
  let retryable = false;
  let statusCode: number | undefined;

  if (error instanceof MoviaHttpError) {
    errorCode = "MOVIA_HTTP_ERROR";
    statusCode = error.statusCode;
    retryable = error.statusCode === 401 || error.statusCode >= 500;
    details = boundedDiagnostic(error.payload);
  } else if (error instanceof MoviaBridgeError) {
    errorCode = error.code;
    message = error.message;
    retryable = error.retryable;
  } else if (error instanceof Error) {
    const safeMessage = sanitizeMoviaValue(error.message);
    message = typeof safeMessage === "string" ? safeMessage : message;
  }

  const errorObject = {
    code: errorCode,
    message,
    retryable,
    ...(statusCode === undefined ? {} : { statusCode }),
    ...(details === undefined ? {} : { details })
  };
  const envelope = { error: errorObject };
  return {
    isError: true,
    content: [
      {
        type: "text" as const,
        text: jsonText(envelope)
      }
    ],
    structuredContent: envelope
  };
}

async function runMovia(call: () => Promise<unknown>) {
  try {
    return jsonResult(await call());
  } catch (error) {
    return safeError(error);
  }
}

function compactArgs(args: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(args).filter(([, value]) => value !== undefined));
}

async function moviaActionCompat(
  actions: readonly string[],
  args: Record<string, unknown> = {},
  requestId?: string
): Promise<unknown> {
  let lastError: unknown;
  for (let index = 0; index < actions.length; index += 1) {
    try {
      return await moviaAction(actions[index], compactArgs(args), requestId);
    } catch (error) {
      lastError = error;
      // A 404 means this agent revision does not expose that spelling. Do not
      // retry a mutation after any other HTTP failure.
      if (!(error instanceof MoviaHttpError) || error.statusCode !== 404 || index === actions.length - 1) {
        throw error;
      }
    }
  }
  throw lastError ?? new MoviaBridgeError("MOVIA_ACTION_UNAVAILABLE", "Movia action is unavailable");
}

async function delay(milliseconds: number): Promise<void> {
  await new Promise<void>(resolvePromise => setTimeout(resolvePromise, milliseconds));
}

function operationState(value: unknown, depth = 0): string | boolean | undefined {
  if (depth > 4 || value === null || typeof value !== "object") return undefined;
  if (Array.isArray(value)) return undefined;
  const object = value as Record<string, unknown>;
  for (const key of ["done", "completed", "complete"]) {
    if (typeof object[key] === "boolean") return object[key];
  }
  for (const key of ["status", "state"]) {
    if (typeof object[key] === "string") return object[key].toLowerCase();
  }
  for (const key of ["operation", "result", "data"]) {
    const nested = operationState(object[key], depth + 1);
    if (nested !== undefined) return nested;
  }
  return undefined;
}

function isTerminalOperation(value: unknown): boolean {
  const state = operationState(value);
  return state === true || (typeof state === "string" && TERMINAL_OPERATION_STATES.has(state));
}

async function pollOperation(operationId: string, waitSeconds: number, intervalMs: number): Promise<unknown> {
  const waitMs = Math.min(Math.floor(waitSeconds * 1_000), 30_000);
  const deadline = Date.now() + waitMs;
  let attempts = 0;
  let operation: unknown;

  while (true) {
    const remainingMs = Math.max(0, deadline - Date.now());
    operation = await moviaRequest(
      `/operations?operationId=${encodeURIComponent(operationId)}`,
      { timeoutMs: Math.min(3_000, Math.max(100, remainingMs || 3_000)) }
    );
    attempts += 1;
    const terminal = isTerminalOperation(operation);
    if (terminal || waitMs === 0 || Date.now() >= deadline) {
      return {
        operationId,
        operation,
        attempts,
        timedOut: waitMs > 0 && !terminal
      };
    }
    await delay(Math.min(intervalMs, Math.max(0, deadline - Date.now())));
  }
}

const readAnnotations = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: false
} as const;

const openReadAnnotations = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: true
} as const;

const stateMutationAnnotations = {
  readOnlyHint: false,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: false
} as const;

const sideEffectAnnotations = {
  readOnlyHint: false,
  destructiveHint: false,
  idempotentHint: false,
  openWorldHint: false
} as const;

const deleteAnnotations = {
  readOnlyHint: false,
  destructiveHint: true,
  idempotentHint: true,
  openWorldHint: false
} as const;

export function registerMoviaTools(server: McpServer): void {
  server.registerTool(
    "movia_snapshot",
    {
      title: "Movia snapshot",
      description: "Read Movia's hot machine state directly over its loopback agent. Starts Movia headlessly if needed; never opens its Activity and does not require Shizuku.",
      inputSchema: {},
      annotations: readAnnotations
    },
    async () => runMovia(() => moviaRequest("/snapshot", { timeoutMs: 3_000 }))
  );

  server.registerTool(
    "movia_diagnostics",
    {
      title: "Movia diagnostics",
      description: "Read Movia playback, stream-selection, Media3 and recent agent-event diagnostics without UI automation.",
      inputSchema: {},
      annotations: readAnnotations
    },
    async () => runMovia(() => moviaRequest("/diagnostics", { timeoutMs: 5_000 }))
  );

  server.registerTool(
    "movia_events",
    {
      title: "Movia events",
      description: "Read Movia's bounded agent event ring buffer.",
      inputSchema: {
        limit: z.number().int().min(1).max(1000).default(100)
      },
      annotations: readAnnotations
    },
    async ({ limit }) => runMovia(() => moviaRequest(`/events?limit=${encodeURIComponent(String(limit))}`, { timeoutMs: 3_000 }))
  );

  server.registerTool(
    "movia_capabilities",
    {
      title: "Movia capabilities",
      description: "Discover Movia's current agent-native capabilities.",
      inputSchema: {},
      annotations: readAnnotations
    },
    async () => runMovia(() => moviaRequest("/capabilities", { timeoutMs: 3_000 }))
  );

  server.registerTool(
    "movia_manifest",
    {
      title: "Movia agent manifest",
      description: "Read Movia's agent API manifest, schema version, actions and headless bootstrap metadata.",
      inputSchema: {},
      annotations: readAnnotations
    },
    async () => runMovia(() => moviaRequest("/manifest", { timeoutMs: 3_000 }))
  );

  server.registerTool(
    "movia_actions",
    {
      title: "Movia actions",
      description: "List stable Movia action IDs, safety classes, availability and argument schemas.",
      inputSchema: {},
      annotations: readAnnotations
    },
    async () => runMovia(() => moviaRequest("/actions", { timeoutMs: 3_000 }))
  );

  server.registerTool(
    "movia_ui",
    {
      title: "Movia logical UI",
      description: "Read Movia's logical UI tree or stable control manifest without screenshots/UIAutomator.",
      inputSchema: {
        manifest: z.boolean().default(false)
      },
      annotations: readAnnotations
    },
    async ({ manifest }) => runMovia(() => moviaRequest(manifest ? "/ui/controls" : "/ui", { timeoutMs: 3_000 }))
  );

  server.registerTool(
    "movia_streams",
    {
      title: "Movia streams",
      description: "Read stable stream IDs grouped by quality/voice and current requested/active selection.",
      inputSchema: {},
      annotations: readAnnotations
    },
    async () => runMovia(() => moviaRequest("/streams", { timeoutMs: 3_000 }))
  );

  server.registerTool(
    "movia_settings",
    {
      title: "Movia settings",
      description: "Read all machine-keyed Movia settings, values, defaults and allowed values.",
      inputSchema: {},
      annotations: readAnnotations
    },
    async () => runMovia(() => moviaRequest("/settings", { timeoutMs: 3_000 }))
  );

  server.registerTool(
    "movia_operation",
    {
      title: "Movia operation",
      description: "Read completion/failure state for one accepted asynchronous Movia operation.",
      inputSchema: {
        operationId: z.string().min(1).max(200)
      },
      annotations: readAnnotations
    },
    async ({ operationId }) =>
      runMovia(() => moviaRequest(`/operations?operationId=${encodeURIComponent(operationId)}`, { timeoutMs: 3_000 }))
  );

  server.registerTool(
    "movia_operation_poll",
    {
      title: "Poll Movia operation",
      description: "Poll one asynchronous Movia operation until it completes or a bounded wait expires.",
      inputSchema: {
        operationId: z.string().min(1).max(200),
        waitSeconds: z.number().min(0).max(30).default(0),
        intervalMs: z.number().int().min(100).max(2000).default(250)
      },
      annotations: readAnnotations
    },
    async ({ operationId, waitSeconds, intervalMs }) => runMovia(() => pollOperation(operationId, waitSeconds, intervalMs))
  );

  server.registerTool(
    "movia_catalog_query",
    {
      title: "Query Movia catalog",
      description: "Search Movia's catalog directly over the loopback agent without opening Catalog UI.",
      inputSchema: {
        query: z.string().min(1).max(500),
        limit: z.number().int().min(1).max(50).default(20)
      },
      annotations: openReadAnnotations
    },
    async ({ query, limit }) => runMovia(() => moviaActionCompat(ACTIONS.catalogQuery, { query, limit }))
  );

  server.registerTool(
    "movia_search",
    {
      title: "Search Movia",
      description: "Compatibility alias for movia_catalog_query.",
      inputSchema: {
        query: z.string().min(1).max(500),
        limit: z.number().int().min(1).max(50).default(20)
      },
      annotations: openReadAnnotations
    },
    async ({ query, limit }) => runMovia(() => moviaActionCompat(ACTIONS.catalogSearch, { query, limit }))
  );

  server.registerTool(
    "movia_people_search",
    {
      title: "Search Movia people",
      description: "Search Movia people and credits directly without opening Catalog UI.",
      inputSchema: {
        query: z.string().min(1).max(500),
        limit: z.number().int().min(1).max(50).default(20)
      },
      annotations: openReadAnnotations
    },
    async ({ query, limit }) => runMovia(() => moviaActionCompat(ACTIONS.peopleSearch, { query, limit }))
  );

  server.registerTool(
    "movia_media_details",
    {
      title: "Movia media details",
      description: "Read full Movia media metadata by mediaId or title without opening Details UI.",
      inputSchema: {
        mediaId: z.string().min(1).max(500).optional(),
        title: z.string().min(1).max(1000).optional()
      },
      annotations: openReadAnnotations
    },
    async ({ mediaId, title }) =>
      runMovia(async () => {
        if (!mediaId && !title) throw new MoviaBridgeError("MOVIA_INVALID_ARGUMENT", "mediaId or title is required");
        return moviaActionCompat(ACTIONS.mediaDetails, { mediaId, title });
      })
  );

  server.registerTool(
    "movia_play",
    {
      title: "Play Movia media",
      description: "Start or resume Movia playback through the native agent; no UI automation or Shizuku is used.",
      inputSchema: {
        mediaId: z.string().min(1).max(500).optional(),
        title: z.string().min(1).max(1000).optional(),
        season: z.number().int().min(1).max(999).optional(),
        episode: z.number().int().min(1).max(9999).optional(),
        quality: z.string().min(1).max(100).optional(),
        voice: z.string().min(1).max(100).optional(),
        streamId: z.string().min(1).max(500).optional(),
        resume: z.boolean().default(true),
        persist: z.boolean().optional(),
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: stateMutationAnnotations
    },
    async ({ mediaId, title, season, episode, quality, voice, streamId, resume, persist, requestId }) =>
      runMovia(() => moviaActionCompat(
        ACTIONS.play,
        { mediaId, title, season, episode, quality, voice, streamId, resume, persist },
        requestId
      ))
  );

  server.registerTool(
    "movia_pause",
    {
      title: "Pause Movia playback",
      description: "Pause Movia playback through the native agent; no UI automation or Shizuku is used.",
      inputSchema: {
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: stateMutationAnnotations
    },
    async ({ requestId }) => runMovia(() => moviaActionCompat(ACTIONS.pause, {}, requestId))
  );

  server.registerTool(
    "movia_seek",
    {
      title: "Seek Movia playback",
      description: "Seek Movia playback to a millisecond position through the native agent.",
      inputSchema: {
        positionMs: z.number().int().min(0).max(604_800_000),
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: stateMutationAnnotations
    },
    async ({ positionMs, requestId }) =>
      runMovia(() => moviaActionCompat(ACTIONS.seek, { positionMs }, requestId))
  );

  server.registerTool(
    "movia_select_stream",
    {
      title: "Select Movia stream",
      description: "Select a stable Movia stream ID directly through the native agent.",
      inputSchema: {
        streamId: z.string().min(1).max(500),
        persist: z.boolean().optional(),
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: stateMutationAnnotations
    },
    async ({ streamId, persist, requestId }) =>
      runMovia(() => moviaActionCompat(ACTIONS.streamSelect, { streamId, persist }, requestId))
  );

  server.registerTool(
    "movia_select_quality",
    {
      title: "Select Movia quality",
      description: "Select a Movia stream quality by its stable quality value through the native agent.",
      inputSchema: {
        quality: z.string().min(1).max(100),
        persist: z.boolean().optional(),
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: stateMutationAnnotations
    },
    async ({ quality, persist, requestId }) =>
      runMovia(() => moviaActionCompat(ACTIONS.qualitySelect, { quality, persist }, requestId))
  );

  server.registerTool(
    "movia_select_voice",
    {
      title: "Select Movia voice",
      description: "Select a Movia stream voice or language by its stable voice value through the native agent.",
      inputSchema: {
        voice: z.string().min(1).max(100),
        persist: z.boolean().optional(),
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: stateMutationAnnotations
    },
    async ({ voice, persist, requestId }) =>
      runMovia(() => moviaActionCompat(ACTIONS.voiceSelect, { voice, persist }, requestId))
  );

  server.registerTool(
    "movia_my_list",
    {
      title: "Read Movia My List",
      description: "Read Movia library and My List state directly through the native agent.",
      inputSchema: {
        limit: z.number().int().min(1).max(100).default(50),
        offset: z.number().int().min(0).max(100_000).default(0)
      },
      annotations: readAnnotations
    },
    async ({ limit, offset }) => runMovia(() => moviaActionCompat(ACTIONS.myList, { limit, offset }))
  );

  server.registerTool(
    "movia_my_list_add",
    {
      title: "Add to Movia My List",
      description: "Add a media item to Movia My List directly through the native agent.",
      inputSchema: {
        mediaId: z.string().min(1).max(500),
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: stateMutationAnnotations
    },
    async ({ mediaId, requestId }) =>
      runMovia(() => moviaActionCompat(ACTIONS.myListAdd, { mediaId, enabled: true }, requestId))
  );

  server.registerTool(
    "movia_my_list_remove",
    {
      title: "Remove from Movia My List",
      description: "Remove a media item from Movia My List directly through the native agent.",
      inputSchema: {
        mediaId: z.string().min(1).max(500),
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: stateMutationAnnotations
    },
    async ({ mediaId, requestId }) =>
      runMovia(() => moviaActionCompat(ACTIONS.myListRemove, { mediaId, enabled: false }, requestId))
  );

  server.registerTool(
    "movia_download_enqueue",
    {
      title: "Enqueue Movia download",
      description: "Enqueue a native Movia download without opening the UI or requiring Shizuku.",
      inputSchema: {
        mediaId: z.string().min(1).max(500).optional(),
        title: z.string().min(1).max(1000).optional(),
        wifiOnly: z.boolean().optional(),
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: sideEffectAnnotations
    },
    async ({ mediaId, title, wifiOnly, requestId }) =>
      runMovia(() => moviaActionCompat(ACTIONS.downloadEnqueue, { mediaId, title, wifiOnly }, requestId))
  );

  server.registerTool(
    "movia_download_status",
    {
      title: "Read Movia download status",
      description: "Read one or more native Movia download records without opening the UI.",
      inputSchema: {
        title: z.string().min(1).max(1000)
      },
      annotations: readAnnotations
    },
    async ({ title }) =>
      runMovia(() => moviaActionCompat(ACTIONS.downloadStatus, { title }))
  );

  server.registerTool(
    "movia_download_delete",
    {
      title: "Delete Movia download",
      description: "Delete a native Movia download record and its local media. Requires MOVIA_DOWNLOAD_DELETE.",
      inputSchema: {
        confirm: z.literal("MOVIA_DOWNLOAD_DELETE"),
        title: z.string().min(1).max(1000),
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: deleteAnnotations
    },
    async ({ title, requestId }) =>
      runMovia(() => moviaActionCompat(ACTIONS.downloadDelete, { title }, requestId))
  );

  server.registerTool(
    "movia_settings_set",
    {
      title: "Set Movia setting",
      description: "Set one machine-keyed Movia setting directly through the native agent.",
      inputSchema: {
        key: z.string().min(1).max(200),
        value: z.union([
          z.string().max(2_000),
          z.number().refine(value => Number.isFinite(value), "value must be finite"),
          z.boolean(),
          z.null()
        ]),
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: stateMutationAnnotations
    },
    async ({ key, value, requestId }) =>
      runMovia(() => moviaActionCompat(ACTIONS.settingsSet, { key, value }, requestId))
  );

  server.registerTool(
    "movia_action",
    {
      title: "Execute Movia action",
      description: "Execute one stable Movia domain/presentation action. Normal domain actions do not open the Activity or require Shizuku. Requires MOVIA_ACTION confirmation.",
      inputSchema: {
        confirm: z.literal("MOVIA_ACTION"),
        action: z.string().min(1).max(200),
        arguments: z.record(z.string(), z.unknown()).default({}),
        requestId: z.string().min(1).max(200).optional()
      },
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: false
      }
    },
    async ({ action, arguments: args, requestId }) =>
      runMovia(() => moviaAction(action, args, requestId))
  );
}
