"""ADR-002 provider spike: Nebius Token Factory vs IONOS AI Model Hub.

Answers the open questions of docs/adr/ADR-002-eu-hosted-llm-provider.md with
measurements instead of opinions:

  1. model discovery   - what does GET /v1/models actually offer per provider
  2. retrieval         - does a DE query find an EN protocol (hard requirement)
  3. threshold         - does one cosine threshold separate hits from misses
                         (that threshold is the Mode A / Mode B switch, NFR-2)
  4. citation prompt   - are [P-nn] citations and the answer language respected
  5. refusal           - does the model admit "no source" instead of inventing
  6. cost + latency    - from the usage fields of the real responses

Each provider is evaluated independently; a missing API key or a failing
provider is recorded and the other provider is still measured.

Raw output goes to results.json. RESULTS.md is written from it by hand so the
interpretation stays reviewable.
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import traceback
from pathlib import Path

import numpy as np
from openai import OpenAI

from corpus import CORPUS, QUERIES

HERE = Path(__file__).parent

# ===========================================================================
# Provider configuration
# ===========================================================================
#
# PRICES — per 1M tokens, in the provider's own billing currency.
#   None  = not publicly listed at the time of the run -> reported as TBD,
#           never guessed.
#
# Nebius: GET /v1/models?verbose=true returns a per-model `pricing` object
#   (per-token strings), so the chat prices below are left None on purpose and
#   filled at runtime from the API itself — the authoritative source.
#   Docs: https://docs.tokenfactory.nebius.com/api-reference/examples/list-of-models
#   The embedding price is not exposed that way for embedding models; the value
#   below is from the Token Factory console model page (Qwen3-Embedding-8B,
#   4096 dims, eu-north1) as inspected on 2026-08-06.
#
# IONOS: published in the official IONOS Cloud price list (EUR):
#   https://docs.ionos.com/cloud/support/general-information/price-list/ionos-cloud-eur-en.md
#   AI Model Hub section, retrieved 2026-08-06:
#     Llama 3.3 70B Instruct   0.65 EUR / 1M input, 0.65 EUR / 1M output
#     Qwen3.5-9B               0.10 EUR / 1M input, 0.15 EUR / 1M output
#     Qwen3.5-397B-A17B        0.60 EUR / 1M input, 3.60 EUR / 1M output
#     bge-m3 (embedding)       0.02 EUR / 1M input (embeddings bill input only)
#     paraphrase-multilingual-mpnet-base-v2  0.01 EUR / 1M input
#     bge-large-en-v1.5        0.015 EUR / 1M input

IONOS_CHAT_PRICES = {
    # lowercase model-id fragment -> (input per 1M, output per 1M) in EUR
    "llama-3.3-70b": (0.65, 0.65),
    "qwen3.5-9b": (0.10, 0.15),
    "qwen3.5-397b": (0.60, 3.60),
}
IONOS_EMBED_PRICES = {
    "bge-m3": 0.02,
    "paraphrase-multilingual-mpnet-base-v2": 0.01,
    "bge-large-en-v1.5": 0.015,
}

PROVIDERS = [
    {
        "key": "nebius",
        "name": "Nebius Token Factory",
        "env": "NEBIUS_API_KEY",
        "base_url": "https://api.tokenfactory.nebius.com/v1",
        "currency": "USD",
        # bge-multilingual-gemma2 / bge-en-icl exist in the catalogue but have
        # no public serverless endpoint (dedicated deployment only), so the
        # multilingual pick here is Qwen3-Embedding-8B.
        "embed_preference": [
            "qwen/qwen3-embedding-8b",
            "baai/bge-m3",
            "intfloat/multilingual-e5-large",
        ],
        "chat_preference": [
            "meta-llama/llama-3.3-70b-instruct",
            "qwen/qwen3-32b",
            "qwen/qwen2.5-72b-instruct",
            "qwen/qwen3-30b-a3b",
        ],
        "embed_price_per_1m": 0.01,  # console, see comment block above
        "chat_price_per_1m": None,   # filled from /v1/models?verbose=true
        # Measured in addition to the primary pick because the primary one
        # breaches NFR-4 on latency; a sparse-MoE model of the same class is
        # the obvious cheaper/faster candidate. See RESULTS.md.
        "chat_alternatives": ["Qwen/Qwen3-30B-A3B-Instruct-2507"],
    },
    {
        "key": "ionos",
        "name": "IONOS AI Model Hub",
        "env": "IONOS_API_KEY",
        "base_url": "https://openai.inference.de-txl.ionos.com/v1",
        "currency": "EUR",
        # The catalogue also carries `bge-m3-migration` and two other
        # `*-migration` aliases; those are compatibility shims for the old
        # endpoint naming, not separate models. The plain ids are the ones to
        # use, so they are matched exactly and the aliases are filtered out.
        "embed_preference": [
            "baai/bge-m3",
            "sentence-transformers/paraphrase-multilingual-mpnet-base-v2",
            "intfloat/multilingual-e5-large",
        ],
        "chat_preference": [
            "meta-llama/llama-3.3-70b-instruct",
            "qwen/qwen3.5-9b",
            "qwen/qwen3.5-397b-a17b",
        ],
        "embed_price_per_1m": None,  # resolved from IONOS_EMBED_PRICES
        "chat_price_per_1m": None,   # resolved from IONOS_CHAT_PRICES
        # Same reason as on Nebius: measure a second, smaller chat model so the
        # latency/citation trade-off can be compared within the provider too.
        "chat_alternatives": ["Qwen/Qwen3.5-9B"],
    },
]

CORPUS_SIZE_TARGET = 150  # protocols to be embedded in Phase 2 (DECISIONS.txt)
MAX_ANSWER_TOKENS = 500

SYSTEM_PROMPT = (
    "You are a maintenance assistant for an industrial plant. You answer "
    "questions from shop-floor staff about maintenance protocols.\n"
    "Rules:\n"
    "1. Answer ONLY from the sources provided in the user message. Never add "
    "knowledge from anywhere else.\n"
    "2. Cite the source protocol after every factual claim, in the format "
    "[P-nn]. A claim without a citation is not allowed.\n"
    "3. Answer in the SAME LANGUAGE the question is written in, even if the "
    "sources are written in another language.\n"
    "4. If the sources do not contain an answer to the question, say in one "
    "sentence that no protocol in the knowledge base covers this case, and "
    "cite nothing. Do not guess and do not offer a repair procedure."
)


# ===========================================================================
# Helpers
# ===========================================================================

def log(msg: str) -> None:
    print(msg, flush=True)


def model_ids(models: list[dict]) -> list[str]:
    return [m.get("id", "") for m in models]


# Deprecated compatibility aliases that must never be picked automatically:
# IONOS still lists `bge-m3-migration` & co. next to the real ids.
ALIAS_IDS = re.compile(r"-migration$", re.IGNORECASE)


def pick_model(models: list[dict], preference: list[str], fallback_pattern: str) -> dict:
    """First preference that exists, else first id matching the fallback regex."""
    ids = [i for i in model_ids(models) if not ALIAS_IDS.search(i)]
    lowered = {i.lower(): i for i in ids}
    for want in preference:
        if want in lowered:
            return {"id": lowered[want], "how": "preferred", "rank": preference.index(want)}
    for want in preference:  # tolerate id prefixes/suffixes (org names differ)
        for low, original in lowered.items():
            if want in low or low.endswith(want.split("/")[-1]):
                return {"id": original, "how": "preferred-substring", "rank": preference.index(want)}
    for low, original in lowered.items():
        if re.search(fallback_pattern, low):
            return {"id": original, "how": "fallback-pattern", "rank": None}
    return {"id": None, "how": "none-available", "rank": None}


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


GERMAN_MARKERS = re.compile(
    r"\b(der|die|das|und|ist|wurde|nicht|kein|keine|wurde|Sensor|Druck|Fehler|"
    r"Presse|nachdem|dadurch|Protokoll|Ursache|Massnahme|Maßnahme|behoben)\b",
    re.IGNORECASE,
)
ENGLISH_MARKERS = re.compile(
    r"\b(the|and|was|were|is|not|no|pressure|sensor|error|press|because|"
    r"protocol|cause|action|fixed|recalibrated)\b",
    re.IGNORECASE,
)
CITATION = re.compile(r"\[P-\d{2}\]")


def looks_german(text: str) -> dict:
    de = len(GERMAN_MARKERS.findall(text))
    en = len(ENGLISH_MARKERS.findall(text))
    return {"german_markers": de, "english_markers": en, "is_german": de > en}


def citation_report(text: str, expected_id: str | None) -> dict:
    cites = CITATION.findall(text)
    cited_ids = sorted({c.strip("[]") for c in cites})
    # rough per-claim check: sentences of substance that carry no citation
    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", text) if len(s.strip()) > 25]
    uncited = [s for s in sentences if not CITATION.search(s)]
    return {
        "citation_count": len(cites),
        "cited_ids": cited_ids,
        "expected_cited": (expected_id in cited_ids) if expected_id else None,
        "sentences_total": len(sentences),
        "sentences_without_citation": len(uncited),
        "uncited_sentences": uncited,
    }


REFUSAL = re.compile(
    r"(kein[e]?\s+(protokoll|quelle|eintrag|information|angaben|hinweis)|"
    r"nicht\s+(abgedeckt|enthalten|vorhanden|dokumentiert)|liegt\s+kein|"
    r"no\s+(protocol|source|information|record)|does\s+not\s+contain)",
    re.IGNORECASE,
)


def refusal_report(text: str) -> dict:
    return {
        "refusal_phrase_found": bool(REFUSAL.search(text)),
        "citation_count": len(CITATION.findall(text)),
        "length_chars": len(text),
    }


# ===========================================================================
# Provider evaluation
# ===========================================================================

def discover_models(client: OpenAI) -> tuple[list[dict], bool]:
    """Return (models, verbose_pricing_available)."""
    try:
        page = client.models.list(extra_query={"verbose": "true"})
        models = [m.model_dump() for m in page.data]
        if any("pricing" in m for m in models):
            return models, True
        return models, False
    except Exception:
        page = client.models.list()
        return [m.model_dump() for m in page.data], False


def nebius_chat_price(models: list[dict], model_id: str) -> tuple[float | None, float | None]:
    """Per-1M input/output price from the verbose model list, if present."""
    for m in models:
        if m.get("id") == model_id and isinstance(m.get("pricing"), dict):
            p = m["pricing"]
            try:
                return float(p["prompt"]) * 1e6, float(p["completion"]) * 1e6
            except (KeyError, TypeError, ValueError):
                return None, None
    return None, None


def lookup(table: dict, model_id: str):
    low = (model_id or "").lower()
    for frag, value in table.items():
        if frag in low:
            return value
    return None


def embed(client: OpenAI, model: str, texts: list[str]) -> tuple[np.ndarray, int, float]:
    """Return (vectors, prompt_tokens, seconds). Falls back to one call per text.

    `encoding_format="float"` is not the default of the OpenAI SDK — it sends
    `base64` and decodes client-side. The IONOS gateway does not implement that
    and answers HTTP 500 ("cannot unmarshal string into ... []float32"), so the
    format is pinned explicitly. Nebius accepts it too, so one code path covers
    both and the two providers stay measured identically.
    """
    t0 = time.perf_counter()
    try:
        resp = client.embeddings.create(model=model, input=texts, encoding_format="float")
        vectors = np.array([d.embedding for d in resp.data], dtype=np.float32)
        tokens = getattr(resp.usage, "prompt_tokens", 0) or getattr(resp.usage, "total_tokens", 0)
        return vectors, tokens, time.perf_counter() - t0
    except Exception as exc:
        log(f"    batch embedding failed ({exc.__class__.__name__}), retrying one by one")
        vectors, tokens = [], 0
        t0 = time.perf_counter()
        for text in texts:
            resp = client.embeddings.create(model=model, input=text, encoding_format="float")
            vectors.append(resp.data[0].embedding)
            tokens += getattr(resp.usage, "prompt_tokens", 0) or 0
        return np.array(vectors, dtype=np.float32), tokens, time.perf_counter() - t0


def chat(client: OpenAI, model: str, question: str, sources: list[dict]) -> dict:
    block = "\n\n".join(f"[{s['id']}]\n{s['text']}" for s in sources)
    user = f"Sources:\n\n{block}\n\n---\n\nQuestion: {question}"
    t0 = time.perf_counter()
    resp = client.chat.completions.create(
        model=model,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user},
        ],
        temperature=0.0,
        max_tokens=MAX_ANSWER_TOKENS,
    )
    latency = time.perf_counter() - t0
    usage = resp.usage
    return {
        "answer": resp.choices[0].message.content or "",
        "prompt_tokens": getattr(usage, "prompt_tokens", None),
        "completion_tokens": getattr(usage, "completion_tokens", None),
        "total_tokens": getattr(usage, "total_tokens", None),
        "latency_s": round(latency, 3),
        "finish_reason": resp.choices[0].finish_reason,
    }


def run_chat_tests(client: OpenAI, model_id: str, retrieval: list[dict]) -> dict:
    """Citation test (query b) and refusal test (query c) for one chat model."""
    by_id = {p["id"]: p for p in CORPUS}
    results: dict = {}
    for q in QUERIES:
        if q["key"] not in ("b", "c"):
            continue
        r = next(x for x in retrieval if x["key"] == q["key"])
        sources = [by_id[h["id"]] for h in r["top3"]]
        try:
            res = chat(client, model_id, q["text"], sources)
        except Exception as exc:
            results[q["key"]] = {"error": f"{exc.__class__.__name__}: {exc}"}
            continue
        res["model"] = model_id
        res["sources_given"] = [s["id"] for s in sources]
        res["language"] = looks_german(res["answer"])
        if q["kind"] == "hit":
            res["citations"] = citation_report(res["answer"], q["expected"])
        else:
            res["refusal"] = refusal_report(res["answer"])
        results[q["key"]] = res
        log(f"  chat ({q['key']}): {res['latency_s']}s, "
            f"{res['prompt_tokens']}+{res['completion_tokens']} tokens")
    return results


def evaluate(provider: dict) -> dict:
    out: dict = {"provider": provider["key"], "name": provider["name"],
                 "base_url": provider["base_url"], "currency": provider["currency"]}

    api_key = os.environ.get(provider["env"], "").strip()
    if not api_key:
        out["status"] = "skipped"
        out["reason"] = f"{provider['env']} not set in the environment"
        log(f"  SKIPPED: {out['reason']}")
        return out

    client = OpenAI(api_key=api_key, base_url=provider["base_url"], timeout=120.0, max_retries=2)

    # --- 1. discovery ------------------------------------------------------
    models, verbose = discover_models(client)
    ids = sorted(model_ids(models))
    out["models_total"] = len(ids)
    out["model_ids"] = ids
    out["verbose_pricing_available"] = verbose

    embed_pick = pick_model(models, provider["embed_preference"], r"embed|bge|e5-|gte|multilingual")
    chat_pick = pick_model(models, provider["chat_preference"], r"instruct|chat|-it$")
    out["embedding_model"] = embed_pick
    out["chat_model"] = chat_pick
    out["embedding_candidates"] = [i for i in ids if re.search(r"embed|bge|e5|gte", i, re.I)]
    log(f"  {len(ids)} models. embedding={embed_pick['id']} ({embed_pick['how']}), "
        f"chat={chat_pick['id']} ({chat_pick['how']})")

    if not embed_pick["id"]:
        out["status"] = "failed"
        out["reason"] = "no embedding model available — disqualifying per ADR-002"
        return out

    # --- prices ------------------------------------------------------------
    if provider["key"] == "nebius":
        chat_in, chat_out = nebius_chat_price(models, chat_pick["id"])
        embed_in = provider["embed_price_per_1m"]
        price_source = "GET /v1/models?verbose=true (chat) + console model page (embedding)"
    else:
        pair = lookup(IONOS_CHAT_PRICES, chat_pick["id"]) or (None, None)
        chat_in, chat_out = pair
        embed_in = lookup(IONOS_EMBED_PRICES, embed_pick["id"])
        price_source = "IONOS Cloud EUR price list, AI Model Hub section"
    out["prices_per_1m"] = {"embed_input": embed_in, "chat_input": chat_in,
                            "chat_output": chat_out, "source": price_source}

    # --- 2./3. retrieval + threshold --------------------------------------
    corpus_texts = [p["text"] for p in CORPUS]
    corpus_vecs, corpus_tokens, corpus_latency = embed(client, embed_pick["id"], corpus_texts)
    out["embedding_dims"] = int(corpus_vecs.shape[1])
    out["corpus_embed"] = {"tokens": corpus_tokens, "latency_s": round(corpus_latency, 3),
                           "protocols": len(CORPUS),
                           "avg_tokens_per_protocol": round(corpus_tokens / len(CORPUS), 1)}
    log(f"  corpus embedded: {corpus_tokens} tokens, {out['embedding_dims']} dims, "
        f"{corpus_latency:.2f}s")

    retrieval = []
    query_embed_tokens, query_embed_latency = 0, 0.0
    for q in QUERIES:
        qvec, qtok, qlat = embed(client, embed_pick["id"], [q["text"]])
        query_embed_tokens += qtok
        query_embed_latency += qlat
        scores = [(CORPUS[i]["id"], CORPUS[i]["lang"], cosine(qvec[0], corpus_vecs[i]))
                  for i in range(len(CORPUS))]
        scores.sort(key=lambda s: s[2], reverse=True)
        top3 = [{"id": i, "lang": l, "score": round(s, 4)} for i, l, s in scores[:3]]
        retrieval.append({
            "key": q["key"], "label": q["label"], "query": q["text"],
            "expected": q["expected"], "kind": q["kind"],
            "top3": top3,
            "top1_score": top3[0]["score"],
            "expected_is_top1": (top3[0]["id"] == q["expected"]) if q["expected"] else None,
            "expected_rank": next((n + 1 for n, s in enumerate(scores) if s[0] == q["expected"]), None)
            if q["expected"] else None,
            "cross_language": (q["expected"] and
                               CORPUS[[c["id"] for c in CORPUS].index(q["expected"])]["lang"] != "de")
            if q["expected"] else None,
            "embed_tokens": qtok, "embed_latency_s": round(qlat, 3),
        })
        log(f"  query ({q['key']}) top1 = {top3[0]['id']} @ {top3[0]['score']:.4f} "
            f"(expected {q['expected']})")

    hits = [r["top1_score"] for r in retrieval if r["kind"] == "hit"]
    misses = [r["top1_score"] for r in retrieval if r["kind"] == "miss"]
    separates = bool(hits and misses and min(hits) > max(misses))
    out["threshold"] = {
        "lowest_hit_top1": min(hits) if hits else None,
        "highest_miss_top1": max(misses) if misses else None,
        "margin": round(min(hits) - max(misses), 4) if hits and misses else None,
        "separates": separates,
        "proposed": round((min(hits) + max(misses)) / 2, 3) if separates else None,
    }
    out["retrieval"] = retrieval

    # --- 4./5. citation + refusal -----------------------------------------
    if not chat_pick["id"]:
        out["status"] = "partial"
        out["reason"] = "no chat model available; retrieval measured, generation not"
        return out

    chat_results = run_chat_tests(client, chat_pick["id"], retrieval)
    out["chat"] = chat_results

    # Secondary chat models, measured only for latency/compliance comparison.
    alternatives = {}
    for alt in provider.get("chat_alternatives", []):
        if alt not in ids:
            alternatives[alt] = {"error": "not offered by this provider"}
            continue
        log(f"  -- alternative chat model: {alt}")
        alternatives[alt] = run_chat_tests(client, alt, retrieval)
        alt_in, alt_out = (nebius_chat_price(models, alt) if provider["key"] == "nebius"
                           else (lookup(IONOS_CHAT_PRICES, alt) or (None, None)))
        alternatives[alt]["prices_per_1m"] = {"chat_input": alt_in, "chat_output": alt_out}
    out["chat_alternatives"] = alternatives

    # --- 6. cost -----------------------------------------------------------
    b = chat_results.get("b", {})
    q_embed_tokens = next(r["embed_tokens"] for r in retrieval if r["key"] == "b")
    cost = {"basis": "measured usage of query (b), the end-to-end Mode A path"}
    if embed_in is not None and b.get("prompt_tokens") is not None and chat_in is not None:
        per_query = (q_embed_tokens / 1e6) * embed_in \
            + (b["prompt_tokens"] / 1e6) * chat_in \
            + (b["completion_tokens"] / 1e6) * chat_out
        cost["per_query"] = round(per_query, 8)
        cost["per_1000_queries"] = round(per_query * 1000, 4)
    else:
        cost["per_query"] = None
        cost["note"] = "TBD — a required price is not publicly listed"
    if embed_in is not None:
        per_protocol = corpus_tokens / len(CORPUS)
        cost["corpus_150_protocols"] = round(
            (per_protocol * CORPUS_SIZE_TARGET / 1e6) * embed_in, 8)
        cost["corpus_tokens_estimated"] = int(per_protocol * CORPUS_SIZE_TARGET)
    else:
        cost["corpus_150_protocols"] = None
    out["cost"] = cost

    out["latency"] = {
        "corpus_embed_s": round(corpus_latency, 3),
        "query_embed_avg_s": round(query_embed_latency / len(QUERIES), 3),
        "chat_b_s": b.get("latency_s"),
        "chat_c_s": chat_results.get("c", {}).get("latency_s"),
        "end_to_end_b_s": round(
            next(r["embed_latency_s"] for r in retrieval if r["key"] == "b")
            + (b.get("latency_s") or 0), 3),
    }
    out["status"] = "ok"
    return out


# ===========================================================================
# Main
# ===========================================================================

def main() -> int:
    log("ADR-002 spike — Nebius Token Factory vs IONOS AI Model Hub")
    log(f"corpus: {len(CORPUS)} protocols "
        f"({sum(1 for p in CORPUS if p['lang'] == 'de')} DE / "
        f"{sum(1 for p in CORPUS if p['lang'] == 'en')} EN), {len(QUERIES)} queries\n")

    # Optional provider filter: `python spike.py ionos` re-runs one provider and
    # merges into the existing results.json instead of discarding the other
    # provider's recorded run.
    selected = [a.lower() for a in sys.argv[1:]] or None
    providers = [p for p in PROVIDERS if not selected or p["key"] in selected]
    if selected:
        log(f"provider filter: {', '.join(selected)}\n")

    results = []
    for provider in providers:
        log(f"== {provider['name']} ==")
        try:
            results.append(evaluate(provider))
        except Exception as exc:
            log(f"  FAILED: {exc.__class__.__name__}: {exc}")
            results.append({
                "provider": provider["key"], "name": provider["name"],
                "status": "failed", "reason": f"{exc.__class__.__name__}: {exc}",
                "traceback": traceback.format_exc(),
            })
        log("")

    merged = results
    out_path = HERE / "results.json"
    if selected and out_path.exists():
        previous = json.loads(out_path.read_text(encoding="utf-8")).get("results", [])
        fresh = {r["provider"] for r in results}
        merged = results + [r for r in previous if r.get("provider") not in fresh]
        merged.sort(key=lambda r: [p["key"] for p in PROVIDERS].index(r["provider"]))
        log(f"merged with the recorded run of: "
            f"{', '.join(r['provider'] for r in merged if r['provider'] not in fresh) or 'nothing'}")

    payload = {
        "corpus": [{k: v for k, v in p.items()} for p in CORPUS],
        "queries": QUERIES,
        "system_prompt": SYSTEM_PROMPT,
        "max_answer_tokens": MAX_ANSWER_TOKENS,
        "results": merged,
    }
    (HERE / "results.json").write_text(json.dumps(payload, indent=2, ensure_ascii=False),
                                       encoding="utf-8")
    log(f"raw output written to {HERE / 'results.json'}")

    usable = [r for r in results if r.get("status") in ("ok", "partial")]
    if not usable:
        log("no provider could be evaluated — check the API keys in .env")
        return 1
    log(f"evaluated: {', '.join(r['name'] for r in usable)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
