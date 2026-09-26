# Learning path

This project is built one step at a time. Each step adds one piece of a RAG
system and teaches one idea. This page records, for each step: **what was
built**, **the idea behind it**, and **what we measured** — the numbers are
the most useful part, because they show that each idea actually matters.

To see exactly what a step changed, look at its commit:

```bash
git log --oneline          # find the step
git show <commit> --stat   # the files it touched
```

Step 7 was skipped at first and done after Step 13.

**Each step describes the project as it was then.** Step 15 merged `/chat`,
`/ask` and `/agent` into one `/assist` endpoint and replaced the hand-written
docs with the official SAP documentation — so the endpoints, files and
commands in Steps 1–14 no longer exist on `master`. Check out a step's commit
to run it as it was.

**Suggested way to learn:** read a step here, check out its commit, read the
classes it names, then run the app and try the experiment listed.

---

## Phase 1 — a RAG system with LangChain4j

### Steps 1–3: Setup, basic chat, the documents
*Commits `63ee558`, `20c0846`*

**Built:** a Spring Boot app, a `/chat` endpoint that sends a question to a
local LLM, and 15 CPI documentation files.

**Ideas:**
- **An LLM API is just HTTP.** LM Studio exposes the same API as OpenAI, so
  switching to OpenAI later is a URL and a key, not a code change.
- **Tokens are the unit of cost and of limits.** Roughly 4 characters of
  English per token. Our whole knowledge base is ~73K characters ≈ **20K tokens**.
- **The data matters more than the framework.** A RAG system can only be as
  good as the documents it retrieves from.

**Try:** `/chat` with a CPI question. Note how confident the answer sounds —
and check whether it is actually right.

---

### Step 4: Chunking
*Commits `8a87050`, `cc03adb` · `IngestionPipeline.split()`, `IngestionProperties`*

**Built:** documents are split into smaller segments ("chunks").

**Idea:** retrieval returns chunks, not documents. A chunk must be **small
enough** to be about one thing, and **large enough** to still make sense on
its own. The *recursive* splitter tries paragraphs first, then sentences, then
words, and only falls back to a smaller unit when a piece doesn't fit.
*Overlap* repeats the end of one chunk at the start of the next, so a sentence
crossing a boundary survives whole in at least one of them.

**Measured:**

| Setting (size, overlap) | Chunks | Smallest chunk |
|---|---|---|
| (200, 0) | 534 | 7 chars — a bare heading |
| **(500, 50)** — current default | 207 | — |

A 7-character chunk gets its own vector, embeds a generic word, and matches
unrelated questions. That's why the default is (500, 50).

**Try:** `./gradlew bootRun --args="--cpi.ingestion.max-segment-size=200 --cpi.ingestion.max-overlap-size=0"`
and compare the chunk statistics in the startup log.

---

### Step 5: Embeddings and semantic search
*Commit `9c0fe4d` · `LangChain4jConfig`, `IngestionPipeline.embed()`, `RetrievalService`*

**Built:** every chunk is turned into a vector and stored; a question is
turned into a vector the same way and the closest chunks are returned.

**Ideas:**
- **An embedding is a list of numbers that captures meaning.** nomic-embed-text
  produces **768** numbers per text. Texts about similar things get vectors
  pointing in similar directions — measured by *cosine similarity*.
- **This is search by meaning, not keywords.** *"How do I connect to a
  database?"* finds the JDBC chunk even though it never says "JDBC".
- **Same model on both sides.** Documents and questions must be embedded by
  the same model — vectors from two models live in unrelated spaces.
- **The embedding model is not the chat model.** It generates no text; it only
  produces vectors. That's why LM Studio needs two models loaded.
- **Some models want task prefixes.** nomic is trained to see
  `search_query: ` before questions and `search_document: ` before documents.
  The prefix only affects the vector — the stored text stays clean.

**The score trap:** the in-memory store does not report raw cosine. It reports
`(cosine + 1) / 2`, which squeezes everything into 0..1. So a floor of `0.5`
means "cosine ≥ 0" and filters almost nothing.

**Measured** (500, 50):

| | Without prefixes | With prefixes |
|---|---|---|
| JDBC chunk, database question | 0.8168 | 0.8642 |
| Best match for *"capital of France"* (noise) | 0.7511 | 0.7873 |
| Weakest real hit (AS2) | — | 0.8661 |

Real hits start around 0.86, noise tops out around 0.79 → the floor
`cpi.retrieval.min-score` is **0.80**, in that gap.

*Later correction (Step 10a):* with 25 questions instead of 3, there is no
clean gap — an off-topic technical question reaches 0.82 and some correct
chunks score just under 0.80. Measurements on a handful of questions don't
generalise.

**Open problem:** for the database question, a Partner Directory chunk (0.8714)
still ranks *above* the JDBC chunk (0.8642). JDBC is in the top 3, which is
enough — but pure semantic search can't fix the order. See Step 10.

**Try:** the startup log prints the top 3 matches for three probe questions.
Change `min-score` to `0.5` and watch *"capital of France"* return CPI chunks.

---

### Step 6: RAG — retrieve, then generate
*Commit `8a75c4d` · `RagService`, `RagController`, `RagServiceTest`*

