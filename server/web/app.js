"use strict";

// ---- Estado ----
const S = {
  lib: null,          // /api/library
  devices: [],        // /api/devices (la última tablet primero)
  queue: [],
  saves: [],
  info: null,
  system: "",         // plataforma elegida ("" = todas)
  filter: "all",
  sort: "name",
  q: "",
};
const $ = (s, el = document) => el.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function fmtSize(b) {
  if (b == null) return "";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
  return (i >= 3 ? b.toFixed(b >= 100 ? 0 : 1) : Math.round(b)).toString().replace(".", ",") + " " + u[i];
}
function fmtDate(s) {
  const d = new Date(s);
  return d.toLocaleString("es", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}
function ago(s) {
  const m = Math.round((Date.now() - new Date(s)) / 60000);
  if (m < 1) return "recién";
  if (m < 60) return `hace ${m} min`;
  const h = Math.round(m / 60);
  if (h < 48) return `hace ${h} h`;
  return `hace ${Math.round(h / 24)} días`;
}
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.t);
  toast.t = setTimeout(() => (t.hidden = true), 2600);
}

async function api(path, opts = {}) {
  const r = await fetch(path, { credentials: "same-origin", ...opts,
    headers: { ...(opts.body && typeof opts.body === "string" ? { "Content-Type": "application/json" } : {}), ...(opts.headers || {}) } });
  if (r.status === 401) { showLogin(); throw new Error("Código incorrecto"); }
  if (r.status === 304 || r.status === 204) return null;
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || `Error ${r.status}`);
  return data;
}

// ---- Login (solo desde otro equipo) ----
function showLogin() { $("#login").hidden = false; $("#loginCode").focus(); }
$("#loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    await api("/api/login", { method: "POST", body: JSON.stringify({ code: $("#loginCode").value.trim() }) });
    $("#login").hidden = true;
    boot();
  } catch (err) { $("#loginErr").textContent = err.message; }
});

// ---- Pestañas ----
$("#tabs").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-tab]");
  if (!b) return;
  setTab(b.dataset.tab);
});
function setTab(tab) {
  for (const b of $("#tabs").children) b.classList.toggle("on", b.dataset.tab === tab);
  for (const s of document.querySelectorAll(".tab")) s.classList.toggle("on", s.id === "tab-" + tab);
  location.hash = tab === "library" ? "" : tab;
  if (tab === "tablet") renderTablet();
  if (tab === "saves") loadSaves();
  if (tab === "settings") renderSettings();
}

// ---- Biblioteca ----
function device() { return S.devices[0] || null; }
function stateMap() {
  const m = new Map();
  const d = device();
  if (d) for (const s of d.compare.states) m.set(s.id, s);
  return m;
}

function renderSystems() {
  const el = $("#systems");
  const systems = S.lib?.systems || [];
  const total = systems.reduce((a, s) => a + s.count, 0);
  const size = systems.reduce((a, s) => a + s.size, 0);
  el.innerHTML = `<button data-s="" class="${S.system === "" ? "on" : ""}"><span class="dot" style="background:linear-gradient(135deg,#3A55C8,#1E8FD6)"></span><span class="nm">Todas</span><span class="ct">${total} · ${fmtSize(size)}</span></button>` +
    systems.map((s) => `<button data-s="${esc(s.id)}" class="${S.system === s.id ? "on" : ""}" title="${esc(s.name)}">
      <span class="dot" style="background:${esc(s.color)}"></span><span class="nm">${esc(s.name)}</span><span class="ct">${s.count} · ${fmtSize(s.size)}</span></button>`).join("");
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
  renderGrid();
});
$("#search").addEventListener("input", (e) => { S.q = e.target.value.trim().toLowerCase(); renderGrid(); });
$("#sort").addEventListener("change", (e) => { S.sort = e.target.value; renderGrid(); });
$("#rescan").addEventListener("click", async () => {
  await api("/api/rescan", { method: "POST" });
  toast("Leyendo la carpeta de ROMs…");
  setTimeout(loadLibrary, 2500);
});

