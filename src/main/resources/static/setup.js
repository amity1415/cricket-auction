/* Setup page — import/replace the pool, manage players and teams (add, edit,
 * remove via modals), and handle pre-auction retentions. */

const fmtShort = n => {
  if (n == null) return '—';
  if (n >= 1e7) return '₹' + (n / 1e7).toFixed(2).replace(/\.?0+$/, '') + ' Cr';
  if (n >= 1e5) return '₹' + (n / 1e5).toFixed(1).replace(/\.0$/, '') + ' L';
  return '₹' + n;
};
const esc = s => String(s ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const ROLE_SHORT = { BATSMAN: 'BAT', BOWLER: 'BWL', ALL_ROUNDER: 'AR', WICKETKEEPER: 'WK' };

let toastTimer = null;
function toast(message, isError) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.className = 'toast ' + (isError ? 'error' : 'ok');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.className = 'toast hidden', isError ? 6000 : 2500);
}

async function getJSON(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error('GET ' + url + ' → ' + res.status);
  return res.json();
}

/** JSON request helper; shows the server's actionable message on failure. */
async function send(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return {};
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    toast(data.message || data.error || `Request failed (${res.status})`, true);
    return null;
  }
  return data;
}

let auctionConfig = null;
let lastPlayers = [];
let lastTeams = [];
let pmSearch = '';

async function refresh() {
  try {
    const [players, dash] = await Promise.all([
      getJSON('/api/players'),
      getJSON('/api/dashboard'),
    ]);
    lastPlayers = players;
    lastTeams = dash.teams;

    const byStatus = {};
    for (const p of players) byStatus[p.status] = (byStatus[p.status] || 0) + 1;
    const detail = Object.entries(byStatus).map(([s, n]) => `${n} ${s.replace('_', ' ')}`).join(' · ');
    document.getElementById('pool-status').innerHTML = players.length
        ? `<b>${players.length} players</b> currently loaded (${detail}). Importing a file replaces all of them.`
        : 'No players yet — import a file or add them one by one below.';

    renderPlayerManager();
    renderTeams();
    refreshRetention(dash.teams, players);
  } catch (e) { /* server briefly unreachable — retry on next tick */ }
}

/* --- Player manager (add / edit / remove) --- */

const EDITABLE = new Set(['AVAILABLE', 'UNSOLD']);

function renderPlayerManager() {
  const countEl = document.getElementById('pm-count');
  if (countEl) countEl.textContent = `· ${lastPlayers.length}`;
  const body = document.getElementById('pm-body');
  if (!body) return; // the player list lives on the Players & analysis page now
  const q = pmSearch.trim().toLowerCase();
  const rows = q ? lastPlayers.filter(p => p.name.toLowerCase().includes(q)) : lastPlayers;
  body.innerHTML = rows.map(p => `
    <tr>
      <td><a class="plink" href="player.html?playerId=${p.playerId}"><b>${esc(p.name)}</b></a></td>
      <td>${ROLE_SHORT[p.role] || p.role}</td>
      <td>${p.category}</td>
      <td>${fmtShort(p.basePrice)}</td>
      <td><span class="badge ${p.status}">${p.status.replace('_', ' ')}</span></td>
      <td>
        ${EDITABLE.has(p.status) ? `
          <div class="row-actions">
            <button class="link-btn" onclick="openPlayerModal('${p.playerId}')">✏️ Edit</button>
            <button class="link-btn subtle" onclick="deletePlayer('${p.playerId}')" title="Remove from the pool">🗑</button>
          </div>` : '<span class="muted" title="Locked while retained / on the block / sold">🔒</span>'}
      </td>
    </tr>`).join('')
    || `<tr><td colspan="6" class="muted">${q ? 'No player matches that filter.' : 'No players yet.'}</td></tr>`;
}

const pmSearchEl = document.getElementById('pm-search');
if (pmSearchEl) pmSearchEl.oninput = () => { pmSearch = pmSearchEl.value; renderPlayerManager(); };

