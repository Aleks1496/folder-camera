import { basename, dirname, join } from "node:path";
import { homedir } from "node:os";
import { Receiver } from "../src/receiver.ts";
import { stateDirectory } from "../src/state-directory.ts";
import { durableJson, readJson } from "../src/files.ts";
import { updateTag } from "./build-version.ts";
import { availableUpdate, downloadUpdate } from "./updates.ts";
import * as system from "./system.ts";
import { controlArrival, controlDevice, controlHandler } from "./control.ts";
interface NativeWindow extends EventTarget {
  bind(name: string, handler: (...args: unknown[]) => unknown): void;
  show(): void;
  hide(): void;
  close(): void;
  isClosed(): boolean;
  focus(): void;
  navigate(url: string): void;
  executeJs(code: string): Promise<unknown>;
}
interface NativeTray extends EventTarget {
  setIcon(bytes: Uint8Array): void;
  setTooltip(text: string): void;
  setMenu(items: unknown[]): void;
  destroy(): void;
}
const native = Deno as unknown as {
  BrowserWindow: new (options: Record<string, unknown>) => NativeWindow;
  Tray: new () => NativeTray;
};
const assets = new URL("./ui/", import.meta.url);
const files: Record<string, string> = {
  "/": "text/html",
  "/index.html": "text/html",
  "/app.js": "text/javascript",
  "/app.css": "text/css",
  "/icon.png": "image/png",
  "/licenses.txt": "text/plain; charset=utf-8",
};
const csp =
  "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
// The first server belongs to the GUI. Later Deno.serve listeners retain the LAN address/port.
const uiServer = Deno.serve({ hostname: "127.0.0.1", onListen() {} }, async (request) => {
  const path = new URL(request.url).pathname;
  if (request.method !== "GET" || !files[path]) {
    return new Response("Not found", { status: 404 });
  }
  return new Response(
    await Deno.readFile(
      new URL(path === "/" ? "index.html" : path.slice(1), assets),
    ),
    {
      headers: {
        "Content-Type": files[path],
        "Content-Security-Policy": csp,
        "X-Content-Type-Options": "nosniff",
        "Cache-Control": "no-store",
      },
    },
  );
});
// Headless runs the receiver with no window and no tray; other apps use the loopback control API.
const headless = Deno.args.includes("--headless");
const windowOptions = {
  title: "Приёмник Folder Camera",
  width: 1100,
  height: 840,
};
let window = headless ? undefined : new native.BrowserWindow(windowOptions);
let backgroundWindow: NativeWindow | undefined;
const bindings = new Map<string, (...args: unknown[]) => unknown>();
function bind(name: string, handler: (...args: unknown[]) => unknown) {
  bindings.set(name, handler);
  window?.bind(name, handler);
}
function showWindow() {
  if (headless || quitting) return;
  if (!window || window.isClosed()) {
    window = new native.BrowserWindow(windowOptions);
    for (const [name, handler] of bindings) window.bind(name, handler);
    watchWindowClose(window);
    window.navigate(`http://127.0.0.1:${(uiServer.addr as Deno.NetAddr).port}/`);
  }
  window?.show();
  window?.focus();
}
function keepReceiverInTray(enabled: boolean) {
  if (enabled && tray && !backgroundWindow) {
    // Deno 2.9.7 closes the native window even when JS handles the close event.
    // Keep a hidden window so closing the last visible window cannot end the event loop.
    backgroundWindow = new native.BrowserWindow({
      width: 1,
      height: 1,
      frameless: true,
      noActivate: true,
      opacity: 0,
    });
    backgroundWindow.hide();
  } else if (!enabled && backgroundWindow) {
    backgroundWindow.close();
    backgroundWindow = undefined;
  }
}
function watchWindowClose(current: NativeWindow) {
  current.addEventListener("close", () => {
    if (quitting || current !== window) return;
    window = undefined;
    if (!backgroundWindow || !tray) void quit();
  });
}
const values = new Map<string, string>();
for (let i = 0; i < Deno.args.length; i++) {
  if (
    ["--state", "--root", "--bind", "--port"].includes(Deno.args[i]) &&
    Deno.args[i + 1]
  ) values.set(Deno.args[i], Deno.args[++i]);
  else if (
    !["--background", "--headless", "--exit-with-stdin", "--smoke"].includes(
      Deno.args[i],
    )
  ) {
    throw new Error("Invalid desktop argument");
  }
}
if (
  Deno.args.includes("--smoke") &&
  (!values.has("--state") || !values.has("--root") ||
    values.get("--bind") !== "127.0.0.1")
) {
  throw new Error(
    "Smoke checks require an explicit isolated state/root and loopback bind",
  );
}
let state = stateDirectory(values.get("--state"), {
  platform: Deno.build.os === "windows" ? "win32" : Deno.build.os,
  home: Deno.env.get("HOME"),
  xdgStateHome: Deno.env.get("XDG_STATE_HOME"),
  localAppData: Deno.env.get("LOCALAPPDATA"),
});
if (Deno.build.os === "darwin" && !values.has("--state")) {
  const legacy = join(homedir(), ".local", "state", "folder-camera");
  if (
    await Deno.stat(join(legacy, "identity.json")).then(() => true).catch(() =>
      false
    )
  ) state = legacy;
}
let receiver = new Receiver(state, values),
  initialized = false,
  startupError: string | null = null;