**Built:** `/ask` retrieves the top 3 chunks, puts them in a prompt with the
question, and returns the LLM's answer with the source files and token counts.

**Ideas:**
- **Augmentation is just text.** The chunks are pasted into the prompt. The
  LLM doesn't know a vector store exists.
- **The prompt has two jobs, in two messages.**
  - *System message* — the rules. The critical one: **answer only from the
    context, otherwise say "I don't know."** Without it the model mixes in
    what it remembers and you can't tell facts from inventions.
  - *User message* — the data: each chunk labelled with its file name,
    separated by `---`, then the question.
- **Scores stay out of the prompt.** They mean nothing to the model.
- **An empty retrieval is said out loud** — `(no relevant documents found)` —
  so the model has an explicit reason to decline.

**Measured** — same question, *"How do I connect to a database from an iFlow?"*:

| | Input tokens | Output tokens | Time | Answer |
|---|---|---|---|---|
| `/chat` (no RAG) | 47 | 577 | 9 s | Invented SAP PI menus — wrong |
| `/ask` (RAG) | 349 | 94 | 2 s | Correct JDBC steps from the docs |
| All docs in the prompt (the alternative) | ~20,000 | — | — | — |

**What this shows:**
- RAG costs **more** input tokens than a bare question (349 vs 47). The saving
  is against the real alternative — stuffing all docs into the prompt —
  where RAG is **~57× cheaper** and keeps working when the docs outgrow the
  context window.
- The real win is **correctness and honesty**, not tokens: a right answer,
  and "I don't know" for *"capital of France"* (80 input tokens).
- **The answer is only as good as the chunk.** The answer said *"the data
  source configured in Step 2"* — but that chunk is Step 3 of the JDBC doc;
  Step 2 lives in another chunk the user never sees. A chunk-boundary effect.

**Testing without a model:** `RagServiceTest` replaces retrieval and the chat
model with hand-written fakes. It checks what goes into the prompt and what
comes out of `/ask` — in milliseconds, no LM Studio. It would have caught a
prompt that ignored the retrieved chunks.

**Try:** ask the same question on `/chat` and `/ask`. Then remove the "ONLY"
rule from the system message and ask *"What is the capital of France?"*.

---

### Step 7: Persistent vector store — pgvector in Docker *(done after Step 13)*

**What:** `docker compose up -d` starts Postgres 18 with the pgvector extension
on port 5433; `cpi.store.type=pgvector` (the default) stores the vectors in the
`cpi_chunks` table — a `vector(768)` column next to the text and the metadata
as JSON. `cpi.store.type=memory` is the old in-memory store.
**Classes:** `StoreProperties`, `LangChain4jConfig.embeddingStore` /
`pgVectorStore`, `IngestionPipeline.embed`, `PgVectorStoreTest`,
`docker-compose.yml`.
**Why now:** skipped at first because 207 chunks rebuild in ~1.5 s. It became
necessary with the plan to save pages fetched from SAP Help: those cannot be
re-read from disk on the next start.
**Idea:** the store is a pluggable detail behind `EmbeddingStore` — retrieval
did not change at all. What *did* change is ingestion: with a store that
remembers, "add everything at startup" becomes "add everything again", so the
local docs are now tagged `source=cpi-docs` and replaced, not appended.
**Measured:**
- 207 rows after the first start, **207 after a restart** — no duplicates.
- Same scores as in memory: the JDBC chunk for the database question is
  0.8642 in both, so the `0.80` floor still holds. `PgVectorStoreTest` checks
  the scale directly: 1.0 same direction, 0.8 for cosine 0.6, 0.5 orthogonal.
**Try:** `docker exec -it cpi-assistant-pgvector psql -U cpi -d cpi`, then
`select metadata->>'file_name', left(text, 60) from cpi_chunks limit 5;` —
the chunks as rows. Then `docker compose down` and start with
`--cpi.store.type=memory`: the app does not care.

### Step 8: Source references in answers
*`RagService.extractSources()`, `RagService.Source`*

**Built:** the prompt numbers the chunks `[1]`, `[2]`, `[3]` and tells the
model to cite the ones it uses. The answer's citations are parsed back into
`sources` — file, score and a short excerpt — returned next to `retrievedFrom`.

**Ideas:**
- **Retrieved is not used.** Retrieval always hands over the top 3; often one
  of them is noise. `retrievedFrom` shows what the model *saw*, `sources`
  shows what it *relied on*. The difference is the noise.
- **Numbers, not file names.** Several chunks can come from the same file, and
  a number is short, unambiguous and easy to parse. `[n]` maps to the n-th
  retrieved chunk.
- **Show the format.** The rule includes an example — *"like [1] or [1, 2]"*.
  Small models copy a format they are shown far better than one described.
- **Parse defensively.** The model writes free text. The parser accepts
  `[1]`, `[1][2]`, `[1, 2]` and `[1 2]`, ignores brackets that aren't
  citations (`[SAP_ApplicationID]`), and drops numbers with no chunk behind
  them (`[0]`, `[7]`) — a made-up source is worse than none.