function sysInfo(id) {
  return (S.lib?.systems || []).find((s) => s.id === id) || S.info?.systems?.[id] || { name: id, short: id.toUpperCase(), color: "#64748b" };
}

function filteredGames() {
  const st = stateMap();
  let games = (S.lib?.games || []).filter((g) => {
    if (S.system && g.system !== S.system) return false;
    if (S.q && !g.title.toLowerCase().includes(S.q) && !g.name.toLowerCase().includes(S.q)) return false;
    const s = st.get(g.id)?.state || "missing";
    if (S.filter !== "all" && s !== S.filter) return false;
    return true;
  });
  if (S.sort === "size") games = games.slice().sort((a, b) => b.size - a.size);
  return games;
}

function coverHTML(g, sys, extra = "") {
  return `<div class="cover" style="--c:${esc(sys.color)}">
    <div class="ph"><small>${esc(sys.short)}</small><b>${esc(g.title)}</b></div>
    <img loading="lazy" alt="" src="/api/art/${g.id}/grid" onerror="this.remove()" onload="if(!this.naturalWidth)this.remove()">${extra}
  </div>`;
}

function renderGrid() {
  const st = stateMap();
  const games = filteredGames();
  const grid = $("#grid");
  const empty = $("#empty");
  const total = games.reduce((a, g) => a + g.size, 0);
  $("#summary").textContent = S.lib ? `${games.length} juegos · ${fmtSize(total)}` + (device() ? ` · tablet revisada ${ago(device().at)}` : " · la tablet todavía no se ha conectado") : "";
  if (!S.lib) { grid.innerHTML = ""; return; }
  if (S.lib.error) {
    grid.innerHTML = "";
    empty.hidden = false;
    empty.innerHTML = `<p>${esc(S.lib.error)}</p><button class="primary" onclick="setTab('settings')">Ir a Ajustes</button>`;
    return;
  }
  empty.hidden = games.length > 0;
  if (!games.length) empty.textContent = S.lib.scanning ? "Leyendo la biblioteca…" : "No hay juegos con este filtro.";
  // Se dibuja por tandas para que una biblioteca grande no congele la página.
  grid.innerHTML = "";
  const queued = new Set(S.queue.map((q) => q.gameId));
  let i = 0;
  const step = () => {
    const frag = document.createDocumentFragment();
    for (const end = Math.min(i + 120, games.length); i < end; i++) {
      const g = games[i];
      const sys = sysInfo(g.system);
      const s = st.get(g.id);
      const badge = s?.state === "installed" ? `<span class="badge ok">En la tablet</span>` :
        s?.state === "partial" ? `<span class="badge part">A medias</span>` : queued.has(g.id) ? `<span class="badge">En cola</span>` : "";
      const b = document.createElement("button");
      b.className = "card";
      b.dataset.id = g.id;
      b.innerHTML = coverHTML(g, sys, `${badge}<span class="sys-tag">${esc(sys.short)}</span>`) +
        `<p class="t">${esc(g.title)}</p><p class="s">${fmtSize(g.size)}${g.parts?.length > 1 ? ` · ${g.parts.length} partes` : ""}</p>`;
      frag.appendChild(b);
    }
    grid.appendChild(frag);
    if (i < games.length) requestAnimationFrame(step);
  };
  step();
}
$("#grid").addEventListener("click", (e) => {
  const c = e.target.closest(".card");
  if (c) openGame(c.dataset.id);
});

