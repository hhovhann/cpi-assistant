# Changelog

## 0.1.0 — first MVP (2026-09-28)

An agent for SAP Cloud Integration, answering from the official SAP documentation.

- **One path for every question:** the two best of 1,660 official SAP pages (hybrid: meaning and keywords),
  downloaded and saved the first time, then one agent answer with citations checked in code.
- **Tools:** `readPage`, `searchDocs`; with a tenant, `listIflows`, `getProblemMessages`, `getErrorDetails` —
  read-only, OAuth from a BTP service key; an error comes with the SAP documentation about it.
- **Hooks** around every tool call: argument guard, result limit, audit log.
- **Skills** (opt-in) and an **MCP client** for official SAP MCP servers (none configured), both off by default.
- **Knowledge stays current:** a saved page is downloaded again after 30 days, or when the cleaning changes
  (content version).
- **Safe defaults:** listens on 127.0.0.1 only; questions 1–1,000 characters; secrets only from environment
  variables; only catalog pages can be fetched.
- **Measured:** 62 unit tests; `scripts/qa.sh` 14 of 14 live checks; documentation 12 of 15 right, 12 of 13
  grounded; tool use 9 of 10 right.
- Runs with LM Studio (Qwen3 14B) locally, or OpenAI / Claude with `cpi.chat.provider`.

The steps that led here are in [docs/LEARNING-PATH.md](docs/LEARNING-PATH.md).