- **Tests first.** The spec for `extractSources` was written as failing tests
  before the code. Two more crash cases (`[1 2]`, a number too big for an
  `int`) were found by reading the code and added as tests.

**Measured** with Llama 3.1 8B:

| Question | Retrieved | Cited | |
|---|---|---|---|
| Error handling in an iFlow | error handling, JDBC, data store | error handling, JDBC | ✅ unused data-store chunk left out |
| Script step vs Groovy Script | 3 chunks of the scripting doc | [1], [2] | ✅ each claim cited |
| Connect to a database | Partner Directory, JDBC, B2B | **Partner Directory**, JDBC | ⚠️ opened with a blanket `[1, 2]` |
| Capital of France | — | — | ✅ "I don't know" |

**The lesson:** a citation is the model's **claim** about what it used, not
proof. The parser records faithfully what the model wrote, so `sources` is
only as honest as the model. Checking citations — does the cited chunk really
contain the claim? — or a stronger model is Step 10 work.

**Try:** ask the database question and compare `sources` with
`retrievedFrom`. Then remove the example `like [1] or [1, 2]` from the rule
and see whether the model still cites in a parseable format.

### Step 9: Web UI
*`src/main/resources/static/index.html`, `RagController.status()`*

**Built:** one static page at <http://localhost:8080> — plain HTML, CSS and
JavaScript, no build step. Spring Boot serves anything in `static/`.
It has an ask box, example questions, the answer with clickable `[1]`
citation chips, source cards (cited ones with excerpt, retrieved-but-unused
ones dashed), token and timing figures, and a toggle that runs `/chat` in a
second column for comparison.

**Ideas:**
- **Model output is untrusted input.** The answer is text the LLM wrote from
  your docs and the user's question. If either contains `<script>`, putting
  the answer straight into the page's HTML runs it: prompt injection becomes
  cross-site scripting. The page escapes the text first, and only then adds
  its own markup (bold, citation chips). A chip is only created for a number
  that is a real source, so `[7]` stays plain text.
- **Readiness is a real state.** The HTTP server starts before ingestion
  finishes; a question asked in that window finds an empty store and gets
  "I don't know". `GET /status` reports how many segments are indexed, and the
  page keeps the Ask button disabled until it is non-zero. The counter is an
  `AtomicInteger` because the startup thread writes it while request threads
  read it.
- **Show the difference, don't describe it.** The side-by-side toggle and the
  "retrieved, not cited" tags make Steps 6 and 8 visible to someone who has
  never heard of RAG — which is what a demo needs.

**Measured:** `/status` right after startup returned
`{"ready":false,"indexedSegments":0}`, then `{"ready":true,"indexedSegments":207}`
— the race is real.

**Try:** tick *Compare with the model alone* and ask *"How do I configure a
JDBC adapter?"*. Then ask the database question and look at which source is
tagged *retrieved, not cited* — or isn't, when the model blanket-cites.

### Step 10: Tuning and evaluation — *local part done*
Measure instead of guessing. Up to now every decision rested on two or three
hand-picked questions. Step 10 replaces that with fixed question sets and
numbers, in three parts:

| Part | Question | Needs the chat model? |
|---|---|---|
| **10a** ✅ — retrieval eval + chunk sweep | Does the right doc land in the top 3? At which chunk size? | No — embeddings only, seconds |
| **10b** ✅ — RAG vs all docs in the prompt | Is RAG better than giving the model all ~20K tokens? Cost, speed, correctness — and which chunk size gives the best *answers*? | Yes |
| **10c** ✅ — citation check | Does a cited chunk really support the claim? | Yes |

#### 10a: Retrieval evaluation and chunk-size sweep
*`RetrievalEvaluation`, `src/test/resources/eval/retrieval-questions.txt` · run with `./gradlew eval`*

**Built:** a question set — 22 CPI questions, each with the file(s) that
answer it, plus 3 off-topic questions that should retrieve nothing. An
evaluation that, for each chunk size, builds a fresh store through the app's
own ingestion and retrieval code and scores every question. It needs LM
Studio, so it is tagged `eval` and kept out of `./gradlew test`.

**Ideas:**
- **An evaluation set turns opinions into numbers.** Questions are worded
  like a user would ask — "database", not "JDBC" — because that's the hard
  case for semantic search.
- **Metrics:**
  - *Hit@k* — share of questions with a correct file in the top k. Hit@3 is
    the one that matters: `/ask` sends the top 3 to the model.
  - *MRR* (mean reciprocal rank) — 1 for a hit at rank 1, ½ at rank 2, …
    0 for a miss. One number that rewards putting the right chunk first.
- **Measure what the user gets.** The first version searched with no score
  floor and counted chunks that `/ask` would have dropped — it reported 100%
  where the truth was 95%. A code review caught it. The metrics now apply the
  same 0.80 floor as `/ask`.
- **Small samples mislead.** With 22 questions, one question moves Hit@k by
  4.5 points. A 1–2 question difference is a hint, not a verdict. More
  questions make the numbers firmer.

**Measured** (22 on-topic, 3 off-topic, floor 0.80):

