/* Entity Graph Explorer — vanilla JS + Cytoscape. */
const TYPES = [
  { key: "company", label: "Companies" },
  { key: "project", label: "Projects" },
  { key: "person", label: "People" },
  { key: "document", label: "Documents" },
  { key: "product", label: "Products" },
];
const REL = {
  HAS_PROJECT: ["Projects", "Customer"],
  HAS_DOCUMENT: ["Documents", "Project"],
  ISSUED_TO: ["Issued to", "Documents issued to"],
  ATTENTION_OF: ["For the attention of", "Named as Attn on"],
  WORKS_FOR: ["Works for", "People"],
  AUTHORED: ["Authored", "Authored by"],
  SENT: ["Sent", "Sent by"],
  RECEIVED: ["Received", "Recipients"],
  REFERENCES: ["References", "Referenced by"],
  LISTS_PRODUCT: ["Lists products", "Listed on"],
  DESCRIBES: ["Describes", "Described in"],
  ADDRESSED_TO: ["Addressed to", "Letters & visits"],
  PARTY_TO: ["Party to", "Parties"],
  ATTACHED_TO: ["Attached to", "Attachments"],
  HOLDS: ["Holds", "Held by"],
  ISSUED_BY: ["Issued by", "Issued"],
  ATTENDED: ["Attended", "Attendees"],
  MENTIONS: ["Mentions", "Mentioned in"],
  INVOLVED_IN: ["Involved in", "People involved"],
  USES_PRODUCT: ["Uses products", "Used in projects"],
  PURCHASED_OR_QUOTED: ["Bought / quoted", "Customers"],
  FILED_UNDER: ["Filed under", "Filed documents"],
};
const METHOD_HELP = {
  normalized: "same after removing case, punctuation and legal suffixes",
  spacing: "same letters, different spacing",
  abbreviation: "a word is abbreviated",
  expansion: "a word is spelled out in full",
  typo: "one-letter typo / transposition",
  truncation: "trailing words dropped",
  acronym: "initials of the full name",
  email_domain: "matched through an e-mail domain",
  "name+organisation": "same name at the same organisation",
  email: "same e-mail address",
  job_id: "same JOB code",
  "title+company": "same job title for the same customer",
  doc_number: "same document number",
  product_code: "same product code",
  gazetteer: "found by searching free text for known aliases",
};

const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const api = (p) => fetch(p).then((r) => { if (!r.ok) throw new Error(r.statusText); return r.json(); });
const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();

const state = { tab: "company", depth: 1, derived: true, hidden: new Set(), selected: null, center: null, stats: null };

