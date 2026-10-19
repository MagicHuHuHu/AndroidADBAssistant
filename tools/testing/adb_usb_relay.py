"""仅供测试：把本机回环 ADB 协议请求转发到已授权 USB 设备，不用于产品。"""
import argparse
import socket
import struct
import threading


def read_exact(sock, length):
    """读取完整消息，断流立即结束测试连接。"""
    result = bytearray()
    while len(result) < length:
        chunk = sock.recv(length - len(result))
        if not chunk:
            raise EOFError()
        result.extend(chunk)
    return bytes(result)


def host_request(sock, value):
    """发送桌面 ADB server 的长度前缀请求。"""
    data = value.encode()
    sock.sendall(f"{len(data):04x}".encode() + data)
    status = read_exact(sock, 4)
    if status != b"OKAY":
        length = int(read_exact(sock, 4), 16)
        raise RuntimeError(read_exact(sock, length).decode())


def serve(client, serial):
    """桥接单个客户端，保留 ADB 的流控与多路复用。"""
    lock = threading.Lock()
    channels = {}

    def send(command, arg0, arg1, payload=b""):
        """发送 ADB 数据包。"""
        command_int = int.from_bytes(command, "little")
        header = struct.pack("<6I", command_int, arg0, arg1, len(payload), sum(payload) & 0xffffffff, command_int ^ 0xffffffff)
        with lock:
            client.sendall(header + payload)

    def pump(remote, local, upstream, ready):
        """将远端服务数据按流控分块送回 Android 客户端。"""
        try:
            while True:
                data = upstream.recv(4096)
                if not data:
                    break
                ready.clear()
                send(b"WRTE", remote, local, data)
                if not ready.wait(30):
                    break
        except (OSError, EOFError):
            pass
        finally:
            try:
                send(b"CLSE", remote, local)
            except OSError:
                pass
            upstream.close()

    try:
        next_id = 1
        while True:
            command_int, arg0, arg1, length, checksum, magic = struct.unpack("<6I", read_exact(client, 24))
            if length > 1024 * 1024 or magic != command_int ^ 0xffffffff:
                raise ValueError("invalid ADB header")
            payload = read_exact(client, length)
            command = command_int.to_bytes(4, "little")
            if command == b"CNXN":
                send(b"CNXN", 0x01000000, 4096, b"device::ro.product.name=USB_TEST_RELAY;\0")
            elif command == b"OPEN":
                remote = next_id
                next_id += 1
                upstream = socket.create_connection(("127.0.0.1", 5037), timeout=20)
                try:
                    host_request(upstream, "host:transport:" + serial)
                    host_request(upstream, payload.rstrip(b"\0").decode())
                except Exception:
                    upstream.close()
                    send(b"CLSE", 0, arg0)
                    continue
                upstream.settimeout(None)
                ready = threading.Event()
                channels[remote] = (upstream, ready)
                send(b"OKAY", remote, arg0)
                threading.Thread(target=pump, args=(remote, arg0, upstream, ready), daemon=True).start()
            elif command == b"WRTE":
                upstream, ready = channels[arg1]
                upstream.sendall(payload)
                send(b"OKAY", arg1, arg0)
            elif command == b"OKAY":
                if arg1 in channels:
                    channels[arg1][1].set()
            elif command == b"CLSE":
                channel = channels.pop(arg1, None)
                if channel:
                    channel[1].set()
                    channel[0].close()
    except (OSError, EOFError, ValueError, KeyError):
        pass
    finally:
        client.close()
        for upstream, ready in channels.values():
            ready.set()
            upstream.close()


def main():
    """启动只监听回环地址的临时测试中继。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--port", type=int, default=18777)
    args = parser.parse_args()
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", args.port))
        listener.listen()
        print("USB test relay ready", flush=True)
        while True:
            client, _ = listener.accept()
            threading.Thread(target=serve, args=(client, args.serial), daemon=True).start()


if __name__ == "__main__":
    main()
