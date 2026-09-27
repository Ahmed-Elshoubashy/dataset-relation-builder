# Entity Graph Resolver

Finds every reference to a company, person, project, document or product across a file dump,
decides which references are the same real-world thing, links them, and serves an explorer UI
with the evidence behind every link. Java 21, SQLite, and a vanilla JS UI in `src/main/resources/web`.

## Run

```bash
./gradlew installDist
build/install/entity-grapgh-resolver/bin/entity-grapgh-resolver      # http://localhost:8765
```

Then click **Analyse dataset**, choose the folder, choose how scans are read (none / Tesseract / Claude + API key) and press **Analyse**.

Tests:

```bash
./gradlew test          # unit tests, and GenericDatasetTest, which builds a graph from a small fixture
./gradlew datasetTest   # slow: builds john-doe (../john-doe or ERKG_DATA_ROOT) with and without its profile
```

Settings are environment variables: `ERKG_DATA_ROOT`, `ERKG_WORK_DIR` (default `data`),
`ERKG_OCR_WORKERS`, `ERKG_CLAUDE_MODEL`, `ERKG_ADJUDICATOR`, `ERKG_PROFILE`, `ERKG_PROFILES_DIR`,
`ERKG_OWNER`, `ERKG_OWNER_DOMAIN`, `ANTHROPIC_API_KEY`, `PORT`.

## Other datasets

Nothing in the code is tied to one dataset. What a dataset has of its own goes in an optional profile:

- **Profile file.** The first one found is used:
  1. `profile.json` in the dataset folder: a dataset's own conventions always win;
  2. the profile chosen in the "Analyse dataset" dialog (the files in `profiles/`, or "None");
  3. `ERKG_PROFILE`, the server's default;
  4. a file in `profiles/` whose folder patterns match 30% or more of the dataset's files
     (so john-doe is recognised with nothing set);
  5. the built-in defaults.

  The analysis log and the result say which one was used. `profiles/john-doe.json` is the sample's
  profile; `ERKG_PROFILES_DIR` moves the `profiles/` folder (the Docker image sets it).

  ```json
  {
    "folderPatterns": ["Clients/{company}/{job_id:P-\\d+} {title}/**"],
    "jobIdPattern": "P-\\d+",
    "owner": null,
    "genericEmailDomains": ["gmail.com", "outlook.com"],
    "legalSuffixes": ["ltd", "inc", "gmbh"]
  }
  ```

  Every key is optional. `folderPatterns` turn folder names into hints (`{company}`, `{job_id:REGEX}`,
  `{title}`, `{category}`, `**` for the rest of the path); the first pattern that matches is used.
  With no pattern, files get no folder hints and everything else still runs. `jobIdPattern` defaults
  to the regex of the first `{job_id:...}`. Without a profile, broad default lists of free e-mail
  providers and legal suffixes (Ltd, Inc, GmbH, SA, BV, SRL, Pty, ...) are used.
- **Owner.** Found from the files: PDF letterheads, then the e-mail domain on the most e-mails (as
  sender or recipient), then the most named organisation. It can be set in the "Analyse dataset" dialog, with `ERKG_OWNER` /
  `ERKG_OWNER_DOMAIN`, or with `owner` in the profile. If nothing is found there is no owner, and
  the graph is built without one.
- **General extractor.** Files no template parser recognises, and free text (e-mail bodies, letters,
  notes), go through a last extractor. With **"Also let Claude read the files no template recognises"**
  ticked in the dialog and an API key, Claude returns the entities and relations as JSON (role `llm`,
  confidence 0.7), cached by file content in `ocr_cache.db`, so a rebuild costs nothing.
- **No API key.** The same extractor uses rules instead (role `free_text`, confidence 0.6): people in
  From/To/Cc lines and signature blocks, companies ending in a legal suffix, and labelled document
  numbers ("Invoice No: HR-1042"). It finds less than Claude, but sends nothing anywhere.
- **Company names** don't depend on the order files are read in. After matching, each company is named
  after the spelling its other spellings agree with most (not a typo that happened to come first), and
  companies that still match (a domain, a longer name seen after a shorter one) are merged, with method
  `merged:<rule>`.
- **Projects without folders.** With no project folders, a project title ("Job: Conveyor Upgrade") seen
  in 2 or more files for the same customer becomes a project (method `title_only`, confidence 0.6).
  A title seen once stays unresolved. Job ids in text are only recognised with a `jobIdPattern`.
- **Claude failures** (a bad key, a rate limit) fall back to the rules for that file, and are counted
  in the extract step's summary (`free_text_claude_failed…`) and reported in the analysis log.
- **Money** is read with `£ $ € ¥` or an ISO code (`USD`, `EUR`, ...) and in `1,234.56` or `1.234,56`
  form; documents keep a `currency`, and the UI formats amounts in it.

## Docker

```bash
docker compose up -d --build        # http://localhost:8766
```

- Two-stage image: built with JDK 21, run on a Java 21 JRE.
  Tesseract is installed, so all three reading options work.
- Your home folder is mounted **read-only at its own path**, so the dataset picker shows real laptop paths.
  Share less with `DATASETS_DIR`, and pre-fill the dialog with `DATASET`, in a local `.env` (git-ignored):

  ```
  DATASETS_DIR=/Users/you/Documents
  DATASET=/Users/you/Documents/datasets/john-doe
  ```
- The shipped profiles are in the image (`/opt/app/profiles`), so john-doe's is picked automatically
  and can be chosen in the dialog. `ERKG_PROFILE` in `.env` sets the container's default.
- Host port **8766** by default (`PORT=...` to change).
- The graph, OCR cache and extracted files live in the `graph-data` volume; `docker compose down -v` deletes them.
- Container name: `entity-grapgh-resolver-app-1`. Logs: `docker compose logs -f`.

## Code map

| Stage | Package / class | What it does |
|---|---|---|
| entry | `com.dubsof.Main`, `pipeline.Pipeline` | CLI commands; `Pipeline.build()` runs the 5 stages into a fresh database |
| 1 ingest | `ingest.Ingestor` | walks the folder, opens zips and e-mail attachments, sniffs real file types, hashes content |
| 2 read | `read.TextStage` + `read.TextReader` | native text (PDFBox, JavaMail, POI); image-only files go to the OCR `TextReader`: `ClaudeReader`, `TesseractReader` or `NullReader`, behind `CachedReader` |
| 3 extract | `extract.Extractor` | one parser per document template; produces mentions + facts, decides nothing |
| 4 resolve | `resolve.NameMatcher`, `resolve.Resolver`, `resolve.Adjudicator` | named matching rules (abbreviation, typo, acronym, e-mail domain...), clusters mentions into entities |
| 5 relate | `relate.Relator` | facts become relations with evidence files; derived links; consistency checks |
| serve | `api.ApiServer`, `api.GraphApi`, `api.AnalysisApi` | JDK `HttpServer`: explorer API, "Analyse dataset" runner, static UI |

## Java version and style

Targets **Java 21** (`options.release = 21`), but the code is written in plain **Java 8-style syntax**
on purpose, so it is easy to follow: classic interfaces and classes, anonymous classes instead of
lambdas, ordinary loops, no `var`, records, text blocks, switch expressions or `List.of`.

PDF text comes from PDFBox, and Tesseract is called as a command-line tool.
