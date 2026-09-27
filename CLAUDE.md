# CLAUDE.md

## Project

Entity Graph Resolver, a take-home prototype. It reads a company's file dump (PDFs, e-mails,
spreadsheets, scans, zips), finds references to companies, people, projects, documents and
products, decides which references are the same real-world thing, and serves a graph explorer UI.

The brief says reviewers judge it on: **ambiguous matches, data quality, scalability, explainability**.

Java 21, Gradle, SQLite, and the JDK `HttpServer` with a vanilla JS UI in `src/main/resources/web`.

### Pipeline (see the README "Code map")
1. ingest: `ingest.Ingestor` walks the folder, unpacks zips and attachments, hashes files.
2. read: `read.TextStage` gets native text; scans go through OCR (`ClaudeReader`, `TesseractReader`, `NullReader`, cached by `CachedReader`).
3. extract: `extract.Extractor` runs 16 template parsers (`extract/parsers/*`) that write **mentions** and **facts**. This stage decides nothing.
4. resolve: `resolve.Resolver` + `resolve.NameMatcher` group mentions into **entities**. Borderline company matches go to an `Adjudicator`.
5. relate: `relate.Relator` turns facts into **relations** between entities, with evidence files.

Schema: `db/Db.java`. Every mention records `entity_id`, `method` (the rule that resolved it) and `confidence`.

### Commands
```bash
./gradlew test                 # unit tests
./gradlew installDist
build/install/entity-grapgh-resolver/bin/entity-grapgh-resolver   # UI on http://localhost:8765
```
There is **no CLI analysis command**. `Main` only starts the server. A graph is built from the UI
("Analyse dataset") or with `POST /api/analysis`, into `data/graph.db`.
The sample dataset is at `../john-doe` (`ERKG_DATA_ROOT`).
`PipelineTest` reads `data/graph.db` and is skipped when that file doesn't exist.

### Code style
- Match the surrounding code. The README asks for plain Java 8-style syntax: no `var`, records,
  text blocks, switch expressions or `List.of`; ordinary loops and classes.
- All SQL lives in `dao/*Dao.java`. Row types live in `dao/row/`.
- Comments are short Javadoc that say *why*, often with a concrete example from the data
  (`"ACME Corp" ~ "Acme Corporation"`). Keep that density.
- Run `./gradlew test` before finishing. Don't commit unless asked.

---

## Current task: stop the system depending on this one dataset

A code review found that the extraction and several settings only work for the `john-doe` sample
dataset. On any other company's files, most content would not be extracted. The goal is that a
different dataset (other folder layout, other country, other document templates) still produces a
useful graph, while `john-doe` results stay at least as good as now.

### Where the dataset is hardcoded

| Assumption | Location | Fix (details in the step) |
|---|---|---|
| Folder layout `Customers/<company>/JOB-yyyy-nnnn <title>/` | `ingest/Ingestor.java` (`JOB_DIR` line 47; path split around lines 344-360) | Step 5: read folder patterns from an optional `profile.json`; no pattern means no folder hints |
| Owner defaults to "Meridian Packaging Systems Ltd" / `meridianpackaging.co.uk` | `Config.java` lines 32-34 | Step 2: remove the defaults; env vars become optional overrides |
| Owner detection needs PDF letterheads with English legal suffixes | `pipeline/OwnerDetector.java` (has a `TODO, revist`) | Step 2: add a most-frequent-organisation/domain signal; allow "no owner"; user can override in the UI |
| `Config.ownerName` is a mutable static, set during analysis and read by parsers and the resolver | `Pipeline.detectOwner`, `ParserUtils`, `LetterParser`, `Resolver`, `Relator` | Step 2: pass an owner value from `Pipeline.build` into the stages; handle null |
| 16 parsers, one per template of this dataset; free text only finds names that are already known | `extract/Extractor.java` lines 79-95; `relate/Relator.linkNamesInFreeText` | Step 6: `LlmParser` as a last-resort parser, with a rule-based fallback when there is no API key |
| Claude adjudicator prompt says "UK packaging-machinery supplier" | `resolve/ClaudeAdjudicator.java` line 40 | Step 3: build the prompt from the detected owner and the mention context |
| Only £ is recognised as a currency | `extract/parsers/BusinessDocParser.java` line 30; `web/app.js` line 258 (`money()`) | Step 4: parse any symbol or ISO code, store `currency`, format with `Intl.NumberFormat` |
| Fixed lists of free e-mail providers and legal suffixes | `resolve/NameMatcher.java` (`GENERIC_DOMAINS`, `LEGAL_SUFFIXES`) | Step 5: broad default lists, overridable in `profile.json` |
| Tests assert john-doe's exact counts (12 customers, 38 projects) and need a prebuilt graph | `src/test/java/com/dubsof/graph/PipelineTest.java` | Step 1: a second-dataset test that builds its own graph and checks general rules, not counts |

