# CLAUDE.md

## Project

Entity Graph Resolver, a take-home prototype. It reads a company's file dump (PDFs, e-mails,
spreadsheets, scans, zips), finds references to companies, people, projects, documents and
products, decides which references are the same real-world thing, and serves a graph explorer UI.

The brief says reviewers judge it on: **ambiguous matches, data quality, scalability, explainability**.

Java 21, Gradle, SQLite, and the JDK `HttpServer` with a vanilla JS UI in `src/main/resources/web`.

### Pipeline (see the README "Code map")
1. ingest: `ingest.Ingestor` walks the folder, unpacks zips and attachments, hashes files, and reads
   folder hints with the dataset's profile (`dataset.Profile` / `dataset.FolderPattern`).
2. read: `read.TextStage` gets native text; scans go through OCR (`ClaudeReader`, `TesseractReader`, `NullReader`, cached by `CachedReader`).
   Then `pipeline.OwnerDetector` finds the owner organisation (or none).
3. extract: `extract.Extractor` runs 16 template parsers (`extract/parsers/*`), then the general
   extractor `LlmParser` on free text. `LlmParser` asks Claude, or uses `FreeTextRules` offline.
   Parsers write **mentions** and **facts**. This stage decides nothing.
4. resolve: `resolve.Resolver` + `resolve.NameMatcher` group mentions into **entities**. Borderline company matches go to an `Adjudicator`.
5. relate: `relate.Relator` turns facts into **relations** between entities, with evidence files.

Per-analysis settings travel in a `dataset.Dataset` (owner + profile) passed to every stage. There are no mutable statics.

Schema: `db/Db.java`. Every mention records `entity_id`, `method` (the rule that resolved it) and `confidence`.

### Commands
```bash
./gradlew test                 # unit tests (GenericDatasetTest builds its own graph)
./gradlew installDist
build/install/entity-grapgh-resolver/bin/entity-grapgh-resolver   # UI on http://localhost:8765
ERKG_PROFILE=profiles/john-doe.json build/install/entity-grapgh-resolver/bin/entity-grapgh-resolver   # the sample, with its profile
```
There is **no CLI analysis command**. `Main` only starts the server. A graph is built from the UI
("Analyse dataset") or with `POST /api/analysis`, for example:
`curl -X POST localhost:8765/api/analysis -H 'content-type: application/json' -d '{"data_root":"/abs/path/john-doe","ocr":"none"}'`,
then poll `GET /api/analysis`. Set `ERKG_WORK_DIR` to a scratch folder so `data/` is not overwritten.
The sample dataset is at `../john-doe`.
`PipelineTest` reads `data/graph.db` and is skipped when that file doesn't exist.

### Code style
- Match the surrounding code. The README asks for plain Java 8-style syntax: no `var`, records,
  text blocks, switch expressions or `List.of`; ordinary loops and classes.
- All SQL lives in `dao/*Dao.java`. Row types live in `dao/row/`.
- Comments are short Javadoc that say *why*, often with a concrete example from the data
  (`"ACME Corp" ~ "Acme Corporation"`). Keep that density.
- Run `./gradlew test` before finishing. Don't commit unless asked.

---

## Done: stop depending on the john-doe dataset (commit `aa70d37`)

Delivered: the dataset profile (`profile.json` / `ERKG_PROFILE`, `profiles/john-doe.json`), owner
detection with "no owner" allowed and a UI override, the general extractor (Claude or offline rules),
any currency, a Claude prompt built from the data, accent-insensitive names, `GenericDatasetTest`, and
the README section on other datasets.

Verified in a review on 2026-09-27:
- All 128 tests pass.
- John-doe rebuilt **with** its profile matches the old results: 12 customers + owner, 38 folder
  projects, `QUO-5238` from 3 files, `DWG-9296` split in two, 0 unresolved company mentions.
- No Meridian, packaging, `JOB-` or `Customers` literals are left in code.

The review found the problems below. They are the current task.

---

## Current task: fix the gaps the review found

### Findings