/* ------------------------------------------------------------ graph */
let cy;
function colors() {
  return Object.fromEntries(TYPES.map((t) => [t.key, css(`--c-${t.key}`)]));
}
function initGraph() {
  const c = colors();
  cy = cytoscape({
    container: $("#cy"),
    minZoom: 0.08,
    maxZoom: 3,
    style: [
      { selector: "node", style: {
          "background-color": (n) => c[n.data("type")],
          label: "data(label)", "font-size": 10, color: css("--text"),
          "text-valign": "bottom", "text-margin-y": 3, "text-wrap": "ellipsis", "text-max-width": 120,
          "text-outline-color": css("--bg"), "text-outline-width": 2, "min-zoomed-font-size": 8,
          width: "data(size)", height: "data(size)", "border-width": 0,
      } },
      { selector: "node[type='document']", style: { shape: "round-rectangle", "font-size": 8.5 } },
      { selector: "node[type='product']", style: { shape: "diamond" } },
      { selector: "node[type='project']", style: { shape: "round-hexagon" } },
      { selector: "node[?missing]", style: { "background-opacity": 0.25, "border-width": 1.5, "border-style": "dashed", "border-color": c.document } },
      { selector: "node.center", style: { "border-width": 3, "border-color": css("--text"), "font-size": 12, "font-weight": 600 } },
      { selector: "node:selected", style: { "border-width": 3, "border-color": css("--accent") } },
      { selector: "edge", style: {
          width: "data(width)", "line-color": css("--border"), "curve-style": "bezier",
          "target-arrow-shape": "triangle", "target-arrow-color": css("--border"), "arrow-scale": 0.7, opacity: 0.9,
      } },
      { selector: "edge[?derived]", style: { "line-style": "dashed" } },
      { selector: "edge.hl", style: { "line-color": css("--accent"), "target-arrow-color": css("--accent"), label: "data(rel)", "font-size": 9, color: css("--muted"), "text-rotation": "autorotate", "text-outline-color": css("--bg"), "text-outline-width": 2, "z-index": 9 } },
      { selector: ".faded", style: { opacity: 0.15 } },
    ],
  });
  cy.on("tap", "node", (e) => select(+e.target.id(), { recenter: false }));
  cy.on("dbltap", "node", (e) => expand(+e.target.id()));
  cy.on("mouseover", "node", (e) => highlight(e.target));
  cy.on("mouseout", "node", () => cy.elements().removeClass("faded hl"));
}
function highlight(node) {
  const hood = node.closedNeighborhood();
  cy.elements().not(hood).addClass("faded");
  node.connectedEdges().addClass("hl");
}
function nodeData(n) {
  const label = n.type === "document" ? (n.key.startsWith("file:") || n.key.includes(":") ? n.name : n.key.split("@")[0]) : n.name;
  return { id: String(n.id), label, type: n.type, missing: n.missing || undefined,
           size: Math.round(12 + Math.min(22, Math.log2((n.degree || 1) + 1) * 2.6)) };
}
function toElements(g) {
  const nodes = g.nodes.filter((n) => !state.hidden.has(n.type) || n.id === g.center);
  const ids = new Set(nodes.map((n) => String(n.id)));
  const edges = g.edges.filter((e) => ids.has(String(e.src)) && ids.has(String(e.dst)));
  return [
    ...nodes.map((n) => ({ group: "nodes", data: nodeData(n) })),
    ...edges.map((e) => ({ group: "edges", data: { id: `e${e.id}`, source: String(e.src), target: String(e.dst), rel: e.rel.replaceAll("_", " ").toLowerCase(), derived: !!e.derived || undefined, width: Math.min(6, 0.8 + Math.log2(e.weight + 1) * 0.8) } })),
  ];
}
const RING = { company: 7, project: 8, product: 6, person: 4, document: 2 };
function ringLayout(centerId) {
  // ego view: centre in the middle, one ring per entity type
  cy.layout({
    name: "concentric", fit: true, padding: 50, animate: false, minNodeSpacing: 40, startAngle: -Math.PI / 2,
    concentric: (n) => (n.id() === String(centerId) ? 10 : RING[n.data("type")] || 1),
    levelWidth: () => 1,
  }).run();
}
function runLayout(opts = {}) {
  const name = typeof cytoscapeFcose !== "undefined" || cytoscape.prototype.hasOwnProperty("fcose") ? "fcose" : "cose";
  const layout = cy.layout({ name, animate: opts.animate ?? false, animationDuration: 450, nodeRepulsion: 12000, idealEdgeLength: 70, nodeSeparation: 60, randomize: !opts.keep, fit: true, padding: 60, quality: "default" });
  try { layout.run(); } catch { cy.layout({ name: "cose", animate: false, fit: true, padding: 60 }).run(); }
}
async function loadGraph(center) {
  state.center = center ?? null;
  const p = new URLSearchParams({ depth: state.depth, derived: state.derived, limit: state.depth === 2 ? 160 : 70 });
  if (center != null) p.set("center", center);
  const g = await api(`/api/graph?${p}`);
  cy.elements().remove();
  cy.add(toElements(g));
  if (center != null) {
    cy.getElementById(String(center)).addClass("center");
    cy.nodes().length > 14 ? ringLayout(center) : runLayout();
  } else runLayout();
}
async function expand(id) {
  const g = await api(`/api/graph?center=${id}&depth=1&limit=60&derived=${state.derived}`);
  const pos = cy.getElementById(String(id)).position();
  const fresh = toElements(g).filter((el) => cy.getElementById(el.data.id).length === 0);
  fresh.forEach((el) => { if (el.group === "nodes") el.position = { x: pos.x + (Math.random() - 0.5) * 80, y: pos.y + (Math.random() - 0.5) * 80 }; });
  cy.add(fresh);
  runLayout({ keep: true, animate: true });
  select(id, { recenter: false });
}

