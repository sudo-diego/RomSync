"use strict";

// La Cocina de RomSync: la tablet sirve esta página (estilo Nonna's Kitchen de Cannoli).
const S = {
  state: null,      // /api/state
  games: [],        // /api/games: tablet + PC, con "presence"
  pcSystems: [],
  bios: [], biosMissing: [],
  system: "", filter: "tablet", sort: "name", q: "",
  downloads: [],
};
const $ = (s, el = document) => el.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function fmtSize(b) {
  if (b == null) return "";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
  return (i >= 2 ? b.toFixed(b >= 100 ? 0 : 1) : Math.round(b)).toString().replace(".", ",") + " " + u[i];
}
function fmtDate(s) {
  const d = new Date(s);
  return isNaN(d) ? s : d.toLocaleString("es", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}
function ago(ms) {
  if (!ms) return "nunca";
  const m = Math.round((Date.now() - ms) / 60000);
  if (m < 1) return "recién";
  if (m < 60) return `hace ${m} min`;
  const h = Math.round(m / 60);
  return h < 48 ? `hace ${h} h` : `hace ${Math.round(h / 24)} días`;
}
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.t);
  toast.t = setTimeout(() => (t.hidden = true), 3000);
}

async function api(path, opts = {}) {
  const r = await fetch(path, { credentials: "same-origin", ...opts });
  if (r.status === 401) { showLogin(); throw new Error("Falta el PIN"); }
  if (r.status === 204) return null;
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || `Error ${r.status}`);
  return data;
}
const post = (path, body) => api(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body || {}) });

// ---- PIN ----
function showLogin() { $("#login").hidden = false; $("#loginPin").focus(); }
$("#loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    await post("/api/login", { pin: $("#loginPin").value.trim().toUpperCase() });
    $("#login").hidden = true;
    boot();
  } catch (err) { $("#loginErr").textContent = err.message; }
});

// ---- Pestañas ----
$("#tabs").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-tab]");
  if (b) setTab(b.dataset.tab);
});
function setTab(tab) {
  for (const b of $("#tabs").children) b.classList.toggle("on", b.dataset.tab === tab);
  for (const s of document.querySelectorAll(".tab")) s.classList.toggle("on", s.id === "tab-" + tab);
  location.hash = tab === "platforms" ? "" : tab;
  if (tab === "bios") renderBios();
  if (tab === "saves") loadSaves();
  if (tab === "tools") renderTools("");
}

// ---- Plataformas ----
function sysInfo(id) {
  const s = S.state?.systems?.[id];
  return { id, name: s?.name || id, short: s?.short || id.toUpperCase(), color: s?.color || "#64748b" };
}
const onTablet = (g) => g.presence === "INSTALLED" || g.presence === "PARTIAL" || g.presence === "TABLET_ONLY";
const onPc = (g) => g.presence !== "TABLET_ONLY";

function renderSystems() {
  const counts = new Map();
  for (const g of S.games) {
    if (S.filter === "tablet" && !onTablet(g)) continue;
    if (S.filter === "pc" && !onPc(g)) continue;
    const c = counts.get(g.system) || { n: 0, size: 0 };
    c.n++;
    c.size += S.filter === "tablet" ? g.localSize : g.size;
    counts.set(g.system, c);
  }
  const list = [...counts.entries()].map(([id, c]) => ({ ...sysInfo(id), ...c })).sort((a, b) => a.name.localeCompare(b.name));
  const total = list.reduce((a, s) => a + s.n, 0);
  $("#systems").innerHTML = `<button data-s="" class="${S.system === "" ? "on" : ""}"><span class="dot" style="background:linear-gradient(135deg,#3A55C8,#1E8FD6)"></span><span class="nm">Todas</span><span class="ct">${total}</span></button>` +
    list.map((s) => `<button data-s="${esc(s.id)}" class="${S.system === s.id ? "on" : ""}" title="${esc(s.name)}">
      <span class="dot" style="background:${esc(s.color)}"></span><span class="nm">${esc(s.name)}</span><span class="ct">${s.n} · ${fmtSize(s.size)}</span></button>`).join("");
}
$("#systems").addEventListener("click", (e) => {
  const b = e.target.closest("button");
  if (!b) return;
  S.system = b.dataset.s;
  renderSystems();
  renderGrid();
});
$("#filters").addEventListener("click", (e) => {
  const b = e.target.closest("button");
  if (!b) return;
  S.filter = b.dataset.f;
  for (const x of $("#filters").children) x.classList.toggle("on", x === b);
  renderSystems();
  renderGrid();
});
$("#search").addEventListener("input", (e) => { S.q = e.target.value.trim().toLowerCase(); renderGrid(); });
$("#sort").addEventListener("change", (e) => { S.sort = e.target.value; renderGrid(); });
$("#refreshBtn").addEventListener("click", async () => {
  toast("Leyendo la tablet y el PC…");
  await api("/api/refresh");
  await loadGames();
  toast("Listo");
});