| # | Problem | Evidence | Location |
|---|---|---|---|
| 1 | The profile is not used unless someone sets `ERKG_PROFILE` or puts `profile.json` in the dataset. Docker and the UI don't set it, so by default john-doe is analysed **without** it. | John-doe with no profile: **0 projects** (was 38), 1,934 project mentions unresolved, **19 companies** (was 15) | `dataset/Profile.java:70` `forDataset`; `api/AnalysisApi.java`; `compose.yaml` |
| 2 | Without customer folders as anchors, company clustering is greedy and order-dependent: the **first spelling seen becomes the name**, even when it is a typo. | "Vantage Electronic Inc", "Ashcome Confectionery", "Kingsly Textiles" became the names | `resolve/Resolver.java:174` `resolveCompanies`, `:235` `decideCompany` |
| 3 | A company mention **longer** than the known name never matches (truncation only works one way). | "Whitmore Dairy" was created first, so "Whitmore Dairy Products" became a second company | `resolve/NameMatcher.java:162` `matchCompany`, `:234` in `align` |
| 4 | E-mail domains become companies of their own when the company's name was misspelled first (see 2); nothing merges them later. | `vantageelectronics.com`, `kingsleytextiles.co.uk`, `ashcombeconfec.co.uk` became companies | `Resolver.decideCompany` |
| 5 | Projects only exist if a folder defines them. A job id or project title found in text (templates, Claude or rules) can only attach to a folder project; otherwise it stays `unresolved`. The general extractor's project findings are thrown away on any dataset without project folders. | See 1; `GenericDatasetTest` has no projects, so no test catches it | `resolve/Resolver.java:286` `resolveProjects`, step 2 at `:343`, `:392` |
| 6 | `ERKG_PROFILE` is server-wide and **overrides** `profile.json`, so it also applies to any other folder analysed from the UI. Set to john-doe, it silently applies john-doe's layout to other datasets. | By reading the code | `Profile.forDataset` |
| 7 | Claude failures in the general extractor are swallowed: a bad key or rate limit silently falls back to the rules, and nothing is logged or counted. | By reading the code | `extract/parsers/LlmParser.java:259` `askClaude`, catch at `:283` |
| 8 | The Claude answer cache is keyed by file sha256 + model only, not prompt version. Changing the prompt reuses stale answers. | By reading the code | `LlmParser.cached` / `save`; `dao/LlmExtractionsDao.java` |
| 9 | The `ocr_cache.db` connection in `LlmParser` is opened lazily and never closed, so one leaks per analysis. | By reading the code | `LlmParser.java:367` `cache()` |
| 10 | `findCompanyNames` keeps capitalised sentence-start words: "Ask Harbor Robotics Inc" includes "Ask". Only a fixed list of leading words is stripped. | By reading the code | `resolve/NameMatcher.java:384` |
| 11 | Person keys spell "ü" as "ue", so "Müller"/"Mueller" match, but the English spelling "Muller" stays a different person. Companies tolerate this through typo matching; people don't. | By reading the code | `NameMatcher.personKey` / `plainLetters` |
| 12 | John-doe regressions are still only checked by hand: `PipelineTest` needs a locally built `data/graph.db`. | Skipped on a clean checkout | `src/test/java/com/dubsof/graph/PipelineTest.java` |

### Plan, in this order

Each step should leave `./gradlew test` passing.

#### Step 1: Make the tests catch findings 1-5 first
- Extend the `GenericDatasetTest` fixture (`src/test/resources/datasets/generic/`) and add assertions. Keep it small, with no profile:
  - a customer whose **first** appearance is misspelled (e.g. bill-to "Harbour Robotcs Inc" before several
    correct "Harbor Robotics Inc" copies) → one company, named by the common spelling;
  - a short name seen before a longer one ("Mueller" before "Mueller GmbH Anlagenbau", or similar) → one company;
  - a sender domain for a customer whose name was first misspelled → merged into that company;
  - a project with no folder: a job id like `P-2041` or a repeated title in two documents → one project entity.
- Add a john-doe regression test that builds its own graph **only when** `../john-doe` exists
  (`assumeTrue`). Run it with and without `profiles/john-doe.json`, and check the counts from
  "Done" above for both runs, or at least companies = 15 and projects = 38. It is slow, so tag it
  (e.g. `@Tag("dataset")`) and document how to run it.

#### Step 2: Pick the profile sensibly (findings 1, 6)
- Precedence should be: `profile.json` in the dataset root > a profile chosen in the Analyse dialog
  > `ERKG_PROFILE` (the default only) > built-in defaults. A dataset's own file wins over the server-wide env var.
- Add a "Profile" select to the Analyse dialog listing `profiles/*.json` plus "none" (new
  `GET /api/analysis/options` field; `AnalysisApi.start` accepts `profile`).
- Optional: auto-suggest a shipped profile when its `folderPatterns` match most of the dataset's
  paths (e.g. >30% of files). Show the chosen profile in the analysis log (it already logs `Profile: ...`).

#### Step 3: Company clustering that doesn't depend on order (findings 2, 3, 4)
- **Canonical name:** after all company mentions are assigned, rename each company (except anchors:
  owner, folder customers) to its most frequent surface form among the strongest roles (bill-to,
  letterhead, vCard before filename/domain). Use `entitiesDao.updateName`, as `resolveProducts` does.
  The key can stay.
