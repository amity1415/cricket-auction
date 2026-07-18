/* Auctions landing page (admin): list every tournament, create a new one with
 * its own full rule book, or switch which one is active. Activating a tournament
 * loads it everywhere (setup, console, broadcast). */

const GROUPS = ['A', 'B', 'C', 'D', 'E'];

const esc = s => String(s ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const fmtShort = n => {
  if (n == null) return '—';
  if (n >= 1e7) return '₹' + (n / 1e7).toFixed(2).replace(/\.?0+$/, '') + ' Cr';
  if (n >= 1e5) return '₹' + (n / 1e5).toFixed(1).replace(/\.0$/, '') + ' L';
  return '₹' + new Intl.NumberFormat('en-IN').format(n);
};

let toastTimer = null;
function toast(message, isError) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.className = 'toast ' + (isError ? 'error' : 'ok');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.className = 'toast hidden', isError ? 5000 : 2500);
}

async function getJSON(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error('GET ' + url + ' → ' + res.status);
  return res.json();
}

async function send(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    toast(data.message || data.error || ('Request failed (' + res.status + ')'), true);
    return null;
  }
  return data;
}

// ---- list -----------------------------------------------------------------

let IS_APP_ADMIN = false;   // the config app admin — can create/delete auctions + manage users
let GRANTS = new Set();      // auction ids a tournament-admin may run
let ALL = [];               // full auction list, filtered client-side by the search box

/** Can the current user run this auction as an admin (app admin, or granted)? */
function canAdmin(id) {
  return IS_APP_ADMIN || GRANTS.has(id);
}

async function loadList() {
  const box = document.getElementById('tournament-list');
  try {
    ALL = await getJSON('/api/tournaments');
    render();
  } catch (e) {
    box.innerHTML = '<p class="muted">Could not load auctions.</p>';
  }
}

function render() {
  const box = document.getElementById('tournament-list');
  const q = (document.getElementById('search').value || '').trim().toLowerCase();
  if (!ALL.length) {
    box.innerHTML = '<p class="muted">No auctions yet'
      + (IS_APP_ADMIN ? ' — create one to get started.' : '.') + '</p>';
    return;
  }
  const list = q ? ALL.filter(t => t.name.toLowerCase().includes(q)) : ALL;
  if (!list.length) {
    box.innerHTML = '<p class="muted">No auctions match “' + esc(q) + '”.</p>';
    return;
  }
  box.innerHTML = '';
  list.forEach((t, i) => {
    const el = card(t);
    el.style.animationDelay = Math.min(i * 0.05, 0.4) + 's';  // staggered entrance
    box.appendChild(el);
  });
}

function card(t) {
  const el = document.createElement('div');
  const admin = canAdmin(t.id);
  el.className = 'team-card' + (t.active ? ' active-tournament' : '');
  // Edit rules: any admin of this auction. Delete: app admin only.
  const editBtn = admin
    ? `<button class="ghost" data-act="edit" data-id="${t.id}">Edit rules</button>` : '';
  const delBtn = IS_APP_ADMIN
    ? `<button class="ghost tt-del" data-act="del" data-id="${t.id}">Delete</button>` : '';
  el.innerHTML = `
    <div class="tt-head">
      <div>
        <b class="tt-name">${esc(t.name)}</b>
        ${t.active ? '<span class="tt-badge" title="Shown when a screen names no auction">★ Default</span>' : ''}
      </div>
      <span class="muted tt-counts">${t.playerCount} player(s) · ${t.teamCount} team(s)</span>
    </div>
    <div class="tt-actions">
      <button class="primary" data-act="open" data-id="${t.id}">${admin ? '⚙️ Open →' : '👁 View →'}</button>
      ${editBtn}${delBtn}
    </div>`;
  el.querySelector('[data-act="open"]').addEventListener('click', () => openAuction(t.id));
  if (admin) {
    el.querySelector('[data-act="edit"]').addEventListener('click', () => openEditor(t.id));
  }
  if (IS_APP_ADMIN) {
    el.querySelector('[data-act="del"]').addEventListener('click', () => deleteAuction(t.id, t.name));
  }
  return el;
}

/**
 * Each auction is its own space — opening one navigates with its id so every
 * screen scopes to it. Admins of the auction land on setup; everyone else on
 * the (read-only) team dashboards.
 */
function openAuction(id) {
  const page = canAdmin(id) ? 'index.html' : 'team.html';
  location.href = page + '?tournamentId=' + encodeURIComponent(id);
}

/** Delete needs the admin password re-entered so a stray click can't wipe an auction. */
async function deleteAuction(id, name) {
  const password = prompt(
    `Delete "${name}" and ALL of its players, teams, sales and owner accounts?\n`
    + `This cannot be undone.\n\nEnter the admin password to confirm:`);
  if (password === null) return;            // cancelled
  if (!password.trim()) { toast('Password required to delete', true); return; }
  const res = await fetch('/api/admin/tournaments/' + id, {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  });
  if (res.status === 204) { toast('Deleted “' + name + '”'); loadList(); return; }
  const data = await res.json().catch(() => ({}));
  toast(data.message || data.error || ('Delete failed (' + res.status + ')'), true);
}

