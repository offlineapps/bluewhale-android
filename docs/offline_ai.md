# Offline AI (`/ai`)

`/ai <prompt>` runs a language model on the device and sends the reply to the conversation you
have open, the same way `/hug` and `/slap` send their messages. In a channel everyone sees the
answer; in a private chat the other person sees it.

Inference happens on your phone. The prompt is never uploaded anywhere, but the answer **is**
transmitted to your peers, so do not type anything into `/ai` that you would not type into the
chat itself.

Replies are prefixed with `[ai]` and quote the prompt that produced them:

```
[ai] "what is a spring tide": A spring tide occurs at new and full moon, when...
```

The marker matters. The message is sent under your nickname, so without it peers could not tell
a model's guess from something you wrote and vouched for.

Only successful answers are transmitted. The `ai: thinking…` progress line, inference failures,
usage errors and empty responses stay on your device.

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

llama.cpp reads GGUF directly, which is what most model links point at, but it publishes no
Android Maven artifact — using it means vendoring the sources and building them with the NDK.
MediaPipe ships a prebuilt AAR that Gradle resolves like any other dependency. The tradeoff is
the model format, and 26 MB of native libraries in an arm64 APK whether or not a model is
installed.
