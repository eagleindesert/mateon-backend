"""One-shot STOMP exchange for the Bash API chat test."""
import argparse
import json
import socket
import sys
import time
from _stomp_client import WebSocket, stomp


def connect(base_url, token):
    ws = WebSocket(base_url)
    ws.send(stomp("CONNECT", {"accept-version": "1.2", "host": "localhost",
                              "heart-beat": "0,0", "Authorization": "Bearer " + token}))
    ws.sock.settimeout(5)
    response = ws.receive()
    if not response.startswith("CONNECTED"):
        ws.close()
        raise ConnectionError(f"STOMP CONNECT failed: {response}")
    return ws


def body_once(ws):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        frame = ws.receive()
        if frame.startswith("MESSAGE") and "\n\n" in frame:
            return frame.split("\n\n", 1)[1].rstrip("\0")
    return ""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--token-a", required=True)
    parser.add_argument("--token-b", required=True)
    parser.add_argument("--room-id", required=True)
    parser.add_argument("--message", required=True)
    args = parser.parse_args()
    result = {"connected": False, "a_received": False, "b_received": False,
              "bad_token_rejected": False}
    a = b = None
    try:
        b = connect(args.base_url, args.token_b)
        a = connect(args.base_url, args.token_a)
        result["connected"] = True
        for ws, sub in ((b, "sub-b"), (a, "sub-a")):
            ws.send(stomp("SUBSCRIBE", {"id": sub,
                                         "destination": "/topic/room." + args.room_id}))
        time.sleep(0.5)
        a.send(stomp("SEND", {"destination": "/app/chat.send",
                              "content-type": "application/json"},
                     json.dumps({"roomId": int(args.room_id), "content": args.message}, ensure_ascii=False)))
        result["b_received"] = args.message in body_once(b)
        result["a_received"] = args.message in body_once(a)
    except (OSError, ConnectionError, socket.timeout) as exc:
        result["error"] = str(exc)
    finally:
        for ws in (a, b):
            if ws:
                ws.close()
    try:
        bad = connect(args.base_url, "this.is.invalid")
        bad.close()
    except (OSError, ConnectionError, socket.timeout):
        result["bad_token_rejected"] = True
    print(json.dumps(result))


if __name__ == "__main__":
    main()
