# Offline AI (`/ai`)

`/ai <prompt>` runs a language model on the device and prints the reply into the current
conversation. Nothing leaves the phone: the reply is a local system message, it is not relayed
over the mesh and it is not sent to any peer. If you want to share an answer, copy it into a
normal message yourself.

## Installing a model

No model ships with the app — the inference runtime is bundled, the weights are not. Until a
model is installed, `/ai` tells you where to put one.

The app reads a single [MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)
task bundle from:

```
/sdcard/Android/data/<package>/files/models/model.task
```

Download a bundle, then copy it across with the device plugged in over USB, renaming it to
`model.task`:

```
curl -LO https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task
adb push Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task \
  /sdcard/Android/data/com.bluewhale.android/files/models/model.task
```

Prebuilt bundles are published by [LiteRT Community](https://huggingface.co/litert-community) on
Hugging Face. Reasonable choices, all Apache-2.0 and ungated:

| Model | File | Size |
|---|---|---|
| Qwen2.5-0.5B-Instruct | `..._multi-prefill-seq_q8_ekv1280.task` | 521 MB |
| Qwen2.5-1.5B-Instruct | `..._multi-prefill-seq_q8_ekv1280.task` | 1.6 GB |

The 0.5B model is the sane default: it loads in a few seconds and fits comfortably in an app's
memory budget. The 1.5B answers better but is more likely to be killed by the OS on mid-range
hardware.

Google's Gemma 3 bundles also work, but the Hugging Face repos are gated — you must accept the
licence and download with an authenticated token, which makes them a poor default for an app
whose users may have no account.

Note that `.task` bundles are not the same format as the GGUF files used by Ollama and
llama.cpp. A GGUF downloaded from `ollama.com` will not load.

## Why MediaPipe and not llama.cpp

llama.cpp reads GGUF directly and would match the model links people usually reach for, but it
publishes no Android Maven artifact — using it means vendoring the sources and building them
with the NDK as part of this repo's build. MediaPipe ships a prebuilt AAR that Gradle resolves
like any other dependency, so the runtime costs a one-line dependency instead of an NDK
toolchain. The tradeoff is the model format.

## Behaviour

- The model loads lazily on the first `/ai` call and is kept in memory afterwards.
- Concurrent `/ai` calls are serialised; MediaPipe rejects overlapping generation on one instance.
- Generation runs off the main thread. The chat shows `ai: thinking…` while it works.
- The model is released in `ChatViewModel.onCleared()`.

## Cost

The MediaPipe native libraries add roughly 26 MB to an arm64 APK, and about 19 MB for
armeabi-v7a. That cost is paid whether or not a model is installed.