| Chunks (size/overlap) | Segments | Hit@1 | Hit@3 | MRR@5 | Off-topic leaked |
|---|---|---|---|---|---|
| 200/20 | 539 | 64% | 82% | 0.723 | 1 |
| **300/30** | 350 | 68% | **95%** | 0.811 | 1 |
| 500/50 — current | 207 | 68% | 91% | 0.789 | 1 |
| 800/80 | 117 | **73%** | 86% | **0.816** | 1 |
| 1200/120 | 75 | 59% | 82% | 0.701 | 1 |
| 2000/200 | 47 | 55% | 73% | 0.633 | 1 |

**What this shows:**
- **Too small and too large both lose.** Tiny chunks carry too little meaning
  to match; huge chunks blend several topics into one vector. The sweet spot
  is 300–800 characters.
- **The score floor has no clean gap.** *"How do I tune garbage collection in
  the JVM?"* scores 0.80–0.82 in every configuration — a technical question
  looks close enough to the docs. Meanwhile some correct chunks score just
  under 0.80. The Step 5 claim "real hits start around 0.86" held for three
  questions, not for twenty-five. The floor stops everyday noise; the
  prompt's "answer only from the context" rule has to catch the rest.
- **Retrieval isn't the goal, answers are.** 300/30 retrieves best, but hands
  the model shorter chunks — which may make the "configured in Step 2"
  problem from Step 6 worse. **The chunk size is decided in 10b**, on answers.

**Try:** add questions to `retrieval-questions.txt` from your own CPI
experience and rerun `./gradlew eval`. The report is saved to
`build/eval/retrieval-report.md`, including a per-question rank table.

#### 10b: RAG vs all docs in the prompt — and the chunk size decision
*`AnswerEvaluation`, `src/test/resources/eval/answer-questions.txt`, `RagService.answer(question, matches)` · run with `./gradlew eval`*

**Built:** 10 CPI questions plus 2 off-topic ones, each with the key facts a
correct answer must contain. Three setups answer every question through the
**same prompt and citation code** — only the context differs:
- RAG with 500/50 chunks (the default)
- RAG with 300/30 chunks (best retrieval in 10a)
- **All docs in the prompt** — all 15 documents whole, no retrieval (the
  "control group")

The chat model now runs at **temperature 0**: always the most likely next
word. Answers from documentation should be factual rather than creative, and
an evaluation must give the same result twice.

**Setup:** the all-docs prompt is ~16K tokens. LM Studio fixes a model's
context size when it loads it, so Llama had to be reloaded with room for it:
`lms load meta-llama-3.1-8b-instruct --context-length 32768`.

**Measured** (Llama 3.1 8B, temperature 0):

| Setup | Key facts found | Fully correct | Off-topic declined | Input tokens | Output tokens | Seconds (warm) |
|---|---|---|---|---|---|---|
| **RAG 500/50** | 90% | 8/10 | 2/2 | **377** | 64 | **1.1** |
| RAG 300/30 | 73% | 6/10 | 2/2 | 278 | 48 | 0.8 |
| All docs | 95% | 9/10 | 2/2 | 16,365 | 186 | 3.4 — plus a **41 s** cold first call |

Seconds are per answer with the model already loaded. The all-docs setup's
first call has to read the whole ~16K-token prompt; LM Studio then caches that
shared prefix, so later calls only read the new question. That cold call is
timed separately — a real user of an all-docs system pays it whenever the
cache is cold.

**What this shows:**
- **Better retrieval ≠ better answers.** 300/30 won the retrieval sweep and
  lost here: short chunks leave the model without the surrounding text. For
  the database question it wrote *"as described in Step 3 of [2]"* instead of
  explaining — the Step 6 chunk-boundary problem, made worse. **Decision:
  keep 500/50.**
- **All docs in the prompt: slightly better, far more expensive.** 5 points
  more facts, for **43× the input tokens**, 3× the time per warm answer, and a
  41-second cold start. Warm answers are fast only because LM Studio caches
  the ~16K-token prefix they all share. And it stops working the day the docs
  outgrow the context window — RAG doesn't.
- **The first run found a timeout bug.** Its cold all-docs call was cut off
  after ~60 s and retried — although the app configured 3 minutes. LangChain4j's
  `OpenAiChatModel` passes its own timeout (60 s by default) to the HTTP
  client, overriding the one set on the client, so the configured 3 minutes
  had never applied. Timeouts now live on the models, and
  `LangChain4jConfigTest` proves one cuts a slow call off. With the fix and an
  untimed warm-up, the evaluation went from 252 s to 110 s. The answers were
  identical, token for token — temperature 0 makes them repeatable. About 60 s
  of that is the fix; generation also ran ~3× faster in every setup after a
  fresh model load, which no code change explains — so the first run's
  seconds (3.1 / 2.3 / 15.3) are not comparable, and are replaced above.
- **Citations fall apart with too much context.** With 15 numbered documents
  the model mixed up numbers and files ("[11] 07-b2b…"), and used `[1]…[7]`
  as list numbering — one answer "cited" 7 unrelated files. Fewer, relevant
  chunks keep Step 8 honest.