/* ------------------------------------------------------------ sidebar */
function renderTabs() {
  const counts = state.stats?.entities || {};
  $("#tabs").innerHTML = TYPES.map((t) =>
    `<button class="tab ${state.tab === t.key ? "active" : ""}" data-tab="${t.key}"><span class="dot ${t.key}" style="width:8px;height:8px;border-radius:50%;display:inline-block"></span>${t.label}<span class="n">${counts[t.key] ?? 0}</span></button>`
  ).join("");
}
let listTimer;
async function renderList() {
  const q = $("#list-filter").value.trim();
  const p = new URLSearchParams({ type: state.tab, limit: 300 });
  if (q) p.set("q", q);
  const res = await api(`/api/entities?${p}`);
  $("#list").innerHTML = res.items.map((e) => itemHtml(e)).join("") || `<div class="list-footer">No matches</div>`;
  const plural = TYPES.find((t) => t.key === state.tab).label.toLowerCase();
  $("#list-footer").textContent = `${res.items.length} of ${res.total} ${plural} · sorted by connections`;
}
function itemHtml(e) {
  return `<div class="item ${state.selected === e.id ? "active" : ""}" data-id="${e.id}">
    <span class="dot ${e.type}"></span>
    <div class="txt"><div class="nm">${esc(e.type === "document" && !e.key.includes(":") ? e.key.split("@")[0] : e.name)}</div>
    <div class="sb">${esc(e.type === "document" && !e.key.includes(":") ? [e.name !== e.key ? e.name : null, e.subtitle].filter(Boolean).join(" · ") : e.subtitle || "")}${e.missing ? " · referenced only" : ""}</div></div>
    ${e.degree != null ? `<span class="deg">${e.degree}</span>` : ""}</div>`;
}
/* ------------------------------------------------------------ details */
async function select(id, { recenter = true } = {}) {
  state.selected = id;
  document.querySelectorAll(".item.active").forEach((el) => el.classList.remove("active"));
  document.querySelector(`.item[data-id="${id}"]`)?.classList.add("active");
  if (recenter) await loadGraph(id);
  else { cy.$(":selected").unselect(); cy.getElementById(String(id)).select(); }
  const e = await api(`/api/entities/${id}`);
  renderDetails(e);
  $("#details").classList.add("open");
}
function renderDetails(e) {
  const typeLabel = e.type === "document" ? (e.attrs.doc_type || "document").replaceAll("_", " ") : e.type;
  const title = e.type === "document" && !e.key.includes(":") ? e.key.split("@")[0] : e.name;
  const sub = e.type === "document" && e.name !== title ? e.name : e.subtitle;
  let h = `<div class="d-type"><span class="dot ${e.type}"></span>${esc(typeLabel)}</div>
    <div class="d-name">${esc(title)}</div>
    <div class="d-sub">${esc(sub || "")}</div>
    <div class="d-actions">
      <button class="btn" data-center="${e.id}">Center graph here</button>
      <span class="stat" title="relationships"><b>${e.degree}</b> links</span>
      <span class="stat" title="references across files"><b>${e.sources.length}</b> mentions</span>
    </div>`;

  // aliases
  const aliases = e.aliases.filter((a) => e.type !== "document" || a.alias !== title);
  if (aliases.length && e.type !== "document") {
    h += `<div class="section"><h4>Also known as <span class="n">${aliases.length} forms</span></h4><div class="aliases">` +
      aliases.slice(0, 40).map((a) => `<div class="alias">
        <span class="a-name">${esc(a.alias)}</span><span class="a-count">×${a.count}</span>
        <span class="a-meta"><span class="method" title="${esc(METHOD_HELP[a.method?.split(":").pop().split("+")[0]] || "")}">${esc((a.method || "").replaceAll("_", " "))}</span>
        <span class="conf" title="confidence ${a.confidence}"><i style="width:${Math.round(a.confidence * 100)}%"></i></span>${Math.round(a.confidence * 100)}%</span></div>`).join("") + `</div></div>`;
  }

  h += attrsHtml(e);

  // relationships
  const groups = {};
  for (const r of e.relations) {
    const k = `${r.rel}|${r.dir}`;
    (groups[k] ||= []).push(r);
  }
  const gkeys = Object.keys(groups).sort((a, b) => groups[b].length - groups[a].length);
  if (gkeys.length) {
    h += `<div class="section"><h4>Relationships <span class="n">${e.relations.length}</span></h4>`;
    for (const k of gkeys) {
      const [rel, dir] = k.split("|");
      const list = groups[k];
      const label = (REL[rel] || [rel, rel])[dir === "out" ? 0 : 1];
      const shown = list.slice(0, 24);
      h += `<div class="rel-group"><div class="rel-label">${esc(label)} <span class="deg">${list.length}</span>${list[0].derived ? `<span class="derived" title="inferred from multi-hop paths">derived</span>` : ""}</div>
        <div class="rel-targets">${shown.map((r) => `<span class="chip" data-id="${r.id}" title="${esc(r.name)}"><span class="dot ${r.type}"></span><span class="nm">${esc(r.name)}</span>${r.weight > 1 ? `<span class="w">×${r.weight}</span>` : ""}</span>`).join("")}
        ${list.length > shown.length ? `<button class="more" data-more="${k}">+${list.length - shown.length} more</button>` : ""}</div></div>`;
    }
    h += `</div>`;
  }

  // evidence
  const srcs = e.sources;
  h += `<div class="section"><h4>Evidence <span class="n">${srcs.length} mentions</span></h4>` +
    srcs.slice(0, 60).map((s) => `<div class="src" data-file="${s.id}"><span class="kind">${esc(s.kind)}</span>
      <div><div class="p">${esc(s.path.replaceAll("::", " ▸ "))}</div>
      <div class="m">as “${esc(s.surface)}” · ${esc(s.role.replaceAll("_", " "))}${s.method ? ` · ${esc(s.method)}` : ""}${s.status !== "ok" ? ` · <b>${esc(s.status.replace("_", " "))}</b>` : ""}</div></div></div>`).join("") +
    (srcs.length > 60 ? `<div class="list-footer">…and ${srcs.length - 60} more</div>` : "") + `</div>`;

  $("#details").innerHTML = h;
  $("#details").scrollTop = 0;
  $("#details").onclick = (ev) => {
    const more = ev.target.closest("[data-more]");
    if (more) {
      const list = groups[more.dataset.more];
      more.parentElement.innerHTML = list.map((r) => `<span class="chip" data-id="${r.id}"><span class="dot ${r.type}"></span><span class="nm">${esc(r.name)}</span>${r.weight > 1 ? `<span class="w">×${r.weight}</span>` : ""}</span>`).join("");
      return;
    }
    const c = ev.target.closest("[data-center]");
    if (c) return loadGraph(+c.dataset.center);
    const chip = ev.target.closest("[data-id]");
    if (chip) return select(+chip.dataset.id);
    const f = ev.target.closest("[data-file]");
    if (f) return openFile(+f.dataset.file, e);
  };
}
function money(v) { return v == null ? "" : "£" + Number(v).toLocaleString("en-GB", { minimumFractionDigits: 2, maximumFractionDigits: 2 }); }
function attrsHtml(e) {
  const a = { ...e.attrs };
  const rows = [];
  const put = (k, v) => { if (v != null && v !== "" && !(Array.isArray(v) && !v.length)) rows.push([k, v]); };
  if (e.type === "company") { put("Role", a.role); put("Domain", a.domain); }
  if (e.type === "person") {
    put("Organisation", a.company?.name);
    put("E-mail", (a.emails || []).join(", "));
    put("Job title", (a.job_titles || []).join(", "));
    put("Phone", (a.phones || []).join(", "));
  }
  if (e.type === "project") {
    put("Job code", a.job_id); put("Title", a.title); put("Customer", a.company?.name);
    put("Source", a.source === "folder" ? "project folder" : a.source?.replaceAll("_", " "));
    put("Status", a.status); put("Value", a.value);
  }
  if (e.type === "product") put("Code", a.code);
  if (e.type === "document") {
    put("Date", a.date); put("Job", a.job_title); put("Subject", a.subject); put("Revision", a.revision);
    put("Instrument", a.instrument); put("Result", a.result); put("Valid until", a.valid_until || a.expiry);
    if (a.subtotal != null) put("Subtotal", money(a.subtotal));
    if (a.total != null) put("Total", money(a.total));
    put("Copies", a.files ? `${a.files.length} file${a.files.length > 1 ? "s" : ""}` : null);
    put("Versions", (a.versions || []).join(", "));
    if (a.missing) put("Status", "referenced by other documents; no copy found");
    if (a.unread) put("Status", "image-only, not OCR'd yet");
  }
  let h = rows.length ? `<div class="section"><h4>Details</h4><dl class="kv">${rows.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join("")}</dl></div>` : "";
  if (a.line_items?.length) {
    h += `<div class="section"><h4>Line items <span class="n">${a.line_items.length}</span></h4><table class="items"><tr><th>Description</th><th class="num">Qty</th><th class="num">Unit price</th><th class="num">Total</th></tr>` +
      a.line_items.map((i) => `<tr><td>${esc(i.desc)}</td><td class="num">${i.qty}</td><td class="num">${money(i.price)}</td><td class="num">${money(i.total)}</td></tr>`).join("") + `</table></div>`;
  }
  return h;
}

