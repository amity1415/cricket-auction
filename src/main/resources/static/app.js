/* Admin console — polls the JSON API every 2s; the server is the single
 * source of truth after every action (ARCHITECTURE.md section 6). */

const fmtINR = n => n == null ? '—'
    : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

const fmtShort = n => {
  if (n == null) return '—';
  if (n >= 1e7) return '₹' + (n / 1e7).toFixed(2).replace(/\.?0+$/, '') + ' Cr';
  if (n >= 1e5) return '₹' + (n / 1e5).toFixed(1).replace(/\.0$/, '') + ' L';
  return fmtINR(n);
};

const esc = s => String(s ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const ROLE_SHORT = { BATSMAN: 'BAT', BOWLER: 'BWL', ALL_ROUNDER: 'AR', WICKETKEEPER: 'WK' };
const ROLE_LABEL = { BATSMAN: 'Batsman', BOWLER: 'Bowler', ALL_ROUNDER: 'All Rounder', WICKETKEEPER: 'Wicket Keeper' };
const ROLE_ORDER = ['BATSMAN', 'BOWLER', 'ALL_ROUNDER', 'WICKETKEEPER'];
const POOL_CAT_ORDER = ['A', 'B', 'C', 'D', 'E', 'MIXED_UTILITY_BAG', 'WICKET_KEEPER', 'BOWLER', 'ALL_ROUNDER', 'MARKEE_PLAYER'];
const POOL_CAT_LABEL = {
  A: 'A', B: 'B', C: 'C', D: 'D', E: 'E',
  MIXED_UTILITY_BAG: 'Mixed Utility', WICKET_KEEPER: 'Wicket Keeper',
  BOWLER: 'Bowler', ALL_ROUNDER: 'All Rounder', MARKEE_PLAYER: 'Markee',
};
const poolCatLabel = c => POOL_CAT_LABEL[c] || c;
const orderRank = (order, v) => { const i = order.indexOf(v); return i < 0 ? 999 : i; };

let toastTimer = null;
let auctionConfig = null; // rule book from /api/config (group quotas etc.)
let poolFilter = 'ALL';
let poolSearch = '';
const poolRoles = new Set();       // 2nd-level Role refine (empty = all)
const poolCats = new Set();        // 2nd-level Category refine (empty = all)
let lastPlayers = [];     // latest poll results, so search re-renders instantly
let lastTeams = [];

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

async function post(url, body) {
  const res = await fetch(url, {
    method: 'POST',
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

async function refresh() {
  try {
    const [dash, players, audit] = await Promise.all([
      getJSON('/api/dashboard'),
      getJSON('/api/players'),
      getJSON('/api/admin/audit'),
    ]);
    lastPlayers = players;
    lastTeams = dash.teams;
    renderBlock(dash);
    renderTeams(dash);
    renderPool(players, dash.teams);
    renderAudit(audit);
    if (dash.onTheBlock) renderBidHistory(dash.onTheBlock.playerId);
    else document.getElementById('bid-history').innerHTML = '';
  } catch (e) {
    /* server briefly unreachable — keep last render, retry on next tick */
  }
}

/** Career profile shown while the player is on the block; hides null stats. */
function profileStats(st) {
  if (!st) return '';
  const items = [
    ['Matches', st.matches], ['Runs', st.runs], ['Avg', st.battingAverage],
    ['SR', st.strikeRate], ['Wickets', st.wickets], ['Econ', st.economyRate],
  ].filter(([, v]) => v != null);
  if (!items.length) return '';
  return `<div class="pstats">${items.map(([label, v]) =>
      `<div class="pstat"><b>${v}</b><span>${label}</span></div>`).join('')}</div>`;
}

function renderBlock(dash) {
  const block = dash.onTheBlock;
  const content = document.getElementById('block-content');
  const buttons = document.getElementById('bid-buttons');
  const btnConfirm = document.getElementById('btn-confirm');
  const btnUnsold = document.getElementById('btn-unsold');
  const btnUndo = document.getElementById('btn-undo');

  if (!block) {
    content.innerHTML = '<p class="muted">Nobody is under auction. Pick a player from the pool →</p>';
    buttons.innerHTML = '';
    btnConfirm.disabled = true;
    btnUnsold.disabled = true;
    btnUndo.disabled = true;
    document.getElementById('custom-bid').style.display = 'none';
    return;
  }

  const hasBid = block.currentBidAmount != null;
  content.innerHTML = `
    <div class="block-player">
      <a class="name plink" href="player.html?playerId=${block.playerId}" title="Open full profile">${esc(block.name)}</a>
      <span>
        <span class="chip">${ROLE_SHORT[block.role] || block.role}</span>
        <span class="chip">${block.category}</span>
      </span>
    </div>
    ${profileStats(block.stats)}
    <div class="bid-line">
      ${hasBid
        ? `<span class="amount">${fmtINR(block.currentBidAmount)}</span>
           <span class="leader"> leading: <b>${esc(block.currentLeadingTeamName)}</b> · bid #${block.bidCount}</span>`
        : `<span class="amount">${fmtINR(block.basePrice)}</span>
           <span class="leader"> base price — no bids yet</span>`}
    </div>
    <div class="next-bid">Next bid: <b>${fmtINR(block.nextBidAmount)}</b></div>`;

  buttons.innerHTML = '';
  for (const t of dash.teams) {
    const btn = document.createElement('button');
    const leading = t.teamId === block.currentLeadingTeamId;
    btn.disabled = leading;
    if (!leading && block.nextBidAmount > t.remainingPurse) btn.classList.add('cant-afford');
    btn.innerHTML = `<b>${esc(t.name)}</b>${leading ? ' 👑' : ''}
      <span class="sub">${fmtShort(t.remainingPurse)} left</span>`;
    btn.onclick = async () => {
      const r = await post(`/api/admin/players/${block.playerId}/place-bid`, { teamId: t.teamId });
      if (r) toast(`Bid #${r.bidNumber}: ${r.currentLeadingTeamName} → ${fmtINR(r.currentBidAmount)}`);
      refresh();
    };
    buttons.appendChild(btn);
  }

  btnConfirm.disabled = !hasBid;
  btnUnsold.disabled = false;
  btnUndo.disabled = !hasBid;
  btnConfirm.onclick = async () => {
    const r = await post(`/api/admin/players/${block.playerId}/confirm-sale`);
    if (r) toast(`SOLD! ${r.player.name} → ${esc(r.teams[0].name)} for ${fmtINR(r.player.soldPrice)}`);
    refresh();
  };
  btnUndo.onclick = async () => {
    const r = await post(`/api/admin/players/${block.playerId}/undo-bid`);
    if (r) toast(r.currentBidAmount != null
        ? `Bid undone — back to ${fmtINR(r.currentBidAmount)} by ${esc(r.currentLeadingTeamName)}`
        : `Bid undone — no bids on ${r.name}, opens at ${fmtINR(r.basePrice)}`);
    refresh();
  };
  btnUnsold.onclick = async () => {
    const r = await post(`/api/admin/players/${block.playerId}/mark-unsold`);
    if (r) toast(unsoldMessage(r));
    refresh();
  };

  // Manual/floor bid: the auctioneer types an amount + picks a team (for bids
  // called out off the increment ladder). Runs the same checks as a quick bid.
  const cb = document.getElementById('custom-bid');
  cb.style.display = '';
  const teamOpts = '<option value="">Choose team…</option>' + dash.teams.map(t =>
    `<option value="${t.teamId}">${esc(t.name)} — ${fmtShort(t.remainingPurse)} left</option>`).join('');
  const teamSel = document.getElementById('cb-team');
  if (teamSel.dataset.sig !== teamOpts) {          // rebuild only on change → keeps the admin's choice
    const cur = teamSel.value;
    teamSel.innerHTML = teamOpts;
    teamSel.dataset.sig = teamOpts;
    if ([...teamSel.options].some(o => o.value === cur)) teamSel.value = cur;
  }
  document.getElementById('cb-place').onclick = async () => {
    const teamId = teamSel.value;
    const amount = Number(document.getElementById('cb-amount').value);
    if (!teamId) { toast('Pick a team for the manual bid', true); return; }
    if (!amount || amount <= 0) { toast('Enter a valid bid amount', true); return; }
    const r = await post(`/api/admin/players/${block.playerId}/place-bid`, { teamId, amount });
    if (r) {
      toast(`Bid #${r.bidNumber}: ${r.currentLeadingTeamName} → ${fmtINR(r.currentBidAmount)}`);
      document.getElementById('cb-amount').value = '';
      refresh();
    }
  };
}

async function renderBidHistory(playerId) {
  try {
    const bids = await getJSON(`/api/admin/players/${playerId}/bids`);
    const el = document.getElementById('bid-history');
    if (!bids.length) { el.innerHTML = ''; return; }
    el.innerHTML = '<b>Bid history</b>' + bids.slice(-6).reverse().map(b =>
        `<div class="row"><span>#${b.bidNumber} ${esc(b.teamName)}</span><span>${fmtINR(b.amount)}</span></div>`
    ).join('');
  } catch (e) { /* ignore */ }
}

function renderTeams(dash) {
  const leadingId = dash.onTheBlock?.currentLeadingTeamId;
  document.getElementById('teams').innerHTML = dash.teams.map(t => {
    const pct = t.startingPurse > 0 ? (t.remainingPurse / t.startingPurse) * 100 : 0;
    // Roles are informational only — counts, no minimums.
    const roles = Object.entries(t.roleCounts || {})
        .filter(([, n]) => n > 0)
        .map(([role, n]) => `${ROLE_SHORT[role] || role} <b>${n}</b>`)
        .join(' · ');
    const groupRules = auctionConfig?.categoryRules || {};
    const groups = Object.entries(t.categoryCounts || {})
        .filter(([, n]) => n > 0)
        .map(([g, n]) => {
          const max = groupRules[g]?.maxPerTeam;
          return `${g} <b>${n}${max != null ? '/' + max : ''}</b>`;
        })
        .join(' · ');
    const block = dash.onTheBlock;
    const mbCan = block && t.maxBidForBlockPlayer != null && t.maxBidForBlockPlayer >= block.nextBidAmount;
    const maxBidHtml = block
      ? `<div class="team-maxbid${mbCan ? '' : ' none'}">🔨 Max next bid <b>${mbCan ? fmtShort(t.maxBidForBlockPlayer) : "Can't bid"}</b></div>`
      : '';
    return `
      <div class="team ${t.teamId === leadingId ? 'leading' : ''}">
        <div class="tname">${esc(t.name)} ${t.teamId === leadingId ? '👑' : ''}</div>
        <div class="purse">${fmtShort(t.remainingPurse)}</div>
        <div class="bar"><i style="width:${pct}%"></i></div>
        ${maxBidHtml}
        <div class="meta">Squad ${t.squadFilled}/${t.squadFilled + t.squadOpenSlots}</div>
        ${roles ? `<div class="roles">${roles}</div>` : ''}
        ${groups ? `<div class="roles">Groups: ${groups}</div>` : ''}
      </div>`;
  }).join('');
}

function renderPool(players, teams) {
  const teamName = id => teams.find(t => t.teamId === id)?.name || '?';
  const filters = ['ALL', 'AVAILABLE', 'UNDER_AUCTION', 'RETAINED', 'SOLD', 'UNSOLD'];
  document.getElementById('pool-filters').innerHTML = filters.map(f =>
      `<button class="${f === poolFilter ? 'active' : ''}" onclick="setFilter('${f}')">${f.replace('_', ' ')}</button>`
  ).join('');

  // 2nd-level refine chips, built from whatever roles/categories the pool actually has.
  renderRefineChips('pool-role-filters', players, p => p.role, ROLE_ORDER,
      r => ROLE_LABEL[r] || r, poolRoles);
  renderRefineChips('pool-cat-filters', players, p => p.category, POOL_CAT_ORDER,
      poolCatLabel, poolCats);

  // Serial numbers follow the full pool listing (stable across status filters).
  const numbered = players.map((p, i) => ({ ...p, sl: i + 1 }));
  let rows = numbered.filter(p => poolFilter === 'ALL' || p.status === poolFilter);
  if (poolRoles.size) rows = rows.filter(p => poolRoles.has(p.role));
  if (poolCats.size) rows = rows.filter(p => poolCats.has(p.category));
  const q = poolSearch.trim().toLowerCase();
  if (q) {
    rows = /^\d+$/.test(q)
        ? rows.filter(p => p.sl === Number(q))
        : rows.filter(p => p.name.toLowerCase().includes(q));
  }
  document.getElementById('pool-body').innerHTML = rows.map(p => `
    <tr>
      <td class="muted">${p.sl}</td>
      <td><a class="plink" href="player.html?playerId=${p.playerId}" title="Open full profile"><b>${esc(p.name)}</b></a>
        ${p.status === 'SOLD' || p.status === 'RETAINED'
          ? `<span class="sold-info">→ ${esc(teamName(p.soldToTeamId))} · ${fmtINR(p.soldPrice)}</span>` : ''}
      </td>
      <td>${ROLE_SHORT[p.role] || p.role}</td>
      <td>${p.category}</td>
      <td>${fmtShort(p.basePrice)}</td>
      <td><span class="badge ${p.status}">${p.status.replace('_', ' ')}</span></td>
      <td>${p.status === 'AVAILABLE'
          ? `<button class="link-btn" onclick="putOnBlock('${p.playerId}')">On block</button>
             <button class="link-btn subtle" onclick="withdraw('${p.playerId}')" title="Mark unsold without auctioning">✕</button>`
          : p.status === 'RETAINED'
          ? `<button class="link-btn subtle" onclick="releaseRetention('${p.playerId}')" title="Undo retention — refunds the purse">↩ Release</button>`
          : ''}</td>
    </tr>`).join('')
    || `<tr><td colspan="7" class="muted">${q ? `No player matches “${esc(poolSearch.trim())}”.` : 'No players match this filter.'}</td></tr>`;
}

// Builds a set of refine chips (Role / Category) from the values present in the
// pool. Selecting chips filters client-side — no server round-trip, unlike the
// status filter which re-polls. A "signature" guard keeps us from clobbering the
// DOM (and losing hover/focus) when the set of values hasn't changed.
const _refineSig = {};
function renderRefineChips(elId, players, pick, order, label, selected) {
  const el = document.getElementById(elId);
  if (!el) return;
  const values = [...new Set(players.map(pick))]
      .sort((a, b) => orderRank(order, a) - orderRank(order, b));
  const sig = elId + '|' + values.join(',') + '|' + [...selected].join(',');
  if (_refineSig[elId] === sig) return;
  _refineSig[elId] = sig;
  el.innerHTML = values.map(v =>
      `<button type="button" class="${selected.has(v) ? 'active' : ''}" data-v="${v}">${label(v)}</button>`
  ).join('');
  el.querySelectorAll('button').forEach(b => b.onclick = () => {
    const v = b.dataset.v;
    selected.has(v) ? selected.delete(v) : selected.add(v);
    renderPool(lastPlayers, lastTeams); // instant, client-side
  });
}

const poolSearchInput = document.getElementById('pool-search');
poolSearchInput.oninput = () => {
  poolSearch = poolSearchInput.value;
  renderPool(lastPlayers, lastTeams); // instant, no server round-trip
};
document.getElementById('pool-search-clear').onclick = () => {
  poolSearchInput.value = '';
  poolSearch = '';
  renderPool(lastPlayers, lastTeams);
  poolSearchInput.focus();
};

function renderAudit(entries) {
  const el = document.getElementById('audit');
  if (!entries.length) { el.innerHTML = '<p class="muted">No sales yet.</p>'; return; }
  const line = s => ({
    SOLD: `🔨 <b>${esc(s.playerName)}</b> → ${esc(s.teamName)} · ${fmtINR(s.amount)}`,
    UNSOLD: `⛔ <b>${esc(s.playerName)}</b> unsold`,
    RETAINED: `📌 <b>${esc(s.playerName)}</b> retained by ${esc(s.teamName)} · ${fmtINR(s.amount)}`,
    RELEASED: `↩ <b>${esc(s.playerName)}</b> released by ${esc(s.teamName)} · ${fmtINR(s.amount)} refunded`,
  }[s.type] || `<b>${esc(s.playerName)}</b> ${s.type}`);
  el.innerHTML = entries.slice().reverse().map(s => `
    <div class="row">
      <span>${line(s)}</span>
      <span class="when">${new Date(s.recordedAt).toLocaleTimeString()}</span>
    </div>`).join('');
}

window.setFilter = f => { poolFilter = f; refresh(); };

window.releaseRetention = async id => {
  const r = await post(`/api/admin/players/${id}/release-retention`);
  if (r) toast(`↩ Released ${r.player.name} — purse refunded`);
  refresh();
};

window.putOnBlock = async id => {
  const r = await post(`/api/admin/players/${id}/mark-under-auction`);
  if (r) toast(`${r.name} is on the block`);
  refresh();
};

window.withdraw = async id => {
  const r = await post(`/api/admin/players/${id}/mark-unsold`);
  if (r) toast(unsoldMessage(r));
  refresh();
};

/** Demotion rule: unsold players may come back one group lower instead of leaving. */
function unsoldMessage(p) {
  return p.status === 'AVAILABLE'
      ? `${p.name} unsold — moved down to group ${p.category} (base ${fmtShort(p.basePrice)})`
      : `${p.name} marked unsold`;
}

document.getElementById('form-player').onsubmit = async e => {
  e.preventDefault();
  const f = new FormData(e.target);
  const r = await post('/api/admin/players', {
    name: f.get('name'),
    role: f.get('role'),
    category: f.get('category'),
    basePrice: f.get('basePrice') ? Number(f.get('basePrice')) : null,
  });
  if (r) { toast(`Registered ${r.name}`); e.target.reset(); refresh(); }
};

document.getElementById('form-team').onsubmit = async e => {
  e.preventDefault();
  const f = new FormData(e.target);
  const r = await post('/api/admin/teams', {
    name: f.get('name'),
    ownerName: f.get('ownerName'),
    startingPurse: Number(f.get('startingPurse')),
    maxSquadSize: Number(f.get('maxSquadSize')),
  });
  if (r) { toast(`Registered ${r.name}`); e.target.reset(); refresh(); }
};

getJSON('/api/config').then(c => { auctionConfig = c; }).catch(() => {});
refresh();
setInterval(refresh, 2000);