function filtered() {
  let gs = S.games.filter((g) => {
    if (S.system && g.system !== S.system) return false;
    if (S.filter === "tablet" && !onTablet(g)) return false;
    if (S.filter === "pc" && !onPc(g)) return false;
    if (S.q && !g.title.toLowerCase().includes(S.q) && !g.name.toLowerCase().includes(S.q)) return false;
    return true;
  });
  if (S.sort === "size") gs = gs.slice().sort((a, b) => b.size - a.size);
  else gs = gs.slice().sort((a, b) => a.title.localeCompare(b.title));
  return gs;
}

function coverHTML(g, extra = "") {
  const sys = sysInfo(g.system);
  return `<div class="cover" style="--c:${esc(sys.color)}">
    <div class="ph"><small>${esc(sys.short)}</small><b>${esc(g.title)}</b></div>
    <img loading="lazy" alt="" src="/api/art/${g.id}/grid?v=${S.artV[g.id] || 0}" onerror="this.remove()" onload="if(!this.naturalWidth)this.remove()">${extra}
  </div>`;
}
S.artV = {};

function badge(g) {
  if (g.download && g.download.status !== "FAILED" && g.download.status !== "DONE")
    return `<span class="badge">${g.download.status === "PAUSED" ? "En pausa" : Math.floor((g.download.done * 100) / Math.max(g.download.total, 1)) + " %"}</span>`;
  if (g.presence === "INSTALLED") return `<span class="badge ok">En la tablet</span>`;
  if (g.presence === "PARTIAL") return `<span class="badge part">A medias</span>`;
  if (g.presence === "TABLET_ONLY") return `<span class="badge">Solo tablet</span>`;
  return "";
}

function renderGrid() {
  const games = filtered();
  const grid = $("#grid");
  const empty = $("#empty");
  const total = games.reduce((a, g) => a + g.size, 0);
  $("#summary").textContent = `${games.length} juegos · ${fmtSize(total)}` + pcLine();
  empty.hidden = games.length > 0;
  if (!games.length) empty.textContent = S.filter === "pc" && !S.state?.pcConfigured ? "Configura el PC en la app (Ajustes) para ver su biblioteca." : "No hay juegos con este filtro.";
  grid.innerHTML = "";
  let i = 0;
  const step = () => {
    const frag = document.createDocumentFragment();
    for (const end = Math.min(i + 120, games.length); i < end; i++) {
      const g = games[i];
      const b = document.createElement("button");
      b.className = "card";
      b.dataset.id = g.id;
      b.innerHTML = coverHTML(g, `${badge(g)}<span class="sys-tag">${esc(sysInfo(g.system).short)}</span>`) +
        `<p class="t">${esc(g.title)}</p><p class="s">${fmtSize(g.size)}${g.parts?.length > 1 ? ` · ${g.parts.length} partes` : ""}</p>`;
      frag.appendChild(b);
    }
    grid.appendChild(frag);
    if (i < games.length) requestAnimationFrame(step);
  };
  step();
}
function pcLine() {
  const st = S.state;
  if (!st?.pcConfigured) return " · PC sin configurar";
  if (st.pc === "online") return " · PC conectado";
  if (st.pc === "connecting") return " · leyendo el PC…";
  if (st.pc === "offline") return ` · PC sin conexión (${st.pcError})`;
  return "";
}
$("#grid").addEventListener("click", (e) => {
  const c = e.target.closest(".card");
  if (c) openGame(c.dataset.id);
});