### Plan, in this order

Do the steps in order. Each step should leave the test suite passing.

#### Step 1: Test on a second dataset (do this first, so every later change can be measured)
- Add a small fixture under `src/test/resources/datasets/generic/`, laid out **differently** from john-doe:
  - no `Customers/` folder;
  - a US or German owner company;
  - accented names ("José Müller", "Müller GmbH" and "Mueller GmbH");
  - a `$` or `€` invoice;
  - a plain `.eml` thread whose body mentions a company and a person;
  - a free-text meeting note.
  Plain text, `.eml`, and a generated PDF are enough. Keep it small.
- Add a test that runs `Pipeline.build(...)` on the fixture into a temp directory, so it does not
  depend on `data/graph.db`. Use `OcrBackend` none and the rules adjudicator.
- Assert general rules, not counts. For example:
  - every e-mail sender becomes a person;
  - the owner is detected;
  - no company mention is unresolved (except free e-mail domains);
  - the invoice total and currency are read;
  - names in the meeting note are linked.
- Some of these assertions will fail until later steps are done. Mark them `@Disabled("step N")`
  and enable each one in the step that fixes it.

#### Step 2: Detect the owner instead of assuming it
- Remove the Meridian defaults in `Config`. Keep `ERKG_OWNER` / `ERKG_OWNER_DOMAIN` as optional overrides only.
- In `OwnerDetector`, add a signal besides letterheads: the most frequent organisation or sender
  domain across all files. Widen the legal-suffix pattern (see step 5).
- If nothing is detected there is **no owner**. Every place that uses the owner must handle null:
  the resolver's anchor, `ParserUtils` `IMPLIED_OWNER`, `LetterParser`, and the gazetteer exclusion in `Relator`.
- Replace the mutable static `Config.ownerName` with an owner value passed from `Pipeline.build`
  to the stages (e.g. a small `Owner` object on the stage constructors). The server has 8 threads,
  so a shared static is a bug.
- Show the detected owner in the "Analyse dataset" dialog and let the user override it
  (`AnalysisApi` + `app.js`).

#### Step 3: Build the Claude prompt from the data
- In `ClaudeAdjudicator`, remove the industry and country wording. Build the prompt from the
  detected owner (if any) and the mention context:
  `"In the business files of <owner>, does the company name '<a>' refer to the same ... as '<b>'?"`

#### Step 4: Recognise any currency
- Parse `£ $ € ¥` and ISO codes (`GBP`, `USD`, `EUR`, ...) next to amounts. Keep accepting the
  garbled `·` / `?` characters the current regex handles.
- Store a `currency` attribute on documents.
- In `app.js`, `money()` should use `Intl.NumberFormat(undefined, {style: "currency", currency})`
  with the document's currency, falling back to plain numbers when there is none.

