'use strict';
// Cinnamon Rush browser client: renders server state and forwards clicks.
// All rules and timers run in the Java server (src/bakery/Game.java).

const FILL = {
  classic:   { name: 'Classic Cinnamon', short: 'Classic',   color: '#8b4a1c' },
  pecan:     { name: 'Pecan Praline',    short: 'Pecan',     color: '#552c10' },
  apple:     { name: 'Apple Spice',      short: 'Apple',     color: '#c9802f' },
  chocolate: { name: 'Chocolate Swirl',  short: 'Chocolate', color: '#2a170c' },
};
const ICE = {
  none:  { name: 'No icing',      short: 'Plain' },
  glaze: { name: 'Vanilla Glaze', short: 'Glaze' },
  cream: { name: 'Cream Cheese',  short: 'Cream Cheese' },
  maple: { name: 'Maple',         short: 'Maple' },
};
const VERB = { mixer: 'Mixing', proofer: 'Proofing', table: 'Rolling', oven: 'Baking', icer: 'Icing' };
const ZONE = { mixer: 'z-mixer', proofer: 'z-proofer', table: 'z-table', oven: 'z-oven', icer: 'z-icer', shelf: 'z-shelf' };
const UNLOCKS = { 2: 'New filling unlocked: Apple Spice', 3: 'New icing unlocked: Maple', 4: 'New filling unlocked: Chocolate Swirl' };