const playerModal = document.getElementById('player-modal');
const playerForm = document.getElementById('player-form');
let editingPlayerId = null;

window.openPlayerModal = playerId => {
  editingPlayerId = playerId || null;
  const p = playerId ? lastPlayers.find(x => x.playerId === playerId) : null;
  document.getElementById('player-modal-title').textContent = p ? `Edit ${p.name}` : 'Add player';
  document.getElementById('player-save').textContent = p ? 'Save changes' : 'Add player';
  playerForm.reset();
  if (p) {
    playerForm.name.value = p.name;
    playerForm.role.value = p.role;
    playerForm.category.value = p.category;
    playerForm.basePrice.value = p.basePrice;
    for (const key of ['matches', 'runs', 'battingAverage', 'strikeRate', 'wickets', 'economyRate']) {
      playerForm[key].value = p.stats?.[key] ?? '';
    }
  }
  playerModal.showModal();
};

const pmAddEl = document.getElementById('pm-add');
if (pmAddEl) pmAddEl.onclick = () => openPlayerModal(null);

playerForm.onsubmit = async e => {
  e.preventDefault();
  const f = playerForm;
  const num = el => f[el].value !== '' ? Number(f[el].value) : null;
  const body = {
    name: f.name.value,
    role: f.role.value,
    category: f.category.value,
    basePrice: num('basePrice'),
    stats: {
      matches: num('matches'), runs: num('runs'), battingAverage: num('battingAverage'),
      strikeRate: num('strikeRate'), wickets: num('wickets'), economyRate: num('economyRate'),
    },
  };
  const r = editingPlayerId
      ? await send('PUT', `/api/admin/players/${editingPlayerId}`, body)
      : await send('POST', '/api/admin/players', body);
  if (r) {
    toast(editingPlayerId ? `Saved ${r.name}` : `Added ${r.name} (group ${r.category})`);
    playerModal.close();
    refresh();
  }
};

window.deletePlayer = async playerId => {
  const p = lastPlayers.find(x => x.playerId === playerId);
  if (!confirm(`Remove ${p?.name ?? 'this player'} from the pool?`)) return;
  const r = await send('DELETE', `/api/admin/players/${playerId}`);
  if (r) { toast(`Removed ${p?.name ?? 'player'}`); refresh(); }
};

/* --- Teams (add / edit / remove) --- */

function renderTeams() {
  document.getElementById('team-list').innerHTML = lastTeams.length
      ? lastTeams.map(t => `
        <div class="row">
          <span><b>${esc(t.name)}</b> <span class="muted">· ${esc(t.ownerName)}</span><br>
            <span class="muted">${fmtShort(t.remainingPurse)} of ${fmtShort(t.startingPurse)} ·
            squad ${t.squadFilled}/${t.squadFilled + t.squadOpenSlots}</span></span>
          <div class="row-actions">
            <button class="link-btn" onclick="openTeamModal('${t.teamId}')">✏️ Edit</button>
            <button class="link-btn subtle" onclick="removeTeam('${t.teamId}')" title="Remove team (squad must be empty)">🗑</button>
          </div>
        </div>`).join('')
      : '<p class="muted">No teams yet — register them below.</p>';
}

const teamModal = document.getElementById('team-modal');
const teamForm = document.getElementById('team-form');
let editingTeamId = null;

window.openTeamModal = teamId => {
  const t = lastTeams.find(x => x.teamId === teamId);
  if (!t) return;
  editingTeamId = teamId;
  teamForm.name.value = t.name;
  teamForm.ownerName.value = t.ownerName;
  teamForm.startingPurse.value = t.startingPurse;
  teamForm.maxSquadSize.value = t.squadFilled + t.squadOpenSlots;
  teamModal.showModal();
};

teamForm.onsubmit = async e => {
  e.preventDefault();
  const r = await send('PUT', `/api/admin/teams/${editingTeamId}`, {
    name: teamForm.name.value,
    ownerName: teamForm.ownerName.value,
    startingPurse: Number(teamForm.startingPurse.value),
    maxSquadSize: Number(teamForm.maxSquadSize.value),
  });
  if (r) { toast(`Saved ${r.name}`); teamModal.close(); refresh(); }
};