- **Two safeguards, working together.** The JVM question gets past the score
  floor (10a), yet every setup answered "I don't know": the prompt rule caught
  what the floor let through.

**Where keyword grading fails — read the answers:**
- *False failure:* every setup explained PunchOut correctly without the word
  "cXML", so all scored 1/2. The fact was too strict.
- *False partial credit:* one routing answer was **wrong** — HTTP adapter and
  an invented "Conditional Split" — and still scored 1/2 for containing
  "condition".
- *False decline:* an answer ending *"I don't know any other methods"* counted
  as "I don't know" — right after an invented tip (a 1-minute data store
  retention "to avoid duplicates").
- *Hallucination inside a correct answer:* "CPI (Consumer-Provider
  Interface)". It's Cloud Platform Integration.

Keyword checks are a cheap first filter. The next level is **LLM-as-judge**: a
stronger model grades each answer against the source — a natural job for the
OpenAI part of this step.

**Try:** open `build/eval/answer-report.md` after `./gradlew eval` and read
the answers side by side. Then add a fact list for a question you know well.

#### 10c: Citation check — does the cited chunk support the claim?
*`CitationEvaluation`, `CitationClaimsTest` · run with `./gradlew eval --tests '*CitationEvaluation'`*

**Built:** answers are split into *claims* — a sentence or list item with the
citation markers in it — giving (claim, cited chunk) pairs. Each pair is
checked two ways:
- **Similarity** — the claim's embedding against the chunk's. Cheap, no LLM.
- **LLM-as-judge** — a separate prompt: *given this PASSAGE and this
  STATEMENT, reply SUPPORTED, PARTIAL or UNSUPPORTED.*

The judge is whatever chat model is active — Llama by default, so it grades
its own answers. The report (`build/eval/citation-report.md`) lists every
pair **with the cited passage** and an empty *Your verdict* column.

**Ideas:**
- **A citation is a claim that can be checked.** Step 8 parsed citations;
  this step verifies them.
- **Splitting answers is harder than it looks.** Where does `[1]` belong in
  *"…the flow. [1] Then…"*? Is *"1."* a sentence? Is *"here are the steps:"* a
  claim? `CitationClaimsTest` pins down the rules; it caught two splitter bugs
  before the first run, and reading the first report caught a third —
  lead-in lines counted as claims inflated "unsupported" from 25% to 40%.
- **Who judges the judge?** An LLM judge is a model too. Its verdicts are only
  worth something once compared with a person who knows the domain — hence
  the passage and *Your verdict* columns.

**Measured** (Llama 3.1 8B answering and judging, 71 s):

| Setup | Cited claims | Pairs judged | Supported | Unsupported | Bare | Uncited sentences |
|---|---|---|---|---|---|---|
| **RAG 500/50** | 14 | 12 | **75%** | 25% | 2 | 20 |
| All docs | 18 | 10 | 10% | **70%** | 9 | 98 |

*Bare* = a citation with nothing to check (a file name, a lone marker).

**What this shows:**
- **Three of four RAG citations hold up.** The failures are of two kinds:
  - *Blanket citations of a noise chunk* — the Partner Directory chunk from
    Step 8, now measured rather than noticed.
  - *Right answer, wrong source* — the most dangerous kind. "Use the EDI
    Splitter" is correct, but the cited chunk is *Best Practices for EDI
    Processing* and never mentions a splitter. The model knew the answer
    without the chunk and cited it anyway. The citation lends credibility the
    source does not have.
- **All docs in the prompt makes citations useless** — 70% unsupported, plus
  file names passed off as citations. 10b's finding, now in numbers.
- **Much of an answer is untraceable.** ~1.5 uncited sentences per RAG answer,
  mostly list items under a cited lead-in.
- **Cheap similarity cannot replace the judge.** The wrong EDI citation scores
  0.889 — above several correct ones (0.874–0.882). Being on the same topic is
  not the same as supporting the claim.
- **The judge caught its own mistakes** — all three pairs it rejected were
  checked against their passages, and it was right each time. The nine it
  accepted have not been checked yet: that is what *Your verdict* is for.

**Try:** open the report, read the passages, and fill in *Your verdict* for
the RAG pairs. Count how often you agree with the judge. Then run the same
evaluation with `-Dcpi.chat.provider=anthropic` and compare the judges.

#### The rest of Step 10 — *needs API credit*
- **A stronger model, and a stronger judge:** Llama 3.1 8B vs a frontier model
  on answer quality, citation reliability and cost — and a stronger model
  grading the answers and citations. The provider switch is ready for it
  (`./gradlew eval -Dcpi.chat.provider=anthropic` or `=openai`); *waits for an
  Anthropic key or OpenAI credit.*
- **Ranking:** fix the Partner Directory chunk outranking JDBC — *hybrid
  search* (keywords + vectors) or a *reranker*. It is the source of the blanket
  citations above.
- **Flag unverified citations in the UI:** run the judge per citation and mark
  the ones it rejects. One extra call per citation — a better fit for a fast
  hosted model than for local Llama.
- **Demo questions:** JDBC adapter setup, Script step vs Groovy Script, error
  handling in an iFlow — each should get an accurate answer with sources.