// ---- editor ---------------------------------------------------------------

const $ = id => document.getElementById(id);

// Every group a tournament can use — the legacy A–E tiers plus the role-based
// cascade groups. A tournament picks whichever it needs; neither set is required.
const ALL_GROUPS = [
  ['A', 'A'], ['B', 'B'], ['C', 'C'], ['D', 'D'], ['E', 'E'],
  ['MIXED_UTILITY_BAG', 'Mixed Utility Bag'],
  ['WICKET_KEEPER', 'Wicket Keeper'],
  ['BOWLER', 'Bowler'],
  ['ALL_ROUNDER', 'All Rounder'],
  ['MARKEE_PLAYER', 'Markee Player'],
];
const groupLabel = code => (ALL_GROUPS.find(g => g[0] === code) || [code, code])[1];
const addedGroupCodes = () =>
  [...document.querySelectorAll('#groupRules .group-row')].map(r => r.dataset.group);

function buildStaticInputs() {
  refreshGroupAddDropdown();
  $('group-add-btn').addEventListener('click', () => {
    const code = $('group-add-select').value;
    if (!code) return;
    addGroupRow(code, {});
    refreshGroupAddDropdown();
  });
}

/** Offers only groups not already added; disables the button when all are in. */
function refreshGroupAddDropdown() {
  const added = new Set(addedGroupCodes());
  const avail = ALL_GROUPS.filter(([code]) => !added.has(code));
  const sel = $('group-add-select');
  sel.innerHTML = avail.length
    ? avail.map(([code, label]) => `<option value="${code}">${label}</option>`).join('')
    : '<option value="">All groups added</option>';
  $('group-add-btn').disabled = avail.length === 0;
}

/** One configurable group: base price + per-team max/min (+ optional reserve/budget). */
function addGroupRow(code, v) {
  const row = document.createElement('div');
  row.className = 'group-row';
  row.dataset.group = code;
  row.innerHTML = `
    <span class="cat-tag" title="${groupLabel(code)}">${groupLabel(code)}</span>
    <input class="gr-base" type="number" min="0" placeholder="own base" value="${v.base ?? ''}">
    <input class="gr-max" type="number" min="0" placeholder="—" value="${v.max ?? ''}">
    <input class="gr-min" type="number" min="0" placeholder="0" value="${v.min ?? ''}">
    <input class="gr-reserve" type="number" min="0" placeholder="base" value="${v.reserve ?? ''}">
    <input class="gr-budget" type="number" min="0" placeholder="none" value="${v.budget ?? ''}">
    <button type="button" class="ghost gr-del" title="Remove group">✕</button>`;
  row.querySelector('.gr-del').addEventListener('click', () => { row.remove(); refreshGroupAddDropdown(); });
  $('groupRules').appendChild(row);
}

function bandRow(upTo, inc) {
  const row = document.createElement('div');
  row.className = 'band-row';
  row.innerHTML = `
    <label>up to ₹<input type="number" class="band-upto" min="1" value="${upTo ?? ''}"></label>
    <label>step ₹<input type="number" class="band-inc" min="1" value="${inc ?? ''}"></label>
    <button type="button" class="ghost band-del" title="Remove band">✕</button>`;
  row.querySelector('.band-del').addEventListener('click', () => row.remove());
  return row;
}

function numOrNull(v) {
  const s = String(v).trim();
  return s === '' ? null : Number(s);
}

// The rules being edited, kept whole so readRules can preserve fields the form
// doesn't expose (e.g. a role-based tournament's unsoldTransitions and
// retentionBasePriceMultiplier) instead of silently dropping them on save.
let editorBaseRules = {};

function fillEditor(rules) {
  editorBaseRules = rules || {};
  $('f-startingPurse').value = rules.teamDefaults.startingPurse;
  $('f-maxSquadSize').value = rules.teamDefaults.maxSquadSize;
  $('f-minViablePrice').value = rules.minViablePrice;
  $('f-defaultIncrement').value = rules.defaultIncrement;
  $('f-demote').checked = !!rules.demoteUnsoldPlayers;
  // One row per group this tournament configures (base prices ∪ category rules),
  // in the canonical ALL_GROUPS order.
  $('groupRules').innerHTML = '';
  const codes = new Set([...Object.keys(rules.basePrices || {}),
                         ...Object.keys(rules.categoryRules || {})]);
  ALL_GROUPS.forEach(([code]) => {
    if (!codes.has(code)) return;
    const c = (rules.categoryRules && rules.categoryRules[code]) || {};
    addGroupRow(code, {
      base: rules.basePrices?.[code], max: c.maxPerTeam, min: c.minPerTeam,
      reserve: c.reservePerSlot, budget: c.budget,
    });
  });
  refreshGroupAddDropdown();
  const bands = $('incrementRules');
  bands.innerHTML = '';
  (rules.incrementRules || []).forEach(b => bands.appendChild(bandRow(b.upTo, b.increment)));
  const r = rules.retention || {};
  $('f-ret-maxPerTeam').value = r.maxPerTeam ?? 0;
  $('f-ret-maxFromGroupA').value = r.maxFromGroupA ?? 0;
  $('f-ret-maxFromLowerGroups').value = r.maxFromLowerGroups ?? 0;
  $('f-ret-costGroupA').value = r.costGroupA ?? 0;
  $('f-ret-costOtherGroups').value = r.costOtherGroups ?? 0;
}

