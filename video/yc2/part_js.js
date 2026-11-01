/* ================================================================ FILM */
(function css() {
  const s = document.createElement("style");
  s.textContent = `
  .mveh{position:absolute;width:36px;height:36px;margin:-18px 0 0 -18px;border-radius:50%;display:grid;place-items:center;font:800 14px Inter,sans-serif;color:#06101f;border:3px solid #070c18;box-shadow:0 4px 16px #000c}
  .mnm{position:absolute;font:700 11px Inter,sans-serif;color:#dbe4f7;background:#0b1224dd;padding:2px 7px;border-radius:6px;white-space:nowrap;transform:translate(-50%,0)}
  .mcard{position:absolute;white-space:nowrap;padding:7px 11px;border-radius:12px;background:#0a1122f4;border:1px solid #2a3758;box-shadow:0 10px 30px #000b;font:600 12px/1.25 "JetBrains Mono","DejaVu Sans Mono",monospace;transform-origin:0 100%}
  .mcard b{font-size:16px;font-weight:800}
  .mcard small{display:block;font-size:10.5px;color:#8b97b5;margin-top:2px;font-weight:500}
  .pkt{position:absolute;width:12px;height:12px;margin:-6px 0 0 -6px;border-radius:50%}
  .tag{position:absolute;transform:translate(-50%,-50%);font:800 12px "JetBrains Mono","DejaVu Sans Mono",monospace;padding:4px 9px;border-radius:999px;color:#06101f;white-space:nowrap;box-shadow:0 4px 16px #000a}
  .pin{position:absolute;width:34px;height:34px;margin:-17px 0 0 -17px;border-radius:50%;display:grid;place-items:center;font-weight:900;font-size:18px;border:3px solid #fff}
  .ring{position:absolute;border-radius:50%;border:3px solid;transform:translate(-50%,-50%)}
  .tr{display:flex;gap:10px;padding:10px 12px;margin-bottom:8px;border-radius:14px;background:#0a1122ee;border:1px solid #1f2b48;box-shadow:0 8px 26px #0008}
  .tr .ok{flex:none;width:20px;height:20px;border-radius:50%;background:#2ee6a6;color:#04281c;display:grid;place-items:center;font-size:12px;font-weight:900;margin-top:1px}
  .tr .ev{font:800 11px "JetBrains Mono","DejaVu Sans Mono",monospace;letter-spacing:.06em;color:#34d8f5}
  .tr .tx{font-size:13.5px;font-weight:600;color:#e6ecfa;line-height:1.3;margin-top:2px}
  .hchip{display:inline-flex;align-items:center;gap:8px;font:800 13px "JetBrains Mono","DejaVu Sans Mono",monospace;padding:7px 13px;border-radius:999px;background:#070c18dd;border:1px solid #2a3758}
  .prow{position:absolute;left:0;width:364px;padding:11px 14px;border-radius:14px;background:#0a1122f0;border:1px solid #1d2742}
  .pbar{height:8px;border-radius:6px;background:#ffffff14;overflow:hidden;margin-top:7px}.pbar i{display:block;height:100%;border-radius:6px;background:linear-gradient(90deg,#4da3ff,#2ee6a6)}
  .msg2{position:absolute;left:0;width:364px;padding:9px 12px;border-radius:12px;font-size:13.5px;line-height:1.35}
  .msg2 small{display:block;font:700 10px "JetBrains Mono","DejaVu Sans Mono",monospace;color:#8b97b5;margin-bottom:2px;letter-spacing:.05em}
  `;
  document.head.appendChild(s);
})();

