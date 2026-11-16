#!/usr/bin/env python3
"""Build the Windows Forms launcher and the bundled, non-desktop receiver engine."""
import argparse
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("archive", type=Path)
args = parser.parse_args()
args.archive.parent.mkdir(parents=True, exist_ok=True)
framework = Path(os.environ["SystemRoot"]) / "Microsoft.NET/Framework64/v4.0.30319"

with tempfile.TemporaryDirectory(prefix="folder-camera-windows-") as work:
    output = Path(work)
    engine = output / "FolderCameraEngine.exe"
    launcher = output / "FolderCameraReceiver.exe"
    icon = output / "FolderCamera.ico"
    subprocess.run([
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Drawing; "
        "$source=[Drawing.Bitmap]::new($env:FC_ICON_PNG); "
        "$bitmap=[Drawing.Bitmap]::new($source, 48, 48); "
        "$icon=[Drawing.Icon]::FromHandle($bitmap.GetHicon()); "
        "$file=[IO.File]::Create($env:FC_ICON_ICO); "
        "try { $icon.Save($file) } finally { $file.Dispose(); $icon.Dispose(); $bitmap.Dispose(); $source.Dispose() }",
    ], env={**os.environ, "FC_ICON_PNG": str(ROOT / "receiver/desktop/ui/icon.png"),
            "FC_ICON_ICO": str(icon)}, check=True)
    subprocess.run([
        "deno", "compile", "--frozen", "--config", str(ROOT / "receiver/deno.json"),
        "--target", "x86_64-pc-windows-msvc", "--output", str(engine),
        "--allow-env=HOME,LOCALAPPDATA,SystemRoot", "--allow-sys=networkInterfaces,homedir",
        "--allow-net", "--allow-read", "--allow-write", "--allow-ffi",
        str(ROOT / "receiver/desktop/windows-engine.ts"),
    ], cwd=ROOT, check=True)
    subprocess.run([
        str(framework / "csc.exe"), "/nologo", "/target:winexe", "/platform:x64",
        "/codepage:65001", "/optimize+", "/r:System.Web.Extensions.dll",
        "/win32icon:" + str(icon), "/out:" + str(launcher),
        str(ROOT / "receiver/desktop/WindowsReceiver.cs"),
    ], check=True)
    for executable in (engine, launcher):
        with executable.open("rb") as handle:
            if handle.read(2) != b"MZ":
                raise ValueError(f"Invalid executable: {executable.name}")
            handle.seek(0x3C)
            handle.seek(struct.unpack("<I", handle.read(4))[0])
            if handle.read(6) != b"PE\0\0\x64\x86":
                raise ValueError(f"Expected Windows x64: {executable.name}")
    with zipfile.ZipFile(args.archive, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in (launcher, engine, ROOT / "LICENSE", ROOT / "THIRD_PARTY_NOTICES.md"):
            archive.write(path, path.name)
        license_path = ROOT / "third_party/receiver/native/deno-LICENSE.txt"
        archive.write(license_path, "licenses/" + license_path.name)
    print(args.archive)