function readRules() {
  const basePrices = {};
  const categoryRules = {};
  document.querySelectorAll('#groupRules .group-row').forEach(row => {
    const g = row.dataset.group;
    const base = numOrNull(row.querySelector('.gr-base').value);
    if (base != null) basePrices[g] = base;   // blank base = no group default price
    categoryRules[g] = {
      maxPerTeam: numOrNull(row.querySelector('.gr-max').value),
      minPerTeam: numOrNull(row.querySelector('.gr-min').value),
      reservePerSlot: numOrNull(row.querySelector('.gr-reserve').value),
      budget: numOrNull(row.querySelector('.gr-budget').value),
    };
  });
  const incrementRules = [];
  document.querySelectorAll('#incrementRules .band-row').forEach(row => {
    const upTo = numOrNull(row.querySelector('.band-upto').value);
    const increment = numOrNull(row.querySelector('.band-inc').value);
    if (upTo != null && increment != null) incrementRules.push({ upTo, increment });
  });
  incrementRules.sort((a, b) => a.upTo - b.upTo);
  return {
    // Preserve any fields the form doesn't manage (unsoldTransitions,
    // retentionBasePriceMultiplier, …); the explicit keys below then override.
    ...editorBaseRules,
    minViablePrice: Number($('f-minViablePrice').value),
    basePrices,
    incrementRules,
    defaultIncrement: Number($('f-defaultIncrement').value),
    categoryRules,
    retention: {
      maxPerTeam: Number($('f-ret-maxPerTeam').value),
      maxFromGroupA: Number($('f-ret-maxFromGroupA').value),
      maxFromLowerGroups: Number($('f-ret-maxFromLowerGroups').value),
      costGroupA: Number($('f-ret-costGroupA').value),
      costOtherGroups: Number($('f-ret-costOtherGroups').value),
    },
    teamDefaults: {
      startingPurse: Number($('f-startingPurse').value),
      maxSquadSize: Number($('f-maxSquadSize').value),
    },
    demoteUnsoldPlayers: $('f-demote').checked,
    seedDemoData: false,
  };
}

function showEditor(show) {
  $('editor').classList.toggle('hidden', !show);
  if (show) $('editor').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

async function openNew() {
  let template;
  try {
    template = await getJSON('/api/config'); // active tournament's rules as a starting point
  } catch (e) { toast('Could not load a rules template', true); return; }
  $('edit-id').value = '';
  $('f-name').value = '';
  $('editor-title').textContent = 'New auction';
  $('btn-save').textContent = 'Create & load';
  fillEditor(template);
  showEditor(true);
}

async function openEditor(id) {
  let detail;
  try {
    detail = await getJSON('/api/admin/tournaments/' + id);
  } catch (e) { toast('Could not load tournament', true); return; }
  $('edit-id').value = detail.id;
  $('f-name').value = detail.name;
  $('editor-title').textContent = 'Edit rules — ' + detail.name;
  $('btn-save').textContent = 'Save changes';
  fillEditor(detail.rules);
  showEditor(true);
}

async function submitEditor(e) {
  e.preventDefault();
  const name = $('f-name').value.trim();
  if (!name) { toast('Name is required', true); return; }
  const body = { name, rules: readRules() };
  const id = $('edit-id').value;
  if (id) {
    const r = await send('PUT', '/api/admin/tournaments/' + id, body);
    if (r) { toast('Saved ' + r.name); showEditor(false); loadList(); }
  } else {
    const r = await send('POST', '/api/admin/tournaments', body);
    if (r) { toast('Created ' + r.name); location.href = 'index.html?tournamentId=' + encodeURIComponent(r.id); }
  }
}

document.addEventListener('DOMContentLoaded', async () => {
  buildStaticInputs();
  $('btn-new').addEventListener('click', openNew);
  $('btn-cancel').addEventListener('click', () => showEditor(false));
  $('add-band').addEventListener('click', () => $('incrementRules').appendChild(bandRow('', '')));
  $('form-editor').addEventListener('submit', submitEditor);
  $('search').addEventListener('input', render);

  // The app admin gets create / delete + can run every auction; a tournament
  // admin can run only its granted auctions; guests and owners get a read-only
  // list. Only the app admin sees "New auction".
  try {
    const me = window.authReady ? await window.authReady : null;
    IS_APP_ADMIN = !!(me && me.role === 'ADMIN');
    GRANTS = new Set(me && Array.isArray(me.adminTournamentIds) ? me.adminTournamentIds : []);
  } catch (e) { /* treat as guest */ }
  if (IS_APP_ADMIN) $('btn-new').classList.remove('hidden');

  loadList();
});