// ---- Ficha ----
function openModal(html) { $("#sheet").innerHTML = html; $("#modal").hidden = false; document.body.style.overflow = "hidden"; }
function closeModal() { $("#modal").hidden = true; document.body.style.overflow = ""; }
$("#modal").addEventListener("click", (e) => { if (e.target.id === "modal" || e.target.closest(".close")) closeModal(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeModal(); });
const partName = { base: "Juego base", update: "Actualización", dlc: "DLC" };

function openGame(id) {
  const g = S.games.find((x) => x.id === id);
  if (!g) return;
  const sys = sysInfo(g.system);
  const missing = new Set((g.missing || []).map((f) => f.path));
  const have = (f) => g.presence === "TABLET_ONLY" || (g.presence !== "MISSING" && !missing.has(f.path));
  const free = S.state?.free || 0;
  const missingSize = (g.missing || []).reduce((a, f) => a + f.size, 0);
  const stateText = {
    INSTALLED: `<b style="color:var(--ok)">En la tablet</b>${g.differs ? `<span class="muted">· algún archivo es distinto al del PC</span>` : ""}`,
    PARTIAL: `<b style="color:var(--warn)">A medias en la tablet</b><span class="muted">faltan ${fmtSize(missingSize)}</span>`,
    MISSING: `<b>No está en la tablet.</b><span class="muted">Ocupa ${fmtSize(g.size)} · quedan ${fmtSize(free)} libres</span>`,
    TABLET_ONLY: `<b>Solo en la tablet</b><span class="muted">no está en el PC</span>`,
  }[g.presence];
  const dl = g.download && g.download.status !== "DONE" ? g.download : null;
  const parts = g.parts?.length > 1 || g.parts?.some((p) => p.kind !== "base") ? `<h3>Contenido</h3><ul class="files">${g.parts.map((p) => {
    const ok = p.files.every(have);
    return `<li><label style="flex:1"><input type="checkbox" data-part="${esc(p.titleId)}" ${ok ? "checked disabled" : "checked"}> ${partName[p.kind] || p.kind}${p.version ? ` v${p.version}` : ""} <span class="muted">${esc(p.titleId)}</span></label><span>${fmtSize(p.size)}</span><span class="${ok ? "have" : "miss"}">${ok ? "En la tablet" : "No"}</span></li>`;
  }).join("")}</ul>` : "";
  const files = `<h3>Archivos</h3><ul class="files">${g.files.map((f) => `<li><span>${esc(f.path)}</span><span>${fmtSize(f.size)}</span><span class="${have(f) ? "have" : "miss"}">${have(f) ? `<a href="/api/download?path=${encodeURIComponent(f.path)}" title="Bajar a este equipo">✓ bajar</a>` : "—"}</span></li>`).join("")}</ul>`;
  const canInstall = (g.presence === "MISSING" || g.presence === "PARTIAL") && !dl;
  const canDelete = g.presence !== "MISSING" && !(dl && dl.status === "RUNNING");

  openModal(`
    <div class="hero" id="hero" style="--c:${esc(sys.color)}">
      <img class="logo" alt="" src="/api/art/${g.id}/logo?v=${S.artV[g.id] || 0}" onerror="this.remove()">
      <button class="close" aria-label="Cerrar">×</button>
    </div>
    <div class="detail">
      <div>${coverHTML(g)}</div>
      <div>
        <h2>${esc(g.title)}</h2>
        <div class="meta"><span>${esc(sys.name)}</span><span>${fmtSize(g.size)}</span><span>${g.files.length} archivo(s)</span>${g.serial ? `<span>${esc(g.serial)}</span>` : ""}</div>
        ${g.tags?.length ? `<div class="pills">${g.tags.map((t) => `<span class="pill">${esc(t)}</span>`).join("")}</div>` : ""}
        <div class="state">${stateText}</div>
        ${dl ? `<div class="state"><b>${dl.status === "PAUSED" ? "En pausa" : dl.status === "FAILED" ? "Falló" : "Descargando"}</b><div class="bar" style="flex:1"><span style="width:${(dl.done * 100) / Math.max(dl.total, 1)}%"></span></div><span class="muted">${fmtSize(dl.done)} de ${fmtSize(dl.total)}</span></div>` : ""}
        <div class="actions">
          ${canInstall ? `<button class="primary" id="installBtn">Instalar en la tablet · ${fmtSize(g.presence === "PARTIAL" ? missingSize : g.size)}</button>` : ""}
          ${dl ? `<button class="ghost" id="dlToggle">${dl.status === "PAUSED" || dl.status === "FAILED" ? "Seguir" : "Pausar"}</button><button class="ghost" id="dlCancel">Cancelar</button>` : ""}
          ${canDelete ? `<button class="danger-btn" id="deleteBtn">Borrar de la tablet</button>` : ""}
        </div>
        <p class="muted small">${canInstall ? "La tablet lo copia desde el disco del PC (SFTP); puedes cerrar esta página." : canDelete ? "Borrar quita solo los archivos del juego: las partidas se quedan." : ""}</p>
        ${parts}${files}
        <h3>Carátula</h3>
        <div class="actions">
          <button class="ghost" id="artSearch">Buscar en SteamGridDB</button>
          <label class="ghost">Subir imagen<input type="file" id="artFile" accept="image/*" hidden></label>
          <button class="ghost" id="artClear">Quitar</button>
        </div>
        <div id="artPick"></div>
      </div>
    </div>`);
  const hero = new Image();
  hero.onload = () => { if (hero.naturalWidth) $("#hero").style.background = `url("${hero.src}") center/cover`; };
  hero.src = `/api/art/${g.id}/hero?v=${S.artV[g.id] || 0}`;

  $("#installBtn")?.addEventListener("click", async () => {
    const sel = [...document.querySelectorAll("[data-part]:not(:disabled)")].filter((c) => c.checked).map((c) => c.dataset.part);
    try {
      await post("/api/install", g.parts?.length ? { id, parts: sel } : { id });
      toast("Descargando en la tablet…");
      await loadGames();
      openGame(id);
    } catch (e) { toast(e.message); }
  });
  $("#dlToggle")?.addEventListener("click", async () => {
    await api(`/api/downloads/${encodeURIComponent(id)}/${dl.status === "PAUSED" || dl.status === "FAILED" ? "resume" : "pause"}`);
    await loadGames(); openGame(id);
  });
  $("#dlCancel")?.addEventListener("click", async () => {
    await api(`/api/downloads/${encodeURIComponent(id)}/cancel`);
    await loadGames(); openGame(id);
  });
  $("#deleteBtn")?.addEventListener("click", async () => {
    const warn = g.presence === "TABLET_ONLY" ? "\n\nOjo: este juego NO está en el PC; no lo podrás volver a descargar con RomSync." : "\n\nLo puedes volver a descargar desde el PC.";
    if (!confirm(`¿Borrar «${g.title}» de la tablet?\n\nSe liberan ${fmtSize(g.localSize)}. Tus partidas no se borran.${warn}`)) return;
    try {
      const r = await post("/api/delete", { id });
      toast(`Borrado · ${fmtSize(r.freed)} libres`);
      closeModal();
      await loadGames();
    } catch (e) { toast(e.message); }
  });
  $("#artSearch").addEventListener("click", () => artPicker(g));
  $("#artClear").addEventListener("click", async () => {
    await api(`/api/art/${g.id}/grid?clear=1`, { method: "POST" });
    S.artV[g.id] = Date.now(); closeModal(); renderGrid();
  });
  $("#artFile").addEventListener("change", async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    await api(`/api/art/${g.id}/grid`, { method: "POST", body: f });
    S.artV[g.id] = Date.now(); toast("Carátula cambiada"); closeModal(); renderGrid();
  });
}