### Step 11: One switch for the chat model

**What:** `cpi.chat.provider` = `lmstudio`, `openai` or `anthropic` picks the
chat model for the whole app; settings per provider live under `cpi.chat` in
`application.yml`. Replaces the `claude` Spring profile.
**Classes:** `ChatProperties`, `LangChain4jConfig.chatModel`, `ChatProviderTests`.
**Idea:** the rest of the code depends only on LangChain4j's `ChatModel`
interface, so swapping the provider touches one method. What does *not* swap
freely is the embedding model: the stored vectors belong to it.
**Differences between providers show up in the settings, not the code:**
Llama runs at temperature 0; Claude Opus 5.5 and newer OpenAI models reject a
temperature, so none is sent.
**Try:** set `OPENAI_API_KEY` or `ANTHROPIC_API_KEY`, start with
`--cpi.chat.provider=...`, and ask the three demo questions. Then start with
the provider but no key and read the error.

### Step 12: Retrieval as a tool

**What:** `GET /agent`. The model gets a `searchCpiDocs(query)` tool and
decides for itself whether to search, what to search for, and whether to
search again. The response lists every tool call with its arguments and result.
**Classes:** `CpiDocsTool`, `CpiAgent`, `AgentController`,
`LangChain4jConfig.cpiAgent`, `CpiAgentTest`.
**Idea:** in `/ask` *your code* decides to retrieve; in `/agent` the *model*
does. The model never sees `CpiDocsTool` — only its name and the text in
`@Tool` and `@P`, which is why that text is written for the model. `CpiAgent`
has no implementation: `AiServices` builds one that sends the question plus the
tool descriptions, runs whatever tool the model asks for, sends the result
back, and repeats until the model answers in text. `maxToolCallingRoundTrips(5)`
stops a model that never converges.
**Measured with Llama 3.1 8B (temperature 0):**

| Question | Tool calls | Answer | Tokens in / out |
|---|---|---|---|
| Connect to a database from an iFlow | 1 — `"connect to database from iFlow"` | "I don't know" — although the JDBC passage was in the result | 1,277 / 30 |
| Handle errors in an iFlow | 1 — `"handling errors in iflow"` | Hedged summary, no citations | 1,267 / 105 |
| Capital of France | 0 | Declined, no search | 482 / 18 |

- **Deciding worked:** it searched for CPI questions, with its own sensible
  query, and did not search for France.
- **Using the result did not:** with the same JDBC passage `/ask` answers
  correctly, but here Llama said "I don't know" and did not search again.
  Tool results arrive as a separate message after the model's own tool call;
  small models handle that turn much worse than a context pasted into the
  prompt.
- **Cost:** ~1,270 input tokens for one search vs ~380 for `/ask` — the tool
  descriptions and the extra round trip are paid on every question.

**Same questions with Qwen3 14B** (`qwen/qwen3-14b`, 32K context, temperature 0):

| Question | Tool calls | Answer | Tokens in / out | Time |
|---|---|---|---|---|
| Connect to a database from an iFlow | 1 — same query | Correct JDBC steps, cited `[02-jdbc-adapter.txt]` | 990 / 621 | 39 s |
| Handle errors in an iFlow | 1 — `"error handling in iFlow"` | Five correct points, every one cited | 979 / 786 | 42 s |
| Capital of France | 0 | Declined, no search | 334 / 166 | 9 s |

- **Same decisions, but it uses what it finds.** Every answer is grounded
  and cited by file name.
- **Slower:** Qwen3 *thinks* before it answers. Most of the 600–800 output
  tokens are hidden reasoning, and they cost ~40 s per question on an M4 Max.
- **One small embellishment:** "e.g., from CPI's JDBC connection settings" is
  not in the passage. Grounded is not the same as exact.
- **Decision:** Qwen3 14B is now the default local chat model
  (`cpi.chat.lmstudio.model-name`). The Step 10 reports were measured with
  Llama; `-Dcpi.chat.lmstudio.model-name=meta-llama-3.1-8b-instruct` reruns them.

**Next:** `/agent` vs `/ask` in the answer evaluation.
**Try:** ask `/agent` something that needs two searches, like "Compare the JDBC
adapter and the OData adapter", and see whether the model searches twice.

### Step 13: CPI tenant tools — live data over HTTP

**What:** three read-only tools that read a CPI tenant through its OData API
— `listIflows`, `getProblemMessages(iflowName?, status?, hoursBack?)` (first
`getFailedMessages`, see below),
`getErrorDetails(messageId)` — next to `searchCpiDocs`. Without a tenant (the
BTP trial is stuck on phone verification), a **fake tenant** runs inside the
app at `/fake-cpi/api/v1`: same paths, `$filter` syntax and JSON as the real
API, with five iFlows and planted failures (JDBC pool timeouts, an HTTP 401, a
mapping error, an SFTP retry, one iFlow in ERROR).
**Classes:** `CpiTenantTools`, `CpiTenantClient`, `CpiODataModel`,
`FakeCpiController`, `FakeCpiData`, `CpiTenantTest`.
**Idea:** two kinds of knowledge — the docs say how CPI works, the tenant says
what is happening. A "why did it fail and how do I fix it" question needs
both, in order, and the model has to work out that order itself. The client
speaks real HTTP to the fake, so a real tenant is a configuration change
(plus OAuth).
**Measured with Qwen3 14B:**

