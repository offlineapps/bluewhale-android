# Offline AI (`/ai`)

`/ai <prompt>` runs a language model on the device and shows the answer in the conversation you
have open. **The answer is private**: it is a local message that only you can see, like the
`ai: thinking…` line before it. Nothing is transmitted unless you ask for it.

| Command | What it does |
|---|---|
| `/ai <prompt>` | Ask the model. The answer is shown only to you. |
| `/ai share` | Send the last answer in this conversation to the chat. |
| `/ai stop` | Stop the question the model is working on. |
| `/ai reset` | Forget this conversation's `/ai` context. |

Inference happens on your phone. The prompt is never uploaded anywhere.

## Sharing an answer

`/ai share` sends the most recent answer from the conversation you type it in, prefixed with
`[ai]` and quoting the prompt that produced it:

```
[ai] "what is a spring tide": A spring tide occurs at new and full moon, when...
```

The marker matters. The message is sent under your nickname, so without it peers could not tell
a model's guess from something you wrote and vouched for.

## Context

Each conversation keeps its own short memory, so follow-up questions work:

```
/ai what is a spring tide
/ai and when is the next one likely?
```

The model also sees the last few messages of the chat the question is asked in, so
`/ai summarise this` does what it says. Small models have a window of about a thousand tokens
shared between the question and the answer, so older turns and messages are dropped first. The
memory lives in RAM only, is never transmitted, and is wiped by `/ai reset`, by panic, and when
the app is closed.

Messages from other people end up in the model's input, and someone could write a message that
tries to steer it. That is one more reason answers stay private until you have read them.

## Timeouts

A question that takes longer than three minutes is stopped, and the model is free for the next
one. Only one question runs at a time; asking another while the model is busy tells you so
instead of queueing it silently.

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
