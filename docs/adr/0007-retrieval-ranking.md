# 0007: Retrieval ranking choices, measured

Status: accepted. Date: 2026-11-02.

The first hybrid search scored recall@5 of 0.34 on the 50-question CardDemo set, against a target of 0.8. The first lexical search scored 0.08. Each decision below came from reading what the misses returned.

| Problem seen | Decision |
|---|---|
| "is" matched 251 chunks, and whole data divisions of up to 1,317 lines outranked everything | Stop words go. Terms are weighted by inverse document frequency. Long units pay for their length. The part before the first paragraph is one chunk per 01 or 77 record |
| A question says "account" and the code says ACCT | Identifier parts are indexed beside whole identifiers (migration V4). Question words get common mainframe abbreviations and plain word forms, at reduced weight. The index itself does not stem, since stemming would mangle identifiers |
| READMEs ranked first, and supplied false CICS transactions | Documents are classified `unknown` and are not indexed. This also removed 6 false RUNS_IN edges from CardDemo |
| JCL split at the first step, away from the comments saying what the job is for | Jobs of up to 150 lines are one chunk. Longer ones split by step, each with the comments above it |
| Programs using a field outranked the copybook declaring it | A chunk that declares a term the question names gets a strong boost |
| DBD and IDCAMS definitions could not be found | `control` members are indexed |
| A top lexical hit lost to anything both sides ranked middling | RRF k is 5, not the customary 60. The semantic list is weighted 0.7, and 0.35 when the question names an identifier |
| Embedding text carried no location, and nomic models expect task prefixes | Embedded text starts with the member and unit. `Embedder.asDocument` and `asQuery` add the prefixes |

## Alternative rejected

A cross-encoder or LLM reranker would probably help most on the natural language misses. It adds a model to install or a per-query LLM call, and the spec's ranking is RRF with a reviewed boost, so it stays out unless a client's numbers call for it.

## Limits of these numbers

The k and weight values were picked with a small grid on the same 50 questions they are then scored on. An agent that had not seen the questions or the code then wrote a 30-question held-out set, reported separately in `docs/evidence/M5.md`. One fix, indexing `control` members, was prompted by a miss in both sets, so only the first held-out figure is free of that influence. Ranking was not changed after that.

The embedding model is the local `nomic-embed-text`. A client's larger hosted model may rank differently. Rerun `toolkit eval` after choosing one.
