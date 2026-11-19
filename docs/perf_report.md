# Performance report (P1-01)

Measured on this phone only. Nothing in this file is an estimate presented as a measurement.

## Status

| Item | Status |
|---|---|
| Timing harness (`llm/Perf.kt`) | Done. Every local and cloud answer records first-token time, total time, streamed pieces and pieces/second. |
| Per-answer display | Done. Settings → Model choice → "Show measured speed under answers" adds the line under each reply. |
| Report screen | Done. Settings → "Measured on this phone" shows the last 40 answers, with Refresh / Clear and the existing speed test. |
| Real-device numbers | **Not measured yet.** No phone was attached to this build, so no number is claimed. |

## How a number is produced

1. Load a model (Models tab) or connect a cloud provider (Fast Mode).
2. Ask a few questions, short and long.
3. Open Settings → "Measured on this phone". Each line reads like:

   `1. local Qwen2.5-1.5B · first 1.2s · 9.4 tok/s · 42 tok · total 4.6s`

Meaning of each field:

- **first** — prompt reading time before the first streamed piece (the number that decides how "instant" a reply feels).
- **tok/s** — streamed pieces per second, computed between the first and the last piece (prompt reading excluded). A piece is not necessarily one token; the JNI layer does not expose token ids yet.
- **tok** — number of streamed pieces in that answer.
- **total** — wall-clock time from request to the last piece.

Local numbers come from `LlamaBridge`/`LlmEngine`; cloud numbers are measured around the OpenRouter stream. The "Speed test" button uses the same timing code with a fixed 64-token prompt, so runs are comparable on the same model; it does not add a line to the journal.

## What is implemented for speed, without any speed claim

- **Stable prompt prefix.** The system prompt and the tool block are identical on every turn of a chat (`Tools.stableSpec()`), so the model's KV cache is reused instead of being discarded each turn. The old per-question tool list changed the prefix on every message.
- **Whole-turn trimming.** `PromptBudget` drops the oldest complete turn when the context is full. It never removes the current task and never shifts the prefix one message at a time.
- **Thinking off by default, with a budget when on.** With thinking on, reasoning is cut at 1600 characters and retried once with the model's no-think prefill (Qwen3 `/no_think`, empty `<think>` block for DeepSeek), instead of burning the whole answer budget.
- **Reasoning is not the answer.** If a model produces only `<think>` and no reply, ZENIQ retries once without thinking and then shows an explicit error card; it no longer prints the reasoning as the answer.
- **Tool-call repair.** `ToolJson` repairs truncated JSON, string-valued arguments and misspelled tool names before a step is failed.
- **Model choice.** `ModelRouter` classifies each prompt (simple chat, complex chat, coding, tools, vision, reasoning, document, translation) and picks the smallest installed model that fits. The load bonus (+25) means an ordinary question keeps the model that is already loaded; a switch happens before the request, never mid-answer. Settings → Model choice turns this off; picking a model in the chat chip overrides it.
- **Short answers.** Unchanged: a short question is capped at 200 tokens.

## Not implemented from the P1 list

These are not in this build, so they are not claimed:

- P1-05 / P1-06 / P1-07: IDE stack registry and IDE automation work.
- P1-08: the multi-step automation planner rewrite.
- P1-09: multiple simultaneous native model handles (the JNI layer still keeps one context; automatic model choice reloads between models).
- P1-12: benchmark matrix across model files and context sizes (the harness records every answer, but no matrix is produced or published).

## Privacy

`Perf` is memory only: a 40-entry journal, cleared when the app is killed or with the Clear button. No prompt text, no file names, no API keys, and nothing is sent anywhere.
