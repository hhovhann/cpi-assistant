# Architecture

How the code is organised, and the decisions that aren't obvious from reading it.

## Classes

All code is in `src/main/java/com/hhovhann/cpiassistant/`.

| Class | Role | Runs |
|---|---|---|
| `AssistController` | `GET /assist` — the whole API — and `GET /status` | Per question |
| `AssistService` | The one path: knowledge → agent (tools as the model needs them) → citation check; records the path | Per question |
| `KnowledgeService` | Finds documentation: the two best SAP Help pages for the question (downloaded and saved the first time) and their best passages, plus the best passages in the store. No model call | Per question |
| `CpiAgent` | The agent: an interface AiServices implements; gets question + passages + skills list, runs the tool loop | Per question |
| `AgentTools` | Every tool in one place — docs, skills, tenant (when configured), MCP servers' allowed tools — each call wrapped in the hooks | Built once, per tool call |
| `ToolHook`, `ToolHooks` | Before/after every tool call: argument guard, result limit, audit log | Per tool call |
| `CpiDocsTool` | `@Tool searchDocs`, `readPage` — a whole SAP catalog page, in parts of 12,000 characters | Per tool call |
| `SkillLibrary` | Skills from `resources/skills/*.md`; the one-line list for the prompt, `@Tool loadSkill` | Per tool call |
| `CpiTenantTools` | `@Tool listIflows`, `getProblemMessages` (FAILED, RETRY, ESCALATED), `getErrorDetails` — read-only | Per tool call |
| `CpiTenantClient` | HTTP client for the CPI OData API; OAuth client credentials for a real tenant | Per tool call |
| `McpProperties` | `cpi.mcp.servers`: stdio command or HTTP URL, and each server's allowed tools | — |
| `CpiODataModel` | The OData records and `/Date(ms)/` conversion, shared by client and fake | — |
| `fake.FakeCpiController`, `fake.FakeCpiData` | A stand-in tenant for the `dev` profile: same paths and JSON as the real API, planted failures | Per request |
| `SapHelpCatalog` | The ~1,660 SAP pages that may be downloaded (`sap-help/catalog.tsv`: path, heading, first sentence); finds a page by meaning and keywords; identifiers must match exactly | Entries embedded once per start |
| `SapHelpClient` | Downloads one page as Markdown from SAP's GitHub docs repository; strips comments, anchors, images, links; HTML tables → one line per row | Per download |
| `IngestionPipeline` | Splits a page into chunks, embeds each together with its page title, stores them | Per download |
| `RetrievalService` | Embeds a question, returns the closest chunks above the floor, re-ordered by meaning + keywords; `searchWithin` one page | Per search |
| `KeywordSearch` | Keyword ranking (BM25) and rank fusion (RRF) — the keyword half of hybrid search | Per search |
| `SeedRunner` | At startup: saves the popular pages (unless fresh), embeds the catalog titles, sets `/status` ready | Once, at startup |
| `LangChain4jConfig` | Builds the beans: HTTP client, chat model (per provider), embedding model, store, assistant | Once, at startup |
| `ChatProperties`, `StoreProperties`, `SeedProperties`, `IngestionProperties` | Settings bound from `cpi.chat.*`, `cpi.store.*`, `cpi.knowledge.*`, `cpi.ingestion.*` | — |
| `static/index.html` | Web UI: one question box; shows path, sources, ⚠ unverified citations, tool calls | In the browser |

## One path

```mermaid
flowchart TB
    AC[AssistController /assist] --> AS[AssistService]
    AS -->|1 find| KS[KnowledgeService]
    KS -->|2 best pages| CAT[SapHelpCatalog]
    KS -->|search| RS[RetrievalService] --> ST[(pgvector)]
    KS -->|download| CL[SapHelpClient] --> GH[(SAP-docs on GitHub)]
    KS -->|split + embed + save| IP[IngestionPipeline] --> ST
    AS -->|2 answer: question + passages + skills| AG[CpiAgent] --> LLM[(chat model)]
    AG -->|when needed| TOOLS[AgentTools: hooks around every call]
    TOOLS --> DT[CpiDocsTool: searchDocs, readPage] --> KS
    TOOLS --> SK[SkillLibrary: loadSkill]
    TOOLS --> TT[CpiTenantTools] --> TC[CpiTenantClient, OAuth] --> TEN[(CPI tenant / dev fake)]
    TOOLS --> MCP[(MCP servers, allowed tools only)]
    AS -->|3 check citations, build path| AC
```

