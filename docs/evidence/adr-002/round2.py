"""ADR-002 spike, round 2: can prompt engineering fix what round 1 broke?

Round 1 (spike.py) left three concrete defects on the table:

  * citations   - the smaller/faster chat models summarise and put one [P-nn]
                  at the end instead of citing every claim (NFR-2 wants the
                  source next to the claim, that is the whole Mode A promise)
  * language    - Llama-3.3-70B refuses in ENGLISH to a GERMAN question on both
                  providers, so the Mode B path would show a German operator an
                  English message
  * latency     - NFR-4 wants <=10 s; round 1 ran with max_tokens=500 and no
                  output cap discipline

This script re-measures all three against BOTH providers with four system
prompt variants, so the effect of each change is attributable:

  V0  baseline          - the exact round-1 prompt (control)
  V1  + few-shot        - one worked example showing [P-nn] after every claim
  V2  + language pin    - explicit "ALL output, including refusals, in the
                          language of the question"
  V3  both              - the candidate Phase-3 prompt

Everything runs with max_tokens=400 (the NFR-7 cap), which is also the latency
condition (c): round 1 used 500.

No embeddings are computed here. The sources handed to the model are the top-3
hits each provider actually retrieved in round 1, read from results.json, so
round 2 measures generation only and each provider is judged on its own
retrieval.

Raw output goes to round2.json; RESULTS.md is written from it by hand.
"""

from __future__ import annotations

import json
import os
import sys
import time
import traceback
from pathlib import Path

from openai import OpenAI

from corpus import CORPUS, QUERIES
from spike import SYSTEM_PROMPT, citation_report, looks_german, refusal_report

HERE = Path(__file__).parent

# NFR-7 layer 2: the per-answer output cap the application will set.
MAX_ANSWER_TOKENS = 400
LATENCY_REPEATS = 3  # for the candidate prompt, so the number is a range

# ===========================================================================
# Models under test
# ===========================================================================
#
# extra: kwargs passed straight to chat.completions.create.
#
# Qwen3.5-9B on IONOS is a *reasoning* model: by default it emits a long
# `reasoning` field and, at any cap this project can afford, never reaches the
# answer — round 1 recorded finish_reason="length" with content = "" twice.
# `reasoning_effort="none"` is the only switch that turns it off; neither
# chat_template_kwargs={"enable_thinking": False} nor reasoning_effort="low"
# nor a "/no_think" instruction had any effect (all still returned empty
# content at 400 tokens). Measured 2026-08-06, see RESULTS.md §7.

TARGETS = [
    {"provider": "nebius", "env": "NEBIUS_API_KEY",
     "base_url": "https://api.tokenfactory.nebius.com/v1",
     "model": "meta-llama/Llama-3.3-70B-Instruct", "extra": {}},
    {"provider": "nebius", "env": "NEBIUS_API_KEY",
     "base_url": "https://api.tokenfactory.nebius.com/v1",
     "model": "Qwen/Qwen3-30B-A3B-Instruct-2507", "extra": {}},
    {"provider": "ionos", "env": "IONOS_API_KEY",
     "base_url": "https://openai.inference.de-txl.ionos.com/v1",
     "model": "meta-llama/Llama-3.3-70B-Instruct", "extra": {}},
    {"provider": "ionos", "env": "IONOS_API_KEY",
     "base_url": "https://openai.inference.de-txl.ionos.com/v1",
     "model": "Qwen/Qwen3.5-9B", "extra": {"reasoning_effort": "none"}},
]

# ===========================================================================
# Prompt variants
# ===========================================================================

