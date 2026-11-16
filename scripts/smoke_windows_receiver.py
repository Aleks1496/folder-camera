#!/usr/bin/env python3
"""Exercise the packaged receiver and Windows Forms tray with only loopback networking."""
import base64
import ctypes
from ctypes import wintypes
import hashlib
import http.client
import json
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PORT = 19445
TITLE = "Приёмник Folder Camera"
WM_SYSCOMMAND, SC_CLOSE = 0x0112, 0xF060
WM_TRAYMOUSEMESSAGE = 0x0800  # .NET Framework NotifyIcon's shell callback.
WM_LBUTTONUP, WM_LBUTTONDBLCLK, WM_RBUTTONUP = 0x0202, 0x0203, 0x0205
TOKEN = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")
RECEIVER_ID = str(uuid.uuid4())

user32 = ctypes.WinDLL("user32", use_last_error=True)
enumerator = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
user32.EnumWindows.argtypes = (enumerator, wintypes.LPARAM)
user32.EnumChildWindows.argtypes = (wintypes.HWND, enumerator, wintypes.LPARAM)
user32.GetWindowThreadProcessId.argtypes = (wintypes.HWND, ctypes.POINTER(wintypes.DWORD))
user32.GetWindowTextW.argtypes = (wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
user32.GetClassNameW.argtypes = (wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
user32.IsWindowVisible.argtypes = (wintypes.HWND,)
user32.PostMessageW.argtypes = (wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
user32.SendMessageTimeoutW.argtypes = (
    wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM,
    wintypes.UINT, wintypes.UINT, ctypes.POINTER(ctypes.c_size_t),
)
user32.SendMessageTimeoutW.restype = wintypes.LPARAM


def windows(pid, parent=None):
    found = []

    @enumerator
    def visit(handle, _):
        owner = wintypes.DWORD()
        user32.GetWindowThreadProcessId(handle, ctypes.byref(owner))
        if owner.value == pid:
            title = ctypes.create_unicode_buffer(256)
            if parent:
                user32.SendMessageTimeoutW(handle, 0x000D, len(title), ctypes.addressof(title),
                                           2, 200, ctypes.byref(ctypes.c_size_t()))
            else:
                user32.GetWindowTextW(handle, title, len(title))
            found.append((handle, title.value, bool(user32.IsWindowVisible(handle))))
        return True

    if parent:
        user32.EnumChildWindows(parent, visit, 0)
    else:
        user32.EnumWindows(visit, 0)
    return found


def receiver_window(pid):
    for hwnd, title, visible in windows(pid):
        if visible and title == TITLE:
            class_name = ctypes.create_unicode_buffer(256)
            user32.GetClassNameW(hwnd, class_name, len(class_name))
            if class_name.value == "#32770":
                raise RuntimeError("Receiver displayed a Windows error dialog")
            return hwnd
    return None


def wait_for(process, check, error):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Process exited early ({process.returncode}): {error}")
        if value := check():
            return value
        time.sleep(.1)
    raise RuntimeError(error)


def post(hwnd, message, wparam=0, lparam=0):
    if not user32.PostMessageW(hwnd, message, wparam, lparam):
        raise ctypes.WinError(ctypes.get_last_error())


def tray_click(pid, *messages):
    for handle, _, visible in windows(pid):
        if not visible:
            for message in messages:
                post(handle, WM_TRAYMOUSEMESSAGE, lparam=message)


def https(state, method, path, data=None, headers=None):
    context = ssl.create_default_context(cafile=str(state / "certificate.pem"))
    connection = http.client.HTTPSConnection("127.0.0.1", PORT, context=context, timeout=6)
    try:
        connection.request(method, path, data, {"Authorization": "Bearer " + TOKEN, **(headers or {})})
        response = connection.getresponse()
        body = json.loads(response.read())
        assert response.status == 200 or method == "PUT" and response.status == 201, body
        return body
    finally:
        connection.close()


def upload(state, destination):
    photo = (ROOT / "receiver/tests/fixtures/photo.jpg").read_bytes()
    photo_id = str(uuid.uuid4())
    metadata = {
        "version": 1, "photoId": photo_id, "filename": photo_id + ".jpg",
        "relativePath": "Съёмка/Проект", "mimeType": "image/jpeg",
        "byteSize": len(photo), "sha256": hashlib.sha256(photo).hexdigest(),
    }
    headers = {
        "Content-Type": "image/jpeg",
        "X-FolderCamera-Metadata": base64.urlsafe_b64encode(json.dumps(metadata).encode()).decode().rstrip("="),
    }
    https(state, "PUT", "/v1/photos/" + photo_id, photo, headers)
    assert https(state, "GET", "/v1/photos/" + photo_id)["photoId"] == photo_id
    assert [entry.name for entry in destination.iterdir() if entry.name.casefold() == "съёмка"] == ["СЪЁМКА"]
    assert [entry.name for entry in (destination / "СЪЁМКА").iterdir()] == ["ПРОЕКТ"]
    assert (destination / "СЪЁМКА" / "ПРОЕКТ" / metadata["filename"]).read_bytes() == photo


with tempfile.TemporaryDirectory(prefix="folder-camera-offline-") as temporary:
    work = Path(temporary)
    with zipfile.ZipFile(sys.argv[1]) as archive:
        assert not {"native.dll", ".deno-desktop-app"}.intersection(archive.namelist())
        archive.extractall(work)
    state = work / "профиль"
    state.mkdir()
    destination = work / "снимки"
    (destination / "СЪЁМКА" / "ПРОЕКТ").mkdir(parents=True)
    (state / "identity.json").write_text(json.dumps({
        "version": 1, "receiverId": RECEIVER_ID, "pending": None, "devices": [{
            "id": str(uuid.uuid4()), "name": "Offline smoke phone", "revoked": False,
            "tokenHash": hashlib.sha256(TOKEN.encode()).hexdigest(), "createdAt": 0,
        }],
    }), encoding="utf-8")
    (state / "desktop-settings.json").write_text('{"version":1,"closeToTray":true}', encoding="utf-8")
    flags = ["--state", str(state), "--root", str(destination), "--bind", "127.0.0.1", "--port", str(PORT)]
    engine_test = subprocess.run(
        [str(work / "FolderCameraEngine.exe"), *flags],
        input='{"action":"status"}\n{"action":"pair"}\n', capture_output=True, text=True,
        encoding="utf-8", timeout=20, check=True,
    )
    status, pairing = map(json.loads, engine_test.stdout.splitlines())
    assert status["result"]["status"] == "ready", status
    assert base64.b64decode(pairing["result"]["qr"]).startswith(b"\x89PNG\r\n\x1a\n"), pairing

    with subprocess.Popen([str(work / "FolderCameraReceiver.exe"), *flags]) as gui:
        try:
            handle = wait_for(gui, lambda: receiver_window(gui.pid), "Native receiver window did not appear")
            def ready():
                try:
                    return https(state, "GET", "/v1/health")["receiverId"] == RECEIVER_ID
                except OSError:
                    return False

            wait_for(gui, ready, "Native launcher did not start the local listener")
            # The first visible refresh has to load the persisted tray setting.
            wait_for(gui, lambda: any(text == "Готов к приёму" for _, text, _ in windows(gui.pid, handle)),
                     "Native UI did not render Ready")
            post(handle, WM_SYSCOMMAND, SC_CLOSE)
            wait_for(gui, lambda: receiver_window(gui.pid) is None, "Close did not hide the native window")
            upload(state, destination)
            tray_click(gui.pid, WM_LBUTTONUP)
            handle = wait_for(gui, lambda: receiver_window(gui.pid), "Tray click did not reopen the window")
            post(handle, WM_SYSCOMMAND, SC_CLOSE)
            wait_for(gui, lambda: receiver_window(gui.pid) is None, "Second close did not hide the native window")
            tray_click(gui.pid, WM_LBUTTONDBLCLK)
            handle = wait_for(gui, lambda: receiver_window(gui.pid), "Tray double-click did not reopen the window")
            post(handle, WM_SYSCOMMAND, SC_CLOSE)
            wait_for(gui, lambda: receiver_window(gui.pid) is None, "Third close did not hide the native window")
            tray_click(gui.pid, WM_RBUTTONUP)
            popup = wait_for(gui, lambda: next((hwnd for hwnd, _, shown in windows(gui.pid) if shown), None),
                             "Tray context menu did not open")
            post(popup, 0x0100, 0x1B)  # Escape
            tray_click(gui.pid, WM_LBUTTONUP)
            handle = wait_for(gui, lambda: receiver_window(gui.pid), "Window did not reopen after the tray menu")
            quit_button = next(hwnd for hwnd, text, _ in windows(gui.pid, handle) if text == "Выйти")
            post(quit_button, 0x00F5)  # BM_CLICK
            assert gui.wait(timeout=12) == 0
        finally:
            if gui.poll() is None:
                gui.kill()
                gui.wait()

print("PASS: portable Windows Forms build, offline QR, LAN upload while hidden and native tray callbacks")
