"""仅供测试：清理本轮创建的临时文件和回环连接配置。"""
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from device_ui import adb


def main():
    """保留应用身份和用户配置，仅移除明确的测试内容。"""
    adb("shell", "am", "force-stop", "com.adbside.assistant")
    source = adb("shell", "run-as", "com.adbside.assistant", "cat", "shared_prefs/assistant.xml")
    root = ET.fromstring(source)
    host = root.find("./string[@name='host']")
    port = root.find("./string[@name='port']")
    if host is not None and port is not None and host.text == "127.0.0.1" and port.text == "18777":
        root.remove(host)
        root.remove(port)
        path = Path("build/assistant-clean.xml")
        ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)
        adb("push", str(path), "/data/local/tmp/adbside-clean-prefs.xml")
        adb("shell", "run-as", "com.adbside.assistant", "cp", "/data/local/tmp/adbside-clean-prefs.xml", "shared_prefs/assistant.xml")
    adb("reverse", "--remove", "tcp:18777")
    adb("shell", "rm", "-f", "/sdcard/adbside-ui.xml", "/sdcard/adbside-screen.png", "/data/local/tmp/adbside-test-prefs.xml", "/data/local/tmp/adbside-clean-prefs.xml")
    subprocess.run(["adb", "-s", "X32023081200912", "uninstall", "com.adbside.assistant.test"], check=True)
    print(adb("shell", "am", "start", "-n", "com.adbside.assistant/.MainActivity"))


if __name__ == "__main__":
    main()