window.removeTeam = async teamId => {
  const t = lastTeams.find(x => x.teamId === teamId);
  if (!confirm(`Remove ${t?.name ?? 'this team'}? This cannot be undone.`)) return;
  const r = await send('DELETE', `/api/admin/teams/${teamId}`);
  if (r) { toast(`Removed ${t?.name ?? 'team'}`); refresh(); }
};

/**
 * Pre-fills the "add team" form's purse and squad size from the auction's own
 * team defaults. Called on load AND after each add — a plain form.reset() would
 * otherwise snap them back to the generic HTML fallback values (the bug where
 * every team after the first defaulted to ₹15 Cr instead of the auction's purse).
 */
function applyTeamDefaults() {
  const d = auctionConfig?.teamDefaults;
  const form = document.getElementById('form-team');
  if (!d || !form) return;
  form.querySelector('[name=startingPurse]').value = d.startingPurse;
  form.querySelector('[name=maxSquadSize]').value = d.maxSquadSize;
}

document.getElementById('form-team').onsubmit = async e => {
  e.preventDefault();
  const f = new FormData(e.target);
  const r = await send('POST', '/api/admin/teams', {
    name: f.get('name'),
    ownerName: f.get('ownerName'),
    startingPurse: Number(f.get('startingPurse')),
    maxSquadSize: Number(f.get('maxSquadSize')),
  });
  if (r) { toast(`Registered ${r.name}`); e.target.reset(); applyTeamDefaults(); refresh(); }
};

/* --- Import (replace all) --- */

let importBusy = false;
document.getElementById('form-import').onsubmit = async e => {
  e.preventDefault();
  if (importBusy) return;
  const file = document.getElementById('file').files[0];
  if (!file) return;
  if (lastPlayers.length > 0 && !confirm(
      `This will DELETE all ${lastPlayers.length} existing players and reset any auction progress ` +
      `(bids, sales, squads, purses).\n\nImport "${file.name}" and replace everything?`)) {
    return;
  }
  const fd = new FormData();
  fd.append('file', file);
  const resultEl = document.getElementById('import-result');
  resultEl.innerHTML = '<p class="muted">Importing…</p>';
  importBusy = true;
  try {
    const res = await fetch('/api/admin/players/bulk-import-replace', { method: 'POST', body: fd });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      resultEl.innerHTML = `<p class="import-error">✕ ${esc(data.message || data.error || 'Import failed')}</p>`;
      toast('Import failed', true);
      return;
    }
    resultEl.innerHTML = `<p class="import-ok">✓ Imported ${data.imported} players from ${esc(file.name)}</p>`;
    toast(`Imported ${data.imported} players`);
    e.target.reset();
    refresh();
  } catch (err) {
    resultEl.innerHTML = '<p class="import-error">✕ Server unreachable</p>';
  } finally {
    importBusy = false;
  }
};

/* --- Retentions --- */

const retTeamSel = document.getElementById('ret-team');
const retPlayerSel = document.getElementById('ret-player');

// Only touch an element when its content actually changed. Re-assigning a
// <select>'s innerHTML on every 3s poll was closing the dropdown mid-selection
// (the "list keeps refreshing" bug); a no-op poll now leaves it open. Selects
// keep their current choice across a genuine re-render.
const _retHtml = {};
function setRetHtml(el, html) {
  if (!el || _retHtml[el.id] === html) return;
  _retHtml[el.id] = html;
  if (el.tagName === 'SELECT') {
    const cur = el.value;
    el.innerHTML = html;
    if (cur && [...el.options].some(o => o.value === cur)) el.value = cur;
  } else {
    el.innerHTML = html;
  }
}

