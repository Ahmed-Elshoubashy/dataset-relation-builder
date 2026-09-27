# HTTP API

The explorer UI (`src/main/resources/web`) uses only this API; anything it shows can be fetched the same way.
Everything is served by `api.ApiServer` on `http://localhost:8765` (`PORT` to change).

- All responses are JSON (`application/json`), except `GET /api/files/{id}/raw` and the static UI files.
- Only `POST /api/chat` and `POST /api/analysis` take a body. Everything else is a `GET` with query parameters.
- There is no authentication. Keep the port on localhost (Docker publishes it on `127.0.0.1` only).
- Graph reads see a consistent graph: a new analysis replaces `data/graph.db` only once it is complete.

Examples below are real responses from the john-doe sample, shortened with `…`.

## Errors

Every error has the same shape, with an HTTP status:

```json
{ "detail": "Not Found" }
```

| Status | When |
|---|---|
| 400 | Invalid input, e.g. `{"detail": "Folder not found: /nope"}` or `{"detail": "from and to must be entity ids"}` |
| 403 | `/api/fs` on a folder the server cannot read |
| 404 | Unknown path, or an unknown entity, relation or file id |
| 409 | `POST /api/analysis` while an analysis is already running |
| 500 | Anything unexpected, e.g. a non-numeric `limit` (`{"detail": "NumberFormatException: …"}`) |

## Summary

