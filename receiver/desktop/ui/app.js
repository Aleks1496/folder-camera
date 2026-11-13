/* Bundled local UI. Privileged actions use native per-window bindings only. */
const $ = (id) => document.getElementById(id);
let model, folder, busy = false;
const api = () => globalThis.bindings ?? globalThis.receiverPreview;
const receiverErrors = {
  "Connect to Wi-Fi or Ethernet to receive photos.":
    "Подключитесь к Wi-Fi или Ethernet, чтобы принимать снимки.",
  "Connect to a private Wi-Fi or Ethernet network to receive photos.":
    "Подключитесь к локальной сети Wi-Fi или Ethernet, чтобы принимать снимки.",
  "Start the receiver before pairing a phone.":
    "Запустите приёмник, прежде чем подключать телефон.",
  "No available receiver port": "Нет свободного порта для приёмника.",
  "State and photo root must be independent directories":
    "Для снимков и настроек приёмника нужны отдельные папки.",
  "Receiver state is in use; stop the running receiver before offline management.":
    "Настройки уже используются. Закройте другой экземпляр приёмника.",
  "Existing uploads from an older receiver were found. Start once with --root YOUR_EXISTING_PHOTO_FOLDER; it will be remembered automatically.":
    "Найдены снимки старой версии. Один раз запустите приёмник с параметром --root и путём к прежней папке снимков.",
};
function errorText(value, fallback = "Не удалось выполнить действие. Подробности в журнале.") {
  if (!value) return fallback;
  if (receiverErrors[value]) return receiverErrors[value];
  if (/[а-яё]/i.test(value) && !/^[a-z]/i.test(value)) return value;
  console.error(value);
  return fallback;
}
function message(text) {
  $("message").textContent = text ?? "";
  $("message").hidden = !text;
}
async function action(fn) {
  if (busy) return;
  busy = true;
  document.body.classList.add("busy");
  try {
    message(null);
    const result = await fn();
    if (result?.status) render(result);
  } catch (error) {
    message(errorText(error?.message ?? String(error)));
  } finally {
    busy = false;
    document.body.classList.remove("busy");
  }
}
function text(tag, value, className) {
  const e = document.createElement(tag);
  e.textContent = value;
  if (className) e.className = className;
  return e;
}
function render(state) {
  model = state;
  const ready = state.status === "ready";
  $("status-dot").className = "dot " +
    (ready ? "ready" : state.status === "error" ? "error" : "");
  $("status-title").textContent = ready
    ? "Готов к приёму"
    : state.status === "starting"
    ? "Приёмник запускается…"
    : state.status === "pausing"
    ? "Завершаем передачу…"
    : state.status === "error"
    ? "Ошибка подключения"
    : "Приём приостановлен";
  $("status-subtitle").textContent = state.active
    ? `Получаем снимки: ${state.active}`
    : ready
    ? "Ожидание снимков"
    : state.error ? errorText(state.error, "Не удалось запустить приёмник. Проверьте сеть и доступ к папке.") : "Снимки и подключения сохранены";
  $("power").textContent = ready ? "Приостановить" : "Начать приём";
  $("power").disabled = !state.initialized ||
    ["starting", "pausing"].includes(state.status);
  $("root").textContent = state.root ??
    "Выберите папку для сохранения снимков.";
  $("folder-name").textContent = state.root
    ? state.root.replace(/[\\/]+$/, "").split(/[\\/]/).pop()
    : "Выберите папку";
  $("active").textContent = state.active
    ? `Активных передач: ${state.active}`
    : "Нет активных передач";
  $("connection").textContent = state.endpoint
    ? `${state.endpoint} · Шифрование при передаче`
    : "Локальное хранение · Шифрование при передаче";
  $("qr").hidden = !state.qr;
  $("qr-empty").hidden = !!state.qr;
  if (state.qr) $("qr").src = "data:image/svg+xml;base64," + btoa(state.qr);
  $("qr-hint").textContent = !ready
    ? "Запустите приём, чтобы подключить телефон."
    : state.devices?.length
    ? "Телефон подключён. При необходимости можно добавить другой."
    : "Создайте код для подключения телефона.";
  $("pair").disabled = !ready;
  $("pair").textContent = state.pairing
    ? "Обновить код подключения"
    : state.devices?.length
    ? "Подключить другой телефон"
    : "Создать код подключения";
  $("expiry").textContent = state.pairing
    ? `Одноразовый код · действует ещё ${
      Math.max(0, Math.ceil((state.pairing.expiresAt - Date.now()) / 60000))
    } мин`
    : "Телефон и компьютер должны быть в одной локальной сети.";
  $("phone-count").textContent = `Телефонов: ${state.devices?.length ?? 0}`;
  $("devices").replaceChildren();
  if (!state.devices?.length) {
    $("devices").append(
      text("p", "Здесь появятся подключённые телефоны.", "muted"),
    );
  }
  for (const device of state.devices ?? []) {
    const row = text("div", "", "device"),
      info = text("div", "", "device-text");
    info.append(
      text("strong", device.name || "Телефон"),
      text(
        "small",
        "Подключён · " + new Date(device.createdAt).toLocaleDateString("ru-RU"),
      ),
    );
    const revoke = text("button", "Отключить", "quiet");
    revoke.onclick = () => {
      if (
        confirm(
          `Отключить ${
            device.name || "этот телефон"
          }? Сохранённые снимки останутся на своих местах.`,
        )
      ) action(() => api().revoke(device.id));
    };
    row.append(text("span", "▣", "arrival-icon"), info, revoke);
    $("devices").append(row);
  }
  if (state.recent?.length) {
    $("arrivals").replaceChildren();
    for (const photo of state.recent) {
      const row = text("div", "", "arrival"),
        info = text("div", "", "arrival-text");
      info.append(
        text("strong", photo.filename),
        text("small", photo.relativePath),
      );
      row.append(
        text("span", "✓", "arrival-icon"),
        info,
        text(
          "time",
          new Date(photo.receivedAt).toLocaleTimeString("ru-RU", {
            hour: "2-digit",
            minute: "2-digit",
          }),
        ),
      );
      $("arrivals").append(row);
    }
  }
  $("autostart").checked = !!state.autostart;
  $("close-to-tray").checked = !!state.closeToTray;
  $("tray-setting").hidden = !state.tray;
  $("open").disabled = !state.root;
  if (state.error && !busy) message(errorText(state.error, "Не удалось запустить приёмник. Проверьте сеть и доступ к папке."));
}
async function browse(path) {
  const result = await api().browseFolders(path);
  folder = result.path;
  $("browse-path").value = folder;
  $("parent").disabled = result.parent === folder;
  $("parent").onclick = () => action(() => browse(result.parent));
  $("folder-list").replaceChildren();
  for (const child of result.folders) {
    const button = text("button", "▱  " + child.name, "folder-row");
    button.onclick = () => action(() => browse(child.path));
    $("folder-list").append(button);
  }
  if (!result.folders.length) {
    $("folder-list").append(
      text("p", "Вложенных папок нет. Можно использовать эту папку.", "muted"),
    );
  }
}
$("choose").onclick = () =>
  action(async () => {
    const result = await api().chooseFolder();
    if (result?.fallback) {
      await browse(model.root ?? undefined);
      $("folder-dialog").showModal();
    } else if (result?.status) return result;
  });