// ---- Ficha ----
function openModal(html) {
  $("#sheet").innerHTML = html;
  $("#modal").hidden = false;
  document.body.style.overflow = "hidden";
}
function closeModal() {
  $("#modal").hidden = true;
  document.body.style.overflow = "";
}
$("#modal").addEventListener("click", (e) => { if (e.target.id === "modal" || e.target.closest(".close")) closeModal(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeModal(); });

const partName = { base: "Juego base", update: "Actualización", dlc: "DLC" };

function openGame(id) {
  const g = S.lib.games.find((x) => x.id === id);
  if (!g) return;
  const sys = sysInfo(g.system);
  const s = stateMap().get(id);
  const missing = new Set(s?.missing || []);
  const have = (f) => s && s.state !== "missing" && !missing.has(f.path);
  const inQueue = S.queue.some((q) => q.gameId === id);
  const d = device();
  let stateHTML;
  if (!d) stateHTML = `<span>La tablet todavía no se ha conectado a este PC.</span>`;
  else if (!s) stateHTML = `<b>No está en la tablet.</b><span class="muted">Ocuparía ${fmtSize(g.size)} · quedan ${fmtSize(d.free)} libres</span>`;
  else if (s.state === "installed") stateHTML = `<b style="color:var(--ok)">En la tablet</b>${s.differs ? `<span class="muted">· algún archivo tiene otro tamaño que en el PC</span>` : ""}`;
  else stateHTML = `<b style="color:var(--warn)">A medias en la tablet</b><span class="muted">faltan ${s.missing.length} archivo(s)</span>`;

  const parts = g.parts?.length ? `<h3>Contenido</h3><ul class="files">${g.parts.map((p) => {
    const ok = p.files.every(have);
    return `<li><span>${partName[p.kind] || p.kind}${p.version ? ` v${p.version}` : ""} <span class="muted">${esc(p.titleId)}</span></span><span>${fmtSize(p.size)}</span><span class="${ok ? "have" : "miss"}">${ok ? "En la tablet" : "No"}</span></li>`;
  }).join("")}</ul>` : "";
  const files = `<h3>Archivos</h3><ul class="files">${g.files.map((f) => `<li><span>${esc(f.path)}</span><span>${fmtSize(f.size)}</span>${d ? `<span class="${have(f) ? "have" : "miss"}">${have(f) ? "✓" : "—"}</span>` : ""}</li>`).join("")}</ul>`;

  openModal(`
    <div class="hero" id="hero" style="--c:${esc(sys.color)}">
      <img class="logo" alt="" src="/api/art/${g.id}/logo" onerror="this.remove()">
      <button class="close" aria-label="Cerrar">×</button>
    </div>
    <div class="detail">
      <div>${coverHTML(g, sys)}</div>
      <div>
        <h2>${esc(g.title)}</h2>
        <div class="meta"><span>${esc(sys.name)}</span><span>${fmtSize(g.size)}</span><span>${g.files.length} archivo(s)</span>${g.serial ? `<span>${esc(g.serial)}</span>` : ""}</div>
        ${g.tags?.length ? `<div class="pills">${g.tags.map((t) => `<span class="pill">${esc(t)}</span>`).join("")}</div>` : ""}
        <div class="state">${stateHTML}</div>
        <div class="actions">
          ${s?.state === "installed" ? "" : `<button class="primary" id="queueBtn">${inQueue ? "Quitar de la cola" : "Enviar a la tablet"}</button>`}
          <button class="ghost" id="artBtn">Cambiar carátula</button>
        </div>
        <p class="muted small">${s?.state === "installed" ? "Para borrarlo, usa la app en la tablet: ahí te pregunta antes y tus partidas no se tocan." : "“Enviar a la tablet” lo deja en la cola: la app lo descarga la próxima vez que se conecte."}</p>
        ${parts}${files}
        <div id="artPick"></div>
      </div>
    </div>`);
  const hero = new Image();
  hero.onload = () => { if (hero.naturalWidth) $("#hero").style.background = `url("${hero.src}") center/cover`; };
  hero.src = `/api/art/${g.id}/hero`;

  $("#queueBtn")?.addEventListener("click", async () => {
    if (inQueue) S.queue = await api("/api/queue?id=" + id, { method: "DELETE" });
    else S.queue = await api("/api/queue", { method: "POST", body: JSON.stringify({ gameId: id }) });
    toast(inQueue ? "Quitado de la cola" : "En la cola: la tablet lo descargará al conectarse");
    renderGrid();
    openGame(id);
  });
  $("#artBtn").addEventListener("click", () => artPicker(g));
}

function artPicker(g) {
  const box = $("#artPick");
  box.innerHTML = `<h3>Buscar en SteamGridDB</h3><div class="inline"><input id="artQ" value="${esc(g.title)}"><button class="ghost" id="artGo">Buscar</button></div><div class="results" id="artRes"></div>`;
  const go = async () => {
    const res = $("#artRes");
    res.innerHTML = `<span class="muted">Buscando…</span>`;
    try {
      const list = await api("/api/art-search?q=" + encodeURIComponent($("#artQ").value));
      res.innerHTML = list.length ? list.map((r) => `<button data-id="${r.id}">${esc(r.name)}</button>`).join("") : `<span class="muted">Sin resultados</span>`;
    } catch (e) { res.innerHTML = `<span class="err">${esc(e.message)}</span>`; }
  };
  $("#artGo").addEventListener("click", go);
  $("#artQ").addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });
  $("#artRes").addEventListener("click", async (e) => {
    const b = e.target.closest("button[data-id]");
    if (!b) return;
    await api("/api/art-choose", { method: "POST", body: JSON.stringify({ gameId: g.id, sgdbId: +b.dataset.id }) });
    toast("Carátula cambiada");
    // Evita la caché del navegador para este juego.
    for (const img of document.querySelectorAll(`img[src^="/api/art/${g.id}/"]`)) img.src = img.src.split("?")[0] + "?t=" + Date.now();
    closeModal();
    renderGrid();
  });
  go();
}