function artPicker(g) {
  const box = $("#artPick");
  box.innerHTML = `<div class="inline"><input id="artQ" value="${esc(g.title)}"><button class="ghost" id="artGo">Buscar</button></div><div class="results" id="artRes"></div>`;
  const go = async () => {
    const res = $("#artRes");
    res.innerHTML = `<span class="muted">Buscando…</span>`;
    try {
      const list = await api("/api/art-search?q=" + encodeURIComponent($("#artQ").value));
      res.innerHTML = list.length ? list.map((r) => `<button data-id="${r.id}">${esc(r.name)}</button>`).join("") : `<span class="muted">Sin resultados (¿falta la clave de SteamGridDB en la app?)</span>`;
    } catch (e) { res.innerHTML = `<span class="err">${esc(e.message)}</span>`; }
  };
  $("#artGo").addEventListener("click", go);
  $("#artQ").addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });
  $("#artRes").addEventListener("click", async (e) => {
    const b = e.target.closest("button[data-id]");
    if (!b) return;
    await api(`/api/art/${g.id}/grid?sgdb=${b.dataset.id}`, { method: "POST" });
    S.artV[g.id] = Date.now(); toast("Carátula cambiada"); closeModal(); renderGrid();
  });
  go();
}

// ---- Subir ROMs (botón o arrastrar) ----
const uploads = [];
function queueUploads(files, system) {
  if (!system) { toast("Elige primero una plataforma a la izquierda"); return; }
  for (const f of files) uploads.push({ file: f, path: `${system}/${f.webkitRelativePath || f.name}`, done: 0, status: "cola" });
  renderTransfers();
  pumpUploads();
}
let uploading = false;
async function pumpUploads() {
  if (uploading) return;
  uploading = true;
  for (const u of uploads) {
    if (u.status !== "cola") continue;
    u.status = "subiendo";
    try {
      await new Promise((resolve, reject) => {
        const x = new XMLHttpRequest();
        x.open("PUT", `/api/upload?path=${encodeURIComponent(u.path)}${u.bios ? "&bios=1" : ""}`);
        x.upload.onprogress = (e) => { u.done = e.loaded; renderTransfers(); };
        x.onload = () => (x.status < 300 ? resolve() : reject(new Error(JSON.parse(x.responseText || "{}").error || x.status)));
        x.onerror = () => reject(new Error("se cortó la conexión"));
        x.send(u.file);
      });
      u.status = "listo";
      u.done = u.file.size;
    } catch (e) { u.status = "error: " + e.message; }
    renderTransfers();
  }
  uploading = false;
  await loadGames();
  if (S.tab === "bios") renderBios();
}
function renderTransfers() {
  const el = $("#transfers");
  const active = uploads.filter((u) => u.status !== "listo" || Date.now() - (u.at || (u.at = Date.now())) < 8000);
  el.hidden = active.length === 0;
  el.innerHTML = `<b>Subidas</b>` + active.map((u) => `<div class="tr"><span>${esc(u.path)}</span><div class="bar"><span style="width:${(u.done * 100) / Math.max(u.file.size, 1)}%"></span></div><span class="muted">${esc(u.status)}</span></div>`).join("");
}
$("#uploadBtn").addEventListener("click", () => $("#uploadInput").click());
$("#uploadInput").addEventListener("change", (e) => { queueUploads([...e.target.files], S.system); e.target.value = ""; });
let dragDepth = 0;
document.addEventListener("dragenter", (e) => {
  if (!$("#tab-platforms").classList.contains("on")) return;
  dragDepth++;
  $("#drop").hidden = false;
  $("#dropTo").textContent = S.system ? sysInfo(S.system).name : "(elige una plataforma)";
});
document.addEventListener("dragleave", () => { if (--dragDepth <= 0) { dragDepth = 0; $("#drop").hidden = true; } });
document.addEventListener("dragover", (e) => e.preventDefault());
document.addEventListener("drop", (e) => {
  e.preventDefault();
  dragDepth = 0;
  $("#drop").hidden = true;
  if ($("#tab-platforms").classList.contains("on")) queueUploads([...e.dataTransfer.files], S.system);
});

