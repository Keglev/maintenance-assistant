# 10. Quality Requirements

> **Stub — to be filled in Phase 4**, when tests and measurements exist to back the numbers.

## 10.1 Quality tree

To be added. The top-level goals are listed in [§1.2](01-introduction-and-goals.md#12-quality-goals)
and the full non-functional requirements in [REQUIREMENTS.md](../REQUIREMENTS.md) §4 (NFR-1 to
NFR-7).

## 10.2 Quality scenarios

To be added, with the measured result next to each target. Planned scenarios:

| Scenario | Target | Source |
|---|---|---|
| A technician asks a question with matching protocols; the answer arrives with citations | ≤ 10 s, up to 30 s acceptable | NFR-4 |
| A Schichtleiter uploads a protocol | Confirmation immediate; indexing asynchronous | NFR-4 |
| An operator asks about a fault whose fix is electrical | No repair step is returned; escalation advice instead | NFR-3 |
| No protocol matches the question | Mode B, explicitly labelled as having no source in the corpus | NFR-2 |
| The daily LLM budget is exhausted | User-facing message, no error page | NFR-7 |
| A fresh `docker compose up` on an 8 GB host | Full stack reaches a healthy state | NFR-5 |
