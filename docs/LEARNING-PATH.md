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

Step 7 was skipped for now (optional for a demo), so Step 8 came before it.

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

### Step 7: Persistent vector store — *skipped for now, optional for a demo*
Swap `InMemoryEmbeddingStore` for **pgvector** (PostgreSQL). Same
`EmbeddingStore` interface, so nothing else changes — but embeddings survive
a restart, and search uses an index instead of comparing against every vector.
**Idea:** the vector store is a pluggable detail; the interface is what
matters. *Optional for a demo.*

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

### Step 9: Web UI — *next*
A simple page to ask questions and see answers with sources. Makes it demo-able.

### Step 10: Tuning and evaluation
Measure instead of guessing:
- **Control group:** answer with *all* docs in the prompt (~20K tokens) and
  compare cost, latency and accuracy against RAG.
- **Chunk size sweep:** does (300, 30) or (800, 80) beat (500, 50)? Does it fix
  the "Step 2" boundary problem?
- **Citation quality:** does each cited chunk really support the claim?
  Does a stronger model stop blanket-citing?
- **Ranking:** fix Partner Directory outranking JDBC — *hybrid search*
  (keywords + vectors) or a *reranker*.
- **Model choice:** Llama 3.1 8B locally vs OpenAI — quality and cost.
- **Demo questions:** JDBC adapter setup, Script step vs Groovy Script, error
  handling in an iFlow — each should get an accurate answer with sources.

---

## Phase 2 — an agent with LangChain4j

Go beyond "retrieve once, answer once": let the model decide which tools to
call (search the docs, look up an adapter, ask a follow-up) and in what order.
**Idea:** how context and reasoning combine; when an agent is worth its extra
calls and cost.

## Phase 3 — production with Spring AI

Re-implement the pipeline with Spring AI (a start is already in place:
`/springai/chat`). **Idea:** once you know what each piece does by hand, a
framework's abstractions stop being magic — you can judge what they hide.