# (a) Few-shot. One worked example is enough to show the *shape*: one claim per
# sentence, the source id immediately after the claim, no trailing footnote.
# The example deliberately uses P-99 / a machine that is not in the corpus, so
# it cannot be mistaken for a source and cannot leak content into an answer.
FEW_SHOT = """
Format example (this is NOT a source, do not cite P-99 in your answer):

  Sources:
  [P-99]
  Protocol P-99 | Pump 7 (PU-07) | fault E-11
  Symptom: The pump loses suction after ten minutes.
  Cause: Blocked inlet strainer.
  Action: Cleaned the strainer, refilled the system, ran a 30 minute test.

  Question: Pumpe 7 verliert Saugleistung, was tun?

  Correct answer:
  Die Pumpe verliert nach etwa zehn Minuten die Saugleistung [P-99]. Ursache ist
  ein verstopfter Saugkorb [P-99]. Als Massnahme wurde der Saugkorb gereinigt,
  die Anlage neu befuellt und 30 Minuten im Testlauf geprueft [P-99].

Note what makes it correct: EVERY sentence carries its own [P-nn] directly at
the end of that sentence. A single citation at the end of the whole answer is
WRONG, even if the answer is otherwise grounded.
""".strip()

# (b) Language pinning. Rule 3 of the baseline already says "answer in the same
# language" — both Llama deployments still refused in English, because the
# refusal does not feel like an "answer". This closes that reading explicitly.
LANGUAGE_PIN = """
LANGUAGE RULE (overrides everything else): ALL text you output must be in the
language of the QUESTION, never the language of the sources. This includes the
"no protocol covers this" case from rule 4: if the question is German, the
refusal must be German. There is no exception. Decide the language of the
question first, then write every word of your output in that language.
""".strip()

VARIANTS = {
    "V0": {"label": "baseline (round-1 prompt)", "prompt": SYSTEM_PROMPT},
    "V1": {"label": "+ few-shot citation example", "prompt": SYSTEM_PROMPT + "\n\n" + FEW_SHOT},
    "V2": {"label": "+ language pinning", "prompt": SYSTEM_PROMPT + "\n\n" + LANGUAGE_PIN},
    "V3": {"label": "+ few-shot + language pinning (candidate)",
           "prompt": SYSTEM_PROMPT + "\n\n" + FEW_SHOT + "\n\n" + LANGUAGE_PIN},
}

# (d) Structured output — the app-side enforcement path. If a model will not
# cite per claim in prose, the application can demand a shape in which an
# uncited claim is not representable, and render the prose itself.
JSON_PROMPT = SYSTEM_PROMPT + "\n\n" + LANGUAGE_PIN + """

OUTPUT FORMAT: reply with a single JSON object and nothing else:
{"answer_language": "<ISO 639-1 code of the QUESTION>",
 "claims": [{"text": "<one factual claim, no citation marker inside>",
             "source": "<the P-nn id this claim comes from>"}]}
Every claim MUST carry its source. If the sources do not answer the question,
return {"answer_language": "<code>", "claims": []} and nothing else.
"""

