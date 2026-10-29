# Developer mode: index with a Mac

Developer mode lets a USB-connected Mac do Filer's heavy indexing work. The Mac runs the same
embedding model as the phone (EmbeddingGemma 2, the same `.litertlm` file) on its GPU, so a large
folder or a "Whole phone" rebuild finishes many times faster than on the phone alone, without
heating it up.

It is developer-only and optional. If the Mac isn't reachable, the app indexes on the phone as usual.

## How it works

```
Phone (Filer)                                   Mac (tools/mac_embedder)
  reads files, splits text, builds prompts,        server.py: runs the .litertlm
  shrinks photos to the 512-px model input   ──►   on the Mac GPU, returns vectors
  saves vectors + FTS index                  ◄──
             http://127.0.0.1:9379 over the USB cable (adb reverse)
```

- The phone does everything except inference, and sends the exact model inputs, so Mac vectors
  match phone vectors (check with **Compare engines**, below). Each vector records the engine that
  made it (`mac-gpu`, `phone-gpu`, …); Index status lists **Vectors by engine**.
- **Searches always run on the phone.** Only indexing goes to the Mac, so the phone still needs
  its own copy of the model (smart search turned on).
- Traffic stays on the USB cable through `adb reverse`, to `127.0.0.1` only (the app allows
  plain HTTP for `127.0.0.1`/`localhost` and nowhere else). The server stores and logs nothing
  about your files.
- The phone accepts the Mac only when it runs the **same model file** (same size, 768-dim
  vectors). If a request fails, the phone ignores the Mac for 30 s and carries on by itself.

## Set up the Mac (once)

Needs an Apple Silicon Mac with Python 3.12 or newer.

1. Install [uv](https://docs.astral.sh/uv/) and adb:

   ```sh
   brew install uv
   brew install --cask android-platform-tools   # skip if adb is already on your PATH
   ```

2. On the phone, turn on **USB debugging** (Settings › About phone › tap Build number 7 times,
   then Settings › System › Developer options › USB debugging).
3. Connect the phone with a USB cable, accept the "Allow USB debugging?" prompt, and check that
   it shows up:

   ```sh
   adb devices        # should list the phone as "device", not "unauthorized"
   ```

## Run the embedder

```sh
cd tools/mac_embedder
./run.sh
```

The first run downloads the model from Hugging Face (485 MB, into `models/`) and installs
`litert-lm` into `.venv/`. When it's ready it prints:

```
Phone can reach the Mac at 127.0.0.1:9379
Filer Mac embedder: embeddinggemma-2-740m.litertlm on mac-gpu, http://127.0.0.1:9379
```

After that it stays quiet: requests aren't printed unless you pass `--verbose` (then you see
`GET /health` every 3 s while Settings › Developer is open, and `POST /embed` per file while a pass
runs via the Mac). To check it from the Mac: `curl http://127.0.0.1:9379/health`.

Leave it running while the phone indexes; stop it with Ctrl-C. `run.sh` re-adds the `adb reverse`
port every 5 s, so unplugging and reconnecting the phone or reinstalling the app doesn't need a
restart.

Options (passed through to `server.py`):

| Option | What it does |
| --- | --- |
| `--cpu` | Run on the Mac CPU instead of the GPU (slower; engine shows as `mac-cpu`) |
| `--verbose` | Print each request line (never what was embedded) |
| `--model PATH` | Use another copy of the model file (must be the same file the phone uses) |
| `PORT=9400 ./run.sh` | Use another port (the app expects 9379, so only for testing) |

## Turn on Developer mode in Filer

1. Turn on smart search first, so the phone has its model (searches still need it).
2. Settings › **Privacy & about** › tap the version line (**Filer x.y**) 7 times. A snackbar
   says **Developer mode on**; this also turns on **Show indexing details**.
3. Back in Settings, the **Developer** section now appears (under the indexing details). Turn on
   **Index with Mac**. The status line under it checks the Mac every 3 s:
   - **Connected · embeddinggemma-2-740m.litertlm · mac-gpu**: ready.
   - **Looking for the Mac…**: checking.
   - **Not reachable — run tools/mac_embedder/run.sh with the phone connected**: start `run.sh`,
     check the cable and `adb devices`.
   - **The Mac runs a different model file (…)**: the Mac's `models/` file differs from the
     phone's; delete it and run `run.sh` again to re-download.
   - **Turn on smart search first…**: the phone has no model yet.

### Developer settings

| Setting | What it does |
| --- | --- |
| **Index with Mac** | Indexing passes send model work to the Mac while it's reachable. |
| **Index now via Mac** | Starts a full pass right away, without waiting for the charger or idle. A pending rebuild runs in it. |
| **Compare engines** | Embeds ~20 indexed passages and ~10 indexed photos on both the phone and the Mac, then shows mean/min cosine similarity and ms per item. Values near 1.0 mean the Mac's vectors match the phone's. |
| **Turn off developer mode** | Hides the section and goes back to phone-only indexing. |

While a pass uses the Mac, the Index screen and the notification say **Indexing via Mac** with
items per second.

## Behaviour while indexing via the Mac

- All passages of a file go in one request (up to 64 items). Photos are sent as lossless PNGs of
  the exact 512-px bitmap the phone would use.
- The phone's routine heat rests and per-file pacing are skipped (the Mac does the heavy work);
  severe/critical heat still pauses the pass.
- A pass that never reached the Mac (phone unplugged, everyday new files) runs on the phone as
  normal. If the Mac drops out **mid-pass**, the pass waits up to 10 minutes (**Waiting for the
  Mac…**) for it to come back, then ends; the next pass picks up the rest.
- Passages longer than the model's 1024-token limit are truncated from the end, not failed.

Measured on an Apple M5 (GPU): ~56 ms per 200-word passage, ~200 ms per photo, about 14.6 items/s
end to end.

## Troubleshooting

| Problem | Try |
| --- | --- |
| `adb devices` is empty or "unauthorized" | Replug the cable, accept the prompt on the phone, try another cable/port (some are charge-only). |
| Status stays **Not reachable** | Is `run.sh` still running? Run `adb reverse --list`; it should show `tcp:9379 tcp:9379`. |
| `Model not found` | Delete `models/` and run `./run.sh` again to re-download. |
| Download fails halfway | Run `./run.sh` again; the partial `.part` file is replaced. |
| GPU errors on start | Run `./run.sh --cpu`. |
| Indexing went back to the phone | The Mac failed a request; the phone retries it after 30 s. Check the `run.sh` terminal for errors. |

## Protocol

JSON over HTTP on `127.0.0.1:9379`. The client is `RemoteEmbedder.kt` in the app
(`data/inference/`); change both together.

```
GET  /health -> {"model": str, "modelBytes": int, "engine": "mac-gpu" | "mac-cpu", "dim": 768}
POST /embed  {"items": [{"text": str} | {"image": base64 bytes}, ...]}     (1–64 items, ≤ 64 MB)
          -> {"vectors": [base64 little-endian float32 × 768, ...], "ms": float}
```

Errors come back as `{"error": "..."}` with status 400/404/413/500; the phone then falls back to
on-device indexing.
