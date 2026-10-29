"""Filer Mac embedder: runs the phone's EmbeddingGemma 2 model on the Mac GPU for Developer mode.

The phone does everything except inference (file reading, chunking, prompts, image downscaling)
and sends the exact model inputs here over USB (`adb reverse`), so vectors stay comparable with
on-device ones. Listens on 127.0.0.1 only; nothing is stored.

Protocol (JSON over HTTP):
  GET  /health -> {"model": str, "modelBytes": int, "engine": "mac-gpu", "dim": 768}
  POST /embed  {"items": [{"text": str} | {"image": base64-bytes}, ...]}
            -> {"vectors": [base64 little-endian float32 x dim, ...], "ms": float}
"""

import argparse
import base64
import json
import os
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from litert_lm import Backend, Content, EmbeddingEngine, EmbeddingOptions, InputOverflowStrategy

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_MODEL = os.path.join(HERE, "models", "embeddinggemma-2-740m.litertlm")
MAX_ITEMS = 64
MAX_BODY = 64 * 1024 * 1024


class Embedder:
    def __init__(self, model_path: str, cpu: bool):
        backend = Backend.CPU() if cpu else Backend.GPU()
        self.engine = EmbeddingEngine(model_path, backend=backend, vision_backend=backend)
        self.name = "mac-cpu" if cpu else "mac-gpu"
        self.model = os.path.basename(model_path)
        self.model_bytes = os.path.getsize(model_path)
        # The engine runs one input at a time; HTTP threads queue here.
        self.lock = threading.Lock()
        # Passages are cut at 2000 characters on the phone, but dense text (code, non-Latin scripts)
        # can still pass the model's 1024-token limit: keep the start rather than failing the batch.
        self.options = EmbeddingOptions(input_overflow_strategy=InputOverflowStrategy.TRUNCATE)

    def embed(self, item: dict) -> bytes:
        if "text" in item:
            content = item["text"]
        elif "image" in item:
            content = Content.ImageBytes(base64.b64decode(item["image"]))
        else:
            raise ValueError("item needs 'text' or 'image'")
        with self.lock:
            vector = self.engine.compute_embedding(content, self.options).embedding
        return struct.pack("<%df" % len(vector), *vector)


def make_handler(embedder: Embedder):
    class Handler(BaseHTTPRequestHandler):
        # Keep-alive: the phone reuses one connection instead of opening a new one through the
        # adb tunnel per request (new tunnel connections are the flaky part).
        protocol_version = "HTTP/1.1"

        def _send(self, code: int, body: dict):
            data = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path != "/health":
                return self._send(404, {"error": "not found"})
            self._send(200, {"model": embedder.model, "modelBytes": embedder.model_bytes, "engine": embedder.name, "dim": 768})

        def do_POST(self):
            if self.path != "/embed":
                return self._send(404, {"error": "not found"})
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY:
                return self._send(413, {"error": "bad body size"})
            try:
                items = json.loads(self.rfile.read(length))["items"]
                if not isinstance(items, list) or not 0 < len(items) <= MAX_ITEMS:
                    return self._send(400, {"error": "1..%d items" % MAX_ITEMS})
                start = time.perf_counter()
                vectors = [base64.b64encode(embedder.embed(item)).decode() for item in items]
                self._send(200, {"vectors": vectors, "ms": (time.perf_counter() - start) * 1000})
            except Exception as e:  # report to the phone, which then falls back to on-device
                self._send(500, {"error": type(e).__name__ + ": " + str(e)})

        def log_message(self, fmt, *args):
            # Request lines only; never log what was embedded.
            if self.server.verbose:
                sys.stderr.write("%s %s\n" % (self.command, self.path))

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--port", type=int, default=9379)
    parser.add_argument("--cpu", action="store_true", help="run on the Mac CPU instead of the GPU")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    if not os.path.exists(args.model):
        sys.exit("Model not found: %s (see README.md)" % args.model)
    embedder = Embedder(args.model, args.cpu)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(embedder))
    server.verbose = args.verbose
    print("Filer Mac embedder: %s on %s, http://127.0.0.1:%d" % (embedder.model, embedder.name, args.port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