// ---- BIOS ----
async function renderBios() {
  S.tab = "bios";
  const el = $("#biosPage");
  const r = await api("/api/bios");
  el.innerHTML = `
    <div class="box"><div class="row"><h2 style="flex:1">BIOS en la tablet (${r.bios.length})</h2>
      <label class="ghost">Subir BIOS<input type="file" id="biosUp" multiple hidden></label></div>
      <p class="muted small">Carpeta: ${esc(S.state?.biosDir || "")}</p>
      ${r.bios.length ? `<ul class="files">${r.bios.map((b) => `<li><span>${esc(b.path)}</span><span>${fmtSize(b.size)}</span><a href="/api/download?bios=1&path=${encodeURIComponent(b.path)}">bajar</a><button class="ghost small" data-del="${esc(b.path)}">Borrar</button></li>`).join("")}</ul>` : `<p class="muted">No hay BIOS.</p>`}</div>
    <div class="box"><div class="row"><h2 style="flex:1">En el PC y no en la tablet (${r.missing.length})</h2>
      ${r.missing.length ? `<button class="primary" id="biosCopy">Copiar desde el PC</button>` : ""}</div>
      ${r.missing.length ? `<ul class="files">${r.missing.map((b) => `<li><span>${esc(b.path)}</span><span>${fmtSize(b.size)}</span></li>`).join("")}</ul>` : `<p class="muted">${S.state?.pcConfigured ? "No falta ninguna." : "Configura el PC en la app para compararlas."}</p>`}</div>`;
  $("#biosUp").addEventListener("change", (e) => {
    for (const f of e.target.files) uploads.push({ file: f, path: f.name, bios: true, done: 0, status: "cola" });
    renderTransfers(); pumpUploads();
  });
  $("#biosCopy")?.addEventListener("click", async () => { await post("/api/bios"); toast("Copiando BIOS desde el PC…"); });
  el.querySelectorAll("[data-del]").forEach((b) => b.addEventListener("click", async () => {
    if (!confirm(`¿Borrar ${b.dataset.del} de la tablet?`)) return;
    await api(`/api/bios?path=${encodeURIComponent(b.dataset.del)}`, { method: "DELETE" });
    renderBios();
  }));
}