**Who decides what.** Finding documentation is code: the two best SAP pages,
then the store — predictable, no model call. Whether to call a tool is the
model's decision: it sees the passages, the skills list and the tools, and a
documentation question usually needs none. There is no router, and no mode.

## Decisions worth knowing

**One endpoint, one source of truth.** Earlier the app had `/chat` (model
alone), `/ask` (fixed RAG), `/agent` (tools) and hand-written local docs. Each
was useful for learning; together they made "what gets called when" hard to
follow. Now every question takes the same path, and the knowledge is only the
official SAP documentation. The comparisons live on in the learning path.

**Finding docs is code, not a tool the model must remember to call.** An
earlier version gave the model `searchSapHelp` / `readSapHelpPage` tools; it
sometimes skipped them, and the path varied from run to run. Now
`KnowledgeService` always runs first and hands the model its passages.

**An agent, like a small Claude Code (Step 21).** Step 20 removed the tools to
simplify, and learned that one page and three passages are too thin. Step 21
brought the tools back in one structure instead of scattered wiring:
`AgentTools` holds every tool — our `@Tool` classes and the allowed tools of
each MCP server — as one map of specification → executor, and wraps every
executor in the hooks. AiServices gets that map. Where a tool comes from does
not matter to the loop, the checks or the log.

**Skills keep the prompt short.** The system prompt lists each skill in one
line; the full playbook arrives only when the model calls `loadSkill`. A new
skill is a Markdown file with a name and a description on top — no code.

**Hooks see every call.** Before: the argument guard refuses arguments over
2,000 characters or with control characters — the model gets the reason as the
tool's result, and the tool never runs. After: results over 16,000 characters
are cut (they would push the passages out of the context), and the audit log
writes tool, source, time and result size. A tool that throws gives the model
"The tool failed: …" instead of failing the request.

**MCP tools are allowlisted per server.** An MCP server can offer tools that
write, delete or reach anywhere; none reaches the model unless its name is in
that server's `allowed-tools`, and none may shadow one of ours. A server that
is down at startup is skipped with a warning, not fatal.

**The tenant is optional and real-ready.** No `cpi.tenant.base-url`: the tenant
tools are not offered, and the model cannot claim to have checked. A real
tenant uses OAuth client credentials from environment variables; the token is
cached until a minute before it expires. The fake tenant lives in its own
package and only runs in the `dev` profile.

**The best page first, not only on a miss.** Earlier the store was searched
first and SAP Help asked only when nothing scored above 0.80. But passages
above the floor can be the wrong ones — AS2 chunks score 0.85 for an AS4
question — so the app needed an "I don't know → ask SAP Help again" retry, a
graph hop to reach pages a passage names, and extra passages after a
download. Now every question looks up its two best catalog pages first:
saved the first time, from the store after that, eight passages of each always
in the answer, plus the three best from the whole store. One rule replaced
three patches, and the AS4 question went from four model calls to one. Why two
pages and eight passages: with one page and three passages the answer was too
thin once the tool descriptions left the prompt (AS4 and Kafka got the
overview and "Related Information" chunks); the second page catches a wrong
first choice. Still about 1,000–1,600 input tokens per question.

**SAP's placeholders are text, not HTML.** SAP writes `*<known\_hosts\>*`.
Inside table cells — and some pages put whole paragraphs in layout tables —
every `<…>` was stripped as a tag, so saved pages said "stored in a \*\* file".
`SapHelpClient` now strips only real tags (a name, then `>`, a space or `/`).
Pages saved before the fix keep the damaged text until they are downloaded
again: empty the table to refresh them all.

