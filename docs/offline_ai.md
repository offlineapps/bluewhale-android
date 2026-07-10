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

Copy a `.task` file there and rename it to `model.task`:

```
adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.bluewhale.android/files/models/model.task
```

Prebuilt task bundles are published on
[LiteRT Community](https://huggingface.co/litert-community) on Hugging Face. Small
instruction-tuned models (roughly the Gemma 3 1B / Qwen 2.5 1.5B class, int4-quantised) are the
practical choice; they land around 500 MB–1.5 GB and load in a few seconds. Larger models will
load, but may be killed by the OS on mid-range hardware.

Note that `.task` bundles are not the same format as the GGUF files used by Ollama and
llama.cpp. A GGUF downloaded from `ollama.com` will not load.

## Behaviour

- The model loads lazily on the first `/ai` call and is kept in memory afterwards.
- Concurrent `/ai` calls are serialised; MediaPipe rejects overlapping generation on one instance.
- Generation runs off the main thread. The chat shows `ai: thinking…` while it works.
- The model is released in `ChatViewModel.onCleared()`.

## Cost

The MediaPipe native libraries add roughly 26 MB to an arm64 APK, and about 19 MB for
armeabi-v7a. That cost is paid whether or not a model is installed.
