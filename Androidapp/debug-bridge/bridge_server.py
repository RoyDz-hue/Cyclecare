#!/usr/bin/env python3
"""Short-lived CycleCare debug bridge. Stdlib-only; no health/user records accepted."""
from __future__ import annotations

import hmac
import json
import os
import secrets
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
APK = Path(os.environ.get("CYCLECARE_DEBUG_APK", ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
STATE_DIR = Path(os.environ.get("CYCLECARE_BRIDGE_STATE", "/tmp/cyclecare-debug-bridge"))
STATE_FILE = STATE_DIR / "last_report.json"
PORT = int(os.environ.get("PORT", "8765"))
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

PAGE = """<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>
<title>CycleCare Debug Build</title><style>body{font:16px system-ui;max-width:680px;margin:10vh auto;padding:0 22px;color:#20202a}a,button{background:#8b3d69;color:white;padding:13px 18px;border-radius:10px;text-decoration:none;border:0;font-weight:650}li{margin:10px 0}.note{background:#f7edf3;padding:14px;border-radius:12px}</style>
<h1>CycleCare remote-debug test build</h1><p>This private test build adds an opt-in diagnostics panel. It is not a Play Store release.</p>
<p><a href='/download'>Download Android APK</a></p><ol><li>Install the APK. Android may ask you to allow this browser to install apps.</li><li>Open CycleCare Debug, finish the first-launch flow, then open <b>Settings → Remote debug</b>.</li><li>Enter the one-time pairing code supplied in the chat and approve the session.</li><li>Tap <b>Send diagnostic snapshot</b> only when you want to share the limited technical fields shown in the app.</li></ol>
<div class=note>Health logs, symptoms, notes, accounts, screen contents and device identifiers are not included. Screen streaming and remote taps are not implemented.</div>
""".encode("utf-8")


def new_pair_code() -> str:
    return "".join(secrets.choice(CODE_ALPHABET) for _ in range(10))


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, handler, pairing_code):
        super().__init__(address, handler)
        self.pairing_code = pairing_code
        self.code_expires_at = time.time() + 24 * 60 * 60
        self.failed_pairs = 0
        self.rate_window = time.time()
        self.lock = threading.Lock()
        self.active = None


class Handler(BaseHTTPRequestHandler):
    server: BridgeServer
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # Don't persist requester IPs or request contents in access logs.
        return

    def _send(self, status: int, data: bytes, content_type: str, extra_headers=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Connection", "close")
        if extra_headers:
            for key, value in extra_headers.items():
                self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def _json(self, status: int, value: dict):
        body = json.dumps(value, separators=(",", ":")).encode()
        self._send(status, body, "application/json; charset=utf-8")

    def _body(self):
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ValueError("Invalid content length.")
        if size < 1 or size > 8192:
            raise ValueError("Request is empty or too large.")
        raw = self.rfile.read(size)
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("Expected a JSON object.")
        return value

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/":
            self._send(200, PAGE, "text/html; charset=utf-8")
        elif path == "/api/health":
            self._json(200, {"ok": True, "service": "cyclecare-debug-bridge"})
        elif path == "/download":
            if not APK.is_file():
                self._json(404, {"error": "Debug APK is not built yet."})
                return
            length = APK.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(length))
            self.send_header("Content-Disposition", 'attachment; filename="CycleCare-debug.apk"')
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Connection", "close")
            self.end_headers()
            with APK.open("rb") as stream:
                while chunk := stream.read(128 * 1024):
                    self.wfile.write(chunk)
            self.close_connection = True
        else:
            self._json(404, {"error": "Not found."})

    def do_POST(self):
        path = urlparse(self.path).path
        try:
            body = self._body()
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            self._json(400, {"error": str(exc)})
            return

        if path == "/api/pair":
            code = body.get("code")
            if not isinstance(code, str) or len(code) > 12:
                self._json(400, {"error": "Enter the one-time pairing code."})
                return
            now = time.time()
            with self.server.lock:
                if now - self.server.rate_window > 600:
                    self.server.rate_window = now
                    self.server.failed_pairs = 0
                if self.server.failed_pairs >= 10:
                    self._json(429, {"error": "Too many pairing attempts. Try again later."})
                    return
                expected = self.server.pairing_code
                if (not expected or now > self.server.code_expires_at or
                        not hmac.compare_digest(code.strip().upper(), expected)):
                    self.server.failed_pairs += 1
                    self._json(401, {"error": "Pairing code is invalid or expired."})
                    return
                token = secrets.token_urlsafe(32)
                session_id = str(uuid.uuid4())
                self.server.active = {"token": token, "session_id": session_id, "paired_at": time.time()}
                self.server.pairing_code = None  # single use
            self._json(200, {"session_id": session_id, "token": token})
            print(f"[paired] session={session_id}", flush=True)
            return

        if path not in ("/api/report", "/api/disconnect"):
            self._json(404, {"error": "Not found."})
            return

        auth = self.headers.get("Authorization", "")
        supplied = auth[7:] if auth.startswith("Bearer ") else ""
        with self.server.lock:
            active = self.server.active
            valid = bool(active and supplied and hmac.compare_digest(supplied, active["token"]))
            if not valid:
                self._json(401, {"error": "This debug session is not active."})
                return
            if path == "/api/disconnect":
                self.server.active = None
                self._json(200, {"ok": True})
                print("[disconnected] phone session ended", flush=True)
                return
            session_id = active["session_id"]

        # Strict allowlist: unknown fields (including free-text and health data) are rejected.
        allowed = {"app_version", "android_release", "android_api", "network_connected", "reported_at_utc"}
        if set(body) != allowed:
            self._json(400, {"error": "Snapshot did not match the allowed diagnostic fields."})
            return
        if (not isinstance(body["app_version"], str) or len(body["app_version"]) > 40 or
                not isinstance(body["android_release"], str) or len(body["android_release"]) > 20 or
                type(body["android_api"]) is not int or not 24 <= body["android_api"] <= 100 or
                type(body["network_connected"]) is not bool or
                not isinstance(body["reported_at_utc"], str) or len(body["reported_at_utc"]) > 32):
            self._json(400, {"error": "Snapshot contains invalid field values."})
            return

        record = {"session_id": session_id, **body, "received_at_unix": int(time.time())}
        STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
        try:
            os.chmod(STATE_DIR, 0o700)
            temp_file = STATE_FILE.with_suffix(".tmp")
            temp_file.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
            os.chmod(temp_file, 0o600)
            temp_file.replace(STATE_FILE)
            os.chmod(STATE_FILE, 0o600)
        except OSError:
            self._json(500, {"error": "Could not securely store this snapshot."})
            return
        print(
            f"[snapshot] session={session_id} app={record['app_version']} "
            f"android_api={record['android_api']} internet={record['network_connected']}",
            flush=True,
        )
        self._json(200, {"ok": True})


def main():
    if not APK.is_file():
        raise SystemExit(f"APK not found: {APK}")
    pair_code = new_pair_code()
    httpd = BridgeServer(("0.0.0.0", PORT), Handler, pair_code)
    print(f"CycleCare debug bridge listening on 0.0.0.0:{PORT}", flush=True)
    print(f"One-time pairing code (expires in 24h): {pair_code}", flush=True)
    print(f"APK: {APK}", flush=True)
    try:
        httpd.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
