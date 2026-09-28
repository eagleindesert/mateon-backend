"""Minimal RFC 6455/STOMP client used by the Bash chat scripts."""
import argparse
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import sys
import threading
from urllib.parse import urlparse


class WebSocket:
    def __init__(self, base_url):
        parsed = urlparse(base_url)
        secure = parsed.scheme in ("https", "wss")
        host = parsed.hostname or "localhost"
        port = parsed.port or (443 if secure else 80)
        sock = socket.create_connection((host, port), timeout=10)
        if secure:
            sock = ssl.create_default_context().wrap_socket(sock, server_hostname=host)
        self.sock = sock
        self.lock = threading.Lock()
        key = base64.b64encode(os.urandom(16)).decode()
        path = (parsed.path.rstrip("/") or "") + "/ws-stomp"
        request = (
            f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        sock.sendall(request.encode())
        response = b""
        while b"\r\n\r\n" not in response:
            chunk = sock.recv(4096)
            if not chunk:
                raise ConnectionError("WebSocket handshake closed")
            response += chunk
        header, self.buffer = response.split(b"\r\n\r\n", 1)
        if not header.startswith(b"HTTP/1.1 101"):
            raise ConnectionError(f"WebSocket handshake failed: {header.decode(errors='replace')}")
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
        if b"sec-websocket-accept: " + accept.lower() not in header.lower():
            raise ConnectionError("WebSocket accept key mismatch")
        self.sock.settimeout(None)

    def _read_exact(self, length):
        while len(self.buffer) < length:
            chunk = self.sock.recv(max(4096, length - len(self.buffer)))
            if not chunk:
                raise ConnectionError("WebSocket disconnected")
            self.buffer += chunk
        data, self.buffer = self.buffer[:length], self.buffer[length:]
        return data

    def send(self, text, opcode=1):
        data = text.encode() if isinstance(text, str) else text
        mask = os.urandom(4)
        length = len(data)
        header = bytes([0x80 | opcode])
        if length < 126:
            header += bytes([0x80 | length])
        elif length < 65536:
            header += bytes([0x80 | 126]) + struct.pack("!H", length)
        else:
            header += bytes([0x80 | 127]) + struct.pack("!Q", length)
        payload = bytes(value ^ mask[index % 4] for index, value in enumerate(data))
        with self.lock:
            self.sock.sendall(header + mask + payload)

    def receive(self):
        fragments = bytearray()
        while True:
            first, second = self._read_exact(2)
            opcode = first & 0x0F
            length = second & 0x7F
            if length == 126:
                length = struct.unpack("!H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if second & 0x80 else None
            payload = self._read_exact(length)
            if mask:
                payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
            if opcode == 8:
                raise ConnectionError("WebSocket closed")
            if opcode == 9:
                self.send(payload, opcode=10)
                continue
            if opcode in (0, 1, 2):
                fragments.extend(payload)
                if first & 0x80:
                    return fragments.decode(errors="replace")

    def close(self):
        try:
            self.send(b"\x03\xe8", opcode=8)
        except OSError:
            pass
        self.sock.close()


def stomp(command, headers=None, body=""):
    lines = [command] + [f"{key}:{value}" for key, value in (headers or {}).items()]
    return "\n".join(lines) + "\n\n" + body + "\0"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--room-id", required=True)
    parser.add_argument("--my-id", required=True)
    parser.add_argument("--label", required=True)
    args = parser.parse_args()
    ws = WebSocket(args.base_url)
    ws.send(stomp("CONNECT", {"accept-version": "1.2", "host": "localhost",
                              "heart-beat": "0,0", "Authorization": "Bearer " + args.token}))
    response = ws.receive()
    if not response.startswith("CONNECTED"):
        raise ConnectionError(f"STOMP CONNECT failed: {response}")
    ws.send(stomp("SUBSCRIBE", {"id": "sub-" + args.label,
                                 "destination": "/topic/room." + args.room_id}))
    print(f"연결됨 — room #{args.room_id} 구독 완료. /quit 또는 /exit로 종료", flush=True)

    stop = threading.Event()

    def receive_loop():
        buffered = ""
        while not stop.is_set():
            try:
                buffered += ws.receive()
                while "\0" in buffered:
                    frame, buffered = buffered.split("\0", 1)
                    if not frame.startswith("MESSAGE") or "\n\n" not in frame:
                        continue
                    body = frame.split("\n\n", 1)[1]
                    try:
                        message = json.loads(body)
                    except ValueError:
                        continue
                    stamp = str(message.get("createdAt") or "")[11:19]
                    if str(message.get("senderId")) == args.my_id:
                        print(f"[{stamp}] 나 ✓  {message.get('content', '')}", flush=True)
                    else:
                        print(f"\n[{stamp}] {message.get('senderName', '')} ▶  {message.get('content', '')}", flush=True)
            except (OSError, ConnectionError):
                stop.set()
                break

    receiver = threading.Thread(target=receive_loop, daemon=True)
    receiver.start()
    try:
        for line in sys.stdin:
            message = line.rstrip("\n")
            if message.strip() in ("/quit", "/exit"):
                break
            if not message.strip():
                continue
            body = json.dumps({"roomId": int(args.room_id), "content": message}, ensure_ascii=False)
            ws.send(stomp("SEND", {"destination": "/app/chat.send",
                                   "content-type": "application/json"}, body))
    finally:
        stop.set()
        ws.close()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ConnectionError, ValueError) as exc:
        print(f"채팅 연결 오류: {exc}", file=sys.stderr)
        sys.exit(1)
