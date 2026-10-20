"""仅供测试：创建并安装无代码、无权限的普通 APK 和拆分 APK 测试应用。"""
import json
import os
from pathlib import Path
import subprocess
import time


def run(*args):
    """执行构建工具，失败时停止，避免使用不完整的测试包。"""
    subprocess.run(list(map(str, args)), check=True)


def main():
    """使用唯一包名，确保不替换设备上的任何已有应用。"""
    root = Path("build/local-app-fixtures")
    root.mkdir(parents=True, exist_ok=True)
    sdk = Path("F:/AndroidSDK")
    build_tools = sdk / "build-tools/36.0.0"
    packages = {}
    stamp = str(int(time.time()))
    for kind in ("single", "split"):
        package = f"com.adbside.testpush.{kind}.t{stamp}"
        packages[kind] = package
        parts = [""] if kind == "single" else ["", "config.test"]
        apks = []
        for index, split in enumerate(parts):
            manifest = root / f"{kind}-{index}.xml"
            split_attr = f' split="{split}"' if split else ""
            manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="1" android:versionName="1.0"{split_attr}>
<uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35"/>
<application android:hasCode="false" android:label="ADB Push Test {kind}"/>
</manifest>''', encoding="utf-8")
            unsigned = root / f"{kind}-{index}-unsigned.apk"
            signed = root / f"{kind}-{index}.apk"
            run(build_tools / "aapt2.exe", "link", "--manifest", manifest, "-I", sdk / "platforms/android-36/android.jar", "-o", unsigned)
            run(build_tools / "apksigner.bat", "sign", "--ks", Path(os.environ["USERPROFILE"]) / ".android/debug.keystore", "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", signed, unsigned)
            apks.append(signed)
        existing = subprocess.check_output(["adb", "-s", "X32023081200912", "shell", "pm", "list", "packages", package], text=True)
        if package in existing:
            raise RuntimeError("Test package already exists")
        run("adb", "-s", "X32023081200912", "install-multiple", *apks)
    (root / "packages.json").write_text(json.dumps(packages), encoding="utf-8")
    print(json.dumps(packages), flush=True)


if __name__ == "__main__":
    main()