// ---- Partidas ----
async function loadSaves() {
  S.tab = "saves";
  const el = $("#savesPage");
  el.innerHTML = `<div class="box"><p class="muted">Revisando los emuladores…</p></div>`;
  const [emus, backups] = await Promise.all([api("/api/emulators"), api("/api/backups").catch(() => [])]);
  const byEmu = new Map();
  for (const b of backups) { if (!byEmu.has(b.emulator)) byEmu.set(b.emulator, []); byEmu.get(b.emulator).push(b); }
  const shizuku = S.state?.shizuku;
  el.innerHTML = (emus.some((e) => e.installed && e.needsShell) && shizuku !== "READY"
    ? `<div class="box"><h2>Falta Shizuku</h2><p>Dolphin, Eden, ARMSX2 y Vita3K guardan en Android/data. Abre Shizuku en la tablet y dale permiso a RomSync.</p></div>` : "") +
    emus.filter((e) => e.installed || byEmu.has(e.id)).map((e) => {
      const list = byEmu.get(e.id) || [];
      return `<div class="box">
        <div class="row"><h2 style="flex:1">${esc(e.name)}</h2>
          ${e.installed && e.supported ? `<a class="ghost" href="/api/emulators/${e.id}/export">Bajar partidas (zip)</a>
          <button class="ghost" data-backup="${e.id}">Respaldar en el PC</button>
          <label class="ghost">Restaurar desde zip<input type="file" accept=".zip" data-restore="${e.id}" hidden></label>` : ""}</div>
        <p class="muted small">${esc(e.what)}${e.installed && e.supported ? ` · ${e.error ? esc(e.error) : `${e.files} archivos, ${fmtSize(e.bytes)}`} · último respaldo ${ago(e.lastBackup)}` : !e.installed ? " · no instalado" : ""}</p>
        ${e.note ? `<p class="muted small">${esc(e.note)}</p>` : ""}
        ${list.length ? `<table><tr><th>Respaldo en el PC</th><th>Archivos</th><th>Tamaño</th><th></th></tr>${list.map((b) => `<tr>
          <td>${fmtDate(b.at)}${b.auto ? ` <span class="pill">auto</span>` : ""}${b.keep ? ` <span class="pill">conservar</span>` : ""}</td><td>${b.files || "—"}</td><td>${fmtSize(b.size)}</td>
          <td class="row" style="justify-content:flex-end">
            <a class="ghost" href="/api/backups/${e.id}/${b.id}.zip">Bajar</a>
            ${e.installed ? `<button class="ghost" data-restore-id="${b.id}" data-emu="${e.id}">Restaurar</button>` : ""}
            <button class="ghost" data-keep="${b.keep ? 0 : 1}" data-emu="${e.id}" data-id="${b.id}">${b.keep ? "No conservar" : "Conservar"}</button>
            <button class="danger-btn" data-del-b="${b.id}" data-emu="${e.id}">Borrar</button>
          </td></tr>`).join("")}</table>` : ""}
      </div>`;
    }).join("") + `<p class="muted small">Restaurar primero respalda lo que hay en la tablet. Cierra el emulador antes.</p>`;

  el.querySelectorAll("[data-backup]").forEach((b) => b.addEventListener("click", async () => {
    b.disabled = true; b.textContent = "Respaldando…";
    try { const r = await api(`/api/emulators/${b.dataset.backup}/backup`); toast(r.message); } catch (e) { toast(e.message); }
    loadSaves();
  }));
  el.querySelectorAll("[data-restore]").forEach((inp) => inp.addEventListener("change", async () => {
    const f = inp.files[0];
    if (!f || !confirm(`¿Restaurar ${f.name} en ${inp.dataset.restore}? Lo actual se respalda antes.`)) return;
    try { await api(`/api/emulators/${inp.dataset.restore}/restore`, { method: "POST", body: f }); toast("Partidas restauradas"); } catch (e) { toast(e.message); }
    loadSaves();
  }));
  el.querySelectorAll("[data-restore-id]").forEach((b) => b.addEventListener("click", async () => {
    if (!confirm("¿Restaurar este respaldo? Lo actual se respalda antes. Cierra el emulador.")) return;
    try { await api(`/api/emulators/${b.dataset.emu}/restore?id=${b.dataset.restoreId}`, { method: "POST" }); toast("Partidas restauradas"); } catch (e) { toast(e.message); }
    loadSaves();
  }));
  el.querySelectorAll("[data-keep]").forEach((b) => b.addEventListener("click", async () => {
    await api(`/api/backups/${b.dataset.emu}/${b.dataset.id}?do=keep&v=${b.dataset.keep}`); loadSaves();
  }));
  el.querySelectorAll("[data-del-b]").forEach((b) => b.addEventListener("click", async () => {
    if (!confirm("¿Borrar este respaldo del PC? No se puede deshacer.")) return;
    await api(`/api/backups/${b.dataset.emu}/${b.dataset.delB}?do=delete`); loadSaves();
  }));
}

