# Synthetic protocol corpus

`protocols.ndjson` holds the ~150 maintenance protocols this demo runs on. It is **data, not
code** — nothing generates protocols at runtime, and the file is the record of what the corpus is.

The protocols are synthetic and were generated for this project (see `AI-USAGE.md`). No real
customer document is used anywhere in this repository.

## Why NDJSON, and why on the classpath

One protocol per line, one JSON object per protocol.

- **Line-oriented** — the loader streams it and can report `line 87: …` when a record is bad, which
  a single top-level JSON array cannot do without holding the whole file in memory first.
- **Reviewable** — a change to one protocol is a one-line diff. A pretty-printed array would show a
  wording fix as a re-indent of everything after it.
- **Appendable** — adding protocols later is an append, not an edit of a surrounding structure.
- **On the classpath** (`src/main/resources/corpus/`) rather than in a mounted directory, so the
  loader behaves identically on a laptop and inside the container with no volume, no build step and
  no path configuration. At roughly 250 KB it costs nothing in the image.

## Record shape

Every key **except `meta`** maps directly to a column of the `protocol` table (Flyway `V1`). `meta`
is corpus metadata: it is used to control and verify the distribution, and the loader ignores it.
The `V1` schema is frozen — none of it becomes a column.

```jsonc
{
  "id":                  "0f9c5b02-…",   // fixed UUID, so the seed is idempotent and PR 3 can pin tests to it
  "machine_id":          "0f9c5b01-…",   // one of the 10 UUIDs from R__seed_machines.sql
  "machine_no":          "PR-03",        // redundant with machine_id; kept for readability of the file
  "incident_date":       "2024-10-08",
  "protocol_type":       "STOERUNG",     // STOERUNG | WARTUNG
  "error_code":          "E-47",         // free text, nullable, sometimes wrong on purpose
  "title":               "…",
  "symptom":             "…",
  "cause":               "…",            // nullable — WARTUNG has none, and some faults were never explained
  "action":              "…",
  "parts_used":          "…",            // nullable
  "downtime_minutes":    410,            // nullable
  "technician_initials": "MK",
  "language":            "de",           // de | en — stored as written, nothing is translated
  "uploaded_by":         "schichtleiter",
  "meta": {
    "cause_class":   "TECHNIK",          // MATERIAL | BEDIENUNG | TECHNIK; null for WARTUNG
    "cause_subtype": "hydraulisch",      // finer bucket inside cause_class
    "quality":       "structured",       // structured | messy | sparse
    "resolution":    "resolved",         // resolved | unresolved | workaround
    "charset":       "ascii",            // only where the text deliberately avoids umlauts
    "demo":          "e47-1",            // marks a protocol a scripted demo depends on
    "note":          "…"                 // free note, e.g. why an alarm text is misleading
  }
}
```

`meta.demo` is the one field worth grepping before changing anything: those protocols carry the
demo scenarios described in `docs/DOMAIN-MODEL.md` §4, and rewording them can break a demo query.

## Distribution

Documented and kept current in [docs/DOMAIN-MODEL.md](../../../../../../docs/DOMAIN-MODEL.md) §4.
Verify it against the file rather than trusting this paragraph — the loader logs the same figures
on startup.