let preferences = { version: 1, closeToTray: false };
let login = false, tray: NativeTray | undefined;
let activation: Deno.HttpServer | undefined;
let activationToken: string | undefined;
let networkRefresh: ReturnType<typeof setInterval> | undefined = undefined;
const initial = {
  version: 1,
  status: "starting",
  root: null,
  endpoint: null,
  receiverId: null,
  fingerprint: null,
  active: 0,
  devices: [],
  recent: [],
  pairing: null,
  qr: null,
};
function snapshot() {
  return {
    ...(initialized ? receiver.snapshot() : {
      ...initial,
      status: startupError ? "error" : "starting",
      error: startupError,
    }),
    initialized,
    autostart: login,
    closeToTray: preferences.closeToTray,
    tray: !!tray,
  };
}
async function activateExisting() {
  for (let attempt = 0; attempt < 8; attempt++) {
    try {
      const previous = await readJson<{ port: number; token: string }>(
        join(state, "desktop-instance.json"),
      );
      if (
        previous && Number.isInteger(previous.port) && previous.port > 0 &&
        previous.port <= 65535 && /^[0-9a-f]{64}$/.test(previous.token)
      ) {
        const response = await fetch(
          `http://127.0.0.1:${previous.port}/activate`,
          {
            method: "POST",
            headers: { Authorization: "Bearer " + previous.token },
            signal: AbortSignal.timeout(800),
          },
        );
        if (response.ok) return true;
      }
    } catch {
      /* A CLI-only profile or a starting GUI has no activation listener yet. */
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  return false;
}
async function boot() {
  try {
    await receiver.init();
    initialized = true;
    preferences = await readJson<typeof preferences>(
      join(state, "desktop-settings.json"),
    ) ?? preferences;
    keepReceiverInTray(preferences.closeToTray);
    login = await system.autostartEnabled();
    await receiver.start().catch(() => {});
    if (!activation) {
      activationToken = [...crypto.getRandomValues(new Uint8Array(32))].map(
        (n) => n.toString(16).padStart(2, "0"),
      ).join("");
      activation = Deno.serve(
        { hostname: "127.0.0.1", port: 0, onListen() {} },
        controlHandler(activationToken, {
          activate: showWindow,
          pair: async () =>
            initialized ? (await receiver.pair()).pairing : null,
          status() {
            const current = snapshot();
            return {
              version: 1,
              status: current.status,
              error: current.error ?? null,
              address: current.endpoint,
              root: current.root,
              devices: current.devices.length,
              pairing: current.pairing
                ? { expiresAt: current.pairing.expiresAt }
                : null,
            };
          },
          devices: () =>
            initialized ? receiver.snapshot().devices.map(controlDevice) : [],
          async revoke(id) {
            if (
              !initialized ||
              !receiver.auth.list().some((d) => d.id === id && !d.revoked)
            ) return false;
            await receiver.revoke(id);
            return true;
          },
          arrivals: () =>
            initialized ? receiver.snapshot().recent.map(controlArrival) : [],
          async start() {
            if (!initialized) {
              throw new Error(startupError ?? "Приёмник запускается");
            }
            await receiver.start();
          },
          async stop() {
            if (initialized) await receiver.stop();
          },
        }),
      );
      await durableJson(join(state, "desktop-instance.json"), {
        port: (activation.addr as Deno.NetAddr).port,
        token: activationToken,
      });
    }
  } catch (e) {
    startupError = e instanceof Error ? e.message : "Не удалось запустить приёмник";
    await receiver.close().catch(() => {});
    if (startupError.includes("in use") && await activateExisting()) {
      await quit();
      return;
    }
  }
}
let desktopUpdate: Awaited<ReturnType<typeof availableUpdate>> = null;
bind("checkForUpdate", async () => {
  desktopUpdate = Deno.build.os === "windows" && updateTag
    ? await availableUpdate(updateTag)
    : null;
  return desktopUpdate?.tag ?? null;
});
bind("downloadUpdate", async () => {
  if (!desktopUpdate || Deno.build.os !== "windows") return;
  const executable = Deno.execPath();
  const directory = dirname(executable);
  const name = basename(executable);
  if (!name.toLowerCase().endsWith(".exe")) throw new Error("Этот вариант установки приёмника не поддерживает обновления");
  for (const file of [".deno-desktop-app", "native.dll"]) await Deno.stat(join(directory, file));

  const staging = await Deno.makeTempDir({ dir: directory, prefix: ".folder-camera-update-" });
  try {
    await Deno.writeFile(join(staging, "update.zip"), await downloadUpdate(desktopUpdate));
    const script = [
      "$ErrorActionPreference = 'Stop'",
      "$stage = $env:FC_UPDATE_STAGE",
      "$root = $env:FC_UPDATE_ROOT",
      "$archive = Join-Path $stage 'update.zip'",
      "$new = Join-Path $stage 'new'",
      "$backup = Join-Path $stage 'backup'",
      "$oldExe = $env:FC_UPDATE_EXE",
      "Start-Transcript -LiteralPath (Join-Path $stage 'install.log') | Out-Null",
      "Add-Type -AssemblyName System.IO.Compression.FileSystem",
      "[IO.Compression.ZipFile]::ExtractToDirectory($archive, $new)",
      "$launchers = @(Get-ChildItem -LiteralPath $new -File -Filter '*.exe')",
      "if ($launchers.Count -ne 1) { throw 'Expected one Windows launcher' }",
      "$map = [ordered]@{'native.dll'='native.dll'; '.deno-desktop-app'='.deno-desktop-app'}",
      "$map[$oldExe] = $launchers[0].Name",
      "foreach ($file in $map.Keys) { if (!(Test-Path -LiteralPath (Join-Path $new $map[$file]) -PathType Leaf)) { throw ('Missing ' + $map[$file]) } }",
      "New-Item -ItemType Directory -Path $backup | Out-Null",
      "foreach ($file in $map.Keys) { Copy-Item -LiteralPath (Join-Path $root $file) -Destination (Join-Path $backup $file) -Force }",
      "Wait-Process -Id ([int]$env:FC_UPDATE_PID) -Timeout 15 -ErrorAction SilentlyContinue; if (Get-Process -Id ([int]$env:FC_UPDATE_PID) -ErrorAction SilentlyContinue) { throw 'Receiver did not exit' }",
      "try {",
      "  foreach ($file in $map.Keys) { Copy-Item -LiteralPath (Join-Path $new $map[$file]) -Destination (Join-Path $root $file) -Force }",
      "  Start-Process -FilePath (Join-Path $root $oldExe) -WorkingDirectory $root",
      "} catch {",
      "  foreach ($file in $map.Keys) { Copy-Item -LiteralPath (Join-Path $backup $file) -Destination (Join-Path $root $file) -Force }",
      "  Start-Process -FilePath (Join-Path $root $oldExe) -WorkingDirectory $root",
      "  throw",
      "}",
      "Stop-Transcript | Out-Null",
      "Remove-Item -LiteralPath $stage -Recurse -Force",
    ].join("\n");
    const child = new Deno.Command("powershell.exe", {
      args: ["-NoProfile", "-NonInteractive", "-Command", script],
      env: {
        FC_UPDATE_STAGE: staging,
        FC_UPDATE_ROOT: directory,
        FC_UPDATE_EXE: name,
        FC_UPDATE_PID: String(Deno.pid),
      },
      stdin: "null",
      stdout: "null",
      stderr: "null",
    }).spawn();
    child.unref();
  } catch (error) {
    await Deno.remove(staging, { recursive: true }).catch(() => {});
    throw error;
  }
  await quit();
});
bind("snapshot", snapshot);
bind("start", async () => {
  if (!initialized) throw new Error(startupError ?? "Приёмник запускается");
  await receiver.start();
  return snapshot();
});
bind("stop", async () => {
  await receiver.stop();
  return snapshot();
});
bind("pair", async () => {
  await receiver.pair();
  return snapshot();
});
bind("revoke", async (id) => {
  if (typeof id !== "string" || !/^[0-9a-f-]{36}$/.test(id)) {
    throw new Error("Недопустимый телефон");
  }
  await receiver.revoke(id);
  return snapshot();
});
bind("browseFolders", (path) => {
  if (path !== undefined && typeof path !== "string") {
    throw new Error("Недопустимая папка");
  }
  return system.browseFolders(path as string | undefined);
});
async function setRoot(root: unknown) {
  if (typeof root !== "string" || root.length > 4096) {
    throw new Error("Недопустимая папка для снимков");
  }
  if (!initialized) {
    values.set("--root", root);
    receiver = new Receiver(state, values);
    startupError = null;
    await boot();
    if (!initialized) {
      throw new Error(startupError ?? "Не удалось запустить приёмник");
    }
  } else await receiver.setRoot(root);
  return snapshot();
}
bind("setRoot", setRoot);
bind("chooseFolder", async () => {
  const choice = await system.chooseFolder(
    initialized ? receiver.settings.root : homedir(),
  );
  if (choice.path) return await setRoot(choice.path);
  return choice.fallback ? { fallback: true } : null;
});
bind("openFolder", async () => {
  if (!initialized) throw new Error("Сначала выберите папку");
  await system.openFolder(receiver.settings.root);
});
bind("setAutostart", async (enabled) => {
  if (typeof enabled !== "boolean") throw new Error("Недопустимая настройка автозапуска");
  await system.setAutostart(enabled);
  login = await system.autostartEnabled();
  return snapshot();
});
bind("setCloseToTray", async (enabled) => {
  if (typeof enabled !== "boolean" || !tray || !initialized) {
    throw new Error("Область уведомлений недоступна");
  }
  keepReceiverInTray(enabled);
  preferences.closeToTray = enabled;
  await durableJson(join(state, "desktop-settings.json"), preferences);
  return snapshot();
});
let quitting = false;
async function quit(code = 0) {
  if (quitting) return;
  quitting = true;
  const timeout = setTimeout(() => Deno.exit(code), 5000);
  try {
    await receiver.close();
  } finally {
    clearTimeout(timeout);
    if (activation) {
      await activation.shutdown().catch(() => {});
      await Deno.remove(join(state, "desktop-instance.json")).catch(() => {});
    }
    clearInterval(networkRefresh);
    await uiServer.shutdown().catch(() => {});
    tray?.destroy();
    if (window && !window.isClosed()) window.close();
    backgroundWindow?.close();
    if (Deno.build.os === "darwin") Deno.exitCode = code;
    else Deno.exit(code);
  }
}
bind("quit", () => quit());
if (!headless) {
  try {
    tray = new native.Tray();
    tray.setIcon(await Deno.readFile(new URL("icon.png", assets)));
    tray.setTooltip("Приёмник Folder Camera");
    tray.setMenu([
      { item: { label: "Открыть приёмник", id: "open", enabled: true } },
      { item: { label: "Выйти из приёмника", id: "quit", enabled: true } },
    ]);
    tray.addEventListener("menuclick", (event) => {
      const id = (event as CustomEvent<{ id: string }>).detail.id;
      if (id === "open") showWindow();
      if (id === "quit") void quit();
    });
    tray.addEventListener("click", showWindow);
    tray.addEventListener("dblclick", showWindow);
  } catch {
    tray?.destroy();
    tray = undefined;
  }
}
if (window) watchWindowClose(window);
if (Deno.args.includes("--background") && tray) window?.hide();
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  try {
    Deno.addSignalListener(signal, () => void quit());
  } catch { /* Windows only delivers SIGINT. */ }
}
// Owned by the app that started it: when that app's end of stdin closes (it quit, crashed or was killed), quit too.
if (Deno.args.includes("--exit-with-stdin")) {
  (async () => {
    try {
      for await (const _ of Deno.stdin.readable) { /* input is ignored */ }
    } catch { /* no stdin at all: nobody owns this receiver any more */ }
    void quit();
  })();
}
await boot();
if (!quitting && headless && !initialized) {
  // Nobody can see or fix a startup error without a window.
  console.error(startupError ?? "Не удалось запустить приёмник");
  await quit(1);
}
if (!quitting) {
  networkRefresh = setInterval(() => {
    if (initialized) void receiver.refreshNetwork();
  }, 5000);
}
if (!quitting && Deno.args.includes("--smoke")) {
  if (!initialized || receiver.snapshot().status !== "ready") {
    throw new Error(
      startupError ?? receiver.snapshot().error ?? "Desktop receiver not ready",
    );
  }
  let rendered = headless;
  for (let attempt = 0; window && attempt < 40; attempt++) {
    try {
      const value = await window.executeJs(
        "document.getElementById('status-title')?.textContent === '\\u0413\\u043e\\u0442\\u043e\\u0432 \\u043a \\u043f\\u0440\\u0438\\u0451\\u043c\\u0443' ? 'ready' : 'waiting'",
      );
      const actual = value && typeof value === "object" && "ok" in value &&
          "value" in value && value.ok === true
        ? value.value
        : value;
      rendered = actual === "ready";
    } catch { /* Wait for the bundled document. */ }
    if (rendered) break;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  if (!rendered) {
    console.error(
      "DESKTOP SMOKE FAIL: native receiver UI did not render the ready state",
    );
    await quit(1);
  }
  console.log(
    (headless
      ? "DESKTOP SMOKE PASS: headless control API, independent TLS listener and persistent receiver initialized; target="
      : "DESKTOP SMOKE PASS: native window, bindings, independent TLS listener and persistent receiver initialized; target=") +
      Deno.build.target,
  );
  setTimeout(() => void quit(), 1500);
}
