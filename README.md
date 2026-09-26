# Entity Graph Resolver (Java)

Java 21 port of `documents-graph-builder`: finds every reference to a company, person, project,
document or product across a file dump, decides which references are the same real-world thing,
links them, and serves an explorer UI with the evidence behind every link.

Same pipeline, same SQLite schema and same JSON API as the Python version, so the web UI
(`src/main/resources/web`) is shared unchanged.

## Run

```bash
./gradlew installDist
build/install/entity-grapgh-resolver/bin/entity-grapgh-resolver      # http://localhost:8765
```

Then click **Analyse dataset**, choose the folder, choose how scans are read (none / Tesseract / Claude + API key) and press **Analyse**.

Tests:

```bash
./gradlew test
```

Settings are environment variables: `ERKG_DATA_ROOT`, `ERKG_WORK_DIR` (default `data`),
`ERKG_OCR_WORKERS`, `ERKG_CLAUDE_MODEL`, `ERKG_ADJUDICATOR`, `ANTHROPIC_API_KEY`, `PORT`.

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
- Host port **8766** by default (`PORT=...` to change), so it runs next to the Python version on 8765.
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

Differences from the Python version: PDF text comes from PDFBox instead of PyMuPDF (same results on
this dataset; PDFBox is a little more lenient with one damaged PDF), and Tesseract is called as a
command-line tool.