// ---- Tablet ----
function renderTablet() {
  const el = $("#tabletPage");
  const d = device();
  if (!d) {
    el.innerHTML = `<div class="box"><h2>La tablet todavía no se ha conectado</h2>
      <p>Abre RomSync en la tablet, toca <b>Buscar PC</b> y escribe el código <span class="code">${esc(S.info?.code || "")}</span>.</p>
      <p class="muted">Dirección de este PC: ${(S.info?.addresses || []).map((a) => `<b>${esc(a)}:${S.info.port}</b>`).join(" · ") || "sin red local"}</p></div>`;
    return;
  }
  const used = d.total - d.free;
  const pct = d.total ? (used / d.total) * 100 : 0;
  const bySys = new Map();
  for (const f of d.files) {
    const s = f.path.split("/")[0];
    bySys.set(s, (bySys.get(s) || 0) + f.size);
  }
  const romsTotal = [...bySys.values()].reduce((a, b) => a + b, 0);
  const sysRows = [...bySys.entries()].sort((a, b) => b[1] - a[1]).map(([s, sz]) => {
    const info = sysInfo(s);
    return `<div class="r"><span>${esc(info.name)}</span><div class="bar"><span style="width:${(sz / Math.max(romsTotal, 1)) * 100}%;background:${esc(info.color)}"></span></div><span class="muted">${fmtSize(sz)}</span></div>`;
  }).join("");
  const c = d.compare;
  const games = S.lib?.games || [];
  const queueRows = S.queue.map((q) => {
    const g = games.find((x) => x.id === q.gameId);
    return g ? `<tr><td>${esc(g.title)}</td><td>${esc(sysInfo(g.system).short)}</td><td>${fmtSize(g.size)}</td><td><button class="ghost" data-unq="${g.id}">Quitar</button></td></tr>` : "";
  }).join("");
  let emus = [];
  try { emus = d.emulators ? (typeof d.emulators === "string" ? JSON.parse(d.emulators) : d.emulators) : []; } catch { emus = []; }

  el.innerHTML = `
    <div class="box">
      <h2>${esc(d.model || d.device)}</h2>
      <p class="muted">Última conexión ${ago(d.at)} (${fmtDate(d.at)})</p>
      <div class="bar" style="height:14px;margin:12px 0 6px"><span class="${pct > 92 ? "danger" : pct > 80 ? "warn" : ""}" style="width:${pct}%"></span></div>
      <div class="row small"><span><b>${fmtSize(d.free)}</b> libres de ${fmtSize(d.total)}</span><span class="muted">ROMs: ${fmtSize(romsTotal)}</span></div>
    </div>
    <div class="box"><h2>Espacio por plataforma</h2><div class="sysbars">${sysRows || `<p class="muted">Sin ROMs en la tablet.</p>`}</div></div>
    <div class="box"><h2>En cola para la tablet</h2>
      ${queueRows ? `<table><tr><th>Juego</th><th></th><th>Tamaño</th><th></th></tr>${queueRows}</table>` : `<p class="muted">Nada en cola. En la Biblioteca, abre un juego y toca “Enviar a la tablet”.</p>`}</div>
    ${emus.length ? `<div class="box"><h2>Emuladores</h2><table><tr><th>Emulador</th><th>Datos</th><th>Último respaldo</th></tr>${emus.map((e) => `<tr><td>${esc(e.name)}</td><td>${e.installed ? (e.size ? fmtSize(e.size) : "—") : `<span class="muted">No instalado</span>`}</td><td>${e.lastBackup ? ago(e.lastBackup) : "—"}</td></tr>`).join("")}</table></div>` : ""}
    <div class="box"><h2>Solo en la tablet (${c.tabletOnly.length})</h2>
      ${c.tabletOnly.length ? `<table><tr><th>Juego</th><th></th><th>Tamaño</th></tr>${c.tabletOnly.map((g) => `<tr><td>${esc(g.title)}</td><td>${esc(sysInfo(g.system).short)}</td><td>${fmtSize(g.size)}</td></tr>`).join("")}</table>` : `<p class="muted">Todo lo de la tablet también está en el PC.</p>`}</div>
    <div class="box"><h2>BIOS</h2>
      <p>${c.biosPresent} en la tablet${c.biosMissing.length ? ` · faltan ${c.biosMissing.length}: la app las puede copiar desde Ajustes → BIOS.` : " · no falta ninguna."}</p>
      ${c.biosMissing.length ? `<ul class="files">${c.biosMissing.map((b) => `<li><span>${esc(b.path)}</span><span>${fmtSize(b.size)}</span></li>`).join("")}</ul>` : ""}</div>`;
  el.querySelectorAll("[data-unq]").forEach((b) => b.addEventListener("click", async () => {
    S.queue = await api("/api/queue?id=" + b.dataset.unq, { method: "DELETE" });
    renderTablet();
  }));
}

