"""Run the receiver GUI smoke test through the registered interactive task."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

TASK = "Folder Camera GUI Smoke"
WORK = Path(r"C:\work\folder-camera-gui-smoke")


def main():
    exe = Path(sys.argv[1]).resolve(strict=True)
    if not WORK.is_dir():
        raise SystemExit(f"Register the interactive task and prepare {WORK} first")
    result = WORK / "exit-code.txt"
    pending = WORK / "exit-code.tmp"
    log = WORK / "smoke.log"
    for path in (result, pending, log):
        path.unlink(missing_ok=True)

    scripts = WORK / "scripts"
    scripts.mkdir(exist_ok=True)
    shutil.copy2(Path(__file__).with_name("smoke_desktop.py"), scripts)
    shutil.copy2(exe, WORK / "receiver.exe")
    (WORK / "run-smoke.cmd").write_text(
        "@echo off\n"
        'cd /d "%~dp0"\n'
        f'"{sys.executable}" "scripts\\smoke_desktop.py" "receiver.exe" > smoke.log 2>&1\n'
        'set "SMOKE_EXIT=%ERRORLEVEL%"\n'
        '>exit-code.tmp echo %SMOKE_EXIT%\n'
        'move /y exit-code.tmp exit-code.txt >nul\n'
        'exit /b %SMOKE_EXIT%\n',
        encoding="mbcs",
    )

    subprocess.run(["schtasks.exe", "/Run", "/TN", TASK], check=True)
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if result.is_file():
            print(log.read_text(errors="replace") if log.is_file() else "", flush=True)
            return int(result.read_text().strip())
        time.sleep(2)
    subprocess.run(["schtasks.exe", "/End", "/TN", TASK], check=False)
    if log.is_file():
        print(log.read_text(errors="replace"), flush=True)
    raise SystemExit("GUI task timed out. Verify that its Windows user is signed in.")


if __name__ == "__main__":
    sys.exit(main())