| Question | Tool calls, in the model's order | Answer | Tokens in / out | Time |
|---|---|---|---|---|
| Why did Order_Sync fail today and how do I fix it? | `getFailedMessages("Order_Sync", 24)` (the tool's first name) → `getErrorDetails(<id>)` → `searchCpiDocs("JdbcAdapterException connection timeout HikariPool")` | JDBC pool timeout on ORDER_DB, fixes from the JDBC doc, cited | 4,429 / 1,524 | 94 s |
| Which iFlows are not running? | `listIflows()` | Material_Master_Load, status ERROR | 1,758 / 430 | 26 s |

- **It chained three tools unprompted**, and searched the docs with words
  from the error text — not from the question.
- **Not everything is grounded:** "check the HikariPool settings" is uncited
  and not something a CPI user controls — the model filling a gap from its
  own knowledge. It also did not say that *three* messages failed.
- **Cost grows per round trip:** four model calls resend the growing
  conversation — 4.4K input tokens, 94 s.
- **A trap on the way:** the first run seemed to ignore the new tools. An older
  copy of the app was still holding port 8080, and the new one had failed to
  start. Same input tokens as before was the clue: new tool descriptions
  would have added to them.

- **A blind spot, found by asking:** "Is Payment_Status_Poll failing?" got
  "running normally". It is stuck in RETRY (SFTP connection refused), but
  `getFailedMessages` returned only FAILED, so the model never saw it. The
  model also passed an `iflowName` to `listIflows`, which takes none —
  LangChain4j dropped it silently.
- **Fixing it took two changes, not one:**
  1. *The tool:* `getFailedMessages` became `getProblemMessages` — FAILED,
     RETRY and ESCALATED by default, an optional `status`, each line labelled.
     `CpiTenantTest.retriesAreProblemsToo` proves the RETRY now comes back.
     The name changed too: the model reads it, and "failed" would undersell
     what the tool returns.
  2. *The routing:* with only that, the model still asked `listIflows` — its
     description said "check whether an iFlow is running". It now says it
     shows deployment status only and points to `getProblemMessages`.

  | Run | Tool calls | Answer |
  |---|---|---|
  | Before | `listIflows` | "running normally" — wrong |
  | New tool, old description | `listIflows` | "running normally" — still wrong |
  | New tool, new description | `getProblemMessages("Payment_Status_Poll")` → `getErrorDetails` | RETRY, SFTP connection refused — right |

- **The same question can take a different path.** Two runs of the Order_Sync
  question in a row: one skipped `searchCpiDocs` and invented HikariCP
  settings plus a help.sap.com URL as its "citation"; the next chained all
  three tools. Temperature 0 does not make a thinking model's tool choices
  repeatable.
- **Made-up citations.** The right Payment_Status_Poll answer cites
  `[03-sftp-adapter.txt]` and `[04-network-issues.txt]` — neither file
  exists, and it never searched the docs. The prompt asks for citations from
  tool results; nothing checks it yet. That check is the next safety step.

**Try:** "Why did Invoice_To_Partner_EDI fail?", and a made-up iFlow name.

### Step 14: Knowledge that grows — SAP Help, saved in pgvector

**What:** two more tools. `searchSapHelp(query)` finds pages in a catalog of
the official SAP Integration Suite documentation; `readSapHelpPage(pageId,
question)` downloads one, saves it in pgvector and returns the best passages,
labelled with the page URL. The system prompt says: local docs first, SAP Help
only when they miss. A saved page is found by `searchCpiDocs` from then on.
**Classes:** `SapHelpCatalog`, `SapHelpClient`, `SapHelpTools`,
`RetrievalService.searchWithin` / `sourceOf`, `SapHelpToolsTest`,
`sap-help/catalog.tsv`.
**The source question came first:**
- help.sap.com returned a 1 KB page with no content — it renders with
  JavaScript — and its robots.txt says `User-agent: * / Disallow: /`. Not a
  source for a program, and not something to work around.
- SAP publishes the same docs as Markdown on GitHub,
  `SAP-docs/btp-integration-suite`, CC BY 4.0. Clean text, allowed with
  attribution. ~1,660 pages in `docs/ISuite_Integrations_APIs`.
**Idea:** a knowledge base that grows from sources you can check, never from
what the model said. The catalog bounds what the model can fetch — it passes a
page id, never a URL — and the fetch time lets a page expire (30 days).
**Measured with Qwen3 14B, "How do I configure the Kafka receiver adapter in
Cloud Integration?"** (the local docs only mention Kafka in an overview):

| | 1st ask | 2nd ask, same question |
|---|---|---|
| Tools | `searchCpiDocs` (miss) → `searchSapHelp` → `readSapHelpPage` (downloaded) | `searchCpiDocs` — found the saved page |
| Saved | 28 chunks, 1 page | nothing new |
| Tokens in / out | 6,454 / 1,580 | 2,846 / 837 |
| Time | 118 s | 50 s |

- **Grows as intended:** the second answer came from pgvector, in one tool
  call, at less than half the tokens and time.
- **A real URL is not a true citation.** The first answer lists "Consumer
  Group" and "Key/Value Deserialization" with the page URL; neither is on the
  page (and a consumer group belongs to the Kafka *sender*). The second answer
  cites "Integration Flow Editor overview" — a real catalog page no tool
  returned. Same lesson as Step 13, now with correct-looking links: citations
  need checking in code.
(A later live AS4 run through the same tools: `searchCpiDocs` miss →
`searchSapHelp` → `readSapHelpPage`, downloaded, 7 chunks saved, 100 s.)
**Try:** `select metadata->>'title', count(*) from cpi_chunks where
metadata->>'source' = 'sap-help' group by 1;` after a few questions. Ask about
a topic the catalog has no page for, and watch what the model does.

### Step 15: One assistant — one path, one source of truth

**Why:** by Step 14 there were three endpoints (`/chat`, `/ask`, `/agent`), two
UI modes, hand-written local docs *and* SAP pages, and SAP Help reachable only
if the model remembered to call two tools. "What gets called when" had become
the hardest question about the project — and in the browser, which only used
`/ask`, none of the tools ever ran.
**What:** one endpoint, `GET /assist`, and one path for every question:
1. **Find documentation — code, no model** (`KnowledgeService`): the store;
   on a miss (< 0.80) the best catalog page, if its title matches ≥ 0.82 —
   downloaded, saved, searched again.
2. **Answer — one assistant** (`CpiAgent`): question + passages + tools. A
   documentation question is answered from the passages with no tool; a
   tenant question makes the model call the tenant tools. No router, no mode.
3. **Check** (`AssistService`): "I don't know" despite passages → SAP Help
   once more; every cited `[Page Title]` checked against the pages the model
   was given (`unverifiedCitations`, ⚠ in the UI).

The only knowledge is the official SAP documentation: the 15 hand-written
files, `/chat`, `/ask`, `/agent`, `RagService` and the Step 10 evaluations
were removed (they are in git history). At startup `SeedRunner` saves ten
popular pages. Every answer returns its **path**.
**Classes:** `AssistService`, `AssistController`, `KnowledgeService`,
`SeedRunner`, `SeedProperties`, `CpiAgent`, `CpiDocsTool` (`searchDocs`),
`index.html`; tests `AssistServiceTest`, `KnowledgeServiceTest`.
**Idea:** decide *where* the model is needed. Finding documents is
deterministic work — code does it the same way every time. Deciding whether a
question needs live data is judgment — the model does that, with the tools in
hand. Most production assistants split the work the same way: retrieval
always, tools when needed.
**Measured with Qwen3 14B, empty store, 10 pages seeded (346 chunks, 23 s):**

| Question | Path | Result |
|---|---|---|
| Configure a JDBC adapter | Database 0.911 → no tool | Grounded; 1 model call, 23 s |
| Handle errors in an iFlow | Database 0.852 → no tool | Cites [Handle Errors Gracefully], 23 s |
| Configure the AS4 receiver adapter | Database (AS2, 0.850) → "I don't know" → downloaded "AS4 Receiver Adapter" → answered | 36 s |
| *same AS4 question again* | Database 0.904 → no tool | 11 s — no download |
| Why did Order_Sync fail today? | Database → `getProblemMessages` → `getErrorDetails` | JDBC pool timeout on ORDER_DB, 90 s |
| Capital of France | Database miss → no title ≥ 0.82 → declined | No download, 9 s |

- **The citation check found a problem at once:** in the first run 5 of 6
  answers cited pages the model was never given. Most were Markdown links
  inside SAP pages (`[Handle Errors in Successful Responses](….md)`), copied as
  citations; one was the model citing [JDBC Receiver Adapter] from memory.
  Stripping links when saving, and telling the model to call `searchDocs`
  before citing anything new: **0 of 6**.
- **The AS2/AS4 trap** — close passages, wrong topic — is what the "I don't
  know → SAP Help once" step is for; it also stopped downloading the
  second-best page (AS2) after `max-pages-per-miss` went to 1.
- **Known limits:** titles pick the page, so an *overview* page ("JDBC
  Receiver Adapter", "AS4 Receiver Adapter") wins over the "Configure the …"
  page with the steps — the answers now say the steps are missing instead of
  inventing them. For "how do I fix it", the model did not always look the fix
  up with `searchDocs`; the fix is then uncited.
**Try:** the QA checklist in the README, from an empty store. Then ask about a
topic in the catalog but not seeded, twice, and compare the two paths.

---

## Phase 2 — an agent with LangChain4j

Done in Steps 12–13 and folded into the one path in Step 15: the model decides
which tools to call and in what order. **Idea:** how context and reasoning
combine; when an agent is worth its extra calls and cost.

## Phase 3 — knowledge that grows

Done in Step 7 (pgvector), Step 14 (SAP Help pages, saved) and Step 15 (one
source of truth, citations checked). Next: pick "Configure the …" pages over
overview pages (hybrid search, or title rules), and an automated evaluation
of the one path.
