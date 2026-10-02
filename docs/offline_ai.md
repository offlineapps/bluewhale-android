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

## Translating messages

The same model translates incoming messages, without anything leaving the phone:

- Long-press a message from someone else and pick **translate message**.
- Or type `/tr` to translate the newest message from someone else in the open conversation.
  `/tr spanish` picks the language; it is remembered until the app restarts. The default is the
  phone's language.

The translation appears as a local line under the conversation, like an `/ai` answer, and is
never sent. Small models translate between major languages reasonably well and struggle with
slang and rare languages; treat the result as a gist. Translation and `/ai` share the model, so
one waits for the other, and `/ai stop` cancels either.

## Timeouts

A question that takes longer than three minutes is stopped, and the model is free for the next
one. Only one question runs at a time; asking another while the model is busy tells you so
instead of queueing it silently.

## Installing a model

No model ships with the app — the inference runtime is bundled, the weights are not. Until a
model is installed, `/ai` tells you where to put one.

The app runs [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) models (`.litertlm`) from:

```
/sdcard/Android/data/<package>/files/models/model.litertlm
```

Download a model, then copy it across with the device plugged in over USB, renaming it to
`model.litertlm`:

```
curl -LO https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm
adb push Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm \
  /sdcard/Android/data/com.bluewhale.android/files/models/model.litertlm
```

Prebuilt models are published by [LiteRT Community](https://huggingface.co/litert-community) on
Hugging Face. Qwen2.5-1.5B-Instruct (1.6 GB, Apache-2.0, ungated) is a reasonable default.
Gemma models also work, but their repos are gated — you must accept the licence and download
with an authenticated token.

LiteRT-LM ships native code for `arm64-v8a` and `x86_64` only. On 32-bit devices `/ai` reports
that LiteRT-LM is not supported; use a `.task` bundle there (below).

### Older `.task` bundles

Earlier versions read a [MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)
task bundle from `models/model.task`. That still works: when there is no `model.litertlm`, the
app falls back to `model.task`. Google has put MediaPipe LLM Inference in maintenance mode in
favour of LiteRT-LM, so new installs should use a `.litertlm` model.

```
adb push Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task \
  /sdcard/Android/data/com.bluewhale.android/files/models/model.task
```

Neither format is the GGUF used by Ollama and llama.cpp. A GGUF downloaded from `ollama.com`
will not load.

## Why LiteRT-LM and not llama.cpp

llama.cpp reads GGUF directly, which is what most model links point at, but it publishes no
Android Maven artifact — using it means vendoring the sources and building them with the NDK.
LiteRT-LM (and MediaPipe before it) ship prebuilt AARs that Gradle resolves like any other
dependency. The tradeoff is the model format, and the size of the native libraries in the APK
whether or not a model is installed. The MediaPipe runtime can be dropped once `.task` installs
have moved over.
