# Entity Graph Resolver

Finds every reference to a company, person, project, document or product across a file dump,
decides which references are the same real-world thing, links them, and serves an explorer UI
with the evidence behind every link. Java 21, SQLite, and a vanilla JS UI in `src/main/resources/web`.

## Getting started

### Prerequisites

| You need | For |
|---|---|
| [Git](https://git-scm.com/) | Getting the code |
| [Docker](https://docs.docker.com/get-docker/) with Compose (Docker Desktop on macOS and Windows) | Option 1, the recommended way to run it |
| [JDK 21](https://adoptium.net/) (`java -version` shows 21) | Option 2, running without Docker. Gradle itself is downloaded by `./gradlew` |
| [Tesseract](https://tesseract-ocr.github.io/tessdoc/Installation.html), optional | Reading scans locally in Option 2 (`brew install tesseract`, `sudo apt install tesseract-ocr`). The Docker image already has it |
| An [Anthropic API key](https://console.anthropic.com/), optional | Claude features; see [API key](#api-key-optional) |

Get the code:

```bash
git clone git@github.com:Ahmed-Elshoubashy/dataset-relation-builder.git
cd dataset-relation-builder
```

### Option 1: Docker (recommended)

Nothing to install besides Docker: the image builds the app with JDK 21 and includes Tesseract.

```bash
docker compose up -d --build
```

Open <http://localhost:8765>. The first build pulls the images and takes a few minutes; later builds take
seconds.

The container can read your home folder (read-only, at its own path, so the dataset picker shows your real
paths). To share less, or to pre-fill the dataset folder, create a `.env` file next to `compose.yaml`
(it is git-ignored):

```
DATASETS_DIR=/Users/you/Documents
DATASET=/Users/you/Documents/datasets/john-doe
```

| `.env` setting | Default | What it does |
|---|---|---|
| `DATASETS_DIR` | your home folder | The folder the container may read, and the only one the dataset picker can open |
| `DATASET` | `DATASETS_DIR` | The dataset folder pre-filled in the "Analyse dataset" dialog |
| `PORT` | `8765` | The port on your machine |
| `ANTHROPIC_API_KEY` | none | Optional; see [API key](#api-key-optional) |
| `ERKG_PROFILE` | none | A default profile for every dataset, e.g. `/opt/app/profiles/john-doe.json` (see [Other datasets](#other-datasets)) |

Useful commands:

```bash
docker compose logs -f              # follow the logs
docker compose down                 # stop (the graph is kept)
docker compose down -v              # stop and delete the graph, the OCR cache and extracted files
```

- The image is built in two stages: with the official `gradle:9.0.0-jdk21` image (Gradle preinstalled),
  then run on a Java 21 JRE. Library jars stay in a BuildKit cache between builds. Keep the image's
  Gradle version equal to `gradle/wrapper/gradle-wrapper.properties`.
- The graph, the OCR cache and extracted archive members live in the `graph-data` volume.
- The shipped profiles are in the image (`/opt/app/profiles`), so john-doe's is picked automatically and
  can be chosen in the dialog.
- The container is called `entity-graph-resolver-app-1`. Its port is published on `127.0.0.1` only:
  the API has no login.

### Option 2: Gradle (no Docker)

With JDK 21 installed, from the repository root:

```bash
./gradlew installDist
build/install/entity-graph-resolver/bin/entity-graph-resolver
```

On Windows, use `gradlew.bat installDist` and `build\install\entity-graph-resolver\bin\entity-graph-resolver.bat`.

Open <http://localhost:8765>. Start the server from the repository root: the shipped profiles
(`profiles/`) are looked up from the current folder. The graph and the caches are written to `data/`.

### Analyse a dataset

1. Click **Analyse dataset**.
2. Choose the dataset folder (any folder of files: PDFs, e-mails, Office files, scans, zips).
3. Choose how scans and screenshots are read: **Without OCR**, **Local OCR (Tesseract)**, or **Claude**.
4. Optionally: name the owner organisation, choose a profile, and tick "Also let Claude read the files no
   template recognises".
5. Press **Analyse**. The explorer keeps showing the previous graph until the new one is ready.

### API key (optional)

Everything works without an API key: reading files, OCR with Tesseract, matching, the graph explorer, and
the chat's fixed questions. A key adds three Claude features: OCR of scans and screenshots, the general
extractor for files no template recognises, and typing your own questions in the chat.

The simplest way is to paste the key in the UI when a feature needs it, in the "Analyse dataset" dialog
or the chat. It is used for that request only and never saved. To give the server a key instead, set
`ANTHROPIC_API_KEY` before starting it (`export ANTHROPIC_API_KEY=...`), or put it in `.env` for Docker.
Claude answers are cached by file content in `data/ocr_cache.db`, so analysing the same files again costs
nothing.

### Settings

All settings are optional environment variables.

| Variable | Default | What it does |
|---|---|---|
| `PORT` | `8765` | The port the explorer listens on |
| `ERKG_WORK_DIR` | `data` | Where the graph (`graph.db`), the Claude/OCR cache (`ocr_cache.db`) and extracted archive members are stored |
| `ERKG_DATA_ROOT` | `../john-doe` | The dataset folder pre-filled in the "Analyse dataset" dialog |
| `ERKG_BROWSE_ROOT` | none | The dataset picker cannot leave this folder (set by Docker) |
| `ANTHROPIC_API_KEY` | none | The server's Anthropic key (see [API key](#api-key-optional)) |
| `ERKG_CLAUDE_MODEL` | `claude-opus-5` | The Claude model for OCR, extraction, adjudication and the chat |
| `ERKG_OCR_WORKERS` | `8` | Scans read, and files sent to Claude, in parallel |
| `ERKG_OCR_PHOTOS` | `0` | `1` sends photos to OCR too (normally skipped: they carry no text) |
| `ERKG_ADJUDICATOR` | `rules` | Who decides borderline company matches: `rules` keeps them apart, `claude` asks Claude |
| `ERKG_PROFILE` | none | The server's default dataset profile (see [Other datasets](#other-datasets)) |
| `ERKG_PROFILES_DIR` | `profiles` | The folder of shipped profiles offered in the dialog |
| `ERKG_OWNER`, `ERKG_OWNER_DOMAIN` | none | The owner organisation, instead of detecting it from the files |

Tuning values (matching thresholds, confidences, graph limits) are constants in `Config.java`.

### Tests

```bash
./gradlew test          # unit tests, and GenericDatasetTest, which builds a graph from a small fixture
./gradlew datasetTest   # slow: builds john-doe (../john-doe or ERKG_DATA_ROOT) with and without its profile
```

### Troubleshooting

- **Port 8765 is already in use:** start with another port, `PORT=8080 build/install/...`, or set `PORT`
  in `.env` for Docker.
- **The dataset folder isn't in the picker (Docker):** the container only sees `DATASETS_DIR` (by default,
  your home folder). Set it in `.env` to a folder that contains the dataset, then `docker compose up -d`.
- **"Local OCR (Tesseract)" is greyed out:** Tesseract isn't installed (Option 2 only). Install it and
  restart the server.
- **The Profile menu is empty, or john-doe isn't recognised:** the server was started outside the
  repository root. Start it from the root, or set `ERKG_PROFILES_DIR` to the `profiles` folder.
- **"Anthropic rejected this API key":** check the key, or leave it empty to use the features that don't
  need it.
- **Start over:** stop the server and delete `data/` (Option 2), or `docker compose down -v` (Docker).

## Graph explorer

The left list shows every entity by type, most connected first. Clicking one centres the graph on it
(1 or 2 hops); the details panel shows its spellings, its relations grouped by type, and the files behind it.

- **Busy nodes.** A customer has 300+ links, and a view draws 70 nodes (160 at 2 hops). Neighbours are
  drawn once each, projects and companies first, then the strongest links. What is left out is not
  dropped silently: it becomes one dashed group node per relation and type next to the centre, and a
  "Not drawn" bar under the graph ("178 documents · issued to", "73 people · works for"). Clicking either
  opens that group in the details panel, with every entry listed.
- **Why two entities are linked.** Clicking an edge shows the relation in the details panel: the files
  that state it, and how each end is written in each file ("ACME Corp" as bill-to, matched by
  `normalized`). A derived link shows its rule and the documents it was inferred from instead.
- **How two entities are connected.** "Find connection…" in the details panel picks a second entity and
  draws only the shortest paths between the two (up to 4 links, at most 5 paths; with or without
  derived links, as the toggle says), listed step by step; clicking a step shows its evidence. The
  owner is never a step in between: it is linked to nearly everything.
- **Ask the graph.** The "Ask" button opens a chat. Names in answers are links that open the entity, and
  the graph centres on the entity the answer is about.
  - Four fixed questions always work, with no API key: "What are the entities?", "How many customers are
    there?", "How many people are there?" and "Who is the owner?" (`chat.ChatPresets`).
  - Typing your own question needs Claude: the server's `ANTHROPIC_API_KEY`, or a key typed in the chat
    (kept only in that browser tab). Without one, the text box is disabled. Claude answers by calling
    read-only tools over the graph (`chat.ChatTools`: find entities, get an entity, list its links, aliases,
    connections, why a link exists); it never writes and never runs its own SQL, so every number comes
    from the graph.
  - Each answer says whether Claude or the graph answered it, and which tools were used.
- **Each link once.** A file's free text (e-mail body, signature) does not add a `MENTIONS` link to
  someone the file's template already linked ("Dana Price SENT the e-mail" is not also "the e-mail
  MENTIONS Dana Price").
- **Derived links** (dashed, "derived links" toggle) are shortcuts computed from paths: a person
  `INVOLVED_IN` a project through a project document they authored, sent or received; a project
  `USES_PRODUCT` through its line items and datasheets; a company `PURCHASED_OR_QUOTED` a product through
  the line items of documents issued to it. "Attn:" lines do not count for `INVOLVED_IN` for now: a person's organisation comes from
  the document, so the same contact exists once per organisation, and each copy would be linked.

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
    "skipDirectories": ["/Software", "node_modules"],
    "jobIdPattern": "P-\\d+",
    "owner": null,
    "ownerDomain": null,
    "genericEmailDomains": ["gmail.com", "outlook.com"],
    "legalSuffixes": ["ltd", "inc", "gmbh"],
    "documentPrefixes": {"HR": "invoice", "RFQ": "request_for_quote"}
  }
  ```

  Every key is optional. `folderPatterns` turn folder names into hints (`{company}`, `{job_id:REGEX}`,
  `{title}`, `{category}`, `**` for the rest of the path); the first pattern that matches is used.
  With no pattern, files get no folder hints and everything else still runs. `skipDirectories` lists
  folders whose files are recorded as skipped and never read, written like `.gitignore` lines:
  `"/Software"` is that folder at the root, a bare `"node_modules"` is that folder at any depth, a path
  with `/` starts at the root, `*` / `?` match inside one folder name and `**` any number of folders
  (the john-doe profile skips `/Software`). `.git` folders, code and media files are always skipped. `jobIdPattern` defaults
  to the regex of the first `{job_id:...}`. Without a profile, broad default lists of free e-mail
  providers and legal suffixes (Ltd, Inc, GmbH, SA, BV, SRL, Pty, ...) are used.
- **Document numbers** identify documents (`extract.parsers.DocumentNumbers` is the one place that knows them).
  A number after a label is read in any format ("Invoice No: HR-1042", "PO Number: PO-3038",
  "Ref: QUO-5236"), and the label gives its type. Without a label, a code only counts when its prefix
  is known: the defaults (INV, QUO, PO, DN, DWG, CAL, ...), the profile's `documentPrefixes`, or a
  prefix seen after a typed label anywhere in the dataset ("Invoice No: HR-1040" teaches that HR is an
  invoice, so `HR-1043.pdf` and "HR-1041" in an e-mail are the same documents). An unknown prefix
  ("PK-10") stays a possible part number. A document with no number is keyed by its file.
- **Owner.** First found wins: the owner typed in the "Analyse dataset" dialog; `owner` in the dataset's
  own `profile.json`; `ERKG_OWNER`; `owner` in a chosen, `ERKG_PROFILE` or suggested profile; detection.
  A given owner keeps a domain only if it goes with it (`ERKG_OWNER_DOMAIN` with `ERKG_OWNER`,
  `ownerDomain` with the profile's `owner`, else the detected domain if it matches the name).
  Detection needs structural evidence: a letterhead on 5+ PDFs (and 10% of them), or a company e-mail
  domain on 5+ e-mails and 30% of those with a company domain (as sender or recipient). With a domain
  but no letterhead, the owner is the most named organisation that matches the domain; if the letterhead
  and the domain disagree, the domain is dropped. Otherwise there is no owner, and the graph is built
  without one. The analysis log says why ("letterhead on 1307 PDFs; domain on 100% of e-mails (323)",
  or "no letterhead on 5+ PDFs, no domain on 5+ e-mails ...").
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

## Code map

| Stage | Package / class | What it does |
|---|---|---|
| entry | `com.dubsof.Main`, `pipeline.Pipeline` | `Main` starts the server; `Pipeline.build()` runs the 5 stages into a fresh database |
| 1 ingest | `ingest.Ingestor` | walks the folder, opens zips and e-mail attachments, sniffs real file types, hashes content |
| 2 read | `read.TextStage` + `read.TextReader` | native text (PDFBox, JavaMail, POI); image-only files go to the OCR `TextReader`: `ClaudeReader`, `TesseractReader` or `NullReader`, behind `CachedReader` |
| 3 extract | `extract.Extractor` | one parser per document template; produces mentions + facts, decides nothing |
| 4 resolve | `resolve.NameMatcher`, `resolve.Resolver`, `resolve.Adjudicator` | named matching rules (abbreviation, typo, acronym, e-mail domain...), clusters mentions into entities |
| 5 relate | `relate.Relator` | facts become relations with evidence files; derived links; orphan documents pruned |
| serve | `api.ApiServer`, `api.GraphApi`, `api.AnalysisApi` | JDK `HttpServer`: explorer API, "Analyse dataset" runner, static UI |
| chat | `chat.ChatService`, `chat.ChatTools`, `chat.ChatPresets` | the explorer's chat: Claude with read-only tools, and the fixed questions |

The HTTP API (every endpoint, its parameters, request body and response) is documented in
[docs/API.md](docs/API.md).

## Java version

Java 21 (`options.release = 21`), built with Gradle 9 through the wrapper (`./gradlew`). PDF text comes
from PDFBox, Office files from Apache POI, e-mails from JavaMail, and Tesseract is called as a
command-line tool.