$("browse-go").onclick = () => action(() => browse($("browse-path").value));
$("browse-path").onkeydown = (event) => {
  if (event.key === "Enter") {
    event.preventDefault();
    action(() => browse($("browse-path").value));
  }
};
$("use-folder").onclick = () =>
  action(async () => {
    const result = await api().setRoot(folder);
    $("folder-dialog").close();
    return result;
  });
$("open").onclick = () => action(() => api().openFolder());
$("pair").onclick = () => action(() => api().pair());
$("power").onclick = () =>
  action(() => model.status === "ready" ? api().stop() : api().start());
$("settings-button").onclick = () => $("settings-dialog").showModal();
$("autostart").onchange = () =>
  action(() => api().setAutostart($("autostart").checked));
$("close-to-tray").onchange = () =>
  action(() => api().setCloseToTray($("close-to-tray").checked));
$("quit").onclick = () => action(() => api().quit());
async function refresh() {
  try {
    if (api()) render(await api().snapshot());
  } catch (e) {
    message(errorText(e?.message, "Не удалось связаться с приёмником"));
  }
}
refresh();
setInterval(refresh, 1500);

$("licenses").onclick = () =>
  action(async () => {
    $("licenses-text").textContent = await (await fetch("licenses.txt")).text();
    $("licenses-dialog").showModal();
  });

async function checkForUpdate() {
  if (!api()?.checkForUpdate) return;
  try {
    const tag = await api().checkForUpdate();
    $("download-update").hidden = !tag;
    $("update-status").textContent = tag
      ? `Доступно обновление: ${tag}. Для установки приёмник перезапустится.`
      : "Обновлений нет";
  } catch {
    $("update-status").textContent = "Не удалось проверить обновления";
  }
}
$("check-update").onclick = checkForUpdate;
$("download-update").onclick = () => action(() => api().downloadUpdate());
setTimeout(checkForUpdate, 3000);
setInterval(checkForUpdate, 6 * 60 * 60 * 1000);
