# Architecture

How the code is organised, and the decisions that aren't obvious from reading it.

## Classes

All code is in `src/main/java/com/hhovhann/cpiassistant/`.

| Class | Role | Runs |
|---|---|---|
| `LangChain4jConfig` | Builds the LangChain4j beans: HTTP client, chat model, embedding model, vector store | Once, at startup |
| `IngestionProperties` | Chunking settings bound from `cpi.ingestion.*` | — |
| `IngestionPipeline` | Loads the docs, splits them into chunks, embeds and stores them | Once, at startup |
| `IngestionRunner` | Drives the pipeline at startup and logs stats plus probe searches | Once, at startup |
| `RetrievalService` | Embeds a question and returns the closest chunks above the score floor | Per question |
| `RagService` | Retrieve → build a numbered prompt → call the LLM → parse the answer's `[n]` citations into sources | Per question |
| `RagController` | `GET /ask`, and `GET /status` for readiness | Per question |
| `LangChain4jChatController` | `GET /chat` — the LLM alone, no retrieval | Per question |
| `SpringAiChatController` | `GET /springai/chat` — the same through Spring AI | Per question |
| `static/index.html` | Web UI: calls `/status`, `/ask` and optionally `/chat` | In the browser |

## Two halves: ingestion and retrieval

```mermaid
flowchart TB
    subgraph Ingestion["Ingestion — slow, once"]
        direction LR
        IR[IngestionRunner] --> IP[IngestionPipeline]
        IP -->|loadDocuments| D[cpi-docs/*.txt]
        IP -->|split| C[chunks]
        IP -->|embed| EM1[EmbeddingModel]
        IP -->|addAll| ES[(EmbeddingStore)]
    end
    subgraph Query["Query — fast, per request"]
        direction LR
        RC[RagController] --> RS[RagService]
        RS -->|search| RT[RetrievalService]
        RT --> EM2[EmbeddingModel]
        RT --> ES2[(EmbeddingStore)]
        RS -->|buildPrompt + chat| CM[ChatModel]
    end
```

They are separate classes on purpose: ingestion runs once and may take
seconds; retrieval runs on every question and must be fast. They share two
beans — the `EmbeddingModel` (must be the same on both sides) and the
`EmbeddingStore`.

## Two frameworks side by side

| | Track A — LangChain4j | Track B — Spring AI |
|---|---|---|
| Wiring | By hand, in `LangChain4jConfig` | Spring Boot auto-configuration |
| Config | `langchain4j.open-ai.*` | `spring.ai.openai.*` |
| Endpoints | `/chat`, `/ask` | `/springai/chat` |
| Status | Full RAG pipeline | Plain chat only (Phase 3) |

Both talk to the same LM Studio server.

## Decisions worth knowing

**No LangChain4j Spring Boot starters.** They are built against Spring Boot
3.5 and fail on Boot 4 (`NoClassDefFoundError: RestClientAutoConfiguration`).
The LangChain4j core has no Spring dependency, so the beans are built in
`LangChain4jConfig` instead. That is also the learning goal: nothing hidden.

**Timeouts are set on the models, not the HTTP client.** `OpenAiChatModel` and
`OpenAiEmbeddingModel` pass their own timeout to the HTTP client — 60 s unless
`.timeout(...)` is set — and it overrides the client's. A 3-minute timeout on the
client was silently ignored until the answer evaluation hit it.
`langchain4j.open-ai.chat-model.timeout` / `.embedding-model.timeout` (default
3m) and `chat-model.max-retries` are the knobs; `LangChain4jConfigTest` proves
the chat timeout really cuts a slow call off.

**HTTP/1.1 is forced for LangChain4j.** The JDK HTTP client defaults to
HTTP/2, which on a plain `http://` URL sends an upgrade request that LM Studio
never answers. The symptom is misleading — requests just time out, as if the
model were slow. See the comment on `langChain4jHttpClientBuilder()`.

**Spring AI's base URL must end in `/v1`.** Spring AI 2.0 doesn't add it. Get
it wrong and LM Studio returns HTTP 200 with an error body, so the failure
looks like a parsing bug (`` `choices` is not set ``), not a 404.