let S = null;
let lastNotice = -1;
let overlayKey = '';
let ordersKey = '';
let picker = null;
const stationEls = new Map();

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const money = (n) => '$' + n.toFixed(2);
const fmt = (ms) => { const s = Math.ceil(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };

// ------------------------------------------------------------------ server I/O

function accept(state) {
  if (S && state.version < S.version) return; // stale poll that raced an action
  S = state;
  render();
}

async function poll() {
  try {
    const r = await fetch('/api/state', { cache: 'no-store' });
    accept(await r.json());
    $('#offline').hidden = true;
  } catch {
    $('#offline').hidden = false;
  }
  setTimeout(poll, 120);
}

async function act(params) {
  try {
    const r = await fetch('/api/action', { method: 'POST', body: new URLSearchParams(params) });
    const j = await r.json();
    if (j.msg) toast(j.msg, j.ok ? 'info' : 'error');
    accept(j.state);
    return j.ok;
  } catch {
    toast('Lost connection to the kitchen server.', 'error');
    return false;
  }
}

// ------------------------------------------------------------------ helpers

function rollHTML(filling, icing, stage = 'BAKED', extra = '') {
  const st = stage.toLowerCase();
  return `<i class="roll r-${st} ice-${icing || 'none'}${extra}" style="--fill:${FILL[filling]?.color || '#8b4a1c'}"></i>`;
}

function trayHTML(t) {
  if (!t) return '';
  const cls = `tray st-${t.stage.toLowerCase()}${t.burnt ? ' burnt' : ''}${t.overproofed ? ' soft' : ''}`;
  if (t.stage === 'DOUGH' || t.stage === 'PROOFED') return `<div class="${cls}"><div class="dough"></div></div>`;
  let rolls = '';
  const icing = t.stage === 'ICED' ? t.icing : 'none';
  for (let i = 0; i < 12; i++) rolls += rollHTML(t.filling, icing, t.stage, i >= t.count ? ' gone' : '');
  return `<div class="${cls}">${rolls}</div>`;
}

const trayKey = (t) => (t ? `${t.id}|${t.stage}|${t.burnt}|${t.overproofed}|${t.count}|${t.icing}|${t.filling}` : '');

function describe(t) {
  if (!t) return 'Empty hands';
  if (t.burnt) return 'A burnt tray. Toss it in the bin.';
  const soft = t.overproofed ? ' (over-proofed)' : '';
  const f = FILL[t.filling]?.short;
  switch (t.stage) {
    case 'DOUGH': return 'Fresh dough → put it in a proofer';
    case 'PROOFED': return `Proofed dough${soft} → roll and fill it at a table`;
    case 'ROLLED': return `Raw ${f} rolls${soft} → into an oven`;
    case 'BAKED': return `Baked ${f} rolls ×${t.count}${soft} → ice them, or box them as Plain`;
    case 'ICED': return `${f} + ${ICE[t.icing].short} ×${t.count}${soft} → box them for an order`;
  }
  return '';
}

function canDrop(st) {
  const h = S.hand;
  return !!h && !h.burnt && !st.tray && st.state !== 'working' && !!st.accepts &&
    (st.accepts === 'ANY' || st.accepts === h.stage);
}

function canGrab(st) {
  return !S.hand && ((st.tray && st.state !== 'working') || (st.kind === 'mixer' && st.state === 'empty'));
}

function handIcing() {
  const h = S.hand;
  return h && h.stage === 'ICED' ? h.icing : 'none';
}

function lineMatches(l) {
  const h = S.hand;
  return !!h && !h.burnt && (h.stage === 'BAKED' || h.stage === 'ICED') &&
    l.filling === h.filling && l.icing === handIcing() && l.packed < l.qty;
}

function demand(kind) {
  const need = {};
  for (const o of S.orders) for (const l of o.lines) {
    const left = l.qty - l.packed;
    if (left <= 0) continue;
    if (kind === 'filling') need[l.filling] = (need[l.filling] || 0) + left;
    else if (S.hand && l.filling === S.hand.filling) need[l.icing] = (need[l.icing] || 0) + left;
  }
  return need;
}

// ------------------------------------------------------------------ rendering

function render() {
  if (!S) return;
  renderTop();
  renderStations();
  renderOrders();
  renderHand();
  renderOverlay();
  renderNotices();
  document.body.classList.toggle('paused', S.paused);
  if (picker && (S.phase !== 'SHIFT' || !S.hand)) closePicker();
}

function renderTop() {
  $('#day').textContent = S.day;
  $('#cash').textContent = money(S.cash);
  const left = Math.max(0, S.shiftLen - S.clock);
  $('#clock').textContent = fmt(S.phase === 'RESULTS' ? 0 : left);
  const pct = S.phase === 'RESULTS' ? 1 : Math.min(1, S.clock / S.shiftLen);
  $('#truck').style.left = `calc(${pct} * (100% - 92px))`;
  $('#road').classList.toggle('soon', S.phase === 'SHIFT' && left <= 30000);
  const pb = $('#pauseBtn');
  pb.disabled = S.phase !== 'SHIFT';
  pb.textContent = S.paused ? 'Resume' : 'Pause';
}

function renderStations() {
  const seen = new Set();
  for (const st of S.stations) {
    seen.add(st.id);
    let el = stationEls.get(st.id);
    if (!el) {
      el = makeStation(st);
      stationEls.set(st.id, el);
      document.getElementById(ZONE[st.kind]).appendChild(el);
    }
    updateStation(el, st);
  }
  for (const [id, el] of stationEls) {
    if (!seen.has(id)) { el.remove(); stationEls.delete(id); }
  }
  $('#trash').classList.toggle('drop', !!S.hand);
}

function makeStation(st) {
  const el = document.createElement('div');
  el.className = `station k-${st.kind}`;
  el.setAttribute('role', 'button');
  el.tabIndex = 0;
  el.innerHTML = `
    <div class="st-head"><span class="st-name">${esc(st.label)}</span></div>
    <div class="st-body"><div class="appliance"></div><div class="slot"></div></div>
    <div class="st-status"></div>
    <div class="bar"><div class="bar-fill"></div></div>`;
  el.addEventListener('click', () => onStation(st.id));
  el.addEventListener('keydown', (e) => { if (e.key === 'Enter') onStation(st.id); });
  return el;
}

function stationStatus(st) {
  switch (st.state) {
    case 'working': return `${VERB[st.kind]}… ${Math.ceil(st.remaining / 1000)}s`;
    case 'danger': return st.kind === 'oven' ? 'Take it out!' : 'Over-proofing!';
    case 'ruined': return 'Burnt!';
    case 'ready':
      if (st.kind === 'shelf') return 'Stored';
      if (st.tray?.overproofed && st.kind === 'proofer') return 'Over-proofed';
      return 'Ready';
    default: return st.kind === 'mixer' ? 'Click to mix' : 'Empty';
  }
}

function updateStation(el, st) {
  el.dataset.state = st.state;
  el.classList.toggle('drop', canDrop(st));
  el.classList.toggle('grab', canGrab(st));
  el.querySelector('.st-status').textContent = stationStatus(st);
  const fill = el.querySelector('.bar-fill');
  const working = st.state === 'working';
  fill.style.width = `${(working ? st.progress : st.danger) * 100}%`;
  fill.className = `bar-fill ${working ? 'work' : st.danger > 0 ? 'heat' : ''}`;
  const key = trayKey(st.tray);
  if (el._trayKey !== key) {
    el._trayKey = key;
    el.querySelector('.slot').innerHTML = trayHTML(st.tray);
  }
}

function renderOrders() {
  const key = JSON.stringify(S.orders) + trayKey(S.hand);
  if (key === ordersKey) return;
  ordersKey = key;
  $('#orderList').innerHTML = S.orders.map((o) => {
    const got = o.lines.reduce((a, l) => a + l.packed, 0);
    const want = o.lines.reduce((a, l) => a + l.qty, 0);
    const hit = o.lines.some(lineMatches);
    return `<div class="order${o.rush ? ' rush' : ''}${got >= want ? ' done' : ''}${hit ? ' hit' : ''}" data-id="${o.id}">
      <div class="o-head">
        <span class="o-client">${esc(o.client)}</span>
        ${o.rush ? '<span class="tag">RUSH</span>' : ''}
        ${got >= want ? '<span class="tag ok">DONE</span>' : ''}
        <span class="o-count">${got}/${want}</span>
      </div>
      ${o.lines.map((l) => `
        <div class="line${lineMatches(l) ? ' hit' : ''}${l.packed >= l.qty ? ' full' : ''}">
          ${rollHTML(l.filling, l.icing)}
          <span class="l-name">${FILL[l.filling].short} <em>·</em> ${ICE[l.icing].short}</span>
          <span class="l-cnt">${l.packed}/${l.qty}</span>
          <div class="l-bar"><div style="width:${(l.packed / l.qty) * 100}%"></div></div>
        </div>`).join('')}
    </div>`;
  }).join('');
}

function renderHand() {
  const key = trayKey(S.hand);
  const tray = $('#handTray');
  if (tray._key !== key) {
    tray._key = key;
    tray.innerHTML = trayHTML(S.hand) || '<div class="empty-hands"></div>';
    $('#handText').textContent = describe(S.hand);
  }
  let hint = '';
  if (S.phase === 'SHIFT') {
    if (!S.hand) hint = 'Click a mixer to start a batch, or click a finished station to pick up its tray.';
    else if (S.hand.burnt) hint = 'Click the Trash bin (or press X).';
    else if (S.hand.stage === 'BAKED' || S.hand.stage === 'ICED') {
      hint = S.orders.some((o) => o.lines.some(lineMatches))
        ? 'Highlighted orders want this tray. Click one to box it.'
        : S.hand.stage === 'BAKED' ? 'No order wants these plain. Take them to Icing.' : 'No open order wants these right now. Park them on a rack.';
    } else hint = 'Glowing stations can take this tray.';
  }
  $('#hint').textContent = hint;
}

function renderOverlay() {
  const key = `${S.phase}|${S.day}|${S.cash}|${S.shop.map((u) => u.level).join()}`;
  if (key === overlayKey) return;
  overlayKey = key;
  const ov = $('#overlay');
  if (S.phase === 'SHIFT') { ov.hidden = true; return; }
  ov.hidden = false;
  const card = ov.querySelector('.card');
  card.innerHTML = S.phase === 'BRIEFING' ? briefingHTML() : resultsHTML();
  card.querySelector('[data-act=start]')?.addEventListener('click', () => act({ type: 'start' }));
  card.querySelector('[data-act=next]')?.addEventListener('click', () => act({ type: 'next' }));
  card.querySelector('[data-act=restart]')?.addEventListener('click', () => {
    if (confirm('Start over from Day 1? Cash and upgrades will be lost.')) act({ type: 'restart' });
  });
  card.querySelectorAll('[data-buy]').forEach((b) => b.addEventListener('click', () => act({ type: 'buy', upgrade: b.dataset.buy })));
}

function briefingHTML() {
  const total = S.orders.reduce((a, o) => a + o.lines.reduce((b, l) => b + l.qty, 0), 0);
  const howTo = S.day === 1 ? `
    <div class="howto">
      <h3>How the line works</h3>
      <ol>
        <li><b>Mixer</b> Click an empty mixer to make a batch of dough.</li>
        <li><b>Proofer</b> Carry the dough in to rise. Don't leave it too long or it over-proofs (60% pay).</li>
        <li><b>Rolling table</b> Roll it out and pick the filling. One tray = 12 rolls.</li>
        <li><b>Oven</b> Bake it, then pull it out before the heat bar fills, or it burns.</li>
        <li><b>Icing</b> Glaze, cream cheese… or skip it for <i>Plain</i> orders.</li>
        <li><b>Box it</b> Click a highlighted order to pack the rolls you're holding.</li>
      </ol>
      <p>You can carry one tray at a time. Park extras on the cooling racks. A rush order shows up mid-shift and pays 50% extra. Filling an order completely earns a 20% bonus.</p>
      <p class="keys"><kbd>Space</kbd> pause · <kbd>X</kbd> trash · <kbd>1</kbd>–<kbd>4</kbd> pick options</p>
    </div>` : '';
  return `
    <h2>Day ${S.day}: Morning Briefing</h2>
    <p class="lead">${S.orders.length} big orders, ${total} rolls in total. The truck leaves the dock in <b>${fmt(S.shiftLen)}</b>. Box as many as you can.</p>
    ${UNLOCKS[S.day] ? `<p class="unlock">${UNLOCKS[S.day]}</p>` : ''}
    <div class="brief-orders">
      ${S.orders.map((o) => `
        <div class="b-order"><b>${esc(o.client)}</b>
          ${o.lines.map((l) => `<div class="b-line">${rollHTML(l.filling, l.icing)} ${l.qty} × ${FILL[l.filling].name}, ${ICE[l.icing].name}<span>${money(l.price)}/roll</span></div>`).join('')}
        </div>`).join('')}
    </div>
    ${howTo}
    <div class="actions"><button class="btn primary" data-act="start">Open the kitchen <kbd>Enter</kbd></button></div>`;
}

function resultsHTML() {
  const r = S.results;
  const pct = r.requested ? Math.round((r.delivered / r.requested) * 100) : 0;
  const stars = '★'.repeat(r.stars) + '☆'.repeat(3 - r.stars);
  const verdict = ['The client managers are not happy.', 'A rough shift, but the truck didn\'t leave empty.', 'Solid shift. The dock crew is impressed.', 'Flawless! The whole city smells like cinnamon.'][r.stars];
  return `
    <h2>Day ${S.day}: The truck has left</h2>
    <div class="stars" aria-label="${r.stars} of 3 stars">${stars}</div>
    <p class="lead">${verdict} You delivered <b>${r.delivered}</b> of ${r.requested} rolls (${pct}%).</p>
    <table class="res">
      <thead><tr><th>Client</th><th>Delivered</th><th>Pay</th><th>Bonus</th></tr></thead>
      <tbody>${r.orders.map((o) => `<tr><td>${esc(o.client)}${o.rush ? ' <span class="tag">RUSH</span>' : ''}</td><td>${o.delivered}/${o.requested}</td><td>${money(o.pay)}</td><td>${o.bonus ? money(o.bonus) : '–'}</td></tr>`).join('')}</tbody>
      <tfoot><tr><td>Total</td><td></td><td>${money(r.revenue)}</td><td>${money(r.bonus)}</td></tr></tfoot>
    </table>
    <p class="muted">Burnt trays: ${r.burnt} · Over-proofed batches: ${r.soft} · Earned so far: ${money(S.totalEarned)}</p>
    <h3>Kitchen upgrades <span class="wallet">Cash: ${money(S.cash)}</span></h3>
    <div class="shop">
      ${S.shop.map((u) => {
        const maxed = u.level >= u.max;
        return `<div class="up${maxed ? ' maxed' : ''}">
          <b>${esc(u.name)}</b><small>${esc(u.desc)}</small>
          <span class="lvl">${'●'.repeat(u.level)}${'○'.repeat(u.max - u.level)}</span>
          <button class="btn" data-buy="${u.id}" ${maxed || S.cash < u.cost ? 'disabled' : ''}>${maxed ? 'Maxed' : money(u.cost)}</button>
        </div>`;
      }).join('')}
    </div>
    <div class="actions">
      <button class="btn link" data-act="restart">Restart from Day 1</button>
      <button class="btn primary" data-act="next">Start Day ${S.day + 1}</button>
    </div>`;
}

function renderNotices() {
  const maxId = S.notices.reduce((m, n) => Math.max(m, n.id), 0);
  if (lastNotice < 0) { lastNotice = maxId; return; } // don't replay old notices on page load
  for (const n of S.notices) if (n.id > lastNotice) toast(n.text, n.kind);
  lastNotice = Math.max(lastNotice, maxId);
}

function toast(text, kind = 'info') {
  const box = $('#toasts');
  const el = document.createElement('div');
  el.className = `toast ${kind}`;
  el.textContent = text;
  box.appendChild(el);
  while (box.children.length > 4) box.firstChild.remove();
  setTimeout(() => el.classList.add('out'), kind === 'rush' || kind === 'warn' ? 4000 : 2400);
  setTimeout(() => el.remove(), kind === 'rush' || kind === 'warn' ? 4400 : 2800);
}

// ------------------------------------------------------------------ input

function onStation(id) {
  if (!S || S.phase !== 'SHIFT' || S.paused) return;
  const st = S.stations.find((s) => s.id === id);
  if (!st) return;
  if (canDrop(st) && st.kind === 'table') return openPicker('filling', id);
  if (canDrop(st) && st.kind === 'icer') return openPicker('icing', id);
  act({ type: 'click', station: id });
}

function openPicker(kind, stationId) {
  const opts = kind === 'filling' ? S.fillings : S.icings.filter((i) => i !== 'none');
  const need = demand(kind);
  picker = { stationId, opts };
  $('#picker .pk-title').textContent = kind === 'filling' ? 'Choose a filling' : `Ice the ${FILL[S.hand.filling].short} rolls`;
  $('#picker .pk-opts').innerHTML = opts.map((o, i) => {
    const sample = kind === 'filling' ? rollHTML(o, 'none', 'ROLLED') : rollHTML(S.hand.filling, o, 'ICED');
    const name = kind === 'filling' ? FILL[o].name : ICE[o].name;
    const n = need[o] || 0;
    return `<button class="pk-opt${n ? ' wanted' : ''}" data-v="${o}"><kbd>${i + 1}</kbd>${sample}<b>${name}</b><small>${n ? `${n} still needed` : 'not on any order'}</small></button>`;
  }).join('');
  $('#picker .pk-note').textContent = kind === 'icing' && need.none
    ? `${need.none} Plain ${FILL[S.hand.filling].short} rolls are on order. Plain ones skip icing: box them straight from the oven.`
    : '';
  $('#picker').querySelectorAll('.pk-opt').forEach((b) => b.addEventListener('click', () => choose(b.dataset.v)));
  $('#picker').hidden = false;
}

function choose(v) {
  const p = picker;
  closePicker();
  if (p) act({ type: 'click', station: p.stationId, choice: v });
}

function closePicker() {
  picker = null;
  $('#picker').hidden = true;
}

$('#picker .pk-cancel').addEventListener('click', closePicker);
$('#picker').addEventListener('click', (e) => { if (e.target.id === 'picker') closePicker(); });
$('#pauseBtn').addEventListener('click', () => act({ type: 'pause' }));
$('#trash').addEventListener('click', () => { if (S?.hand) act({ type: 'trash' }); });
$('#orderList').addEventListener('click', (e) => {
  const card = e.target.closest('.order');
  if (!card || !S || S.phase !== 'SHIFT') return;
  if (!S.hand) return toast('Pick up a tray of finished rolls first, then click the order.', 'error');
  act({ type: 'pack', order: card.dataset.id });
});

document.addEventListener('keydown', (e) => {
  if (!S) return;
  if (picker) {
    if (e.key === 'Escape') closePicker();
    const n = parseInt(e.key, 10);
    if (n >= 1 && n <= picker.opts.length) choose(picker.opts[n - 1]);
    return;
  }
  if (e.key === ' ' && S.phase === 'SHIFT') { e.preventDefault(); act({ type: 'pause' }); }
  else if (e.key === 'Enter' && S.phase === 'BRIEFING') { e.preventDefault(); act({ type: 'start' }); }
  else if ((e.key === 'x' || e.key === 'X' || e.key === 'Delete') && S.phase === 'SHIFT' && S.hand) act({ type: 'trash' });
});

poll();