**Two thresholds.** A page is used only if it matches the question at
`cpi.knowledge.min-title-score` (0.82) — measured: real CPI questions
0.83–0.95, off-topic ones up to 0.80 ("capital of France" 0.769, "tune JVM
garbage collection" 0.800). A passage from the whole store only at
`cpi.retrieval.min-score` (0.80).

**An empty answer is never passed on.** Qwen3 has returned only its thinking
and no text; the app answers "The model returned no answer. Please ask again."
instead of `null`.

**Questions are 1 to 1,000 characters.** Checked in `AssistController` before
any model call (HTTP 400 otherwise); every character goes into the prompt.

**Citations are checked in code.** A bracket right after a word or a slash is
code, not a citation (`payload/LogEntry[severity = 'Error']`). Passages are labelled by page title, and
the model is told to cite `[Title]` only from what it was given. `AssistService`
collects the title of every passage the model was given and returns cited
titles outside that set as `unverifiedCitations` (a URL or an id in brackets
is not a citation);
the UI marks them ⚠. A name that appears in the text the model read is not
flagged: the model also brackets pages a passage merely mentions ("see
Configure JDBC Drivers"), and a prompt rule against it did not work. It is a
check, not a guarantee: it catches a page cited from memory, not a claim that
is missing from a page it did see.

**Links are stripped from saved pages.** SAP's Markdown links
(`[Handle Errors in Successful Responses](….md)`) look exactly like
citations, and the model copied them as sources it was never given — 5 of 6
answers were flagged before `SapHelpClient.clean` turned links into plain text,
0 of 6 after.

**SAP Help comes from GitHub, not help.sap.com.** help.sap.com renders its
pages with JavaScript — a plain download returns a 1 KB shell — and its
robots.txt disallows every automated client except named search engines. SAP
publishes the same documentation as Markdown in
`github.com/SAP-docs/btp-integration-suite` under CC BY 4.0, which allows
reuse with attribution; answers cite the page, and the README credits SAP.

**Only catalog pages can be downloaded.** `sap-help/catalog.tsv` holds ~1,660
pages — path, heading and first sentence, pinned to a commit and built by
`scripts/build_sap_help_catalog.py`. Nothing — model or user input — can make
the app fetch another URL; a page must be in the catalog.

**A page is found by title *and* first sentence.** SAP opens each page with a
one-sentence description, and it carries words the title lacks: "Configure
Receiver Channel with ebMS3 Push" never says AS4, its first sentence does.

**Then one ranking rule, from a measured miss** (`SapHelpCatalog.rank`):
*identifiers must match exactly* — to the embedding model AS4 and AS2 are
nearly the same word, and "configure the AS4 receiver adapter" came closest to
"Configure the AS2 Receiver Adapter"; a word with a digit or two capitals
(AS4, JDBC, SFTP, OData, V2) must appear in the page's title or summary. The "Configure …" preference that used to follow was dropped in Step 20: with hybrid search and the best page's passages in every answer, `scripts/qa.sh` passes without it.

**Pages are cleaned for search, not only for reading.** HTML parameter tables
(on about half of all pages) became tag soup in chunks; each row is now one
line, `Field | Description`. And each chunk is embedded together with its page
title: a table row like "Connection Timeout | Provide a connection timeout …"
never says JDBC, and "configure a JDBC adapter" did not find it. The store
keeps the chunk without the title.

**What is saved is SAP's text, never the model's answer.** A page is split
like any document and stored with `source=sap-help`, its URL, title and fetch
time. A page older than `cpi.knowledge.max-age` (30 days) is downloaded again
and replaces its old chunks (`removeAll` by URL, then add). Saving answers
would store every mistake and serve it back with a citation.

**Popular pages are saved at startup.** `SeedRunner` saves ten pages from
`cpi.knowledge.seed-pages` unless a fresh copy is stored, and embeds the
catalog titles (~15 s), so neither delays the first question. A failure is
logged, not fatal. The seed list is bound by a record (`SeedProperties`):
`@Value` cannot read a YAML list and silently gave an empty one.

**The store is Postgres with pgvector, or memory.** `cpi.store.type` picks:
`pgvector` (default, `docker compose up -d`, port 5433) keeps the `cpi_chunks`
table across restarts; `memory` is what the tests use
(`src/test/resources/application.properties`). Both report
`(cosine + 1) / 2`, so thresholds carry over. No vector index: at a few
thousand rows an exact scan is fast and never misses a neighbour.

**`langchain4j-pgvector` is a beta module** (1.20.0-beta30). It is plain JDBC
with no Spring in it. Its own hybrid mode is not used — see below.

**Hybrid search is BM25 in Java (`KeywordSearch`), fused by rank.** The catalog fuses its 20
best pages by meaning with its 20 best by keywords; passages: 4× candidates
above the floor, re-ordered. Reciprocal rank fusion (k = 60) needs no common
score scale, and every result keeps its similarity score, so the 0.80 and 0.82
thresholds are unchanged. Keywords re-order passages but never add one below
the floor. pgvector's `searchMode` hybrid was rejected: it returns RRF scores
(~0.02) that break the thresholds, `plainto_tsquery` needs every word in one
chunk, and the in-memory store the tests use has no such mode.

**No LangChain4j Spring Boot starters.** They are built against Spring Boot
3.5 and fail on Boot 4 (`NoClassDefFoundError: RestClientAutoConfiguration`).
The LangChain4j core has no Spring dependency, so the beans are built in
`LangChain4jConfig` — which is also the learning goal: nothing hidden.

**Timeouts are set on the models, not the HTTP client.** `OpenAiChatModel` and
`OpenAiEmbeddingModel` pass their own timeout to the HTTP client — 60 s unless
`.timeout(...)` is set — and it overrides the client's. `cpi.chat.timeout` /
`langchain4j.open-ai.embedding-model.timeout` (3m) and `cpi.chat.max-retries`
are the knobs; `LangChain4jConfigTest` proves the chat timeout cuts a slow
call off.

**HTTP/1.1 is forced for LangChain4j.** The JDK HTTP client defaults to
HTTP/2, which on a plain `http://` URL sends an upgrade request that LM Studio
never answers. The symptom is misleading — requests just time out.

**One switch picks the chat model: `cpi.chat.provider`.** `lmstudio` (the
default, Qwen3 14B), `openai` or `anthropic`; everything else sees only the
`ChatModel` interface. A provider without its key stops the app at startup.
Embeddings stay on LM Studio: the stored vectors were made by nomic, and
Anthropic has no embedding API. Opus 5.5 rejects temperature, top_p and top_k
with a 400 — `ChatProviderTests` guards that none of them is sent.

**Passages are data, never instructions.** They come from outside, so the
system prompt says to ignore any instructions inside them — a first defence.
The app only reads: it downloads catalog pages and answers; nothing the model
writes is executed or saved.

**The UI escapes everything the model writes**, and links only to
`github.com/SAP-docs/…`. Never assign an answer to `innerHTML` unescaped.

**The chat model runs at temperature 0**, and **prefixes are applied to the
embedded text only** — the store keeps the original chunk, so the model never
sees `search_document:`.

**Lombok is pinned above Spring Boot's version** (1.18.48): Boot 4.1.1's
1.18.46 breaks on Java 27.

## Testing

| Test | What it checks | Needs |
|---|---|---|
| `AssistServiceTest` | The one path with a scripted model: a database hit is one call with no tool; a cited page never given is flagged, a page named inside a passage is not; an iFlow name becomes a note; tool calls are reported and their pages count as given; the tool-call limit gives an answer, not an error; an empty answer is never passed on; numbers, ids, URLs and tool names are not citations | Nothing — fakes |
| `KnowledgeServiceTest` | A local server plays GitHub: the best page is downloaded, stripped of links and images, saved and answered from; the second time it comes from the store; off-topic downloads nothing; the page's passages and the store's best are combined without duplicates; refresh after max-age without duplicates; a moved page; passages labelled by title; the real catalog loads | Nothing — local server, bag-of-words embeddings |
| `SapHelpCatalogTest` | The ranking rule with real titles and measured scores: AS4 is not AS2, an identifier no page has changes nothing | Nothing |
| `KeywordSearchTest` | The rare word decides; stop words are not searched; fusion rewards agreement between lists; keywords lift a passage both lists like but add none, and scores stay similarities | Nothing |
| `SapHelpClientTest` | Cleaning, on real SAP shapes: an HTML table becomes `Field \| Description` rows; comments, anchors and images go, link text stays; placeholders like `<known_hosts>` stay | Nothing |
| `CpiAgentTest` | The tool loop: passages arrive in the message, every tool is offered, a docs question needs no tool, `searchDocs` runs with the model's query and its result goes back, the round-trip limit | Nothing — scripted model |
| `AgentToolsTest` | Hooks: a refused call never runs, a huge result is cut, hooks run in order, a failing tool gives text; MCP: only allowed tools, never shadowing ours | Nothing — a stand-in MCP client |
| `SkillLibraryTest` | The real skills load and list in one line each; an unknown skill names the existing ones; a skill without front matter is rejected | Nothing |
| `CpiDocsToolTest` | `readPage` reads a catalog page in parts under its title; anything not in the catalog is refused | Nothing |
| `CpiTenantTest` | Client against the fake tenant over real HTTP: filters, time window, RETRY, quote escaping, error text and 404, iFlow list, the tools' text; OAuth: a token from the token URL, reused | Nothing — random port, local token server |
| `PgVectorStoreTest` | Real pgvector: same score scale as memory, remove-by-URL deletes only that page, rows survive a new store on the same table | **Docker** (Testcontainers) |
| `ChatProviderTests` | `cpi.chat.provider` builds the right model with the right sampling settings; a missing key fails at startup | Nothing — offline clients |
| `LangChain4jConfigTest` | The chat timeout really cuts off a slow server | Nothing — local stub |
| `CpiAssistantApplicationTests` | The Spring context starts and all beans wire | Nothing |
| `RagVsFileSearchMeasurement` | Not a test: RAG against an agent that searches (BM25) and reads the SAP pages, same questions and model — tokens and calls counted at the model, time, right, grounded. Own table `cpi_chunks_measure`, reset to the seed pages every run. Tagged `measure`, run with `./gradlew measure` | LM Studio, pgvector, internet |
| `ToolUseMeasurement` | Not a test: the agent against the fake tenant's planted failures — tenant questions, a tenant error plus its fix in the docs, a parameter deep in a page, off-topic — with skills and without. Expected tools called, right, unverified citations, calls and tokens. Tagged `measure`: `./gradlew measure --tests '*ToolUse*'` | LM Studio, pgvector, internet, port 18081 |

Answer *quality* against the real model: `scripts/qa.sh` asks up to fourteen questions (the five tenant ones only when the agent has tenant tools)
and checks the path and sources of each answer (see the [README](../README.md)). The
earlier automated evaluations (Step 10) were built on the hand-written docs
and are in git history.

## Gotchas when running

- **JDK version.** The build targets Java 27; use `./gradlew bootRun`, or
  `sdk env` in the project folder.
- **Asking too early.** The HTTP server starts before `SeedRunner` finishes.
  The web UI waits for `/status`; `curl` doesn't.
- **An app already on port 8080.** A second `bootRun` fails with "Port 8080
  was already in use" — and questions then go to the old one. Stop it, or run
  with `--server.port=8081`.
- **Docker must run.** Without pgvector every question fails. `docker compose
  up -d`, then restart the app.
- **The first question on a new topic is slower** — it downloads a page. The
  UI's path shows `SAP Help: downloaded …` when that happened.
