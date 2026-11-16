import { createInterface } from "node:readline";
import { join } from "node:path";
// @ts-types="qrcode-types"
import QRCode from "qrcode";
import { durableJson, readJson } from "../src/files.ts";
import { Receiver } from "../src/receiver.ts";
import { stateDirectory } from "../src/state-directory.ts";

const options = new Map<string, string>();
for (let i = 0; i < Deno.args.length; i += 2) {
  const [key, value] = Deno.args.slice(i, i + 2);
  if (!["--state", "--root", "--bind", "--port"].includes(key) || !value) {
    throw new Error("Invalid receiver argument");
  }
  options.set(key, value);
}
const state = stateDirectory(options.get("--state"), {
  platform: process.platform,
  localAppData: Deno.env.get("LOCALAPPDATA"),
});
const settingsPath = join(state, "desktop-settings.json");
const receiver = new Receiver(state, options);
let input: ReturnType<typeof createInterface> | undefined;
let preferences = { version: 1, closeToTray: false };
let pairingSecret: string | undefined;
let qr: string | null = null;
let refresh: ReturnType<typeof setInterval> | undefined;

async function view() {
  const { pairing, devices, recent, error, status, endpoint, root, active } =
    receiver.snapshot();
  if (pairing?.secret !== pairingSecret) {
    qr = pairing
      ? (await QRCode.toBuffer(JSON.stringify(pairing), { margin: 4 }))
        .toString(
          "base64",
        )
      : null;
    pairingSecret = pairing?.secret;
  }
  return {
    status,
    error,
    endpoint,
    root,
    active,
    qr,
    closeToTray: preferences.closeToTray,
    devices: devices.map(({ id, name }) => ({ id, name })),
    recent: recent.map(({ filename, relativePath, root }) => ({
      filename,
      relativePath,
      root,
    })),
  };
}

try {
  await receiver.init();
  preferences = await readJson<typeof preferences>(settingsPath) ?? preferences;
  await receiver.start().catch(() => {});
  refresh = setInterval(
    () => void receiver.refreshNetwork().catch(() => {}),
    5000,
  );
  input = createInterface({ input: process.stdin, crlfDelay: Infinity });
  for await (const line of input) {
    try {
      const { action, value } = JSON.parse(line);
      switch (action) {
        case "status":
          break;
        case "start":
          await receiver.start();
          break;
        case "stop":
          await receiver.stop();
          break;
        case "pair":
          await receiver.pair();
          break;
        case "root":
          if (typeof value !== "string" || !value || value.length > 4096) {
            throw new Error("Недопустимая папка для снимков.");
          }
          await receiver.setRoot(value);
          break;
        case "revoke":
          if (typeof value !== "string") {
            throw new Error("Недопустимый телефон.");
          }
          await receiver.revoke(value);
          break;
        case "closeToTray":
          if (typeof value !== "boolean") {
            throw new Error("Недопустимая настройка.");
          }
          await durableJson(settingsPath, {
            ...preferences,
            closeToTray: value,
          });
          preferences.closeToTray = value;
          break;
        default:
          throw new Error("Неизвестная команда приёмника.");
      }
      console.log(JSON.stringify({ result: await view() }));
    } catch (error) {
      console.log(
        JSON.stringify({
          error: error instanceof Error ? error.message : String(error),
        }),
      );
    }
  }
} catch (error) {
  console.log(
    JSON.stringify({
      error: error instanceof Error ? error.message : String(error),
    }),
  );
  Deno.exitCode = 1;
} finally {
  clearInterval(refresh);
  input?.close();
  process.stdin.pause();
  await receiver.close();
}
