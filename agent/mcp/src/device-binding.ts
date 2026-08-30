import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const EXPECTED_MODEL = "24069PC21G";
const EXPECTED_DEVICE = "peridot";
const EXPECTED_PRODUCT = "peridot_ru";
const EXPECTED_HARDWARE = "qcom";
const EXPECTED_BOARD_PLATFORM = "pineapple";
const EXPECTED_HOME = "/data/data/com.termux/files/home";
const EXPECTED_PREFIX = "/data/data/com.termux/files/usr";
const EXPECTED_TERMUX_UID = 10736;
const EXPECTED_ANDROID_ID_SHA256 = "15e850cc70b808a266bac549e4941e76717b8d3c9c82460e81dbe51d866b16b4";
const EXPECTED_TOKEN_SHA256 = "043481833baf716c022c684fe31543f9c99aa58605adc36cead7a5cb6bdadc25";

const TOKEN_PATH = join(EXPECTED_HOME, ".config/jarvis-device-binding/device-token");
const RISH = join(EXPECTED_HOME, "shizuku_tmp/rish");
const RISH_DEX = join(EXPECTED_HOME, "shizuku_tmp/rish_shizuku.dex");

function sha256(data: string | Buffer): string {
  return createHash("sha256").update(data).digest("hex");
}

function getProp(name: string): string {
  const result = spawnSync("/system/bin/getprop", [name], {
    encoding: "utf8",
    timeout: 3_000,
    maxBuffer: 64 * 1024
  });
  if (result.status !== 0) throw new Error(`getprop_failed:${name}`);
  return String(result.stdout || "").trim();
}

function runRish(command: string): string {
  const result = spawnSync(RISH, ["-c", command], {
    encoding: "utf8",
    timeout: 8_000,
    maxBuffer: 256 * 1024,
    env: {
      ...process.env,
      RISH_APPLICATION_ID: "com.termux",
      RISH_DISH_PATH: RISH_DEX
    }
  });
  if (result.error) throw new Error(`rish_error:${result.error.message}`);
  if (result.status !== 0) throw new Error(`rish_exit:${String(result.status)}`);
  // Some rish/Shizuku versions place shell command output on stderr.
  return `${result.stdout || ""}\n${result.stderr || ""}`.trim();
}

function getAndroidIdHash(): string {
  const output = runRish("settings get secure android_id");
  const match = output.match(/\b[0-9a-fA-F]{16}\b/);
  if (!match) throw new Error("android_id_unavailable");
  return sha256(match[0].toLowerCase());
}

function assertShizukuShellIdentity(): void {
  const output = runRish("id");
  if (!/uid=2000\(shell\)/.test(output)) throw new Error("unexpected_shizuku_identity");
}

function assertFileMode(path: string, expected: number): void {
  const mode = statSync(path).mode & 0o777;
  if (mode !== expected) throw new Error(`unexpected_permissions:${path}:${mode.toString(8)}`);
}

export function assertDeviceBinding(): void {
  if (process.env.HOME !== EXPECTED_HOME) throw new Error("wrong_termux_home");
  if (process.env.PREFIX !== EXPECTED_PREFIX) throw new Error("wrong_termux_prefix");
  if (typeof process.getuid !== "function" || process.getuid() !== EXPECTED_TERMUX_UID) {
    throw new Error("wrong_termux_uid");
  }

  if (getProp("ro.product.model") !== EXPECTED_MODEL) throw new Error("wrong_model");
  if (getProp("ro.product.device") !== EXPECTED_DEVICE) throw new Error("wrong_device");
  if (getProp("ro.product.name") !== EXPECTED_PRODUCT) throw new Error("wrong_product");
  if (getProp("ro.hardware") !== EXPECTED_HARDWARE) throw new Error("wrong_hardware");
  if (getProp("ro.board.platform") !== EXPECTED_BOARD_PLATFORM) throw new Error("wrong_board_platform");

  assertFileMode(join(EXPECTED_HOME, ".config/jarvis-device-binding"), 0o700);
  assertFileMode(TOKEN_PATH, 0o600);
  const tokenHash = sha256(readFileSync(TOKEN_PATH));
  if (tokenHash !== EXPECTED_TOKEN_SHA256) throw new Error("binding_token_mismatch");

  assertShizukuShellIdentity();
  if (getAndroidIdHash() !== EXPECTED_ANDROID_ID_SHA256) throw new Error("wrong_android_id");
}

export function getDeviceBindingStatus(): {
  bound: boolean;
  model: string;
  device: string;
  reason: string | null;
} {
  try {
    assertDeviceBinding();
    return { bound: true, model: EXPECTED_MODEL, device: EXPECTED_DEVICE, reason: null };
  } catch (error) {
    return {
      bound: false,
      model: EXPECTED_MODEL,
      device: EXPECTED_DEVICE,
      reason: error instanceof Error ? error.message : String(error)
    };
  }
}