**The vector store is in memory.** Embeddings are rebuilt on every startup
(~1.5 s for 207 chunks). Fine at this size; pgvector is the planned
replacement, behind the same `EmbeddingStore` interface.

**Retrieval scores are `(cosine + 1) / 2`,** not raw cosine. The `0.80` floor
is on that scale, and is specific to nomic-embed-text with task prefixes. It is
a trade-off, not a clean cut: the evaluation shows an off-topic technical
question scoring 0.82 and some correct chunks just under 0.80.

**The UI escapes everything the model writes.** LLM output is untrusted —
it is built from documents and user input — so `index.html` escapes it before
adding its own markup. Never assign an answer to `innerHTML` unescaped.

**Lombok is pinned above Spring Boot's version.** Boot 4.1.1 manages Lombok
1.18.46, which breaks on Java 27; `build.gradle.kts` overrides it to 1.18.48.
A new JDK often breaks Lombok first, because it hooks into compiler internals.

**The `claude` profile swaps only the chat model.** `LangChain4jConfig` builds
an `AnthropicChatModel` instead of the LM Studio one (`@Profile("claude")` /
`@Profile("!claude")`), and `spring.ai.model.chat` picks the Anthropic starter
over the OpenAI one for Spring AI. Embeddings stay on LM Studio: Anthropic has
no embedding API. Opus 5.5 rejects temperature, top_p and top_k with a 400 —
`ClaudeProfileTests` guards that neither track sends them. It always thinks,
and thinking counts toward `max-tokens` (16,000); LangChain4j 1.20 cannot set
effort, so it runs at the model's default, `medium`.

**The chat model runs at temperature 0.** Answers from documentation should be
factual, and the evaluations need repeatable runs.

**Prefixes are applied to the embedded text only.** `IngestionPipeline.embed()`
embeds a prefixed copy but stores the original chunk, so the LLM never sees
`search_document:`.

## Testing

| Test | What it checks | Needs LM Studio? |
|---|---|---|
| `RagServiceTest` | Prompt contents and numbering, citation parsing and its edge cases, answer mapping, empty retrieval, missing token usage | No — hand-written fakes |
| `CpiAssistantApplicationTests` | The Spring context starts and all beans wire | No — sets `cpi.ingestion.run-on-startup=false` |
| `LangChain4jConfigTest` | The configured chat timeout really cuts off a slow server (the 60 s override bug) | No — a local stub server |
| `ClaudeProfileTests` | Under the `claude` profile both tracks get a Claude chat model — right model id, no temperature/top_p/top_k | No — builds the clients offline with a placeholder key |
| `RetrievalEvaluation` | Retrieval quality across chunk sizes on a fixed question set — Hit@1, Hit@3, MRR, floor leaks | **Yes** — tagged `eval`, run with `./gradlew eval`, excluded from `./gradlew test` |
| `AnswerEvaluation` | Answer quality: RAG at 500/50 and 300/30 vs all docs in the prompt — key facts, declines, tokens, time | **Yes** — same `eval` tag; the chat model needs a ≥ 32K context |

Answer *quality* against a real model is not unit-tested — that belongs to
evaluation (Step 10 in the [learning path](LEARNING-PATH.md)).

The evaluation lives in the same package as the services so it can call
package-private overloads — `IngestionPipeline.split(docs, size, overlap)`,
`embedInto(segments, store)`, `RetrievalService.search(embedding, k, store, floor)`,
`RagService.answer(question, matches)`.
They let it build a fresh store per chunk size while running the app's own
code. `embedInto` does not update the `/status` counter, which is why it is
not public: it must never be used on the live store.

## Gotchas when running

- **JDK version.** The build targets Java 27. `java -jar` on an older default
  JDK fails with `UnsupportedClassVersionError`; use `./gradlew bootRun`, or
  run `sdk env` in the project folder to switch to the JDK in `.sdkmanrc`.
- **Asking too early.** The HTTP server starts before ingestion finishes. A
  question asked before the `Embedded N segments` log line finds nothing and
  gets "I don't know". The web UI checks `/status` and waits; `curl` doesn't.
- **First request is slow.** LM Studio loads the model into memory on first
  use, and a cold long prompt takes time to read (41 s for all 15 docs). The
  chat and embedding timeouts are 3 minutes for that reason.