| Method | Path | What it returns |
|---|---|---|
| GET | [`/api/stats`](#get-apistats) | Counts for the whole graph, and how it was built |
| GET | [`/api/entities`](#get-apientities) | Entities, filtered and paged, most connected first |
| GET | [`/api/entities/{id}`](#get-apientitiesid) | One entity: attributes, spellings, relations, evidence |
| GET | [`/api/graph`](#get-apigraph) | Nodes and edges around an entity, or the overview |
| GET | [`/api/relations/{id}`](#get-apirelationsid) | Why two entities are linked |
| GET | [`/api/connection`](#get-apiconnection) | The shortest paths between two entities |
| GET | [`/api/aliases`](#get-apialiases) | The spellings of the entity that best matches a text |
| GET | [`/api/files/{id}`](#get-apifilesid) | A file's text and every mention found in it |
| GET | [`/api/files/{id}/raw`](#get-apifilesidraw) | The original file |
| POST | [`/api/chat`](#post-apichat) | Answers a question about the graph |
| POST | [`/api/analysis`](#post-apianalysis) | Starts building a new graph from a folder |
| GET | [`/api/analysis`](#get-apianalysis) | Progress and result of the current (or last) analysis |
| GET | [`/api/analysis/options`](#get-apianalysisoptions) | What the "Analyse dataset" dialog can offer |
| GET | [`/api/fs`](#get-apifs) | Sub-folders of a folder, for the dataset picker |

## Shared shapes

**Entity summary.** Used in lists, graph nodes, and the ends of a relation.

| Field | Type | Meaning |
|---|---|---|
| `id` | number | Entity id |
| `type` | string | `company`, `person`, `project`, `document` or `product` |
| `name` | string | Canonical name |
| `key` | string | Identity key, unique per type: `"acme"`, `"JOB-2023-0004"`, `"QUO-5067"`, `"file:9f2c…"` for a document without a number |
| `subtitle` | string or null | Short context: a company's role (`customer`, `owner`), a person's or project's organisation, a document's type and date, a product's code |
| `doc_type` | string or null | Documents only: `invoice`, `quote`, `email`, … |
| `missing` | boolean | Documents only: referenced by other files, but no copy was found |
| `degree` | number | Number of relations (not on every response; see each endpoint) |

**Relation.** Used in graph edges and connection paths.

| Field | Type | Meaning |
|---|---|---|
| `id` | number | Relation id (for `/api/relations/{id}`) |
| `src`, `dst` | number | Entity ids; the relation reads "src REL dst" ("QUO-5067 ISSUED_TO Acme") |
| `rel` | string | `ISSUED_TO`, `WORKS_FOR`, `HAS_DOCUMENT`, … (`extract.RelationType`) |
| `weight` | number | Number of files that state it (for a derived relation: number of paths it comes from) |
| `derived` | 0 or 1 | 1 for shortcuts computed from other relations (`INVOLVED_IN`, `USES_PRODUCT`, `PURCHASED_OR_QUOTED`) |

---

## GET /api/stats

Counts for the whole graph, and the settings of the analysis that built it.

**Query parameters:** none.

**Response**

```json
{
  "entities": { "company": 15, "document": 3092, "person": 1514, "product": 9, "project": 38 },
  "relations": 12025,
  "mentions": 21627,
  "files": { "container": 25, "empty": 348, "needs_ocr": 558, "ok": 2802, "skipped": 164 },
  "resolution_methods": { "company:normalized": 5484, "company:typo": 47, "project:job_id": 2841, … },
  "meta": {
    "data_root": "/…/john-doe",
    "ocr_backend": "none",
    "profile": "/…/profiles/john-doe.json",
    "started_at": "2026-09-27T18:58:49Z",
    "finished_at": "2026-09-27T18:59:03Z"
  }
}
```

- `mentions` counts resolved mentions only.
- `files` counts files by status: `ok`, `empty`, `needs_ocr`, `skipped`, `container`, `corrupt`.
- `resolution_methods` counts mentions by `"type:method"`: which rule resolved how many.
- `meta` holds the settings of the run. It is empty before the first analysis.

## GET /api/entities

Entities, most connected first.

**Query parameters**

| Name | Default | Meaning |
|---|---|---|
| `type` | any | `company`, `person`, `project`, `document` or `product` |
| `q` | none | Text to find in the name, the key, or any spelling (alias); case-insensitive, substring |
| `doc_type` | any | Documents of one type: `invoice`, `quote`, … |
| `limit` | 100 | At most 1000 (`Config.ENTITY_LIST_MAX_LIMIT`) |
| `offset` | 0 | For paging |

**Response**

```json
{
  "total": 1,
  "items": [
    { "id": 2, "type": "company", "name": "Acme Corporation", "key": "acme", "subtitle": "customer",
      "doc_type": null, "missing": false, "degree": 365 }
  ]
}
```

`total` counts every match. `items` holds one page of entity summaries, with `degree`.

## GET /api/entities/{id}

Everything about one entity: the explorer's details panel.

**Response.** An entity summary with `degree`, plus:

```json
{
  "id": 2, "type": "company", "name": "Acme Corporation", "key": "acme", "subtitle": "customer", "degree": 365, …,
  "attrs": { "role": "customer" },
  "aliases": [
    { "alias": "Acme Corporation", "count": 559, "method": "normalized", "confidence": 0.912 },
    { "alias": "ACME Corp", "count": 67, "method": "normalized", "confidence": 0.946 }, …
  ],
  "relations": [
    { "rel": "HAS_PROJECT", "dir": "out", "id": 17, "type": "project", "name": "JOB-2023-0004 Palletiser Line Upgrade",
      "doc_type": null, "weight": 84, "derived": 0 }, …
  ],
  "sources": [
    { "id": 60, "path": "Admin/Scans/Reports_archive_013.zip::PO-3106_Acme Corporation.pdf", "kind": "pdf",
      "status": "ok", "text_source": "native", "surface": "Acme", "role": "bill_to",
      "method": "normalized", "confidence": 1.0 }, …
  ]
}
```

**`attrs`** holds everything else known about the entity:

- a person's `emails`, `job_titles` and `phones`;
- a document's `files`, `versions`, `total`, `currency`, `date` and line `items`;
- a project's `job_id`, `title` and `source`.

A `company_id` is replaced by `company: {id, name}`.

**`aliases`** lists every spelling seen, with:

- `count`: the number of mentions;
- `method`: the matching rule most used for it;
- `confidence`: its average confidence.

**`relations`** lists every relation, seen from this entity:

- `dir` is `"out"` when this entity is the source and `"in"` when it is the target;
- `id`, `type` and `name` describe the entity at the other end.

**`sources`** lists the mentions of this entity, the evidence, with at most 400 shown (`Config.MAX_EVIDENCE_FILES_SHOWN`). The file's own document comes first. Each entry has:

- `id`: the file id;
- `surface`: how the entity is written in that file;
- `role`: where in the file it appears (`bill_to`, `email_from`, `folder`, …);
- `method` and `confidence`: how the mention was resolved.

## GET /api/graph

The nodes and edges to draw around an entity. Without `center`, the overview: customers and their filed projects.

**Query parameters**

| Name | Default | Meaning |
|---|---|---|
| `center` | none | Entity id to centre on; without it, the overview |
| `depth` | 1 | Hops from the centre, 1 or 2 |
| `limit` | 70 | Nodes to draw, at most 600 |
| `derived` | `true` | `false` leaves out derived relations |
| `types` | all | Comma-separated entity types to include, e.g. `company,project` |

**Response**

```json
{
  "center": 2,
  "nodes": [
    { "id": 2, "type": "company", "name": "Acme Corporation", "key": "acme", "subtitle": "customer", "degree": 365, … },
    { "id": 17, "type": "project", "name": "JOB-2023-0004 Palletiser Line Upgrade", "subtitle": "Acme Corporation", "degree": 124, … }, …
  ],
  "edges": [
    { "id": 496, "src": 2, "dst": 17, "rel": "HAS_PROJECT", "weight": 84, "derived": 0 }, …
  ],
  "hidden": [
    { "rel": "ISSUED_TO", "dir": "in", "type": "document", "count": 178 },
    { "rel": "WORKS_FOR", "dir": "in", "type": "person", "count": 128 }, …
  ]
}
```

- **`nodes`** are entity summaries with `degree`. Each neighbour appears once: projects and companies come first, then the strongest links.
- **`edges`** are every relation between two drawn nodes. In the overview, only `HAS_PROJECT` edges are returned.
- **`hidden`** is what the view left out, when the centre has more neighbours than `limit`. It gives one entry per relation, direction (seen from the centre) and entity type, largest first. A neighbour linked in two ways counts in both entries. It is always `[]` in the overview.

## GET /api/relations/{id}

Why two entities are linked: what the explorer shows when an edge is clicked.

**Response.** The relation, plus:

```json
{
  "id": 137, "src": 1608, "dst": 2, "rel": "ISSUED_TO", "weight": 3, "derived": 0,
  "src_entity": { "id": 1608, "type": "document", "name": "QUO-5067", "key": "QUO-5067", "subtitle": "quote · 15 Apr 2024", … },
  "dst_entity": { "id": 2, "type": "company", "name": "Acme Corporation", … },
  "evidence": [
    { "id": 458, "path": "Customers/Acme Corporation/JOB-2024-0007 Annual Maintenance/Quotations/QUO-5067_Acme Corporation.pdf",
      "kind": "pdf", "status": "ok",
      "mentions": [
        { "entity_id": 1608, "surface": "QUO-5067", "role": "self", "method": "doc_number", "confidence": 1.0 },
        { "entity_id": 2, "surface": "ACME Corp", "role": "bill_to", "method": "normalized", "confidence": 1.0 }, …
      ] }, …
  ],
  "via": [],
  "rule": null
}
```

- **`src_entity` and `dst_entity`** are the two ends, as entity summaries.
- **`evidence`** lists the files that state the relation, at most 400. For each file, `mentions` gives how either end is written in it and in what role.
- **For a derived relation**, `evidence` is `[]`. Instead:
  - `via` lists the documents it was inferred from, as entity summaries;
  - `rule` says how it was inferred, in words.

```json
{
  "id": 11918, "src": 2, "dst": 4922, "rel": "PURCHASED_OR_QUOTED", "weight": 16, "derived": 1, …,
  "evidence": [],
  "via": [ { "id": 1749, "type": "document", "name": "DN-6020", "subtitle": "delivery note · 14 Jan 2024", … }, … ],
  "rule": "These documents issued to the company list the product."
}
```

## GET /api/connection

How two entities are connected: the shortest paths between them.

- The search goes up to 4 relations (`Config.CONNECTION_MAX_HOPS`) and returns at most 5 paths (`CONNECTION_MAX_PATHS`).
- A path never goes through the owner company, which is linked to nearly everything.

**Query parameters**

| Name | Default | Meaning |
|---|---|---|
| `from` | required | Entity id |
| `to` | required | Entity id |
| `derived` | `true` | `false` only follows relations stated in files |

**Response**

```json
{
  "from": 2, "to": 6,
  "hops": 2, "max_hops": 4,
  "paths": [
    { "steps": [
        { "id": 11918, "src": 2, "dst": 4922, "rel": "PURCHASED_OR_QUOTED", "weight": 16, "derived": 1 },
        { "id": 11954, "src": 6, "dst": 4922, "rel": "PURCHASED_OR_QUOTED", "weight": 11, "derived": 1 } ] }, …
  ],
  "nodes": [ { "id": 2, "name": "Acme Corporation", "degree": 365, … }, { "id": 6, "name": "Falcon Aerospace Components Ltd", … }, … ],
  "edges": [ { "id": 11918, … }, … ],
  "avoided": [ "Meridian Packaging Systems Ltd" ]
}
```

- **`hops`** is the length of the shortest paths, or `null` when there is none within `max_hops`.
- **`paths`** are all equally short.
- **`steps`** are relations in order from `from` to `to`. A step can point either way: follow the end that is not the previous entity.
- **`nodes` and `edges`** are everything on the paths, to draw.
- **`avoided`** is the owner, when it was skipped.

## GET /api/aliases

The spellings of the entity that best matches a text. This is meant for tools, e.g. a chat or MCP layer.

**Query parameters:** `q`, the text to match.

**Response**

```json
{
  "entity": { "id": 2, "name": "Acme Corporation", "type": "company" },
  "aliases": [ { "alias": "Acme Corporation", "count": 559, "method": "normalized", "confidence": 0.912 }, … ]
}
```

When nothing matches, the response is `{"entity": null, "aliases": []}`.

## GET /api/files/{id}

One file as read: its text and every mention found in it.

**Response**

```json
{
  "id": 56, "path": "Admin/Scans/Reports_archive_013.zip::PO-3009_Acme Corporation.pdf",
  "kind": "pdf", "status": "needs_ocr", "text_source": null, "size": 85200, "error": null,
  "text": null,
  "mentions": [
    { "surface": "PO-3009", "role": "self", "method": "doc_number", "confidence": 1.0,
      "entity_id": 1616, "entity_name": "PO-3009", "type": "document" },
    { "surface": "Acme Corporation", "role": "filename", "method": "normalized", "confidence": 0.7,
      "entity_id": 2, "entity_name": "Acme Corporation", "type": "company" }
  ]
}
```

- **`path`** is relative to the dataset. `::` separates a zip or e-mail from a file inside it.
- **`text`** is `null` when the file was not read, for example an image-only file without OCR (`needs_ocr`).
- **`text_source`** is `native`, `claude` or `tesseract`.
- **`mentions`** have `entity_id: null` when the mention was not resolved.

## GET /api/files/{id}/raw

The original bytes of the file, including files inside zips and e-mail attachments, so the browser can open it.

**Response.** The file itself, not JSON, with headers such as:

```
Content-Type: application/pdf
Content-Disposition: inline; filename*=UTF-8''PO-3009_Acme%20Corporation.pdf
```

## POST /api/chat

Answers a question about the graph. There are two kinds of request:

- **A fixed question** (`preset`) needs no API key. It is answered from the graph directly (`chat.ChatPresets`).
  The questions are listed in `/api/analysis/options` as `chat_questions`.
- **A typed question** (`messages`) needs Claude: the `api_key` in the request, else the server's
  `ANTHROPIC_API_KEY`. Claude answers by calling read-only tools over the graph, at most 8 rounds
  (`Config.CHAT_MAX_TOOL_ROUNDS`). The tools are `overview`, `find_entities`, `get_entity`, `list_related`,
  `get_aliases`, `find_connection`, `explain_relation` and `show_in_graph` (`chat.ChatTools`). Claude never writes
  and never runs its own SQL. The server keeps no chat state: the client sends the whole conversation each time.

**Request body: a fixed question**

```json
{ "preset": "customers" }
```

| `preset` | Question |
|---|---|
| `entities` | What are the entities? |
| `customers` | How many customers are there? |
| `people` | How many people are there? |
| `owner` | Who is the owner? |

**Request body: a typed question**

```json
{
  "messages": [
    { "role": "user", "content": "List all the quotes sent to Acme Corporation" }
  ],
  "api_key": null
}
```

| Field | Required | Meaning |
|---|---|---|
| `messages` | yes | The conversation, oldest first: `role` is `user` or `assistant`, `content` is text. The last message must be the user's question |
| `api_key` | when the server has no `ANTHROPIC_API_KEY` | Anthropic API key for this question only; never stored or logged |

**Response**

```json
{
  "answer": "The dataset has **12** customers:\n- [[2|Acme Corporation]]\n- [[6|Falcon Aerospace Components Ltd]]\n…",
  "engine": "preset",
  "notice": null,
  "focus": null,
  "entities": [
    { "id": 2, "type": "company", "name": "Acme Corporation" },
    { "id": 6, "type": "company", "name": "Falcon Aerospace Components Ltd" }, …
  ],
  "tools": [
    { "name": "find_entities", "input": { "customers_only": true, "limit": 50 } }
  ]
}
```

- **`answer`** is short Markdown: `**bold**` and `- ` list lines. Entities appear as `[[id|name]]`, which the explorer shows as links.
- **`engine`** is `claude` or `preset`.
- **`notice`** says something the user should know, e.g. that Claude stopped after 8 tool rounds; otherwise null.
- **`focus`** is the entity the answer is about, for the explorer to centre on, or null.
- **`entities`** gives the type and name of every entity the tools returned, so each `[[id|name]]` can be drawn with its type.
- **`tools`** lists the tool calls made, in order, with their input: how the answer was found.

**Errors**

- **400:** "Typing a question needs Claude: add an Anthropic API key, or pick one of the questions."
- **400:** "Anthropic rejected the API key. …"
- **400:** "Unknown question: …"
- **400:** "messages must end with the user's question"
- **502:** "Claude could not answer: …"

## POST /api/analysis

Starts building a new graph from a folder, in the background. It returns at once, with the status. Poll [`GET /api/analysis`](#get-apianalysis) to follow it.

- The explorer keeps serving the previous graph until the new one is complete.
- Only one analysis runs at a time.

**Request body** (`application/json`)

```json
{
  "data_root": "/Users/you/datasets/john-doe",
  "ocr": "none",
  "llm": false,
  "api_key": null,
  "owner": null,
  "profile": null
}
```

| Field | Required | Meaning |
|---|---|---|
| `data_root` | yes | Absolute path of the dataset folder (`~` is expanded). With `ERKG_BROWSE_ROOT` set (Docker), it must be inside that folder |
| `ocr` | no | How image-only files are read: `none` (default), `tesseract` or `claude` |
| `llm` | no | `true`: Claude reads the files no template recognises (billed); otherwise offline rules do |
| `api_key` | when `ocr` is `claude` or `llm` is `true`, unless the server has `ANTHROPIC_API_KEY` | Anthropic API key. It is checked with one free call before the run starts, used for this run only, and never stored or logged |
| `owner` | no | The owner organisation; empty or null means detect it from the files |
| `profile` | no | `null` or `""`: automatic. `"none"`: no profile. Or the file name of a shipped profile, e.g. `"john-doe.json"` (see `/api/analysis/options`). A `profile.json` inside the dataset always wins |

**Response.** `202 Accepted`, with the same body as `GET /api/analysis`:

```json
{ "state": "running", "step": 0, "stage": null, "detail": null, "error": null,
  "data_root": "/Users/you/datasets/john-doe", "ocr": "none",
  "started_at": "2026-09-27T19:23:16Z", "finished_at": null, "result": null, "log": [] }
```

**Errors (400):**

- "Folder not found: …"
- "… is empty"
- "Only folders inside … are shared with the app"
- "Unknown OCR option: …"
- "Unknown profile: …"
- "Enter an Anthropic API key to use Claude."
- "Anthropic rejected this API key. …"
- "Tesseract isn't installed. …"

**409:** "An analysis is already running."

## GET /api/analysis

Progress and result of the current analysis, or of the last one.

**Response**

```json
{
  "state": "done",
  "step": 5,
  "stage": "relate",
  "detail": "Analysis complete",
  "error": null,
  "data_root": "/…/datasets/generic",
  "ocr": "none",
  "started_at": "2026-09-27T19:23:16Z",
  "finished_at": "2026-09-27T19:23:17Z",
  "log": [
    "ingest: 12 files and archive members found",
    "read: 12 read natively, 0 via OCR, 0 image-only left unread",
    "read: Owner organisation: Harbor Robotics Inc (harborrobotics.com), domain on 100% of e-mails (7)",
    "extract: Done: {files=12, mentions=75, facts=69, free_text_files=8} in 0.0s", …
    "relate: Analysis complete"
  ],
  "result": {
    "entities": { "company": 4, "document": 12, "person": 5, "product": 3, "project": 1 },
    "read": { "native": 12, "ocr": 0, "ocr_pending": 0, "empty": 0, "corrupt": 0, "skipped_photos": 0 },
    "owner": "Harbor Robotics Inc",
    "owner_domain": "harborrobotics.com",
    "owner_reason": "domain on 100% of e-mails (7)",
    "profile": "defaults"
  }
}
```

| Field | Meaning |
|---|---|
| `state` | `idle` (none since the server started), `running`, `done` or `error` |
| `step`, `stage`, `detail` | The current stage, 1–5 (`ingest`, `read`, `extract`, `resolve`, `relate`), and its latest message |
| `error` | Why it failed, when `state` is `error` |
| `log` | The last 12 progress lines |
| `result` | When `state` is `done`: entity counts, how files were read, the owner used and why (`owner` is null when there is none), and the profile used |

## GET /api/analysis/options

What the "Analyse dataset" dialog can offer on this server.

**Response**

```json
{
  "default_root": "",
  "browse_start": "/…/project",
  "browse_root": null,
  "home": "/Users/you",
  "tesseract": false,
  "env_key": false,
  "claude_model": "claude-opus-5",
  "profiles": [ "john-doe.json" ],
  "default_profile": null,
  "chat_questions": [ { "id": "entities", "text": "What are the entities?" }, … ]
}
```

| Field | Meaning |
|---|---|
| `default_root` | The dataset folder to pre-fill (`ERKG_DATA_ROOT`), or `""` when it does not exist |
| `browse_start`, `browse_root` | Where the folder picker opens, and the folder it cannot leave (`ERKG_BROWSE_ROOT`; null means anywhere) |
| `tesseract` | Whether Tesseract is installed, i.e. whether `ocr: "tesseract"` can be used |
| `env_key` | Whether the server has `ANTHROPIC_API_KEY`, i.e. whether `api_key` can be omitted |
| `claude_model` | The model Claude calls use (`ERKG_CLAUDE_MODEL`) |
| `profiles` | The shipped profiles, for the `profile` field of `POST /api/analysis` |
| `chat_questions` | The chat's fixed questions, `[{"id": "entities", "text": "What are the entities?"}, …]`, for `POST /api/chat` |
| `default_profile` | The server's default profile (`ERKG_PROFILE`), or null |

## GET /api/fs

The sub-folders of a folder, for the dataset picker.

**Query parameters:** `path` (default: `ERKG_DATA_ROOT`).

- A path that does not exist opens its nearest existing parent.
- The picker never goes above `ERKG_BROWSE_ROOT`.

**Response**

```json
{
  "path": "/Users/you/datasets",
  "parent": "/Users/you",
  "dirs": [ { "name": "john-doe", "path": "/Users/you/datasets/john-doe" }, … ],
  "files": 3,
  "missing": null,
  "root": null
}
```

- `files` counts the files directly in the folder.
- `missing` is the requested path when it did not exist.
- `parent` is null at the top.
- Hidden entries (names starting with `.`) are left out.

## Static files

Any path outside `/api/` serves the UI from `src/main/resources/web`: `/` (`index.html`), `/app.js` and `/style.css`.