#### Step 5: Move conventions into an optional profile file
- Add an optional `profile.json`, read from the dataset root or from `ERKG_PROFILE`:
  ```json
  {
    "folderPatterns": ["Customers/{company}/{job_id:JOB-\\d{4}-\\d{4}} {title}/**"],
    "owner": null,
    "genericEmailDomains": ["gmail.com", "outlook.com", "hotmail.com", "yahoo.com", "icloud.com"],
    "legalSuffixes": ["ltd", "limited", "inc", "llc", "corp", "plc", "gmbh", "ag", "sa", "sarl", "bv", "nv", "pty", "srl", "spa"]
  }
  ```
- `Ingestor` should use `folderPatterns` instead of the hardcoded `Customers` / `JOB_DIR` logic.
  With no patterns, files simply get no folder hints and everything else still runs.
- Ship a john-doe profile that reproduces today's behaviour exactly, and use a broad default list
  of legal suffixes and free e-mail providers when there is no profile.

#### Step 6: A general extractor for files no template recognises (largest step, biggest gain)
- Add `extract/parsers/LlmParser.java` implementing `Parser`, placed **last** in `Extractor.parsers`.
  It runs when no template parser handled the file, and also on free text: e-mail bodies, notes, letters.
- It asks Claude for structured JSON, limited to the existing enums (`EntityType`, `MentionRole`, `RelationType`):
  `{"entities": [{"type", "name", "email", "organisation", "role"}], "relations": [{"src", "rel", "dst"}]}`.
  Reuse the structured-output call pattern in `ClaudeAdjudicator` (`output_config` with a JSON schema)
  and `Config.CLAUDE_MODEL`.
- Cache answers by the file's `sha256` in `ocr_cache.db`, as `CachedReader` does, so rebuilds cost nothing.
- Mentions it creates get `method = "llm"` and a lower confidence (e.g. 0.7), so the resolver and
  the UI can tell them apart from template fields. Show that method in the UI evidence list and in
  `METHOD_HELP` in `app.js`.
- **No API key:** fall back to a rule-based extractor with the same output:
  - people from `From:` / `To:` headers and signature blocks (name line + e-mail/phone);
  - companies from capitalised word runs ending in a legal suffix;
  - document numbers from patterns like `[A-Z]{2,5}-\d+`.
  The offline mode must keep working.
- Do not change the resolver's interface. It only reads mentions and facts, so new mentions flow
  through the existing matching.

### Done when
- `./gradlew test` passes on a clean checkout with **no** `data/graph.db`. The new fixture test runs
  and is not skipped.
- The fixture test passes with no `@Disabled` assertions left (with the rule-based fallback, i.e. no API key).
- Built from the UI, john-doe gives the same results as before: 12 customers + owner, 38 folder
  projects, `QUO-5238` merged from 3+ files, `DWG-9296` split in two (see `PipelineTest`).
- No code mentions Meridian, packaging, `JOB-` or `Customers` outside the john-doe profile and its tests.
- The README has a short section on how the system handles other datasets (the profile file, the
  general extractor, and what happens with no API key).

### Known issues outside this task (don't fix unless asked, but don't make them worse)
- Data-quality checks were removed in commit `eff91c0`. Document `conflicts` are stored but never shown.
- Borderline company matches (score 0.65-0.80) are thrown away without being recorded
  (`Resolver.decideCompany`), even though `RuleAdjudicator`'s comment says they are logged.
- Ambiguous people without an organisation all merge into one `name|?` entity with confidence 1.0
  (`Resolver.resolvePeople`).
- People with a free e-mail address (gmail, ...) are matched by name only; their e-mail is ignored.
- `NameMatcher` strips non-ASCII letters ("José" becomes "jos"). Step 1's fixture will expose this.
  Normalising accents with `java.text.Normalizer` (NFD, then drop combining marks) in `companyWords`
  and `personKey` is a small fix that is in scope if the fixture needs it.
- `relation_evidence` is written but never read by the API. There is no evidence or confidence per link in the UI.