const NN = 131072;
const P = (lat, lon) => { const x = (lon + 180) / 360 * NN, r = lat * Math.PI / 180, y = (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * NN; return [(x - 20958) * 256, (y - 50656) * 256]; };
const RT = {};
for (const k in ROUTES) {
  const pts = ROUTES[k][0].c.map(([lo, la]) => P(la, lo)); const cum = [0];
  for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
  RT[k] = { pts, cum, len: cum[cum.length - 1], sec: ROUTES[k][0].t, m: ROUTES[k][0].d };
}
function along(r, f, rev) {   // point at fraction f along route (rev: from the incident end)
  f = clamp(f); if (rev) f = 1 - f; const d = f * r.len; let i = 1; while (i < r.cum.length - 1 && r.cum[i] < d) i++;
  const a = r.pts[i - 1], b = r.pts[i], u = (d - r.cum[i - 1]) / Math.max(1e-6, r.cum[i] - r.cum[i - 1]); return [lerp(a[0], b[0], u), lerp(a[1], b[1], u)];
}
const polyTo = (r, f) => { const out = []; const d = clamp(f) * r.len; for (let i = 0; i < r.pts.length; i++) { if (r.cum[i] <= d) out.push(r.pts[i]); else { const a = r.pts[i - 1], b = r.pts[i], u = (d - r.cum[i - 1]) / Math.max(1e-6, r.cum[i] - r.cum[i - 1]); out.push([lerp(a[0], b[0], u), lerp(a[1], b[1], u)]); break; } } return out; };

const FV = [  // volunteers: real bids & scores from a run of backend/app/market.py
  { id: "maya", n: "Maya", full: "Maya Chen", tr: "4WD", c: "#2ee6a6", price: 14.97, eta: 4, rt: 4.96, jobs: 212, ot: 97, bat: 86, score: 94.5, lat: 1.1, rk: 0, rk2: "RN-grade first aid · power equipment", route: "maya_direct" },
  { id: "priya", n: "Priya", full: "Priya Natarajan", tr: "CAR", c: "#4da3ff", price: 10.30, eta: 4, rt: 4.88, jobs: 176, ot: 94, bat: 63, score: 79.1, lat: 1.4, rk: 1, route: "priya" },
  { id: "lena", n: "Lena", full: "Lena Novak", tr: "ON FOOT", c: "#34d8f5", price: 0, eta: 22, rt: 4.99, jobs: 305, ot: 98, bat: 92, score: 54.8, lat: 0.9, rk: 2, route: "lena" },
  { id: "diego", n: "Diego", full: "Diego Ramirez", tr: "BOAT", c: "#a78bfa", price: 14.73, eta: 13, rt: 4.71, jobs: 148, ot: 91, bat: 74, score: 50.2, lat: 1.8, rk: 3, route: "diego" },
  { id: "sam", n: "Sam", full: "Sam Okafor", tr: "BIKE", c: "#ffb547", price: 10.45, eta: 11, rt: 4.52, jobs: 61, ot: 86, bat: 41, score: 44.9, lat: 2.6, rk: 4, route: "sam" },
];
const INCW = P(37.7749, -122.4194), CLW = P(37.77715, -122.4190);
const HAZ = [[37.776124, -122.417397], [37.779664, -122.412737], [37.780096, -122.413263], [37.776556, -122.417923]].map(([a, b]) => P(a, b));
const RESP = [[37.7838, -122.4105], [37.7861, -122.4290], [37.7671, -122.4302], [37.7695, -122.4080]].map(([a, b]) => P(a, b));
const PICK = RT.maya_pick.pts[RT.maya_pick.pts.findIndex((p, i) => i > 0 && Math.abs(p[0] - P(37.780854, -122.423206)[0]) < 2 && Math.abs(p[1] - P(37.780854, -122.423206)[1]) < 2)] || P(37.780854, -122.423206);
const V = {}; FV.forEach((v) => { v.r = RT[v.route]; v.w = v.r.pts[0]; V[v.id] = v; });

/* ---------- timeline (seconds) ---------- */
const TL = { rfq: 43.6, panelIn: 41.6, score: 62.6, award: 65.6, p2: 69.4, p2end: 77.2, sms: 78.4, drive0: 79.6, drive1: 85.2, res: 85.6, close: 90.6, end: 97.0 };
const CK = [ // sim clock "seconds since the SOS": [film t, sim s]
  [28.6, 0], [36, 2.2], [40.6, 3.6], [43.6, 3.9], [47, 4.6], [62, 5.4], [65.6, 6.0], [78, 6.1], [85.4, 6.2], [85.7, 6.2], [87.4, 540]];
const interp = (arr, t) => { if (t <= arr[0][0]) return arr[0][1]; for (let i = 1; i < arr.length; i++) if (t <= arr[i][0]) return lerp(arr[i - 1][1], arr[i][1], (t - arr[i - 1][0]) / (arr[i][0] - arr[i - 1][0])); return arr[arr.length - 1][1]; };
const fmtClock = (s) => s < 60 ? s.toFixed(1) + " s" : Math.floor(s / 60) + ":" + String(Math.floor(s % 60)).padStart(2, "0");

const KF = [ // camera: [t, focus, scale, anchor-x]
  [27.4, CLW, 0.30, 640], [31.6, INCW, 1.75, 790], [38.4, INCW, 1.5, 790], [41.2, INCW, 1.25, 700], [43.8, CLW, 0.31, 446],
  [51.6, CLW, 0.31, 446], [52.7, V.maya.w, 1.55, 446], [54.4, V.maya.w, 1.6, 446],
  [55.6, V.lena.w, 0.95, 446], [56.8, V.lena.w, 1.0, 446],
  [57.8, V.diego.w, 1.3, 446], [59.0, V.diego.w, 1.35, 446], [60.0, V.sam.w, 1.3, 446], [61.4, V.sam.w, 1.35, 446],
  [62.4, CLW, 0.31, 446], [65.2, CLW, 0.31, 446], [66.8, INCW, 1.15, 446], [68.8, INCW, 1.1, 446],
  [69.8, CLW, 0.31, 446], [77.0, CLW, 0.31, 446], [78.3, V.maya.w, 1.15, 640], [85.9, INCW, 1.35, 640], [90.6, INCW, 0.85, 640], [97.2, CLW, 0.34, 640]];
function camAt(t) {
  let a = KF[0], b = KF[KF.length - 1];
  if (t <= KF[0][0]) return { x: a[1][0], y: a[1][1], s: a[2], ax: a[3] };
  for (let i = 1; i < KF.length; i++) if (t <= KF[i][0]) { a = KF[i - 1]; b = KF[i]; break; }
  const u = ezio((t - a[0]) / (b[0] - a[0]));
  let c = { x: lerp(a[1][0], b[1][0], u), y: lerp(a[1][1], b[1][1], u), s: Math.exp(lerp(Math.log(a[2]), Math.log(b[2]), u)), ax: lerp(a[3], b[3], u) };
  if (t > 78.3 && t < 86) { // chase-cam on the winning route
    const f = ezio(ramp(t, TL.drive0, TL.drive1)); const p = along(RT.maya_pick, f); const k = ramp(t, 78.4, 79.6) * (1 - ramp(t, 85.0, 85.9));
    c.x = lerp(c.x, p[0], k); c.y = lerp(c.y, p[1], k); c.s = lerp(c.s, 1.05, k * .6);
  }
  return c;
}

/* ---------- build ---------- */
let built = false; const R2 = {};
function buildMap() {
  if (built) return; built = true;
  const svg = $("#wsvg"), mk = $("#mk");
  const NS_ = (a) => ({ "vector-effect": "non-scaling-stroke", ...a });
  R2.hazG = el("polygon", NS_({ points: HAZ.map((p) => p.join(",")).join(" "), fill: "#ff4d5e55", stroke: "#ff4d5e", "stroke-width": 5, "stroke-opacity": .35, "stroke-linejoin": "round" }), svg);
  R2.haz = el("polygon", NS_({ points: HAZ.map((p) => p.join(",")).join(" "), fill: "#ff4d5e40", stroke: "#ff6b7a", "stroke-width": 2, "stroke-dasharray": "8 6" }), svg);
  FV.forEach((v) => { v.line = el("polyline", NS_({ points: v.r.pts.map((p) => p.join(",")).join(" "), fill: "none", stroke: v.c, "stroke-width": 3, "stroke-linecap": "round", "stroke-linejoin": "round", opacity: 0 }), svg); });
  R2.win = el("polyline", NS_({ fill: "none", stroke: "#2ee6a6", "stroke-width": 6, "stroke-linecap": "round", "stroke-linejoin": "round" }), svg);
  R2.winG = el("polyline", NS_({ fill: "none", stroke: "#2ee6a6", "stroke-width": 16, "stroke-opacity": .22, "stroke-linecap": "round", "stroke-linejoin": "round" }), svg);
  R2.rings = [0, 1, 2].map(() => el("circle", NS_({ cx: INCW[0], cy: INCW[1], r: 0, fill: "none", stroke: "#4da3ff", "stroke-width": 3 }), svg));
  // markers (screen space)
  R2.res = RESP.map((p) => ({ p, e: div("width:16px;height:16px;margin:-8px 0 0 -8px;border-radius:4px;background:#0ea5e9;border:2px solid #fff;opacity:.8;position:absolute", "", mk) }));
  R2.pick = { p: PICK, e: div("width:22px;height:22px;margin:-11px 0 0 -11px;border-radius:6px;background:#ffb547;border:2px solid #fff;display:grid;place-items:center;font-size:12px;position:absolute", "&#9889;", mk) };
  R2.pickL = div('position:absolute;transform:translate(-50%,0);font:700 11px Inter,sans-serif;color:#ffe2ad;background:#1a1203dd;padding:2px 7px;border-radius:6px;white-space:nowrap', "Power station · 1000 Wh", mk);
  R2.hazL = div('position:absolute;transform:translate(-50%,-50%) rotate(-40deg);font:800 12px Inter,sans-serif;letter-spacing:.12em;color:#ff8f9c;text-shadow:0 2px 8px #000;white-space:nowrap', "FLOODED · IMPASSABLE", mk);
  R2.inc = div("background:#ff4d5e;box-shadow:0 0 0 8px #ff4d5e33;position:absolute", "!", mk); R2.inc.className = "pin";
  R2.incL = div('position:absolute;transform:translate(-50%,0);font:800 11px "JetBrains Mono","DejaVu Sans Mono",monospace;color:#fff;background:#ff4d5eee;padding:3px 9px;border-radius:7px;white-space:nowrap', "ROSA · OXYGEN 10%", mk);
  R2.irs = [0, 1, 2].map(() => { const e = div("border-color:#ff4d5e;position:absolute", "", mk); e.className = "ring"; return e; });
  FV.forEach((v) => {
    v.e = div(`background:${v.c};position:absolute`, v.n[0], mk); v.e.className = "mveh";
    v.nm = div("position:absolute", v.n, mk); v.nm.className = "mnm";
    v.card = div("position:absolute;opacity:0", "", mk); v.card.className = "mcard";
    v.tagL = div("position:absolute;opacity:0", "", mk);
  });
  R2.pk = []; for (let i = 0; i < 14; i++) { const e = div("opacity:0", "", mk); e.className = "pkt"; R2.pk.push(e); }
  R2.tg = []; for (let i = 0; i < 8; i++) { const e = div("opacity:0", "", mk); e.className = "tag"; R2.tg.push(e); }
  // HUD
  $("#hud").innerHTML = `<span class="hchip" id="hc1" style="color:#2ee6a6;border-color:#2ee6a666">&#9201; SOS + <span id="hcv">0.0 s</span></span><span class="hchip" id="hc2" style="color:#ff8f9c;border-color:#ff4d5e66"><i style="width:9px;height:9px;border-radius:50%;background:#ff4d5e;display:inline-block"></i> 911 &middot; on hold<span id="hcd"></span></span><span class="hchip" id="hc3" style="color:#ffb547;border-color:#ffb54766;display:none"></span>`;
  // trace items
  const TR = [
    [29.2, "AudioTranscribed", "“Water is coming into our living room on 12th Street near St. Jude's… my mother's oxygen concentrator is at 10%…”"],
    [31.9, "EntityExtracted", "Need: Oxygen / Power · Caller: Rosa"],
    [34.0, "UrgencyRated", "P0 · life-threatening"],
    [36.0, "GeoResolved", "12th St near St. Jude's → 37.7749, −122.4194"],
    [38.3, "HazardChecked", "Main Ave flooded — impassable, rerouting"],
    [40.0, "SupplyLocated", "Portable Power Station 1000 Wh nearby"]];
  R2.tr = TR.map(([t0, ev, tx]) => { const d = div("opacity:0;position:relative", `<div class="ok">&#10003;</div><div><div class="ev">${ev}</div><div class="tx">${tx}</div></div>`, $("#trace")); d.className = "tr"; return { t0, d, full: tx }; });
  // panel rows
  $("#panel").innerHTML = `<div class="kick" id="pk" style="margin-bottom:6px">Marketplace &middot; live</div><div id="pt" style="font-size:24px;font-weight:850;letter-spacing:-.02em;line-height:1.15;margin-bottom:14px"></div><div id="prows" style="position:relative"></div>`;
  FV.forEach((v) => {
    v.row = div(`top:${v.rk * 96}px;opacity:0`, "", $("#prows")); v.row.className = "prow";
    v.row.innerHTML = `<div style="display:flex;align-items:center;gap:10px"><div class="mveh" style="position:relative;margin:0;background:${v.c};flex:none">${v.n[0]}</div><div style="flex:1;min-width:0"><div style="font-weight:800;font-size:15.5px">${v.full}</div><div style="font-size:11px;color:#8b97b5;margin-top:1px">${v.tr} &middot; &#9733;${v.rt} &middot; ${v.jobs} jobs &middot; ${v.ot}% on-time</div></div><div class="mono" style="text-align:right"><b style="font-size:16px">${v.price <= 0 ? "FREE" : "$" + v.price.toFixed(2)}</b><div style="font-size:11px;color:#34d8f5">ETA ${v.eta} min</div></div></div><div class="pbar"><i style="width:0"></i></div><div class="mono" style="display:flex;justify-content:space-between;font-size:10.5px;color:#7f8cab;margin-top:3px"><span>composite score</span><b class="sc">–</b></div>`;
  });
  R2.neg = []; // P2 transcript
  const NEG = [
    [70.0, "d", "Dispatcher → Priya", "Counter $9.88 · her ask $12.77"],
    [71.1, "a", "Priya → Dispatcher", "“Tight for a 4.88★ record — can do $10.13.”"],
    [72.2, "d", "Dispatcher → Maya", "Counter $14.19 · her ask $21.38"],
    [73.1, "a", "Maya → Dispatcher", "“Tight for a 4.96★ record — $16.80.”"],
    [74.1, "d", "Dispatcher → both", "Best-and-final: Priya $10.01 · Maya $15.50"],
    [75.5, "k", "Both agents", "Deal. Priya awarded at $10.01"]];
  R2.neg = NEG.map(([t0, k, who, tx], i) => { const d = div(`top:${i * 66}px;opacity:0;background:${k === "d" ? "#12203f" : k === "a" ? "#14281f" : "#1b3b2c"};border:1px solid ${k === "k" ? "#2ee6a6" : "#24324f"}`, `<small>${who}</small>${tx}`, $("#prows")); d.className = "msg2"; d.style.display = "none"; return { t0, d }; });
  R2.smsB = $("#sms");
  R2.smsB.innerHTML = `<div style="border-radius:22px;background:#0b1224f4;border:1px solid #2a3758;padding:14px 16px;box-shadow:0 16px 50px #000a"><div class="mono" style="font-size:11px;color:#8b97b5;letter-spacing:.1em">SMS &middot; MAYA CHEN</div><div id="sm1" style="margin-top:8px;border-radius:14px;background:#17233f;padding:10px 12px;font-size:14px;line-height:1.35"><b style="color:#ff8f9c">ReliefSync Alert &middot; P0</b><br>Oxygen concentrator failure. Deliver 1&times; power unit. Reply YES to confirm.</div><div id="sm2" style="margin:8px 0 0 auto;width:90px;text-align:center;border-radius:14px;background:#2ee6a6;color:#04281c;font-weight:900;padding:8px;opacity:0">YES</div><div id="sm3" class="mono" style="margin-top:10px;font-size:12.5px;color:#2ee6a6;font-weight:800;opacity:0">&#10003; Dispatched &middot; ETA 6 min</div></div>`;
}

/* ---------- per-frame ---------- */
function typed(s, f) { return s.slice(0, Math.floor(s.length * clamp(f))); }
function renderMap(t) {
  buildMap();
  const A = Math.min(ramp(t, 27.4, 28.3), 1 - ramp(t, 96.6, 97.2)); $("#MAP").style.opacity = A;
  const cam = camAt(t); const SC = (w) => [(w[0] - cam.x) * cam.s + cam.ax, (w[1] - cam.y) * cam.s + 360];
  $("#world").style.transform = `translate(${cam.ax - cam.x * cam.s}px,${360 - cam.y * cam.s}px) scale(${cam.s})`;
  const place = (e, w, dx = 0, dy = 0) => { const p = SC(w); e.style.left = (p[0] + dx) + "px"; e.style.top = (p[1] + dy) + "px"; return p; };
  const p2 = t >= TL.p2 - .3 && t < TL.p2end + .8;           // routine-request phase
  const delivery = t >= TL.sms - .5;
  // ---- clock chips
  const clock = interp(CK, t); $("#hcv").textContent = fmtClock(clock);
  $("#hud").style.opacity = ramp(t, 28.4, 29.2) * (1 - ramp(t, 90.0, 90.6));
  $("#hcd").textContent = ".".repeat(1 + Math.floor(t * 2) % 3);
  $("#hc2").style.opacity = t > 88.4 ? 1 : .85 + .15 * Math.sin(t * 5);
  // ---- incident pin
  const incOn = ez(ramp(t, 35.6, 36.4)) ; const pinCol = p2 ? "#ffb547" : "#ff4d5e";
  R2.inc.style.background = pinCol; R2.inc.style.boxShadow = `0 0 0 8px ${pinCol}33`; R2.inc.textContent = p2 ? "F" : "!";
  const ipx = place(R2.inc, INCW); R2.inc.style.opacity = incOn; R2.inc.style.transform = `scale(${lerp(2.2, 1, incOn)})`;
  place(R2.incL, INCW, 0, 26); R2.incL.style.opacity = incOn * (1 - ramp(t, 85.9, 86.5));
  R2.incL.textContent = p2 ? "FAMILY · FOOD & WATER · P2" : (t > 86 ? "ROSA · SAFE" : "ROSA · OXYGEN 10%"); R2.incL.style.background = p2 ? "#ffb547ee" : "#ff4d5eee"; R2.incL.style.color = p2 ? "#201400" : "#fff";
  R2.irs.forEach((e, i) => { const ph = ((t * .5) + i / 3) % 1; const on = incOn * (t < TL.rfq - .3 || p2 || t > 78 ? 1 : .0); const rr = 18 + ph * 70; e.style.left = ipx[0] + "px"; e.style.top = ipx[1] + "px"; e.style.width = e.style.height = rr * 2 + "px"; e.style.borderColor = pinCol; e.style.opacity = on * (1 - ph) * .8; });
  // ---- flood
  const fl = ez(ramp(t, 38.0, 39.4)); R2.haz.setAttribute("opacity", fl); R2.hazG.setAttribute("opacity", fl * (.6 + .4 * Math.sin(t * 3)));
  const hc = SC(P(37.7783, -122.4153)); R2.hazL.style.left = hc[0] + "px"; R2.hazL.style.top = hc[1] + "px"; R2.hazL.style.opacity = fl * ramp(cam.s, .45, .8);
  // ---- resources
  R2.res.forEach((r) => { place(r.e, r.p); r.e.style.opacity = ramp(t, 39.6, 40.6) * .85 * (1 - .5 * ramp(cam.s, 1, 1.6)); });
  place(R2.pick.e, PICK); const pkOn = ramp(t, 39.8, 40.6); R2.pick.e.style.opacity = pkOn; R2.pick.e.style.transform = `scale(${1 + .25 * Math.sin(t * 6) * (t < 42 ? 1 : 0)})`;
  place(R2.pickL, PICK, 0, 16); R2.pickL.style.opacity = pkOn * ramp(cam.s, .9, 1.2);
  // ---- RFQ rings (world circles) + signal timing
  R2.rings.forEach((c, i) => { const u = clamp((t - TL.rfq - i * .45) / 2.2); c.setAttribute("r", u * 1500); c.setAttribute("stroke-opacity", (1 - u) * .85 * (t > TL.rfq - .1 && t < TL.rfq + 4 ? 1 : 0)); });
  const sendT = (v) => TL.rfq + .1 + v.rk * .12;
  FV.forEach((v) => {
    const out0 = sendT(v), arrive = out0 + 1.25, bidAt = arrive + v.lat * .8, back = bidAt + .1, home = back + 1.15;
    v._arrive = arrive; v._bid = bidAt; v._home = home;
    const lineOn = ramp(t, out0, out0 + .8) * (p2 ? ((v.id === "priya" || v.id === "maya") ? 1 : .15) : (t > TL.award + .6 ? (v.id === "maya" ? 0 : .25) : 1));
    v.line.setAttribute("opacity", lineOn * (delivery ? 0 : .9));
    v.line.setAttribute("stroke-width", v.id === "maya" && t > TL.award ? 5 : 3);
  });
  // ---- vehicles
  FV.forEach((v) => {
    let w = v.w; if (v.id === "maya" && t >= TL.drive0) w = along(RT.maya_pick, ezio(ramp(t, TL.drive0, TL.drive1)));
    const p = place(v.e, w); place(v.nm, w, 0, 22);
    const lose = (t > TL.award + .6 && t < TL.p2 - .3 || delivery) && v.id !== "maya"; const p2lose = p2 && v.id !== "priya" && v.id !== "maya";
    const wake = ramp(t, v._arrive, v._arrive + .4);
    v.e.style.opacity = (lose || p2lose) ? .35 : 1; v.nm.style.opacity = ((lose || p2lose) ? .3 : 1) * ramp(cam.s, .0, .01);
    v.e.style.boxShadow = `0 4px 16px #000c${wake > 0 && t < v._bid + .6 ? ", 0 0 0 " + (6 + 5 * Math.sin(t * 8)) + "px " + v.c + "44" : ""}`;
    v.e.style.transform = `scale(${v.id === "maya" && t > TL.award ? 1.2 : 1})`;
    // bid card
    const showCard = !p2 && !delivery && t >= v._bid; const tour = (id, a, b) => v.id === id && t > a && t < b;
    const focus = tour("maya", 52.4, 54.6) || tour("lena", 55.4, 57.0) || tour("diego", 57.6, 59.2) || tour("sam", 59.8, 61.5);
    const toured = t > 52 && t < 62, any = focus; const dim = toured && !any ? .22 : 1;
    let h = `<b>${v.price <= 0 ? "FREE" : "$" + v.price.toFixed(2)}</b> &middot; ETA ${v.eta} min<small>&#9733;${v.rt} &middot; ${v.jobs} jobs &middot; ${v.ot}% on-time</small>`;
    if (focus) {
      const extra = { maya: "4WD · EMT-B · power equipment · 🔋86%", lena: "Registered nurse · on foot · 🔋92%", diego: "Boat · flood-zone only · slow to reach", sam: "Bike · small loads · 🔋41% low" }[v.id];
      h += `<small style="color:${v.c};font-weight:700;margin-top:4px">${extra}</small>`;
    }
    if (t > TL.award + .6 && !focus) h = `<b style="color:${v.id === "maya" ? "#2ee6a6" : "#8b97b5"}">${v.id === "maya" ? "AWARDED" : "STANDBY"}</b> &middot; score ${v.score}`;
    if (p2 && (v.id === "priya" || v.id === "maya")) { h = `<b>ask $${v.id === "priya" ? "12.77" : "21.38"}</b><small>${v.n} &middot; &#9733;${v.rt}</small>`; }
    v.card.innerHTML = h;
    const vis = (showCard || (p2 && (v.id === "priya" || v.id === "maya") && t > TL.p2)) && !delivery;
    const pop = vis ? ez(ramp(t, p2 ? TL.p2 : v._bid, (p2 ? TL.p2 : v._bid) + .35)) : 0;
    v.card.style.opacity = pop * (p2 ? 1 : dim); v.card.style.left = (p[0] + 24) + "px"; v.card.style.top = (p[1] - 24 - (1 - pop) * 10) + "px"; v.card.style.transform = `translateY(-100%) scale(${(focus ? 1.12 : 1)})`; v.card.style.borderColor = (v.id === "maya" && t > TL.award) ? "#2ee6a6" : "#2a3758";
  });
  // ---- packets along real streets
  let pi = 0; const pkt = (w, col) => { const e = R2.pk[pi++]; if (!e) return; const p = SC(w); e.style.left = p[0] + "px"; e.style.top = p[1] + "px"; e.style.background = col; e.style.boxShadow = `0 0 16px 4px ${col}`; e.style.opacity = 1; };
  R2.pk.forEach((e) => (e.style.opacity = 0));
  if (!p2 && t < TL.award + 1 && t > TL.rfq) FV.forEach((v) => {
    const out0 = sendT(v); if (t >= out0 && t < v._arrive) pkt(along(v.r, (t - out0) / 1.25, true), "#4da3ff");
    if (t >= v._bid + .1 && t < v._home) pkt(along(v.r, (t - v._bid - .1) / 1.15), v.c);
  });
  // ---- negotiation tags (P2): offers travelling on the street routes
  R2.tg.forEach((e) => (e.style.opacity = 0)); let ti = 0;
  const tag = (txt, w, col) => { const e = R2.tg[ti++]; if (!e) return; const p = SC(w); e.style.left = p[0] + "px"; e.style.top = p[1] + "px"; e.style.background = col; e.style.opacity = 1; e.textContent = txt; };
  if (p2) {
    const leg = (a, b, r, rev, txt, col) => { if (t >= a && t < b) tag(txt, along(r, (t - a) / (b - a), rev), col); };
    leg(70.0, 71.0, V.priya.r, true, "$9.88", "#4da3ff"); leg(71.1, 72.0, V.priya.r, false, "$10.13", "#a7f3d0");
    leg(72.2, 73.0, V.maya.r, true, "$14.19", "#4da3ff"); leg(73.1, 74.0, V.maya.r, false, "$16.80", "#a7f3d0");
    leg(74.1, 75.2, V.priya.r, true, "B&F $10.01", "#ffd479"); leg(74.1, 75.2, V.maya.r, true, "B&F $15.50", "#ffd479");
    if (t >= 75.5 && t < 77.4) { tag("DEAL $10.01", along(V.priya.r, .55, true), "#2ee6a6"); }
    V.priya.line.setAttribute("stroke-width", t > 75.5 ? 6 : 3);
  }
  // ---- winning route (real roads, around the flood)
  const wf = ezio(ramp(t, 78.8, 79.9)) , drive = ezio(ramp(t, TL.drive0, TL.drive1));
  const wp = polyTo(RT.maya_pick, delivery ? Math.max(.02, ramp(t, 78.6, 79.8) * 1) : 0); const wstr = wp.map((p) => p.join(",")).join(" ");
  R2.win.setAttribute("points", delivery ? wstr : ""); R2.winG.setAttribute("points", delivery ? wstr : "");
  R2.win.setAttribute("opacity", delivery ? 1 : 0); R2.winG.setAttribute("opacity", delivery ? 1 : 0);
  // ---- trace cards
  const trOn = ramp(t, 28.6, 29.1) * (1 - ramp(t, 41.2, 41.8)); $("#trace").style.opacity = trOn;
  R2.tr.forEach((r, i) => { const a = ez(ramp(t, r.t0, r.t0 + .45)); r.d.style.opacity = a; r.d.style.transform = `translateX(${(1 - a) * -30}px)`; if (i === 0) r.d.querySelector(".tx").textContent = "“" + typed(r.full.replace(/^“|”$/g, ""), (t - r.t0) / 1.9) + "”"; });
  // ---- right panel
  const pIn = ramp(t, TL.panelIn, TL.panelIn + .7) * (1 - ramp(t, 77.2, 77.9)) ; $("#panel").style.opacity = pIn; $("#pbg").style.opacity = pIn; $("#panel").style.transform = `translateX(${(1 - pIn) * 40}px)`;
  const showNeg = p2;
  $("#pk").textContent = showNeg ? "Negotiation · live" : "Marketplace · live";
  $("#pt").innerHTML = showNeg ? "Routine request &middot; agents haggle inside the $16 relief-fund cap" : (t < TL.award ? "RFQ sent &middot; agents answer with bids" : "Awarded in 1 second &middot; P0, no haggling");
  FV.forEach((v) => {
    const a = showNeg ? 0 : ez(ramp(t, v._home, v._home + .4)); v.row.style.opacity = a; v.row.style.display = showNeg ? "none" : "block";
    const f = ez(ramp(t, TL.score + v.rk * .15, TL.score + 1.6 + v.rk * .15)); v.row.querySelector("i").style.width = (v.score * f) + "%"; v.row.querySelector(".sc").textContent = f > 0 ? (v.score * f).toFixed(1) : "–";
    const win = v.id === "maya" && t > TL.award; v.row.style.borderColor = win ? "#2ee6a6" : "#1d2742"; v.row.style.boxShadow = win ? "0 0 0 3px #2ee6a644" : "none";
    v.row.style.top = (v.rk * 96) + "px"; v.row.style.transform = (t > TL.award + .6 && !win) ? "scale(.97)" : "none"; if (t > TL.award + .6 && !win) v.row.style.opacity = a * .5;
  });
  R2.neg.forEach((m) => { m.d.style.display = showNeg ? "block" : "none"; const a = ez(ramp(t, m.t0, m.t0 + .35)); m.d.style.opacity = a; m.d.style.transform = `translateY(${(1 - a) * 12}px)`; });
  // ---- award banner
  const aw = ez(ramp(t, TL.award, TL.award + .5)) * (1 - ramp(t, 68.6, 69.2));
  $("#award").style.opacity = aw; $("#award").innerHTML = `<span style="display:inline-block;padding:12px 26px;border-radius:16px;background:#062a1d;border:2px solid #2ee6a6;font-size:24px;font-weight:850;letter-spacing:-.01em;box-shadow:0 14px 50px #000b"><span style="color:#2ee6a6">AWARDED</span> &rarr; Maya Chen &middot; $14.97 &middot; ETA 4 min<span class="mono" style="font-size:13px;color:#8be8c4;margin-left:12px">score 94.5</span></span>`;
  // ---- SMS
  const sm = ez(ramp(t, TL.sms, TL.sms + .5)) * (1 - ramp(t, 86.2, 86.8)); R2.smsB.style.opacity = sm; R2.smsB.style.transform = `translateY(${(1 - sm) * -20}px)`;
  $("#sm2").style.opacity = ramp(t, 80.2, 80.6); $("#sm3").style.opacity = ramp(t, 81.2, 81.7);
  const dc = $("#hc3"); dc.style.display = delivery && t < 90.6 ? "inline-flex" : "none"; dc.textContent = t < TL.res ? "● DISPATCHED · ROUTE AVOIDS FLOOD" : "";
  // ---- resolution overlay
  const rs = ez(ramp(t, TL.res + .3, TL.res + 1.0)) * (1 - ramp(t, 90.0, 90.6));
  $("#res").style.opacity = rs;
  const rclk = fmtClock(interp(CK, t));
  $("#res").innerHTML = `<div style="display:inline-flex;flex-direction:column;align-items:center;gap:14px;padding:22px 34px;border-radius:24px;background:#05100bf2;border:2px solid #2ee6a6;box-shadow:0 20px 70px #000c"><div class="kick" style="color:#2ee6a6">Resolved</div><div class="big" style="font-size:50px">Power restored.</div><div style="display:flex;gap:26px;align-items:center"><div style="text-align:center"><div class="mono" style="font-size:34px;font-weight:800;color:#2ee6a6">${rclk}</div><div style="font-size:12px;color:#8be8c4;letter-spacing:.1em">RELIEFSYNC &middot; SOS TO RESOLVED</div></div><div style="width:1px;height:50px;background:#2a3758"></div><div style="text-align:center"><div class="mono" style="font-size:34px;font-weight:800;color:#ff8f9c">on hold…</div><div style="font-size:12px;color:#ff9aa5;letter-spacing:.1em">911</div></div></div><div class="mono" style="font-size:13px;color:#cdd7ee;opacity:${ramp(t, 88.6, 89.2)}">&#128274; Rosa's location &amp; medical details erased</div></div>`;
  // ---- closing words over the dimmed map
  $("#MAP_dim").style.opacity = ramp(t, 90.4, 91.4) * .62;
  const md = ez(ramp(t, 91.0, 91.8)) * (1 - ramp(t, 96.2, 96.9)); $("#mid").style.opacity = md; $("#mid").style.top = (250 + (1 - md) * 16) + "px";
  $("#mid").innerHTML = `<div class="big" style="font-size:64px;letter-spacing:-.04em">Not a bulletin board.</div><div class="big" style="font-size:64px;letter-spacing:-.04em;margin-top:8px;color:#2ee6a6">An autonomous dispatcher.</div><div style="display:flex;gap:14px;justify-content:center;margin-top:34px;opacity:${ramp(t, 93.0, 93.8)}">${["Transparent: every decision streamed live", "Offline-first: SMS fallback", "Safe by design: PII erased"].map((s) => `<span class="hchip" style="font-family:Inter,sans-serif;font-weight:700;font-size:15px;color:#dbe4f7">${s}</span>`).join("")}</div>`;
  $("#attrib").style.opacity = 1;
}

/* ---------- S1 ---------- */
function renderS1(t) {
  const A = Math.min(ramp(t, 0, .6), 1 - ramp(t, 8.6, 9.2)); $("#S1").style.opacity = A;
  $("#S1_k").style.opacity = ez(ramp(t, .5, 1.2)); $("#S1_h").style.opacity = ez(ramp(t, 1.0, 1.8)); $("#S1_h").style.transform = `translateY(${(1 - ez(ramp(t, 1.0, 1.8))) * 18}px)`;
  $("#S1_p").style.opacity = ez(ramp(t, 2.6, 3.4));
  const pct = Math.round(lerp(18, 10, ramp(t, 3.6, 6.0)));
  $("#S1_batt").style.opacity = ez(ramp(t, 3.6, 4.4));
  $("#S1_batt").innerHTML = `<svg width="86" height="42" viewBox="0 0 86 42"><rect x="2" y="3" width="74" height="36" rx="8" fill="none" stroke="#ff7a87" stroke-width="3"/><rect x="78" y="14" width="6" height="14" rx="2" fill="#ff7a87"/><rect x="7" y="8" width="${Math.max(3, 64 * pct / 100)}" height="26" rx="4" fill="#ff4d5e" opacity="${.6 + .4 * Math.sin(t * 8) ** 2}"/></svg><div><div class="mono" style="font-size:40px;font-weight:800;color:#ff7a87">${pct}%</div><div style="font-size:14px;color:#9aa8c6;font-weight:600">oxygen concentrator battery</div></div>`;
  $("#S1_phone").style.opacity = ez(ramp(t, 4.6, 5.6)); $("#S1_phone").style.transform = `translateY(${(1 - ez(ramp(t, 4.6, 5.6))) * 30}px)`;
  const w = Math.max(0, t - 5.4); $("#S1_timer").textContent = Math.floor(w / 60) + ":" + String(Math.floor(w) % 60).padStart(2, "0") + "";
  $("#S1_busy").style.opacity = ramp(t, 5.4, 6.2); $("#S1_hold").textContent = "ON HOLD" + ".".repeat(1 + Math.floor(t * 2) % 3);
  $("#S1_red").style.opacity = .5 + .5 * Math.sin(t * 2.4) ** 2;
}

/* ---------- END ---------- */
function renderEnd(t) {
  const u = t - 96.8; $("#END").style.opacity = ramp(u, 0, .7);
  const l = ez(ramp(u, .5, 1.3)); $("#E_logo").style.opacity = l; $("#E_logo").style.transform = `translateX(-50%) scale(${lerp(.7, 1, l)})`;
  $("#E_name").style.opacity = ez(ramp(u, 1.0, 1.8)); $("#E_name").style.transform = `translateY(${(1 - ez(ramp(u, 1.0, 1.8))) * 18}px)`;
  $("#E_tag").style.opacity = ez(ramp(u, 2.2, 3.0)); $("#E_url").style.opacity = ez(ramp(u, 3.8, 4.6)); $("#E_glow").style.opacity = .6 + .4 * Math.sin(u * 1.4) ** 2;
  $("#END").style.opacity = ramp(u, 0, .7) * (1 - ramp(u, 6.4, 6.7));
}

const END_T = 103.5;
window.renderFrame = (t) => {
  const on = (id, a, b) => ($("#" + id).style.display = t >= a && t < b ? "block" : "none");
  on("S1", 0, 9.4); on("A", 8.5, 19.2); on("B", 18.2, 28.6); on("MAP", 27.3, 97.4); on("END", 96.7, 110);
  if (t < 9.4) renderS1(t);
  if (t >= 8.5 && t < 19.2) renderA(t - 8.6, 10.4);
  if (t >= 18.2 && t < 28.6) renderB(t - 18.3, 10.1);
  if (t >= 27.3 && t < 97.4) renderMap(t);
  if (t >= 96.7) renderEnd(t);
};
window.END_T = END_T;
if (!navigator.webdriver) {
  const fit = () => { $("#stage").style.transform = `scale(${Math.min(innerWidth / 1280, innerHeight / 720)})`; };
  addEventListener("resize", fit); fit(); const t0 = performance.now(); const start = +(location.hash.slice(1) || 0);
  (function loop() { window.renderFrame(start + ((performance.now() - t0) / 1000) % (END_T - start)); requestAnimationFrame(loop); })();
}
