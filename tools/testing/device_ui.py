"""仅供测试：使用 USB ADB 检查调试版界面，坐标来自无障碍节点。"""
import re
import subprocess
import time
import xml.etree.ElementTree as ET

SERIAL = "X32023081200912"


def adb(*args):
    """向指定测试设备执行 ADB 命令。"""
    return subprocess.check_output(["adb", "-s", SERIAL, *args], text=True, encoding="utf-8", errors="replace")


def dump():
    """读取当前界面的节点树。"""
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/adbside-ui.xml")
        value = adb("shell", "cat", "/sdcard/adbside-ui.xml")
        if value.startswith("<?xml"):
            return ET.fromstring(value)
        time.sleep(0.3)
    raise RuntimeError("UI dump failed")


def tap_node(node):
    """点击节点中央，避免依赖固定屏幕分辨率。"""
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap(text):
    """点击指定文本或无障碍描述。"""
    for node in dump().iter("node"):
        if node.get("text") == text or node.get("content-desc") == text:
            tap_node(node)
            return
    raise RuntimeError("No UI node: " + text)


def fill(label, value):
    """填写包含指定标签的输入框。"""
    for node in dump().iter("node"):
        if node.get("class") == "android.widget.EditText" and any(label in child.get("text", "") for child in node.iter("node")):
            tap_node(node)
            adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * 80))
            adb("shell", "input", "text", value)
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            return
    raise RuntimeError("No input field: " + label)


def texts():
    """返回界面可见文字用于断言。"""
    return [n.get("text") for n in dump().iter("node") if n.get("text")]


if __name__ == "__main__":
    print("\n".join(texts()))