// ---- Partidas ----
const emuNames = { retroarch: "RetroArch", dolphin: "Dolphin", armsx2: "ARMSX2", eden: "Eden", vita3k: "Vita3K", watermelonds: "WatermelonDS", azahar: "Azahar" };

async function loadSaves() {
  S.saves = await api("/api/saves");
  renderSaves();
}
function renderSaves() {
  const el = $("#savesPage");
  if (!S.saves.length) {
    el.innerHTML = `<div class="box"><h2>Sin respaldos todavía</h2><p>En la tablet, abre RomSync → <b>Partidas</b> y toca <b>Respaldar todo</b>. También puede respaldar sola cada día cuando esté en tu WiFi.</p></div>`;
    return;
  }
  const groups = new Map();
  for (const b of S.saves) {
    const k = b.device + "/" + b.emulator;
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(b);
  }
  el.innerHTML = [...groups.entries()].map(([k, list]) => {
    const [dev, emu] = k.split("/");
    return `<div class="box"><div class="row"><h2 style="flex:1">${esc(emuNames[emu] || emu)}</h2><span class="muted small">${esc(dev)} · ${list.length} respaldo(s)</span></div>
      <table><tr><th>Fecha</th><th>Archivos</th><th>Tamaño</th><th></th></tr>${list.map((b) => `<tr>
        <td>${fmtDate(b.at)}${b.auto ? ` <span class="pill">auto</span>` : ""}${b.keep ? ` <span class="pill">conservar</span>` : ""}</td>
        <td>${b.files}</td><td>${fmtSize(b.size)}</td>
        <td class="row" style="justify-content:flex-end">
          <a class="ghost" href="/api/saves/download?device=${encodeURIComponent(b.device)}&emulator=${encodeURIComponent(b.emulator)}&id=${b.id}">Descargar</a>
          <button class="ghost" data-keep="${b.keep ? 0 : 1}" data-d="${esc(b.device)}" data-e="${esc(b.emulator)}" data-id="${b.id}">${b.keep ? "No conservar" : "Conservar"}</button>
          <button class="danger-btn" data-del data-d="${esc(b.device)}" data-e="${esc(b.emulator)}" data-id="${b.id}">Borrar</button>
        </td></tr>`).join("")}</table></div>`;
  }).join("") + `<p class="muted small">Se guardan los últimos respaldos de cada emulador (Ajustes). Los marcados “Conservar” no se borran solos.</p>`;
  el.querySelectorAll("[data-keep]").forEach((b) => b.addEventListener("click", async () => {
    await api(`/api/saves/keep?device=${encodeURIComponent(b.dataset.d)}&emulator=${encodeURIComponent(b.dataset.e)}&id=${b.dataset.id}&keep=${b.dataset.keep}`, { method: "POST" });
    loadSaves();
  }));
  el.querySelectorAll("[data-del]").forEach((b) => b.addEventListener("click", async () => {
    if (!confirm("¿Borrar este respaldo del PC? No se puede deshacer.")) return;
    await api(`/api/saves/delete?device=${encodeURIComponent(b.dataset.d)}&emulator=${encodeURIComponent(b.dataset.e)}&id=${b.dataset.id}`, { method: "POST" });
    loadSaves();
  }));
}