/* ------------------------------------------------------------ file dialog */
async function openFile(fid, ent) {
  const f = await api(`/api/files/${fid}`);
  $("#file-title").textContent = f.path.split("::").pop().split("/").pop();
  $("#file-sub").textContent = `${f.path.replaceAll("::", " ▸ ")} · ${f.kind} · ${f.status}${f.text_source ? ` · text via ${f.text_source}` : ""}`;
  $("#file-open").href = `/api/files/${fid}/raw`;
  let text = esc(f.text || (f.status === "needs_ocr" ? "(image-only file — enable an OCR backend to read it; use “Open original” to view)" : "(no text)"));
  const surfaces = [...new Set(f.mentions.map((m) => m.surface).filter((s) => s.length > 2))].sort((a, b) => b.length - a.length);
  if (surfaces.length) {
    const re = new RegExp(surfaces.map((s) => esc(s).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|"), "g");
    text = text.replace(re, (m) => `<mark>${m}</mark>`);
  }
  $("#file-text").innerHTML = text;
  $("#file-mentions").innerHTML = f.mentions.map((m) => `<div class="mention" ${m.entity_id ? `data-id="${m.entity_id}"` : ""}>
      <span class="dot ${m.type}" style="width:8px;height:8px;border-radius:50%;flex:none"></span>
      <span>${esc(m.surface)}${m.entity_name && m.entity_name !== m.surface ? ` <span style="color:var(--muted)">→ ${esc(m.entity_name)}</span>` : ""}</span>
      <span class="r">${esc(m.role.replaceAll("_", " "))}</span></div>`).join("");
  $("#file-mentions").onclick = (ev) => {
    const m = ev.target.closest("[data-id]");
    if (m) { $("#file-dialog").close(); select(+m.dataset.id); }
  };
  $("#file-dialog").showModal();
}

/* ------------------------------------------------------------ search */
let searchTimer;
async function doSearch(q) {
  const box = $("#search-results");
  if (!q) { box.hidden = true; return; }
  const res = await api(`/api/entities?q=${encodeURIComponent(q)}&limit=25`);
  box.innerHTML = res.items.map(itemHtml).join("") || `<div class="list-footer">No entity matches “${esc(q)}”</div>`;
  box.hidden = false;
}

/* ------------------------------------------------------------ boot */
/* ------------------------------------------------------------ analyse dialog */
const STAGE_LABELS = ["Collect files", "Read text", "Extract", "Resolve", "Relate"];
let pollTimer, analyzeOpts;
const dlg = () => $("#analyze-dialog");

function showAnalyzeView(view) {
  $("#an-setup").hidden = view !== "setup";
  $("#an-progress").hidden = view !== "progress";
}
function showError(msg) {
  $("#an-error").textContent = msg || "";
  $("#an-error").hidden = !msg;
}
async function openAnalyze() {
  analyzeOpts = await api("/api/analysis/options");
  const tess = $("#ocr-tesseract");
  tess.disabled = !analyzeOpts.tesseract;
  $("#tess-missing").hidden = analyzeOpts.tesseract;
  if (tess.disabled && tess.checked) $("#ocr-none").checked = true;
  $("#an-key-hint").textContent = analyzeOpts.env_key
    ? "Leave empty to use the key already set on the server. Used for this analysis only; it isn't saved to disk, the database or logs."
    : "Used for this analysis only. It isn't saved to disk, the database or logs.";
  if (!$("#an-path").value) $("#an-path").value = state.stats?.meta?.data_root || analyzeOpts.default_root;
  syncOcrChoice();
  showError("");
  const job = await api("/api/analysis");
  if (job.state === "running") { showAnalyzeView("progress"); renderJob(job); poll(); }
  else { showAnalyzeView("setup"); setFooter("setup"); }
  if (!dlg().open) dlg().showModal();
}
function syncOcrChoice() {
  const ocr = document.querySelector('input[name="ocr"]:checked').value;
  $("#an-key-wrap").hidden = ocr !== "claude";
  $("#an-foot").textContent = ocr === "claude"
    ? `Uses ${analyzeOpts?.claude_model || "Claude"}. Only image-only files are sent; results are cached by content.`
    : ocr === "tesseract" ? "Runs locally. Slower on large scans." : "Takes about half a minute for ~4,000 files.";
}
function setFooter(mode) {
  const btn = $("#an-start");
  btn.hidden = false;
  btn.disabled = false;
  if (mode === "setup") { btn.textContent = "Analyse"; syncOcrChoice(); }
  if (mode === "running") { btn.textContent = "Analysing…"; btn.disabled = true; $("#an-foot").textContent = "You can close this window; the analysis keeps running."; }
  if (mode === "done") { btn.textContent = "Explore the graph"; $("#an-foot").textContent = ""; }
  if (mode === "error") { btn.textContent = "Back to settings"; $("#an-foot").textContent = ""; }
  btn.dataset.mode = mode;
}

async function loadDir(path) {
  try {
    const d = await api(`/api/fs${path ? `?path=${encodeURIComponent(path)}` : ""}`);
    state.browse = d;
    $("#br-path").textContent = d.path;
    $("#br-path").title = d.path;
    $("#br-up").disabled = !d.parent;
    $("#br-list").innerHTML = d.dirs.length
      ? d.dirs.map((x) => `<button type="button" class="browser-item" role="option" data-path="${esc(x.path)}">${esc(x.name)}</button>`).join("")
      : `<div class="browser-empty">No subfolders</div>`;
    $("#br-count").textContent = d.missing
      ? `${d.missing} isn't available; showing the closest folder that is`
      : !d.parent && d.root
        ? `Top of the folder shared with the app (${d.root})`
        : `${d.files.toLocaleString()} file${d.files === 1 ? "" : "s"} directly in this folder`;
  } catch (e) {
    $("#br-list").innerHTML = `<div class="browser-empty">Can't open this folder.</div>`;
  }
}

function renderJob(job) {
  const failed = job.state === "error";
  $("#an-steps").innerHTML = STAGE_LABELS.map((label, i) => {
    const n = i + 1;
    const cls = job.state === "done" || n < job.step ? "done" : n === job.step ? (failed ? "failed" : "active") : "";
    return `<li class="${cls}">${label}</li>`;
  }).join("");
  if (job.state === "done") {
    const e = job.result?.entities || {};
    const total = Object.values(e).reduce((a, b) => a + b, 0);
    $("#an-detail").textContent = `Analysis complete: ${total.toLocaleString()} entities (${TYPES.map((t) => `${e[t.key] || 0} ${t.label.toLowerCase()}`).join(", ")}).`;
  } else if (failed) {
    $("#an-detail").textContent = `The analysis stopped: ${job.error}`;
  } else {
    $("#an-detail").textContent = job.detail || "Starting…";
  }
  $("#an-log").textContent = (job.log || []).join("\n");
  $("#an-log").scrollTop = $("#an-log").scrollHeight;
  setFooter(job.state === "running" ? "running" : job.state);
}
function poll() {
  clearTimeout(pollTimer);
  pollTimer = setTimeout(async () => {
    const job = await api("/api/analysis").catch(() => null);
    if (!job) return poll();
    renderJob(job);
    if (job.state === "running") return poll();
    if (job.state === "done") refreshAll();
  }, 700);
}

function initAnalyze() {
  $("#btn-analyze").onclick = openAnalyze;
  dlg().querySelector("[data-close]").onclick = () => dlg().close();
  document.querySelectorAll('input[name="ocr"]').forEach((r) => (r.onchange = syncOcrChoice));
  $("#an-browse").onclick = () => {
    const box = $("#an-browser");
    box.hidden = !box.hidden;
    $("#an-browse").setAttribute("aria-expanded", String(!box.hidden));
    if (!box.hidden) loadDir($("#an-path").value.trim() || analyzeOpts.browse_start);
  };
  $("#br-up").onclick = () => state.browse?.parent && loadDir(state.browse.parent);
  $("#br-list").onclick = (e) => {
    const it = e.target.closest("[data-path]");
    if (it) loadDir(it.dataset.path);
  };
  $("#br-use").onclick = () => {
    if (!state.browse) return;
    $("#an-path").value = state.browse.path;
    $("#an-browser").hidden = true;
    $("#an-browse").setAttribute("aria-expanded", "false");
  };
  $("#analyze-form").onsubmit = async (e) => {
    e.preventDefault();
    const mode = $("#an-start").dataset.mode;
    if (mode === "done") { dlg().close(); return; }
    if (mode === "error") { showAnalyzeView("setup"); setFooter("setup"); return; }
    const ocr = document.querySelector('input[name="ocr"]:checked').value;
    const data_root = $("#an-path").value.trim();
    if (!data_root) return showError("Choose the folder that holds the dataset.");
    if (ocr === "claude" && !$("#an-key").value.trim() && !analyzeOpts.env_key) {
      $("#an-key").focus();
      return showError("Enter your Anthropic API key to use Claude, or pick another option.");
    }
    showError("");
    const btn = $("#an-start");
    btn.disabled = true;
    btn.textContent = ocr === "claude" ? "Checking key…" : "Starting…";
    try {
      const r = await fetch("/api/analysis", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ data_root, ocr, api_key: ocr === "claude" ? $("#an-key").value.trim() || null : null }),
      });
      const body = await r.json();
      if (!r.ok) throw new Error(typeof body.detail === "string" ? body.detail : "Couldn't start the analysis.");
      $("#an-key").value = "";  // don't keep the key in the page once it's been handed over
      showAnalyzeView("progress");
      renderJob(body);
      poll();
    } catch (err) {
      showError(err.message);
      setFooter("setup");
    }
  };
}

