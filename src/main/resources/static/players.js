/* Players & analysis — owner-facing. Lists every player with full detail and
 * lets the owner filter (role, status; default: unsold), sort by any available
 * stat, and group by category / role / status. Data is fetched from the public
 * read APIs and all filtering/sorting/grouping happens client-side, so the view
 * redistributes instantly as options change. Polls every 8s to stay live. */

const fmtINR = n => n == null ? '—'
    : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);
const fmtShort = n => {
  if (n == null) return '—';
  if (n >= 1e7) return '₹' + (n / 1e7).toFixed(2).replace(/\.?0+$/, '') + ' Cr';
  if (n >= 1e5) return '₹' + (n / 1e5).toFixed(1).replace(/\.0$/, '') + ' L';
  return '₹' + n;
};
const num = n => n == null ? '—' : n;
const esc = s => String(s ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const ROLE_NAME = { BATSMAN: 'Batsman', BOWLER: 'Bowler', ALL_ROUNDER: 'All-rounder', WICKETKEEPER: 'Wicketkeeper' };
const ROLE_ICON = { BATSMAN: '🏏', BOWLER: '🎯', ALL_ROUNDER: '🔄', WICKETKEEPER: '🧤' };
const STATUS_LABEL = {
  AVAILABLE: 'Available', UNSOLD: 'Unsold', SOLD: 'Sold',
  RETAINED: 'Retained', UNDER_AUCTION: 'Under auction',
};

// Sort keys → how to read the value. Only stats the data model actually carries
// are offered (there is no separate bowling average / bowling strike rate yet;
// economy and wickets are the available bowling metrics).
const SORTS = {
  name:           { label: 'Name',                get: p => p.name,                 type: 'str' },
  basePrice:      { label: 'Base price',          get: p => p.basePrice,            type: 'num' },
  soldPrice:      { label: 'Sold price',          get: p => p.soldPrice,            type: 'num' },
  battingAverage: { label: 'Batting average',     get: p => p.stats?.battingAverage, type: 'num' },
  strikeRate:     { label: 'Batting strike rate', get: p => p.stats?.strikeRate,    type: 'num' },
  economyRate:    { label: 'Bowling economy',     get: p => p.stats?.economyRate,   type: 'num' },
  wickets:        { label: 'Wickets',             get: p => p.stats?.wickets,       type: 'num' },
  runs:           { label: 'Runs',                get: p => p.stats?.runs,          type: 'num' },
  matches:        { label: 'Matches',             get: p => p.stats?.matches,       type: 'num' },
};

const GROUP_ORDER = {
  category: ['A', 'B', 'C', 'D', 'E'],
  role: ['BATSMAN', 'ALL_ROUNDER', 'WICKETKEEPER', 'BOWLER'],
  status: ['UNDER_AUCTION', 'AVAILABLE', 'UNSOLD', 'SOLD', 'RETAINED'],
};
const GROUP_LABEL = {
  category: g => 'Group ' + g,
  role: g => (ROLE_ICON[g] || '') + ' ' + (ROLE_NAME[g] || g),
  status: g => STATUS_LABEL[g] || g,
};
const GROUP_KEY = { category: p => p.category, role: p => p.role, status: p => p.status };

const state = {
  scope: 'all', status: 'UNSOLD', role: 'ALL',
  sort: 'battingAverage', sortDir: 'desc', group: 'none', search: '',
};

let allPlayers = [];
let teamNames = {};     // teamId -> name
let myTeamId = null;

const $ = id => document.getElementById(id);

async function getJSON(url) {
  const res = await fetch(url, { cache: 'no-store' });
  if (!res.ok) throw new Error(url + ' -> ' + res.status);
  return res.json();
}

function teamName(id) { return id ? (teamNames[id] || '—') : '—'; }

function sortPlayers(list) {
  const s = SORTS[state.sort];
  const dir = state.sortDir === 'asc' ? 1 : -1;
  return [...list].sort((a, b) => {
    const av = s.get(a), bv = s.get(b);
    const an = av == null, bn = bv == null;
    if (an && bn) return a.name.localeCompare(b.name);
    if (an) return 1;   // missing values always sink to the bottom
    if (bn) return -1;
    if (s.type === 'str') return dir * String(av).localeCompare(String(bv));
    return dir * (av - bv);
  });
}

function filtered() {
  return allPlayers.filter(p => {
    if (state.scope === 'mine' && p.soldToTeamId !== myTeamId) return false;
    if (state.status !== 'ALL' && p.status !== state.status) return false;
    if (state.role !== 'ALL' && p.role !== state.role) return false;
    if (state.search && !p.name.toLowerCase().includes(state.search)) return false;
    return true;
  });
}

function rowHtml(p, i) {
  const st = p.stats || {};
  return `
    <tr>
      <td class="muted">${i + 1}</td>
      <td><a class="plink" href="player.html?playerId=${p.playerId}"><b>${esc(p.name)}</b></a></td>
      <td>${ROLE_ICON[p.role] || ''} ${ROLE_NAME[p.role] || p.role}</td>
      <td><span class="chip">${p.category}</span></td>
      <td><span class="badge ${p.status}">${(STATUS_LABEL[p.status] || p.status)}</span></td>
      <td>${fmtShort(p.basePrice)}</td>
      <td>${p.soldPrice != null ? `${fmtShort(p.soldPrice)} <span class="muted">· ${esc(teamName(p.soldToTeamId))}</span>` : '—'}</td>
      <td class="numc">${num(st.matches)}</td>
      <td class="numc">${num(st.runs)}</td>
      <td class="numc">${num(st.battingAverage)}</td>
      <td class="numc">${num(st.strikeRate)}</td>
      <td class="numc">${num(st.wickets)}</td>
      <td class="numc">${num(st.economyRate)}</td>
    </tr>`;
}

function tableHtml(list) {
  const head = `
    <thead><tr>
      <th>#</th><th>Player</th><th>Role</th><th>Grp</th><th>Status</th>
      <th>Base</th><th>Bought</th>
      <th class="numc">Mat</th><th class="numc">Runs</th><th class="numc">Bat Avg</th>
      <th class="numc">Bat SR</th><th class="numc">Wkts</th><th class="numc">Econ</th>
    </tr></thead>`;
  const body = list.length
      ? list.map((p, i) => rowHtml(p, i)).join('')
      : '<tr><td colspan="13" class="muted">No players match these filters.</td></tr>';
  return `<div class="table-scroll"><table class="pool players-table">${head}<tbody>${body}</tbody></table></div>`;
}

function render() {
  const rows = filtered();
  const total = rows.length;

  if (state.group === 'none') {
    $('results').innerHTML = `<section class="card">${tableHtml(sortPlayers(rows))}</section>`;
  } else {
    const key = state.group;
    const buckets = {};
    for (const p of rows) (buckets[GROUP_KEY[key](p)] ||= []).push(p);
    const ordered = GROUP_ORDER[key].filter(k => buckets[k])
        .concat(Object.keys(buckets).filter(k => !GROUP_ORDER[key].includes(k)));
    $('results').innerHTML = ordered.map(k => `
      <section class="card group-block">
        <h3 class="group-head">${GROUP_LABEL[key](k)} <span class="muted">· ${buckets[k].length} player${buckets[k].length === 1 ? '' : 's'}</span></h3>
        ${tableHtml(sortPlayers(buckets[k]))}
      </section>`).join('') || '<section class="card"><p class="muted">No players match these filters.</p></section>';
  }

  const scopeTxt = state.scope === 'mine' ? 'your squad' : 'all players';
  const statusTxt = state.status === 'ALL' ? 'any status' : (STATUS_LABEL[state.status] || state.status).toLowerCase();
  $('summary').textContent =
      `${total} player${total === 1 ? '' : 's'} · ${scopeTxt} · ${statusTxt}`
      + ` · sorted by ${SORTS[state.sort].label.toLowerCase()} (${state.sortDir})`
      + (state.group !== 'none' ? ` · grouped by ${state.group}` : '');
}

async function load() {
  try {
    const [players, dash] = await Promise.all([
      getJSON('/api/players'),
      getJSON('/api/dashboard').catch(() => ({ teams: [] })),
    ]);
    allPlayers = players;
    teamNames = Object.fromEntries((dash.teams || []).map(t => [t.teamId, t.name]));
    render();
  } catch (e) {
    $('results').innerHTML = '<section class="card"><p class="muted">Could not load players. Retrying…</p></section>';
  }
}

function buildSortOptions() {
  $('sort').innerHTML = Object.entries(SORTS)
      .map(([k, v]) => `<option value="${k}" ${k === state.sort ? 'selected' : ''}>${v.label}</option>`).join('');
}

function wire() {
  $('scope').onchange = e => {
    state.scope = e.target.value;
    // Your squad is only ever sold/retained players, so default to all statuses there.
    if (state.scope === 'mine') { state.status = 'ALL'; $('status').value = 'ALL'; }
    else { state.status = 'UNSOLD'; $('status').value = 'UNSOLD'; }
    render();
  };
  $('status').onchange = e => { state.status = e.target.value; render(); };
  $('role').onchange = e => { state.role = e.target.value; render(); };
  $('sort').onchange = e => { state.sort = e.target.value; render(); };
  $('group').onchange = e => { state.group = e.target.value; render(); };
  $('sort-dir').onclick = () => {
    state.sortDir = state.sortDir === 'asc' ? 'desc' : 'asc';
    $('sort-dir').textContent = state.sortDir === 'asc' ? '▲ Asc' : '▼ Desc';
    render();
  };
  let searchTimer = null;
  $('search').oninput = e => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => { state.search = e.target.value.trim().toLowerCase(); render(); }, 150);
  };
}

(async function init() {
  const me = window.authReady ? await window.authReady : null;
  myTeamId = me && me.teamId ? me.teamId : null;
  if (myTeamId) {
    // Offer a "my squad" scope for franchise owners.
    const opt = document.createElement('option');
    opt.value = 'mine';
    opt.textContent = 'My squad only';
    $('scope').appendChild(opt);
  }
  buildSortOptions();
  wire();
  await load();
  setInterval(load, 8000);
})();
