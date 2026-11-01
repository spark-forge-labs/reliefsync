/* ReliefSync web – Agent Operations Room */
(() => {
  const API = location.origin;
  const $ = (s) => document.querySelector(s);
  const S = { incidents: new Map(), volunteers: [], resources: [], hazards: [], messages: [], trace: [], pending: [], sel: null, cfg: {} };
  const toast = (m) => { const t = $("#toast"); t.textContent = m; t.classList.add("show"); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove("show"), 2600); };
  const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  const ago = (ts) => { const s = Math.max(0, Date.now() / 1000 - ts); return s < 60 ? `${s | 0}s ago` : s < 3600 ? `${(s / 60) | 0}m ago` : `${(s / 3600) | 0}h ago`; };

  /* ---------------- Map ---------------- */
  const map = L.map("map", { zoomControl: true, attributionControl: true }).setView([37.7768, -122.4192], 15);
  let tiles = null;
  async function loadTiles() {
    let c = { url: "https://tile.openstreetmap.org/{z}/{x}/{y}.png", subdomains: "", dark: false,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors' };
    try { c = await (await fetch(`${API}/api/map`)).json(); } catch {}
    if (tiles) map.removeLayer(tiles);
    tiles = L.tileLayer(c.url, { maxZoom: 19, subdomains: c.subdomains || "abc", crossOrigin: false, referrerPolicy: "strict-origin-when-cross-origin", attribution: c.attribution, tileSize: 256 }).addTo(map);
    tiles.bringToBack();
    document.getElementById("map").classList.toggle("lightmap", c.dark === false);
  }
  loadTiles();
  const layers = { hz: L.layerGroup().addTo(map), res: L.layerGroup().addTo(map), vol: L.layerGroup().addTo(map), inc: L.layerGroup().addTo(map), route: L.layerGroup().addTo(map) };
  const volMarkers = new Map();
  const icon = (cls, html = "", size = 26) => L.divIcon({ className: "", html: `<div class="mk ${cls}" style="width:${size}px;height:${size}px">${html}</div>`, iconSize: [size, size], iconAnchor: [size / 2, size / 2] });
  const TICON = { FOOT: "🚶", BICYCLE: "🚲", CAR: "🚗", BOAT: "🚤", "4WD": "🛻" };

  function drawStatic() {
    layers.hz.clearLayers(); layers.res.clearLayers();
    S.hazards.forEach((h) => {
      L.polygon(h.polygon, { color: "#ff4d5e", weight: 2, fillColor: "#ff4d5e", fillOpacity: 0.18, dashArray: "6 6" })
        .bindTooltip(`⚠ ${esc(h.name)}`, { sticky: true }).addTo(layers.hz);
    });
    const byProv = {};
    S.resources.forEach((r) => (byProv[r.provider_name] ??= { r, items: [] }).items.push(r));
    Object.values(byProv).forEach(({ r, items }) => {
      L.marker([r.latitude, r.longitude], { icon: icon("res", "▣", 24) })
        .bindPopup(`<b>${esc(r.provider_name)}</b><br>${items.map((i) => `${esc(i.item_name)} · <b>${i.quantity}</b>`).join("<br>")}`)
        .addTo(layers.res);
    });
    drawVolunteers();
  }
  /* Top-down vehicle sprites (rotate with heading) */
  const VCOL = { "4WD": "#16a34a", CAR: "#2563eb", BOAT: "#0891b2", BICYCLE: "#9333ea", FOOT: "#ea580c" };
  function vehicleSvg(t, color) {
    if (t === "BOAT") return `<svg viewBox="0 0 40 40"><path d="M20 3 L29 17 L28 33 Q20 37 12 33 L11 17 Z" fill="${color}" stroke="#fff" stroke-width="2"/><rect x="15" y="17" width="10" height="9" rx="2" fill="#fff" opacity=".85"/></svg>`;
    if (t === "BICYCLE") return `<svg viewBox="0 0 40 40"><rect x="17.5" y="5" width="5" height="12" rx="2.5" fill="#222"/><rect x="17.5" y="23" width="5" height="12" rx="2.5" fill="#222"/><rect x="18.5" y="9" width="3" height="22" fill="${color}"/><circle cx="20" cy="20" r="5.5" fill="${color}" stroke="#fff" stroke-width="2"/></svg>`;
    if (t === "FOOT") return `<svg viewBox="0 0 40 40"><circle cx="20" cy="22" r="9" fill="${color}" stroke="#fff" stroke-width="2.5"/><path d="M20 4 L26 13 L14 13 Z" fill="${color}" stroke="#fff" stroke-width="1.5"/></svg>`;
    const truck = t === "4WD";
    return `<svg viewBox="0 0 40 40"><rect x="10" y="${truck ? 3 : 5}" width="20" height="${truck ? 34 : 30}" rx="6" fill="${color}" stroke="#fff" stroke-width="2"/>
      <rect x="12.5" y="${truck ? 9 : 10}" width="15" height="7" rx="2" fill="#0b1220" opacity=".85"/>
      <rect x="13" y="${truck ? 19 : 24}" width="14" height="${truck ? 14 : 6}" rx="1.5" fill="${truck ? "#fff" : "#0b1220"}" opacity="${truck ? ".25" : ".7"}"/>
      <rect x="11" y="${truck ? 4 : 6}" width="4" height="2.5" rx="1" fill="#fde68a"/><rect x="25" y="${truck ? 4 : 6}" width="4" height="2.5" rx="1" fill="#fde68a"/></svg>`;
  }
  function vehicleIcon(v, heading, busy) {
    const c = VCOL[v.transport_type] || "#16a34a";
    return L.divIcon({ className: "veh-wrap", iconSize: [44, 44], iconAnchor: [22, 22],
      html: `<div class="veh ${busy ? "busy" : ""}"><div class="veh-rot" style="transform:rotate(${heading || 0}deg)">${vehicleSvg(v.transport_type, c)}</div>
             <span class="veh-tag">${esc(v.name.split(" ")[0])}</span></div>` });
  }
  const anim = new Map(); // vid -> {from, to, t0, dur, heading}
  function drawVolunteers() {
    S.volunteers.forEach((v) => {
      const busy = !!v.assigned_incident_id;
      let m = volMarkers.get(v.id);
      const h = anim.get(v.id)?.heading || 0;
      if (!m) { m = L.marker([v.current_latitude, v.current_longitude], { icon: vehicleIcon(v, h, busy), zIndexOffset: 800 }).addTo(layers.vol); volMarkers.set(v.id, m); m._busy = busy; }
      else { if (!anim.has(v.id)) m.setLatLng([v.current_latitude, v.current_longitude]); if (m._busy !== busy) { m.setIcon(vehicleIcon(v, h, busy)); m._busy = busy; } }
      m.bindTooltip(`${esc(v.name)} · ${v.transport_type}${busy ? " · on mission" : ""}`);
    });
  }
  // 60 fps interpolation between server position updates
  let follow = true;
  function frame(now) {
    anim.forEach((a, vid) => {
      const m = volMarkers.get(vid); if (!m) return;
      const k = Math.min(1, (now - a.t0) / a.dur);
      const lat = a.from[0] + (a.to[0] - a.from[0]) * k, lon = a.from[1] + (a.to[1] - a.from[1]) * k;
      m.setLatLng([lat, lon]);
      const rot = m.getElement()?.querySelector(".veh-rot");
      if (rot) { a.cur = a.cur ?? a.heading; let d = ((a.heading - a.cur + 540) % 360) - 180; a.cur = (a.cur + d * 0.18 + 360) % 360; rot.style.transform = `rotate(${a.cur}deg)`; }
      if (follow && a.incident === S.sel && a.live) map.panTo([lat, lon], { animate: false });
    });
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
  setTimeout(() => { const b = $("#btnFollow"); if (b) b.onclick = () => { follow = !follow; b.classList.toggle("on", follow); }; });
  map.on("dragstart", () => { follow = false; $("#btnFollow")?.classList.remove("on"); });
  const routeLines = new Map();
  function drawIncidents() {
    layers.inc.clearLayers(); layers.route.clearLayers(); routeLines.clear();
    S.incidents.forEach((i) => {
      if (i.latitude == null) return;
      L.marker([i.latitude, i.longitude], { icon: icon(`inc ${i.urgency || "P2"} ${i.status}`, "!", 24), zIndexOffset: 1000 })
        .on("click", () => select(i.id)).bindTooltip(`${i.urgency || ""} ${esc(i.category || "")} · ${esc(i.status)}`).addTo(layers.inc);
      if (i.route?.path && !["RESOLVED"].includes(i.status) && (S.sel === i.id || ["EN_ROUTE", "ACCEPTED", "DISPATCHING"].includes(i.status))) {
        const color = i.status === "DISPATCHING" ? "#f59e0b" : "#2563eb";
        L.polyline(i.route.path, { color: "#0b1220", weight: 10, opacity: 0.25, lineCap: "round", lineJoin: "round" }).addTo(layers.route);
        const remain = L.polyline(i.route.path, { color, weight: 6, opacity: 0.95, lineCap: "round", lineJoin: "round" }).addTo(layers.route);
        const done = L.polyline([], { color: "#94a3b8", weight: 6, opacity: 0.9, lineCap: "round", lineJoin: "round" }).addTo(layers.route);
        routeLines.set(i.id, { remain, done, path: i.route.path });
        if (i.route.pickup_idx != null && i.resource) {
          const p = i.route.path[i.route.pickup_idx];
          L.marker(p, { icon: L.divIcon({ className: "", html: `<div class="pickup">PICKUP</div>`, iconSize: [60, 20], iconAnchor: [30, 30] }) }).addTo(layers.route);
        }
      }
    });
  }

  /* ---------------- Incidents panel ---------------- */
  const FLOW = ["RECEIVED", "TRANSCRIBED", "TRIAGED", "DISPATCHING", "EN_ROUTE", "ON_SCENE", "RESOLVED"];
  const LABEL = { RECEIVED: "Received", TRANSCRIBED: "Transcribed", TRIAGED: "Triaged", DISPATCHING: "Dispatching", ACCEPTED: "Accepted", EN_ROUTE: "Volunteer en route", ON_SCENE: "On scene", RESOLVED: "Resolved", ESCALATED_MUNICIPAL: "Municipal handoff", NEEDS_LOCATION: "Needs location" };
  function renderIncidents() {
    const list = [...S.incidents.values()].sort((a, b) => b.created_at - a.created_at);
    $("#incidents").innerHTML = list.slice(0, 12).map((i) => {
      let idx = FLOW.indexOf(i.status === "ACCEPTED" ? "EN_ROUTE" : i.status);
      const steps = FLOW.slice(1).map((_, k) => `<i class="${k + 1 < idx ? "on" : k + 1 === idx ? (i.status === "RESOLVED" ? "on" : "now") : ""}"></i>`).join("");
      const eta = i.status === "EN_ROUTE" && i.eta_min != null ? ` · ETA ${i._eta ?? i.eta_min} min` : "";
      return `<div class="icard ${i.urgency || ""} ${S.sel === i.id ? "sel" : ""}" data-id="${i.id}">
        <div class="top"><span class="t">${esc((i.category || "Assessing…").replace("_", " / "))}</span>${i.urgency ? `<span class="badge ${i.urgency}">${i.urgency}</span>` : ""}</div>
        <div class="d">${esc(i.transcription || "…")}</div>
        <div class="steps">${steps}</div>
        <div class="s"><span>${esc(LABEL[i.status] || i.status)}${eta}</span><span>${ago(i.created_at)}</span></div>
        ${i.volunteer ? `<div class="s"><span>🧑‍🚒 ${esc(i.volunteer.name)} · ${i.volunteer.transport}</span><span>${esc(i.confirm_token || "")}</span></div>` : ""}
        ${["EN_ROUTE", "ON_SCENE", "ACCEPTED"].includes(i.status) ? `<button class="resolve" data-resolve="${i.id}">Mark delivered & scrub PII</button>` : ""}
      </div>`;
    }).join("") || "";
    const active = list.filter((i) => !["RESOLVED", "ESCALATED_MUNICIPAL"].includes(i.status)).length;
    const avail = S.volunteers.filter((v) => v.is_active && !v.assigned_incident_id).length;
    const p0 = list.filter((i) => i.urgency === "P0" && i.status !== "RESOLVED").length;
    $("#stats").innerHTML = [["Active incidents", active], ["P0 critical", p0], ["Volunteers ready", `${avail}/${S.volunteers.length}`], ["Supplies", S.resources.reduce((a, r) => a + r.quantity, 0)]]
      .map(([k, v]) => `<div class="stat"><b>${v}</b><span>${k}</span></div>`).join("");
  }
  $("#incidents").addEventListener("click", async (e) => {
    const r = e.target.closest("[data-resolve]");
    if (r && SL) { e.stopPropagation(); slResolve(); return; }
    if (r) { e.stopPropagation(); await fetch(`${API}/api/incidents/${r.dataset.resolve}/resolve`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" }); toast("Resolved · PII scrubbed"); return; }
    const c = e.target.closest(".icard"); if (c) select(c.dataset.id);
  });
  function select(id) {
    S.sel = id; const i = S.incidents.get(id);
    if (i?.latitude != null) map.flyTo([i.latitude, i.longitude], 16, { duration: 0.8 });
    renderIncidents(); drawIncidents(); renderTrace();
  }

  /* ---------------- Trace ---------------- */
  const PIPE = [["AudioTranscribed", "Transcribe"], ["EntityExtracted", "Triage"], ["GeoResolved", "Geo"], ["SupplyLocated", "Supply"], ["VolunteersRanked", "Rank"], ["RouteEvaluated", "Route"], ["DispatchSent", "Dispatch"], ["Accepted", "Accepted"], ["EnRoute", "En route"]];
  function renderTrace() {
    const id = S.sel || [...S.incidents.values()].sort((a, b) => b.created_at - a.created_at)[0]?.id;
    const evs = S.trace.filter((e) => !id || e.incident_id === id);
    const names = new Set(evs.map((e) => e.name));
    let activeSet = false;
    $("#pipeline").innerHTML = PIPE.map(([k, l]) => {
      let c = names.has(k) ? "done" : "";
      if (!c && !activeSet && evs.length) { c = "active"; activeSet = true; }
      return `<span class="pipe ${c}">${l}</span>`;
    }).join("");
    const ol = $("#trace");
    if (!evs.length) { ol.innerHTML = `<li class="empty">Press <b>Record SOS</b> or <b>Run demo scenario</b> to watch the agents think, call tools and act in real time.</li>`; return; }
    const inc = S.incidents.get(id);
    ol.innerHTML = `<li class="ev sep">Incident ${id.slice(0, 8)} · ${esc(inc?.source || "")}</li>` + evs.map((e) => `
      <li class="ev ${e.kind}">
        <div class="h"><span class="kind">${e.kind.replace("_", " ")}</span><span class="nm">${esc(e.name)}</span><span class="ag">${esc(e.agent)} · ${new Date(e.ts * 1000).toLocaleTimeString()}</span></div>
        <div class="dt">${esc(e.detail)}</div>
      </li>`).join("");
    const p = ol.parentElement; p.scrollTop = p.scrollHeight;
  }

  /* ---------------- Volunteer phone ---------------- */
  let phoneVol = null, offerTimer = null;
  function renderPhone() {
    const sel = $("#volSelect");
    const ids = S.volunteers.map((v) => v.id).join();
    if (sel.dataset.ids !== ids) {
      sel.dataset.ids = ids;
      sel.innerHTML = S.volunteers.map((v) => `<option value="${v.id}">📱 ${esc(v.name)} · ${v.transport_type}</option>`).join("");
      if (phoneVol && S.volunteers.some((v) => v.id === phoneVol)) sel.value = phoneVol;
    }
    phoneVol = sel.value;
    const v = S.volunteers.find((x) => x.id === phoneVol);
    if (!v) return;
    const msgs = S.messages.filter((m) => m.phone === v.phone_number);
    const th = $("#thread");
    th.innerHTML = msgs.map((m) => `<div class="bub ${m.direction === "out" ? "out" : "in"}">${esc(m.body)}<small>${new Date(m.ts * 1000).toLocaleTimeString()} · ${m.channel}</small></div>`).join("") || `<div class="empty">No messages yet for ${esc(v.name)}.</div>`;
    th.scrollTop = th.scrollHeight;
    const pend = S.pending.find((p) => p.volunteer_id === v.id);
    $("#offer").hidden = !pend;
  }
  $("#volSelect").addEventListener("change", (e) => { phoneVol = e.target.value; renderPhone(); });
  async function answer(a) {
    if (SL) return slRespond(a);
    await fetch(`${API}/api/dispatch/respond`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ volunteer_id: phoneVol, answer: a }) });
    S.pending = S.pending.filter((p) => p.volunteer_id !== phoneVol);
    renderPhone();
  }
  $("#btnYes").onclick = () => answer("ACCEPT");
  $("#btnNo").onclick = () => answer("DECLINE");
  function startCountdown(sec) {
    const bar = $("#cdBar"); const t0 = Date.now();
    clearInterval(offerTimer);
    offerTimer = setInterval(() => { const f = 1 - (Date.now() - t0) / (sec * 1000); bar.style.width = `${Math.max(0, f) * 100}%`; if (f <= 0) clearInterval(offerTimer); }, 200);
  }

  /* ---------------- Live connection ---------------- */
  let ws, retry = 0;
  let wsFails = 0, es = null;
  function connectSSE() {
    const c = $("#conn"); if (es) return;
    es = new EventSource(`${API}/api/events`);
    es.onopen = () => { c.className = "conn on"; c.querySelector("em").textContent = "live (SSE)"; flushQueue(); };
    es.onmessage = (m) => { try { handle(JSON.parse(m.data)); } catch {} };
    es.onerror = () => { c.className = "conn off"; c.querySelector("em").textContent = "reconnecting"; };
  }
  function connect() {
    const c = $("#conn");
    if (wsFails >= 2) return connectSSE();
    ws = new WebSocket(API.replace(/^http/, "ws") + "/ws");
    ws.onopen = () => { retry = 0; c.className = "conn on"; c.querySelector("em").textContent = "live"; flushQueue(); };
    ws.onerror = () => { wsFails++; };
    ws.onclose = () => { if (wsFails >= 2) return connectSSE(); c.className = "conn off"; c.querySelector("em").textContent = "offline – retrying"; setTimeout(connect, Math.min(8000, 800 * 2 ** retry++)); };
    ws.onmessage = (m) => handle(JSON.parse(m.data));
    clearInterval(connect._ping); connect._ping = setInterval(() => ws.readyState === 1 && ws.send("ping"), 20000);
  }
  function applySnapshot(st) {
    S.incidents = new Map(st.incidents.map((i) => [i.id, i]));
    S.volunteers = st.volunteers; S.resources = st.resources; S.hazards = st.hazards; S.messages = st.messages; S.pending = st.pending; S.cfg = st.config; renderMode(st.config);
    volMarkers.forEach((m) => layers.vol.removeLayer(m)); volMarkers.clear();
    drawStatic(); drawIncidents(); renderIncidents(); renderPhone(); renderTrace();
  }
  function handle(ev) {
    switch (ev.type) {
      case "snapshot":
        if (SL && ev.state.incidents && ev.state.incidents.length === 0 && S.incidents.size) break; // ignore other instances
        if (ev.trace) S.trace = ev.trace; applySnapshot(ev.state);
        if (ev.state.config?.serverless && !SL) enableServerless();
        break;
      case "trace": S.trace.push(ev.event); if (S.trace.length > 1500) S.trace.splice(0, 500);
        if (!S.sel || ev.event.name === "AudioTranscribed" && S.trace.filter((e) => e.incident_id === ev.event.incident_id).length < 3) S.sel = ev.event.incident_id;
        renderTrace(); break;
      case "incident": {
        const prev = S.incidents.get(ev.incident.id);
        S.incidents.set(ev.incident.id, { ...ev.incident, _eta: prev?._eta });
        if (!prev) { S.sel = ev.incident.id; toast("🚨 New distress signal received"); }
        if (ev.incident.status === "EN_ROUTE" && prev?.status !== "EN_ROUTE") showBanner(`✓ Dispatched – Volunteer En Route (ETA ${ev.incident.eta_min} mins)`);
        if (ev.incident.latitude != null && (!prev || prev.latitude == null)) map.flyTo([ev.incident.latitude, ev.incident.longitude], 15.5, { duration: 1 });
        refreshVolunteers(); drawIncidents(); renderIncidents(); break;
      }
      case "sms": S.messages.push(ev.message); renderPhone(); break;
      case "offer": {
        S.pending.push({ volunteer_id: ev.volunteer_id, incident_id: ev.incident_id });
        phoneVol = ev.volunteer_id; $("#volSelect").value = phoneVol; renderPhone();
        const d = $(".device"); d.classList.remove("buzz"); void d.offsetWidth; d.classList.add("buzz");
        if (navigator.vibrate) navigator.vibrate([200, 100, 200]);
        startCountdown(ev.timeout_s); break;
      }
      case "volunteer_pos": {
        const v = S.volunteers.find((x) => x.id === ev.volunteer_id);
        if (v) {
          const m = volMarkers.get(v.id); const prev = anim.get(v.id);
          const from = m ? [m.getLatLng().lat, m.getLatLng().lng] : [ev.lat, ev.lon];
          anim.set(v.id, { from, to: [ev.lat, ev.lon], t0: performance.now(), dur: (ev.tick || 0.2) * 1000 * 1.05, heading: ev.heading ?? prev?.heading ?? 0, cur: prev?.cur, incident: ev.incident_id, live: true });
          v.current_latitude = ev.lat; v.current_longitude = ev.lon;
          if (!v.assigned_incident_id) { v.assigned_incident_id = ev.incident_id; m && m.setIcon(vehicleIcon(v, ev.heading, true)); m && (m._busy = true); }
          const rl = routeLines.get(ev.incident_id);
          if (rl && ev.seg != null) { rl.done.setLatLngs([...rl.path.slice(0, ev.seg + 1), [ev.lat, ev.lon]]); rl.remain.setLatLngs([[ev.lat, ev.lon], ...rl.path.slice(ev.seg + 1)]); }
          if (ev.progress >= 1) { const a = anim.get(v.id); if (a) a.live = false; }
        }
        const i = S.incidents.get(ev.incident_id); if (i) { i._eta = ev.eta_min; if (Math.random() < 0.2) renderIncidents(); }
        break;
      }
    }
  }
  async function refreshVolunteers() {
    if (SL) { drawVolunteers(); renderPhone(); renderIncidents(); return; }
    try { const st = await (await fetch(`${API}/api/state`)).json(); S.volunteers = st.volunteers; S.pending = st.pending; S.resources = st.resources; drawVolunteers(); renderPhone(); renderIncidents(); } catch {}
  }
  function showBanner(t) { const b = $("#banner"); b.textContent = t; b.hidden = false; clearTimeout(b._h); b._h = setTimeout(() => (b.hidden = true), 7000); }

  /* ---------------- Offline queue (IndexedDB) ---------------- */
  const idb = new Promise((res) => { const r = indexedDB.open("reliefsync", 1); r.onupgradeneeded = () => r.result.createObjectStore("queue", { keyPath: "id" }); r.onsuccess = () => res(r.result); r.onerror = () => res(null); });
  async function qAll() { const d = await idb; if (!d) return []; return new Promise((r) => { const t = d.transaction("queue").objectStore("queue").getAll(); t.onsuccess = () => r(t.result); }); }
  async function qPut(item) { const d = await idb; if (d) d.transaction("queue", "readwrite").objectStore("queue").put(item); renderQueue(); }
  async function qDel(id) { const d = await idb; if (d) d.transaction("queue", "readwrite").objectStore("queue").delete(id); }
  async function renderQueue() { const all = await qAll(); $("#queue").innerHTML = all.map((q) => `<div class="qitem">⏳ Queued offline · ${q.audio ? "voice note" : "text"} · ${new Date(q.created).toLocaleTimeString()}</div>`).join(""); }
  async function flushQueue() {
    for (const item of await qAll()) { if (await post(item)) { await qDel(item.id); toast("Queued SOS synced ✓"); } }
    renderQueue();
  }
  window.addEventListener("online", flushQueue);

  /* ---------------- SOS submit ---------------- */
  let gps = null;
  navigator.geolocation?.getCurrentPosition((p) => (gps = [p.coords.latitude, p.coords.longitude]), () => {}, { timeout: 4000 });
  async function post(item) {
    const fd = new FormData();
    if (item.text) fd.append("transcript", item.text);
    if (item.audio) fd.append("audio", item.audio, item.ext ? `sos.${item.ext}` : "sos.webm");
    if (item.lat != null) { fd.append("lat", item.lat); fd.append("lon", item.lon); }
    fd.append("client_id", item.id);
    try { const r = await fetch(`${API}/api/sos`, { method: "POST", body: fd }); return r.ok; } catch { return false; }
  }
  async function submit(text, audio, ext) {
    if (!text && !audio) return toast("Say or type what is happening first");
    // Use device GPS only when no landmark is likely in the text (demo stays deterministic).
    const item = { id: crypto.randomUUID(), text, audio, ext, created: Date.now(), lat: null, lon: null };
    if (gps && !/(street|st\b|avenue|ave|near|church|park|school|station|bridge)/i.test(text || "")) { item.lat = gps[0]; item.lon = gps[1]; }
    if (SL && text) { $("#sosText").value = ""; go("trace"); slRun(text, item.lat, item.lon); return; }
    if (navigator.onLine && (await post(item))) { toast("SOS sent – agents engaged"); $("#sosText").value = ""; go("trace"); }
    else { await qPut(item); toast("Offline – SOS queued and will auto-send"); }
  }
  $("#btnSend").onclick = () => submit($("#sosText").value.trim(), null);
  const DEMO = "Water is coming into our living room on 12th Street near St. Jude's church! My mother's oxygen concentrator battery is down to 10% and we have no power!";
  $("#btnSample").onclick = () => ($("#sosText").value = DEMO);
  $("#btnDemo").onclick = async () => { if (SL) { go("trace"); return slRun(DEMO); } await fetch(`${API}/api/demo/scenario`, { method: "POST" }); go("trace"); };
  $("#btnReset").onclick = async () => {
    await fetch(`${API}/api/demo/reset`, { method: "POST" }); S.trace = []; S.sel = null;
    if (SL) { slStop(); const st = await (await fetch(`${API}/api/state`)).json(); applySnapshot(st); }
    toast("Scenario reset");
  };
  $("#btnSms").onclick = async () => {
    const body = $("#smsBody").value.trim(); if (!body) return;
    const r = await (await fetch(`${API}/api/sms/simulate`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ phone: $("#smsFrom").value, body }) })).json();
    toast(r.reply.slice(0, 120)); $("#smsBody").value = "";
  };

  /* ---------------- Recorder (MediaRecorder + WebAudio + Web Speech) ---------------- */
  let rec = null, chunks = [], recog = null, t0 = 0, tick = null, actx = null, analyser = null, raf = null, stream = null;
  const MAX = 30;
  const canvas = $("#wave"), cx = canvas.getContext("2d");
  function drawWave() {
    const w = (canvas.width = canvas.clientWidth * devicePixelRatio), h = (canvas.height = 56 * devicePixelRatio);
    cx.clearRect(0, 0, w, h);
    const data = new Uint8Array(analyser ? analyser.frequencyBinCount : 64);
    analyser?.getByteFrequencyData(data);
    const bars = 48, bw = w / bars;
    for (let i = 0; i < bars; i++) {
      const v = analyser ? data[Math.floor((i / bars) * data.length * 0.6)] / 255 : 0.04 + 0.03 * Math.sin(Date.now() / 300 + i / 3);
      const bh = Math.max(3, v * h * 0.95);
      const g = cx.createLinearGradient(0, h / 2 - bh / 2, 0, h / 2 + bh / 2); g.addColorStop(0, "#ffb547"); g.addColorStop(1, "#ff4d5e");
      cx.fillStyle = g; cx.beginPath(); cx.roundRect(i * bw + bw * 0.2, h / 2 - bh / 2, bw * 0.6, bh, bw * 0.3); cx.fill();
    }
    raf = requestAnimationFrame(drawWave);
  }
  drawWave();
  async function startRec() {
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } }); }
    catch { return toast("Microphone unavailable – type your SOS instead"); }
    const mime = ["audio/webm;codecs=opus", "audio/mp4", "audio/webm"].find((m) => window.MediaRecorder?.isTypeSupported?.(m)) || "";
    rec = new MediaRecorder(stream, mime ? { mimeType: mime, audioBitsPerSecond: 24000 } : {});
    chunks = []; rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);
    rec.onstop = onStop; rec.start(250);
    actx = new AudioContext(); analyser = actx.createAnalyser(); analyser.fftSize = 256; actx.createMediaStreamSource(stream).connect(analyser);
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    $("#sosText").value = "";
    if (SR) {
      recog = new SR(); recog.continuous = true; recog.interimResults = true; recog.lang = navigator.language || "en-US";
      recog.onresult = (e) => { $("#sosText").value = [...e.results].map((r) => r[0].transcript).join(" "); };
      try { recog.start(); } catch {}
    }
    t0 = Date.now(); $("#btnRecord").classList.add("rec"); $("#recLabel").textContent = "STOP";
    tick = setInterval(() => { const s = (Date.now() - t0) / 1000; $("#recTimer").textContent = `00:${String(s | 0).padStart(2, "0")} / 00:30`; if (s >= MAX) stopRec(); }, 200);
    if (navigator.vibrate) navigator.vibrate(40);
  }
  function stopRec() {
    if (!rec) return; clearInterval(tick); rec.stop(); recog?.stop?.(); stream.getTracks().forEach((t) => t.stop());
    actx?.close(); analyser = null; $("#btnRecord").classList.remove("rec"); $("#recLabel").textContent = "SOS";
  }
  function onStop() {
    const blob = new Blob(chunks, { type: rec.mimeType || "audio/webm" }); rec = null;
    const ext = blob.type.includes("mp4") ? "m4a" : "webm";
    setTimeout(() => submit($("#sosText").value.trim(), blob, ext), 500); // let final ASR result land
  }
  $("#btnRecord").onclick = () => (rec ? stopRec() : startRec());

  /* ---------------- Serverless mode (e.g. Vercel) ----------------
     The server runs each step inside the request and returns its events; we replay them live
     here, carry the dispatch context between requests, run the reply timer and animate the drive. */
  let SL = false, slCtx = null, slIdx = null, slTimer = null, slDriveTimer = null;
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  function enableServerless() {
    SL = true;
    try { if (ws) { ws.onclose = null; ws.onmessage = null; ws.close(); } } catch {}
    try { if (es) { es.close(); } } catch {}
    const c = $("#conn"); c.className = "conn on"; c.querySelector("em").textContent = "live (serverless)";
  }
  async function slPost(path, body) {
    const r = await fetch(`${API}${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json();
  }
  async function slPlay(events) {
    for (const ev of events) { handle(ev); if (ev.type === "trace") await sleep(170); else if (ev.type === "incident") await sleep(60); }
  }
  function slStop() { clearTimeout(slTimer); clearInterval(slDriveTimer); slCtx = null; slIdx = null; S.pending = []; anim.clear(); }
  async function slRun(text, lat = null, lon = null) {
    slStop(); toast("SOS sent – agents engaged");
    try {
      const r = await slPost("/api/sl/run", { text, lat, lon });
      slCtx = r.ctx; slIdx = r.index; await slPlay(r.events); slArm();
    } catch (e) { toast("Agent run failed: " + e.message); }
  }
  function slArm() {
    clearTimeout(slTimer);
    if (slCtx && slIdx != null) slTimer = setTimeout(() => slRespond("TIMEOUT"), (S.cfg.dispatch_timeout_s || 45) * 1000);
  }
  async function slRespond(a) {
    if (!slCtx || slIdx == null) return;
    clearTimeout(slTimer); S.pending = []; renderPhone();
    try {
      const r = await slPost("/api/sl/respond", { ctx: slCtx, index: slIdx, answer: a });
      slCtx = r.ctx; slIdx = r.index;
      await slPlay(r.events);
      if (a === "ACCEPT" && r.route) { slIdx = null; slDrive(r.route, r.volunteer_id, slCtx.iid); } else slArm();
    } catch (e) { toast("Dispatch failed: " + e.message); }
  }
  async function slResolve() {
    if (!slCtx) return;
    clearInterval(slDriveTimer);
    try { const r = await slPost("/api/sl/resolve", { ctx: slCtx }); await slPlay(r.events); toast("Resolved · PII scrubbed"); } catch (e) { toast(e.message); }
    const v = S.volunteers.find((x) => x.assigned_incident_id === slCtx?.iid); if (v) v.assigned_incident_id = null;
    slCtx = null; drawVolunteers();
  }
  function bearing(a, b) { const r = Math.PI / 180, y = Math.sin((b[1] - a[1]) * r) * Math.cos(b[0] * r), x = Math.cos(a[0] * r) * Math.sin(b[0] * r) - Math.sin(a[0] * r) * Math.cos(b[0] * r) * Math.cos((b[1] - a[1]) * r); return (Math.atan2(y, x) / r + 360) % 360; }
  function slDrive(rt, vid, iid) {
    const P = rt.path || []; if (P.length < 2) return;
    const cum = [0]; for (let i = 1; i < P.length; i++) cum.push(cum[i - 1] + Math.hypot(P[i][0] - P[i - 1][0], (P[i][1] - P[i - 1][1]) * 0.79));
    const total = cum[cum.length - 1] || 1e-9, pick = rt.pickup_idx != null ? cum[rt.pickup_idx] : null;
    const dur = Math.max(10, S.cfg.demo_travel_s || 45) * 1000, tick = 200, t0 = Date.now(); let paused = 0, picked = pick == null, seg = 0;
    clearInterval(slDriveTimer);
    slDriveTimer = setInterval(() => {
      let el = Date.now() - t0 - paused, d = Math.min(total, total * el / dur);
      if (!picked && d >= pick) { d = pick; picked = true; paused += 1500; handle({ type: "trace", event: { seq: Date.now(), incident_id: iid, ts: Date.now() / 1000, agent: "Dispatch", kind: "RESULT", name: "PickedUp", detail: "Volunteer collected supplies at the pickup; heading to caller.", data: {} } }); }
      while (seg < P.length - 2 && cum[seg + 1] < d) seg++;
      const f = cum[seg + 1] === cum[seg] ? 0 : (d - cum[seg]) / (cum[seg + 1] - cum[seg]);
      const lat = P[seg][0] + (P[seg + 1][0] - P[seg][0]) * f, lon = P[seg][1] + (P[seg + 1][1] - P[seg][1]) * f;
      handle({ type: "volunteer_pos", volunteer_id: vid, incident_id: iid, lat, lon, heading: bearing(P[seg], P[seg + 1]), seg, tick: tick / 1000,
               progress: d / total, eta_min: Math.max(0, Math.ceil((rt.eta_min || 0) * (1 - d / total))) });
      if (d >= total) {
        clearInterval(slDriveTimer);
        const inc = S.incidents.get(iid); if (inc) { inc.status = "ON_SCENE"; handle({ type: "incident", incident: inc }); }
        handle({ type: "trace", event: { seq: Date.now() + 1, incident_id: iid, ts: Date.now() / 1000, agent: "Dispatch", kind: "RESULT", name: "Arrived", detail: "Volunteer on scene. Awaiting token verification.", data: {} } });
      }
    }, tick);
  }

  /* ---------------- Tabs (mobile) & nav deep-link ---------------- */
  function go(tab) {
    document.querySelectorAll(".tabs button").forEach((b) => b.classList.toggle("on", b.dataset.go === tab));
    document.querySelectorAll(".panel").forEach((p) => p.classList.toggle("on", p.dataset.tab === tab));
    if (tab === "map") setTimeout(() => map.invalidateSize(), 50);
  }
  $("#tabs").onclick = (e) => e.target.dataset.go && go(e.target.dataset.go);
  go("sos");
  function hashRoute() {
    const m = location.hash.match(/#nav\/([\w-]+)/);
    if (m) { fetch(`${API}/api/incidents/${m[1]}`).then((r) => r.json()).then((d) => { S.incidents.set(d.incident.id, d.incident); select(d.incident.id); go("map"); if (d.incident.route?.path) map.fitBounds(d.incident.route.path, { padding: [60, 60] }); showBanner(`Safe route · ${d.incident.route?.miles ?? "?"} mi · avoids ${(d.incident.route?.blocked || []).join(", ") || "no hazards"}`); }); }
  }
  window.addEventListener("hashchange", hashRoute); hashRoute();

  /* ---------------- Settings & API keys ---------------- */
  const TESTS = { AI: [["anthropic", "Test Claude"], ["openai", "Test Whisper"]], SMS: [["twilio", "Test Twilio"]], Maps: [["map", "Test map"]] };
  const OPTIONS = { MAP_PROVIDER: [["osm", "OpenStreetMap (no key)"], ["maptiler", "MapTiler (key)"], ["mapbox", "Mapbox (key)"], ["stadia", "Stadia Maps (key)"], ["custom", "Custom tile URL"]],
    GEOCODE_ONLINE: [["0", "Off – offline gazetteer"], ["1", "On – OSM Nominatim"]] };
  const GROUP_TITLE = { AI: "AI services", SMS: "SMS dispatch (Twilio)", Maps: "Maps", Server: "Server & demo tuning" };
  function renderMode(st) {
    const b = $("#modeBadge"); if (!st) return;
    b.textContent = st.mode === "live" ? "LIVE KEYS" : "DEMO MODE"; b.classList.toggle("live", st.mode === "live");
    b.title = `Triage: ${st.triage} · SMS: ${st.sms} · Speech: ${st.transcription}`;
  }
  async function openSettings() {
    const d = await (await fetch(`${API}/api/settings`)).json();
    renderMode(d.status);
    const st = d.status;
    $("#svcStatus").innerHTML = [["Triage", st.triage, st.triage === "claude"], ["Transcription", st.transcription, st.transcription === "whisper"],
      ["SMS", st.sms, st.sms === "twilio"], ["Map", st.map, true]]
      .map(([k, v, on]) => `<div><b>${k}</b><span class="${on ? "on" : "off"}">${esc(v)}</span></div>`).join("");
    const groups = {};
    d.fields.forEach((f) => (groups[f.group] ??= []).push(f));
    $("#setForm").innerHTML = Object.entries(groups).map(([g, fs]) => `
      <div class="grp"><h3><span>${GROUP_TITLE[g] || g}</span>
        <span>${(TESTS[g] || []).map(([s, l]) => `<button type="button" class="ghost small" data-test="${s}">${l}</button><span class="testres" id="tr-${s}"></span>`).join(" ")}</span></h3>
        ${fs.map((f) => `<div class="frow"><label for="f-${f.key}"><i class="${f.set ? "set" : ""}"></i>${esc(f.label)}</label><div>
          ${f.secret ? `<div class="pw"><input id="f-${f.key}" name="${f.key}" type="password" placeholder="${f.set ? esc(f.value) + " (saved)" : "not set"}" value=""><button type="button" data-clear="${f.key}">${f.set ? "clear" : ""}</button></div>`
            : OPTIONS[f.key] ? `<select id="f-${f.key}" name="${f.key}">${OPTIONS[f.key].map(([v, l]) => `<option value="${v}" ${v === f.value ? "selected" : ""}>${l}</option>`).join("")}</select>`
            : `<input id="f-${f.key}" name="${f.key}" value="${esc(f.value)}" placeholder="${esc(f.default)}">`}
          ${f.help ? `<small>${esc(f.help)}</small>` : ""}</div></div>`).join("")}
      </div>`).join("");
    $("#settings").hidden = false;
  }
  $("#btnSettings").onclick = openSettings; $("#modeBadge").onclick = openSettings;
  $("#setClose").onclick = () => ($("#settings").hidden = true);
  $("#settings").addEventListener("click", (e) => { if (e.target.id === "settings") $("#settings").hidden = true; });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") $("#settings").hidden = true; });
  const cleared = new Set();
  $("#setForm").addEventListener("click", async (e) => {
    const c = e.target.closest("[data-clear]");
    if (c && c.textContent) { cleared.add(c.dataset.clear); const i = $(`#f-${c.dataset.clear}`); i.value = ""; i.placeholder = "will be cleared on save"; c.textContent = ""; }
    const t = e.target.closest("[data-test]");
    if (t) {
      await saveSettings(true);
      const out = $(`#tr-${t.dataset.test}`); out.className = "testres"; out.textContent = "testing…";
      const r = await (await fetch(`${API}/api/settings/test/${t.dataset.test}`, { method: "POST" })).json();
      out.className = `testres ${r.ok ? "ok" : "bad"}`; out.textContent = r.message;
    }
  });
  async function saveSettings(quiet) {
    const values = {};
    new FormData($("#setForm")).forEach((v, k) => { if (String(v).trim() !== "" ) values[k] = v; });
    $("#setForm").querySelectorAll("input:not([type=password])").forEach((i) => { if (i.value.trim() === "") values[i.name] = ""; });
    cleared.forEach((k) => (values[k] = "")); cleared.clear();
    const r = await fetch(`${API}/api/settings`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ values }) });
    if (!r.ok) { toast((await r.json()).detail || "Could not save"); return false; }
    const d = await r.json(); renderMode(d.status); loadTiles();
    if (!quiet) { toast("Settings saved – applied immediately"); $("#settings").hidden = true; }
    return true;
  }
  $("#setSave").onclick = (e) => { e.preventDefault(); saveSettings(false); };
  fetch(`${API}/api/settings`).then((r) => r.json()).then((d) => renderMode(d.status)).catch(() => {});

  setInterval(renderIncidents, 15000);
  renderQueue(); connect();
  fetch(`${API}/api/state`).then((r) => r.json()).then((st) => { if (st.config?.serverless) { applySnapshot(st); enableServerless(); } }).catch(() => {});
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});
})();