const OCR_LABEL = { none: "no OCR", tesseract: "Tesseract OCR", claude: "Claude OCR" };
async function refreshAll() {
  state.stats = await api("/api/stats");
  const s = state.stats;
  const meta = s.meta || {};
  $("#stats").innerHTML = [
    ["files", Object.values(s.files).reduce((a, b) => a + b, 0)],
    ["mentions resolved", s.mentions],
    ["entities", Object.values(s.entities).reduce((a, b) => a + b, 0)],
    ["relationships", s.relations],
  ].map(([k, v]) => `<span class="stat"><b>${v.toLocaleString()}</b> ${k}</span>`).join("");
  const root = meta.data_root || "";
  const label = $("#dataset-label");
  label.textContent = root ? `${root.split("/").filter(Boolean).pop()} · ${OCR_LABEL[meta.ocr_backend] || "no OCR"}` : "no dataset analysed yet";
  label.title = root;
  state.selected = null;
  $("#details").innerHTML = `<div class="empty"><h3>Nothing selected</h3><p>Pick an entity from the list, the search box, or the graph to see who it is, what it's called across files, how it connects, and the evidence behind every link.</p></div>`;
  renderTabs();
  renderList();
  loadGraph(null);
  return s;
}

async function boot() {
  initGraph();
  $("#legend").innerHTML = TYPES.map((t) => `<button data-type="${t.key}" title="show/hide ${t.label.toLowerCase()}"><span class="dot ${t.key}" style="width:8px;height:8px;border-radius:50%;display:inline-block"></span>${t.label}</button>`).join("");
  const s = await refreshAll();
  const m = location.hash.match(/entity=(\d+)/);
  if (m) select(+m[1]);
  initAnalyze();
  const running = (await api("/api/analysis")).state === "running";
  if (running || !Object.keys(s.entities).length || location.hash === "#analyse") openAnalyze();

  $("#tabs").onclick = (e) => {
    const t = e.target.closest("[data-tab]");
    if (!t) return;
    state.tab = t.dataset.tab;
    $("#list-filter").value = "";
    renderTabs(); renderList();
  };
  $("#list").onclick = (e) => {
    const it = e.target.closest("[data-id]");
    if (it) return select(+it.dataset.id);
  };
  $("#list-filter").oninput = () => { clearTimeout(listTimer); listTimer = setTimeout(renderList, 200); };
  $("#search").oninput = (e) => { clearTimeout(searchTimer); searchTimer = setTimeout(() => doSearch(e.target.value.trim()), 180); };
  $("#search-results").onclick = (e) => {
    const it = e.target.closest("[data-id]");
    if (it) { $("#search-results").hidden = true; $("#search").value = ""; select(+it.dataset.id); }
  };
  document.addEventListener("click", (e) => { if (!e.target.closest(".search")) $("#search-results").hidden = true; });
  document.addEventListener("keydown", (e) => {
    if (e.key === "/" && document.activeElement.tagName !== "INPUT") { e.preventDefault(); $("#search").focus(); }
    if (e.key === "Escape") $("#search-results").hidden = true;
  });
  $("#btn-overview").onclick = () => loadGraph(null);
  $("#btn-fit").onclick = () => cy.fit(undefined, 50);
  document.querySelectorAll(".seg-btn").forEach((b) => b.onclick = () => {
    document.querySelectorAll(".seg-btn").forEach((x) => x.classList.toggle("active", x === b));
    state.depth = +b.dataset.depth;
    loadGraph(state.center);
  });
  $("#chk-derived").onchange = (e) => { state.derived = e.target.checked; loadGraph(state.center); };
  $("#legend").onclick = (e) => {
    const b = e.target.closest("[data-type]");
    if (!b) return;
    const t = b.dataset.type;
    state.hidden.has(t) ? state.hidden.delete(t) : state.hidden.add(t);
    b.classList.toggle("off", state.hidden.has(t));
    loadGraph(state.center);
  };
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => { const els = cy.json().elements; cy.destroy(); initGraph(); cy.json({ elements: els }); runLayout({ animate: false }); });
}
boot();