- **Symmetric truncation:** in `NameMatcher.align`, when mention words are left over but the candidate has none,
  allow it with the same per-word penalty when the leftover words are only descriptive (i.e. the candidate is a
  prefix of the mention). Add `NameMatcherTest` cases both ways; keep the "Acme" vs "Acme Robotics" guard
  (a different second word must still not match).
- **Merge pass:** after the first pass, compare every non-anchor company with every other one again (both
  directions, domains vs names too) and merge pairs at or above `ACCEPT`: move mentions, keep the anchor or
  the larger entity. Record `method = "merged:<rule>"` on moved mentions so the UI explains it.
  Company counts are small, so O(n²) is fine here; note it in a comment.

#### Step 4: Projects without folders (finding 5)
- In `Resolver.resolveProjects`:
  - A mention with a `job_id` but no folder project already creates a project in step 1. Make sure job ids
    found in text reach it: `ParserUtils` refs use `profile.jobIdPattern`, which is null without a profile.
    Add a conservative default pattern only if it doesn't create noise on john-doe; otherwise leave it
    and rely on titles.
  - A title-only mention with no candidate: create a project keyed by `titleKey` (+ customer when known)
    **if** the same title appears in at least 2 files; otherwise keep `unresolved`. Use method `title_only`
    and a confidence of 0.6.
- John-doe with its profile must still have exactly 38 projects (folder projects only). Check
  whether `title_only` projects appear there. If they do, they must be real, or be limited to datasets without folder patterns.

#### Step 5: Make Claude extraction observable and correct (findings 7, 8, 9)
- Count Claude failures by type (auth, rate limit, other) in `LlmParser` and add them to the extract step's
  summary (`free_text_claude_failed=…`). Log the first error message once. If *every* call fails, say so in the
  analysis log: "Claude extraction failed for all N files: <message>; used rules instead".
- Add a `PROMPT_VERSION` constant and include it in the cache key (`LlmExtractionsDao`). Bump it whenever the prompt or schema changes.
- Close the cache connection when extraction ends (e.g. `LlmParser implements AutoCloseable`, closed in `Extractor.run`).

#### Step 6: Small name fixes (findings 10, 11)
- `findCompanyNames`: drop leading capitalised words that are sentence-initial (after `^`, `.`, `!`, `?`, or a line
  start) when the rest is still at least one word + suffix, or require the name to be seen at least twice across files
  before it counts. Add tests.
- `personKey`: after `plainLetters`, also compare the variant with "ue/oe/ae" → "u/o/a", e.g. by trying both
  keys when looking up a person. Keep it exact otherwise (no fuzzy person matching in this task).

### Done when
- `./gradlew test` passes on a clean checkout with no `data/graph.db`, with the new `GenericDatasetTest` assertions enabled.
- John-doe **without** a profile: 15 companies with the correct names (the folder spellings or the most common
  spelling, e.g. "Vantage Electronics Inc", not "Vantage Electronic Inc"); no e-mail-domain companies for known customers.
- John-doe **with** its profile: unchanged (12 customers + owner, 38 folder projects, `QUO-5238` from 3+ files,
  `DWG-9296` split in two, 0 unresolved company mentions).
- A dataset analysed from the UI uses its own `profile.json` even when `ERKG_PROFILE` is set, and the dialog can choose a profile.
- A failing API key shows up in the analysis log, not only as fewer mentions.
- README "Other datasets" is updated: profile precedence, the dialog's profile choice, projects without folders.

### Known issues outside this task (don't fix unless asked, but don't make them worse)
- Data-quality checks were removed in commit `eff91c0`. Document `conflicts` are stored but never shown.
- Borderline company matches (score 0.65-0.80) are thrown away without being recorded
  (`Resolver.decideCompany`), even though `RuleAdjudicator`'s comment says they are logged.
  Step 3's merge pass must not auto-merge these; only `>= ACCEPT`.
- Ambiguous people without an organisation all merge into one `name|?` entity with confidence 1.0
  (`Resolver.resolvePeople`).
- People with a free e-mail address (gmail, ...) are matched by name only; their e-mail is ignored.
- People are probably over-split: the person's organisation comes from the document (`attn` → bill-to customer),
  so the same name at several customers becomes several people (john-doe: 1,514 people, "Liam Osei" ×7).
- `relation_evidence` is written but never read by the API. There is no evidence or confidence per link in the UI.
- Fixed since the first review: `NameMatcher` no longer strips accented letters (`plainLetters`).