// ---- Ajustes ----
async function renderSettings() {
  const el = $("#settingsPage");
  if (!S.info?.local) {
    el.innerHTML = `<div class="box"><h2>Ajustes</h2><p>Los ajustes solo se cambian desde el mismo PC donde corre RomSync (http://localhost:${location.port || 8642}).</p></div>`;
    return;
  }
  const { config: c, biosResolved, backupResolved } = await api("/api/config");
  const field = (k, label, ph, browse) => `<label for="f-${k}">${label}</label>
    <div class="inline"><input id="f-${k}" value="${esc(c[k] ?? "")}" placeholder="${esc(ph)}">${browse ? `<button class="ghost" data-browse="${k}">Elegir…</button>` : ""}</div>`;
  el.innerHTML = `
    <div class="box"><h2>Conectar la tablet</h2>
      <p>En la app de la tablet: <b>Ajustes → Buscar PC</b>. Si no lo encuentra, escribe la dirección a mano.</p>
      <div class="row"><span class="code">${esc(c.code)}</span><span class="muted">${(S.info.addresses || []).map((a) => esc(a) + ":" + c.port).join(" · ")}</span></div></div>
    <div class="box"><h2>Carpetas</h2>
      <div class="kv">
        ${field("romsDir", "ROMs (una carpeta por plataforma)", "D:\\Emulacion\\ROMs", true)}
        ${field("biosDir", "BIOS", biosResolved, true)}
        ${field("mediaDir", "Carátulas de ES-DE (opcional)", "…\\ES-DE\\downloaded_media", true)}
        ${field("backupDir", "Respaldos de partidas", backupResolved, true)}
      </div>
      <div id="browser"></div></div>
    <div class="box"><h2>Carátulas</h2>
      <div class="kv">${field("steamGridDbKey", "Clave de la API de SteamGridDB", "pega aquí tu clave")}</div>
      <p class="muted small">Gratis: inicia sesión en <a href="https://www.steamgriddb.com/profile/preferences/api" target="_blank" rel="noopener">steamgriddb.com → Preferencias → API</a> y copia la clave. Las imágenes quedan guardadas en el PC; la tablet no necesita internet.</p></div>
    <div class="box"><h2>Otros</h2>
      <div class="kv">
        ${field("name", "Nombre de este PC", "PC gamer")}
        <label for="f-keepBackups">Respaldos por emulador</label><input id="f-keepBackups" type="number" min="1" max="500" value="${c.keepBackups}">
        <label for="f-port">Puerto</label><input id="f-port" type="number" min="1024" max="65535" value="${c.port}">
      </div></div>
    <div class="row"><button class="primary" id="saveCfg">Guardar</button><span class="muted small" id="cfgMsg"></span></div>`;
  el.querySelectorAll("[data-browse]").forEach((b) => b.addEventListener("click", () => browse(b.dataset.browse, $("#f-" + b.dataset.browse).value)));
  $("#saveCfg").addEventListener("click", async () => {
    const v = (k) => $("#f-" + k).value.trim();
    const body = { ...c, romsDir: v("romsDir"), biosDir: v("biosDir"), mediaDir: v("mediaDir"), backupDir: v("backupDir"),
      steamGridDbKey: v("steamGridDbKey"), name: v("name"), keepBackups: +v("keepBackups"), port: +v("port") };
    try {
      const r = await api("/api/config", { method: "POST", body: JSON.stringify(body) });
      $("#cfgMsg").textContent = r.restart ? "Guardado. Cierra y abre RomSync para usar el puerto nuevo." : "Guardado. Leyendo la biblioteca…";
      setTimeout(loadLibrary, 2000);
    } catch (e) { $("#cfgMsg").textContent = e.message; }
  });
}