// ---- Herramientas ----
async function renderTools(path) {
  S.tab = "tools";
  const el = $("#toolsPage");
  let r;
  try { r = await api("/api/files?path=" + encodeURIComponent(path)); } catch (e) { el.innerHTML = `<div class="box"><p class="err">${esc(e.message)}</p></div>`; return; }
  const crumbs = ["", ...r.path.split("/").filter(Boolean)];
  el.innerHTML = `
    <div class="box"><div class="row"><h2 style="flex:1">Instalar APK</h2><label class="primary">Elegir APK<input type="file" id="apk" accept=".apk" hidden></label></div>
      <p class="muted small">La tablet pide confirmar la instalación en pantalla.</p></div>
    <div class="box">
      <div class="row"><h2 style="flex:1">Archivos</h2>
        <button class="ghost" id="mk">Nueva carpeta</button>
        <label class="ghost">Subir aquí<input type="file" id="fup" multiple hidden></label></div>
      <p class="small">${crumbs.map((c, i) => `<a href="#" data-go="${esc(crumbs.slice(1, i + 1).join("/"))}">${i === 0 ? "Almacenamiento" : esc(c)}</a>`).join(" / ")} <span class="muted">· ${fmtSize(r.free)} libres</span></p>
      <table>${r.entries.map((f) => `<tr>
        <td>${f.dir ? `📁 <a href="#" data-go="${esc((r.path ? r.path + "/" : "") + f.name)}">${esc(f.name)}</a>` : `📄 ${esc(f.name)}`}</td>
        <td class="muted">${f.dir ? "" : fmtSize(f.size)}</td>
        <td class="row" style="justify-content:flex-end">${f.dir ? "" : `<a class="ghost" href="/api/files/download?path=${encodeURIComponent((r.path ? r.path + "/" : "") + f.name)}">Bajar</a>`}
          <button class="danger-btn" data-rm="${esc((r.path ? r.path + "/" : "") + f.name)}">Borrar</button></td></tr>`).join("")}</table>
    </div>`;
  el.querySelectorAll("[data-go]").forEach((a) => a.addEventListener("click", (e) => { e.preventDefault(); renderTools(a.dataset.go); }));
  el.querySelectorAll("[data-rm]").forEach((b) => b.addEventListener("click", async () => {
    if (!confirm(`¿Borrar ${b.dataset.rm}? No se puede deshacer.`)) return;
    await api("/api/files?path=" + encodeURIComponent(b.dataset.rm), { method: "DELETE" });
    renderTools(r.path);
  }));
  $("#mk").addEventListener("click", async () => {
    const n = prompt("Nombre de la carpeta");
    if (!n) return;
    await api("/api/files/mkdir?path=" + encodeURIComponent((r.path ? r.path + "/" : "") + n), { method: "POST" });
    renderTools(r.path);
  });
  $("#fup").addEventListener("change", async (e) => {
    for (const f of e.target.files) {
      toast("Subiendo " + f.name + "…");
      await api("/api/files?path=" + encodeURIComponent((r.path ? r.path + "/" : "") + f.name), { method: "PUT", body: f });
    }
    renderTools(r.path);
  });
  $("#apk").addEventListener("change", async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    toast("Enviando " + f.name + "…");
    try { const res = await api("/api/apk", { method: "POST", body: f }); toast(res.message); } catch (err) { toast(err.message); }
  });
}

// ---- Carga ----
async function loadGames() {
  const [st, g] = await Promise.all([api("/api/state"), api("/api/games")]);
  S.state = st;
  S.games = g.games;
  renderPill();
  renderSystems();
  renderGrid();
}
function renderPill() {
  const st = S.state;
  if (!st) return;
  const pct = st.total ? ((st.total - st.free) / st.total) * 100 : 0;
  $("#storagePill").innerHTML = `<span>${esc(st.model)}</span><div class="bar"><span class="${pct > 92 ? "danger" : pct > 80 ? "warn" : ""}" style="width:${pct}%"></span></div><span>${fmtSize(st.free)} libres</span>`;
}

async function boot() {
  try { await loadGames(); } catch { return; }
  const tab = location.hash.slice(1);
  if (["bios", "saves", "tools"].includes(tab)) setTab(tab);
  // El avance de las descargas en la tablet.
  setInterval(async () => {
    if (!$("#tab-platforms").classList.contains("on") || document.hidden) return;
    const busy = S.games.some((g) => g.download && (g.download.status === "RUNNING" || g.download.status === "QUEUED"));
    if (busy || S.state?.scanning) { try { await loadGames(); } catch { /* sin conexión */ } }
  }, 3000);
}
boot();
