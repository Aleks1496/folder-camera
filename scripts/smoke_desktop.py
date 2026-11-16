#!/usr/bin/env python3
"""Require a packaged native GUI to render Ready and shut down cleanly in a fixture profile."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('application', type=Path)
args = parser.parse_args()
app = args.application.resolve()
if not app.exists() and Path(str(app) + '.app').exists():
    app = Path(str(app) + '.app')
if app.is_dir():
    if (app / 'Contents/MacOS').exists():
        candidates = list(app.glob('Contents/MacOS/*'))
    elif os.name == 'nt':
        candidates = list(app.glob('*.exe'))
    else:
        candidates = [app / app.name]
    candidates = [path for path in candidates if path.is_file() and path.suffix not in ('.so', '.dll', '.dylib', '.png')]
    if len(candidates) != 1:
        raise SystemExit('Cannot identify native launcher: ' + ', '.join(str(path) for path in candidates))
    app = candidates[0]
work = ROOT / '.work/desktop-smoke' / str(uuid.uuid4())
work.mkdir(parents=True)
temp = ROOT / '.work/tmp'
temp.mkdir(parents=True, exist_ok=True)
command = [str(app), '--state', str(work / 'state'), '--root', str(work / 'photos'), '--bind', '127.0.0.1', '--port', '19445', '--smoke']
log = ROOT / '.work/desktop-native-smoke.log'
try:
    result = subprocess.run(command, env={**os.environ, 'TMPDIR': str(temp)}, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
except subprocess.TimeoutExpired as error:
    data = error.output or b''
    output = data.decode(errors='replace') if isinstance(data, bytes) else data
    log.write_text(output)
    raise SystemExit('Native GUI startup timed out; retained diagnostics:\n' + output[-6000:])
log.write_text(result.stdout)
if result.returncode or 'DESKTOP SMOKE PASS:' not in result.stdout:
    if sys.platform == 'darwin':
        time.sleep(5)
        reports = Path.home() / 'Library/Logs/DiagnosticReports'
        for report in sorted(reports.glob('*.ips'), key=lambda path: path.stat().st_mtime, reverse=True)[:4]:
            if 'gui-directory' in report.name.lower() or 'folder' in report.name.lower():
                shutil.copy2(report, ROOT / '.work' / report.name)
    raise SystemExit(f'Packaged GUI smoke failed (exit {result.returncode}); see {log}\n' + result.stdout[-6000:])
print('PASS: packaged native GUI rendered Ready, TLS receiver started, and process exited cleanly')

if os.name == 'nt':
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL('user32', use_last_error=True)
    enum_callback = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user32.EnumWindows.argtypes = (enum_callback, wintypes.LPARAM)
    user32.IsWindowVisible.argtypes = (wintypes.HWND,)
    user32.GetWindowThreadProcessId.argtypes = (wintypes.HWND, ctypes.POINTER(wintypes.DWORD))
    user32.GetWindowTextW.argtypes = (wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
    user32.PostMessageW.argtypes = (wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
    tray_state = work / 'tray-state'
    tray_state.mkdir()
    (tray_state / 'desktop-settings.json').write_text(
        json.dumps({'version': 1, 'closeToTray': True}), encoding='utf-8'
    )
    command = [
        str(app), '--state', str(tray_state), '--root', str(work / 'tray-photos'),
        '--bind', '127.0.0.1', '--port', '19445', '--exit-with-stdin',
    ]

    def visible_receiver_windows(pid):
        handles = []

        @enum_callback
        def visit(handle, _):
            owner = wintypes.DWORD()
            user32.GetWindowThreadProcessId(handle, ctypes.byref(owner))
            if owner.value == pid and user32.IsWindowVisible(handle):
                title = ctypes.create_unicode_buffer(256)
                user32.GetWindowTextW(handle, title, len(title))
                if title.value == 'Приёмник Folder Camera':
                    handles.append(handle)
            return True

        user32.EnumWindows(visit, 0)
        return handles

    def wait_for(process, predicate, message):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f'Receiver exited early ({process.returncode}): {message}')
            if value := predicate():
                return value
            time.sleep(.1)
        raise RuntimeError(message)

    instance_file = tray_state / 'desktop-instance.json'
    tray_log = ROOT / '.work/desktop-native-tray-smoke.log'
    with tray_log.open('wb') as output, subprocess.Popen(
        command, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
        env={**os.environ, 'TMPDIR': str(temp)},
    ) as process:
        try:
            wait_for(process, instance_file.is_file, 'Receiver control API did not start')
            instance = json.loads(instance_file.read_text(encoding='utf-8'))

            def control(route, method='GET'):
                request = Request(
                    f"http://127.0.0.1:{instance['port']}/{route}", method=method,
                    headers={'Authorization': 'Bearer ' + instance['token']},
                )
                with urlopen(request, timeout=3) as response:
                    return json.load(response) if response.status == 200 else response.status

            windows = wait_for(process, lambda: visible_receiver_windows(process.pid), 'Receiver window did not open')
            before = control('status')
            # WM_CLOSE uses the same native close path as the title-bar button.
            if not user32.PostMessageW(windows[0], 0x0010, 0, 0):
                raise ctypes.WinError(ctypes.get_last_error())
            wait_for(process, lambda: not visible_receiver_windows(process.pid), 'Receiver window did not close')
            time.sleep(.5)
            after = control('status')
            if after['status'] != 'ready' or after['address'] != before['address']:
                raise RuntimeError('Receiver stopped listening when the window closed')
            if control('activate', 'POST') != 204:
                raise RuntimeError('Receiver could not be activated again')
            wait_for(process, lambda: visible_receiver_windows(process.pid), 'Receiver window did not reopen')
        finally:
            process.stdin.close()
            try:
                if process.wait(timeout=8) != 0:
                    raise RuntimeError(f'Receiver shutdown failed; see {tray_log}')
            except subprocess.TimeoutExpired:
                process.kill()
                raise
    print('PASS: closing the Windows receiver window kept reception running and the window reopened')