async function browse(key, start) {
  const box = $("#browser");
  const show = async (dir) => {
    try {
      const r = await api("/api/browse?dir=" + encodeURIComponent(dir || ""));
      box.innerHTML = `<h3>${esc(r.dir || "Unidades")}</h3><div class="browser">
        ${r.dir ? `<button data-pick="${esc(r.dir)}"><b>✓ Usar esta carpeta</b></button>` : ""}
        ${r.parent || r.dir ? `<button data-go="${esc(r.parent || "")}">↑ Subir</button>` : ""}
        ${r.entries.map((e) => `<button data-go="${esc(e.path)}">📁 ${esc(e.name)}</button>`).join("")}</div>`;
      box.querySelectorAll("[data-go]").forEach((b) => b.addEventListener("click", () => show(b.dataset.go)));
      box.querySelectorAll("[data-pick]").forEach((b) => b.addEventListener("click", () => { $("#f-" + key).value = b.dataset.pick; box.innerHTML = ""; }));
    } catch (e) { box.innerHTML = `<p class="err">${esc(e.message)}</p>`; }
  };
  show(start);
}

// ---- Carga ----
async function loadLibrary() {
  try {
    const [lib, devices, queue] = await Promise.all([api("/api/library"), api("/api/devices"), api("/api/queue")]);
    S.lib = lib;
    S.devices = devices || [];
    S.queue = queue || [];
  } catch (e) {
    if (!S.lib) S.lib = { error: e.message, games: [], systems: [] };
  }
  renderSystems();
  renderGrid();
  renderPill();
  if (S.lib?.scanning) setTimeout(loadLibrary, 2500);
}

function renderPill() {
  const d = device();
  const el = $("#storagePill");
  if (!d) { el.hidden = true; return; }
  const pct = d.total ? ((d.total - d.free) / d.total) * 100 : 0;
  el.hidden = false;
  el.innerHTML = `<span>Tablet</span><div class="bar"><span class="${pct > 92 ? "danger" : pct > 80 ? "warn" : ""}" style="width:${pct}%"></span></div><span>${fmtSize(d.free)} libres</span>`;
  el.onclick = () => setTab("tablet");
  el.style.cursor = "pointer";
}

async function boot() {
  try { S.info = await api("/api/info"); } catch { return; }
  await loadLibrary();
  const tab = location.hash.slice(1);
  if (["tablet", "saves", "settings"].includes(tab)) setTab(tab);
  else if (!S.info.configured && S.info.local) setTab("settings");
  // La tablet puede conectarse mientras la página está abierta.
  setInterval(async () => {
    try {
      const devices = await api("/api/devices");
      if (JSON.stringify(devices?.map((d) => d.at)) !== JSON.stringify(S.devices.map((d) => d.at))) {
        S.devices = devices;
        renderGrid();
        renderPill();
      }
    } catch { /* sin conexión */ }
  }, 30000);
}
boot();