async function refreshRetention(teams, players) {
  try {
    // Sort deterministically so identical data yields identical option HTML —
    // otherwise Postgres's unordered rows would differ every poll and re-render.
    const byName = (a, b) => a.name.localeCompare(b.name);
    setRetHtml(retTeamSel, '<option value="">Choose a team…</option>' + [...teams].sort(byName).map(t =>
        `<option value="${t.teamId}">${esc(t.name)} — ${fmtShort(t.remainingPurse)} left</option>`).join(''));

    const available = players.filter(p => p.status === 'AVAILABLE').sort(byName);
    setRetHtml(retPlayerSel, '<option value="">Choose a player to retain…</option>' + available.map(p =>
        `<option value="${p.playerId}">${esc(p.name)} — Group ${p.category} (${fmtShort(p.basePrice)})</option>`).join(''));

    const slotsEl = document.getElementById('ret-slots');
    const listEl = document.getElementById('ret-list');
    if (!retTeamSel.value) { setRetHtml(slotsEl, ''); setRetHtml(listEl, ''); return; }

    const detail = await getJSON(`/api/dashboard/teams/${retTeamSel.value}`);
    const retained = detail.squad.filter(p => p.retained);
    const rules = auctionConfig?.retention || { maxPerTeam: 3, maxFromGroupA: 2, maxFromLowerGroups: 1 };
    const fromA = retained.filter(p => p.category === 'A').length;
    setRetHtml(slotsEl, `Group A <b>${fromA}/${rules.maxFromGroupA}</b> ·
        Lower groups <b>${retained.length - fromA}/${rules.maxFromLowerGroups}</b> ·
        Total <b>${retained.length}/${rules.maxPerTeam}</b>`);
    setRetHtml(listEl, retained.length
        ? retained.map(p => `
          <div class="row">
            <span>📌 <a class="plink" href="player.html?playerId=${p.playerId}"><b>${esc(p.name)}</b></a>
              <span class="chip">${p.category}</span> · ${fmtShort(p.soldPrice)}</span>
            <button class="link-btn subtle" onclick="releaseRetention('${p.playerId}')">↩ Release</button>
          </div>`).join('')
        : '<p class="muted">No retentions yet for this team.</p>');
  } catch (e) { /* retry on next tick */ }
}

retTeamSel.onchange = () => refreshRetention(lastTeams, lastPlayers);

document.getElementById('ret-btn').onclick = async () => {
  if (!retTeamSel.value || !retPlayerSel.value) {
    toast('Pick a team and a player first', true);
    return;
  }
  const r = await send('POST', `/api/admin/teams/${retTeamSel.value}/retain/${retPlayerSel.value}`);
  if (r) {
    toast(`📌 Retained ${r.player.name} for ${fmtShort(r.player.soldPrice)}`);
    retPlayerSel.value = '';
    refresh();
  }
};

window.releaseRetention = async playerId => {
  const r = await send('POST', `/api/admin/players/${playerId}/release-retention`);
  if (r) {
    toast(`↩ Released ${r.player.name} — ${fmtShort(r.player.basePrice)} refunded`);
    refresh();
  }
};

/* --- Sample CSV + boot --- */

const SAMPLE = [
  'name,role,category,basePrice,matches,runs,battingAvg,strikeRate,wickets,economy',
  'Rohit Verma,BATSMAN,A,20000000,150,5100,42.5,139.8,,',
  'James Wood,BOWLER,B,10000000,95,120,8.9,90.5,115,7.6',
  'Aman Singh,ALL_ROUNDER,C,,70,1100,24.4,128.0,52,8.3',
  'Ryan Cole,WICKETKEEPER,D,,40,860,26.1,124.5,,',
  'Sunil Yadav,BOWLER,E,,15,20,6.0,70.0,18,7.9',
].join('\n');
document.getElementById('sample').href =
    URL.createObjectURL(new Blob([SAMPLE], { type: 'text/csv' }));

(async () => {
  try {
    auctionConfig = await getJSON('/api/config');
    applyTeamDefaults();
  } catch (e) { /* keep the HTML defaults */ }
  refresh();
})();

setInterval(refresh, 3000);