JSON_SCHEMA = {
    "type": "object",
    "properties": {
        "answer_language": {"type": "string"},
        "claims": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"text": {"type": "string"}, "source": {"type": "string"}},
                "required": ["text", "source"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["answer_language", "claims"],
    "additionalProperties": False,
}


# ===========================================================================
# Helpers
# ===========================================================================

def log(msg: str) -> None:
    print(msg, flush=True)


def sources_for(provider: str, query_key: str) -> list[dict]:
    """The top-3 protocols this provider actually retrieved in round 1."""
    data = json.loads((HERE / "results.json").read_text(encoding="utf-8"))
    result = next(r for r in data["results"] if r["provider"] == provider)
    hit = next(r for r in result["retrieval"] if r["key"] == query_key)
    by_id = {p["id"]: p for p in CORPUS}
    return [by_id[h["id"]] for h in hit["top3"]]


def user_message(question: str, sources: list[dict]) -> str:
    block = "\n\n".join(f"[{s['id']}]\n{s['text']}" for s in sources)
    return f"Sources:\n\n{block}\n\n---\n\nQuestion: {question}"


def ask(client: OpenAI, model: str, system: str, user: str, extra: dict,
        response_format: dict | None = None) -> dict:
    kwargs = dict(extra)
    if response_format:
        kwargs["response_format"] = response_format
    t0 = time.perf_counter()
    resp = client.chat.completions.create(
        model=model,
        messages=[{"role": "system", "content": system}, {"role": "user", "content": user}],
        temperature=0.0,
        max_tokens=MAX_ANSWER_TOKENS,
        **kwargs,
    )
    latency = time.perf_counter() - t0
    choice = resp.choices[0]
    # Reasoning models put their scratchpad in a side field and can run out of
    # budget before emitting any content; record that instead of hiding it.
    reasoning = getattr(choice.message, "reasoning", None) or \
        getattr(choice.message, "reasoning_content", None) or ""
    return {
        "answer": choice.message.content or "",
        "reasoning_chars": len(reasoning),
        "empty_answer": not (choice.message.content or "").strip(),
        "prompt_tokens": resp.usage.prompt_tokens,
        "completion_tokens": resp.usage.completion_tokens,
        "latency_s": round(latency, 3),
        "finish_reason": choice.finish_reason,
    }


def score(res: dict, query: dict) -> dict:
    text = res["answer"]
    res["language"] = looks_german(text)
    if query["kind"] == "hit":
        res["citations"] = citation_report(text, query["expected"])
    else:
        res["refusal"] = refusal_report(text)
        res["refusal"]["language_correct"] = res["language"]["is_german"]
    return res


# ===========================================================================
# Run
# ===========================================================================

def run_target(target: dict) -> dict:
    key = f"{target['provider']}:{target['model']}"
    out: dict = {"provider": target["provider"], "model": target["model"],
                 "extra_kwargs": target["extra"], "max_tokens": MAX_ANSWER_TOKENS}

    api_key = os.environ.get(target["env"], "").strip()
    if not api_key:
        out["status"] = "skipped"
        out["reason"] = f"{target['env']} not set"
        return out

    client = OpenAI(api_key=api_key, base_url=target["base_url"], timeout=180.0, max_retries=2)
    by_key = {q["key"]: q for q in QUERIES}

    # --- (a)+(b)+(c): the four prompt variants -----------------------------
    variants: dict = {}
    for vid, variant in VARIANTS.items():
        variants[vid] = {"label": variant["label"]}
        for qk in ("b", "c"):          # b = grounded/citation, c = refusal
            q = by_key[qk]
            try:
                res = ask(client, target["model"], variant["prompt"],
                          user_message(q["text"], sources_for(target["provider"], qk)),
                          target["extra"])
                variants[vid][qk] = score(res, q)
                cites = res.get("citations", {})
                extra = (f"{cites['citation_count']} cites / "
                         f"{cites['sentences_total']} sentences"
                         if qk == "b" else
                         f"refused={res['refusal']['refusal_phrase_found']} "
                         f"german={res['refusal']['language_correct']}")
                log(f"    {vid} ({qk}) {res['latency_s']:>6.2f}s  {extra}"
                    + ("  EMPTY ANSWER" if res["empty_answer"] else ""))
            except Exception as exc:
                variants[vid][qk] = {"error": f"{exc.__class__.__name__}: {exc}"}
                log(f"    {vid} ({qk}) ERROR {exc.__class__.__name__}")
    out["variants"] = variants

    # --- (c): latency of the candidate prompt, repeated --------------------
    q = by_key["b"]
    user = user_message(q["text"], sources_for(target["provider"], "b"))
    runs = []
    for n in range(LATENCY_REPEATS):
        try:
            res = ask(client, target["model"], VARIANTS["V3"]["prompt"], user, target["extra"])
            runs.append({"latency_s": res["latency_s"],
                         "completion_tokens": res["completion_tokens"],
                         "finish_reason": res["finish_reason"]})
        except Exception as exc:
            runs.append({"error": f"{exc.__class__.__name__}: {exc}"})
    ok = [r["latency_s"] for r in runs if "latency_s" in r]
    out["latency_v3"] = {
        "runs": runs,
        "min_s": min(ok) if ok else None,
        "max_s": max(ok) if ok else None,
        "median_s": sorted(ok)[len(ok) // 2] if ok else None,
        "meets_nfr4_target_10s": (max(ok) <= 10.0) if ok else None,
        "meets_nfr4_ceiling_30s": (max(ok) <= 30.0) if ok else None,
    }
    log(f"    V3 latency x{LATENCY_REPEATS}: {ok} s")

    # --- (d): structured JSON ---------------------------------------------
    json_out: dict = {}
    for qk in ("b", "c"):
        q = by_key[qk]
        user = user_message(q["text"], sources_for(target["provider"], qk))
        attempt = None
        for label, fmt in (
            ("json_schema", {"type": "json_schema",
                             "json_schema": {"name": "grounded_answer",
                                             "schema": JSON_SCHEMA, "strict": True}}),
            ("json_object", {"type": "json_object"}),
            ("prompt_only", None),
        ):
            try:
                res = ask(client, target["model"], JSON_PROMPT, user, target["extra"], fmt)
                res["response_format"] = label
                attempt = res
                break
            except Exception as exc:
                attempt = {"error": f"{exc.__class__.__name__}: {exc}", "response_format": label}
        if attempt and "answer" in attempt:
            try:
                parsed = json.loads(attempt["answer"])
                attempt["parsed_ok"] = True
                attempt["parsed"] = parsed
                claims = parsed.get("claims", []) if isinstance(parsed, dict) else []
                attempt["claims_total"] = len(claims)
                attempt["claims_with_source"] = sum(
                    1 for c in claims if isinstance(c, dict) and str(c.get("source", "")).strip())
                attempt["answer_language"] = (parsed.get("answer_language")
                                              if isinstance(parsed, dict) else None)
            except (json.JSONDecodeError, AttributeError):
                attempt["parsed_ok"] = False
        json_out[qk] = attempt
        log(f"    JSON ({qk}) via {attempt.get('response_format')}: "
            f"parsed={attempt.get('parsed_ok')} "
            f"claims={attempt.get('claims_with_source')}/{attempt.get('claims_total')} "
            f"lang={attempt.get('answer_language')}")
    out["structured_json"] = json_out

    out["status"] = "ok"
    log(f"  done: {key}")
    return out


def main() -> int:
    log("ADR-002 spike round 2 — citation / language / latency engineering")
    labels = ", ".join(f"{k}={v['label']}" for k, v in VARIANTS.items())
    log(f"max_tokens={MAX_ANSWER_TOKENS} (NFR-7 cap), variants: {labels}\n")

    selected = [a.lower() for a in sys.argv[1:]] or None
    results = []
    for target in TARGETS:
        if selected and target["provider"] not in selected:
            continue
        log(f"== {target['provider']} / {target['model']} ==")
        try:
            results.append(run_target(target))
        except Exception as exc:
            log(f"  FAILED: {exc.__class__.__name__}: {exc}")
            results.append({"provider": target["provider"], "model": target["model"],
                            "status": "failed", "reason": f"{exc.__class__.__name__}: {exc}",
                            "traceback": traceback.format_exc()})
        log("")

    payload = {
        "max_answer_tokens": MAX_ANSWER_TOKENS,
        "latency_repeats": LATENCY_REPEATS,
        "variants": {k: {"label": v["label"], "prompt": v["prompt"]} for k, v in VARIANTS.items()},
        "json_prompt": JSON_PROMPT,
        "results": results,
    }
    (HERE / "round2.json").write_text(json.dumps(payload, indent=2, ensure_ascii=False),
                                      encoding="utf-8")
    log(f"raw output written to {HERE / 'round2.json'}")
    return 0 if any(r.get("status") == "ok" for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
