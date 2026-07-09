/* Team-owner dashboard — read-only, polls every 3s (ARCHITECTURE.md section 6).
 * V1 has no auth; the team is picked via ?teamId=… (scoped auth is phase 5). */

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
const ROLE_ICON = { BATSMAN: '🏏', BOWLER: '🎯', ALL_ROUNDER: '🔄', WICKETKEEPER: '🧤' };

const teamId = new URLSearchParams(location.search).get('teamId');
let auctionConfig = null;
let myTeamId = null; // the logged-in owner's own team, if any (set on init)

async function getJSON(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(url + ' → ' + res.status);
  return res.json();
}

async function showPicker() {
  const dash = await getJSON('/api/dashboard');
  document.getElementById('picker').style.display = '';
  document.getElementById('picker-links').innerHTML = dash.teams.map(t =>
      `<a href="team.html?teamId=${t.teamId}">${esc(t.name)} — ${esc(t.ownerName)}`
      + `${t.teamId === myTeamId ? ' ⭐ Your team' : ''}</a>`).join('');
}

async function refresh() {
  try {
    const [detail, dash] = await Promise.all([
      getJSON(`/api/dashboard/teams/${teamId}`),
      getJSON('/api/dashboard'),
    ]);
    document.getElementById('dashboard').style.display = '';
    renderBanner(dash.onTheBlock, detail.team);
    renderHead(detail.team, detail.squad);
    renderComposition(detail.team);
    renderSquad(detail.squad, detail.team);
  } catch (e) { /* retry on next tick */ }
}

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

function renderBanner(block, team) {
  const el = document.getElementById('block-banner');
  if (!block) { el.innerHTML = ''; return; }
  const leading = block.currentLeadingTeamId === team.teamId;
  el.innerHTML = `
    <div class="banner ${leading ? 'leading' : ''}">
      <a class="plink" href="player.html?playerId=${block.playerId}"><b>${esc(block.name)}</b></a>
      (${ROLE_SHORT[block.role]}, Group ${block.category}${block.overseas ? ', ✈' : ''})
      is on the block —
      ${block.currentBidAmount != null
        ? `current bid <b>${fmtINR(block.currentBidAmount)}</b> by <b>${esc(block.currentLeadingTeamName)}</b>${leading ? ' — 👑 that\'s you!' : ''}`
        : `opens at <b>${fmtINR(block.basePrice)}</b>`}
      ${profileStats(block.stats)}
    </div>`;
}

function renderHead(t, squad) {
  const spent = t.startingPurse - t.remainingPurse;
  const pct = t.startingPurse > 0 ? (t.remainingPurse / t.startingPurse) * 100 : 0;
  const retained = squad.filter(p => p.retained).length;
  const maxRetained = auctionConfig?.retention?.maxPerTeam ?? 3;
  document.getElementById('team-head').innerHTML = `
    <div class="hero-top">
      <h2>${esc(t.name)} <span class="muted">· ${esc(t.ownerName)}</span>${teamId === myTeamId ? ' <span class="chip">⭐ Your team</span>' : ''}</h2>
      <span class="live"><i></i>LIVE</span>
    </div>
    <div class="big-purse">${fmtINR(t.remainingPurse)}</div>
    <div class="purse-bar"><i style="width:${pct}%"></i></div>
    <div class="muted">${fmtShort(spent)} spent · ${fmtShort(t.remainingPurse)} of ${fmtShort(t.startingPurse)} remaining</div>
    <div class="tile-row">
      <div class="tile"><span class="ticon">🧢</span><b>${t.squadFilled}/${t.squadFilled + t.squadOpenSlots}</b><span>Squad</span></div>
      <div class="tile"><span class="ticon">💰</span><b>${fmtShort(t.maxAffordableBid)}</b><span>Max affordable bid</span></div>
      <div class="tile"><span class="ticon">📌</span><b>${retained}/${maxRetained}</b><span>Retained</span></div>
      <div class="tile"><span class="ticon">✈️</span><b>${t.overseasUsed}</b><span>Overseas (no limit)</span></div>
    </div>`;
}

function meterRow(icon, label, have, target, capped) {
  const pct = target > 0 ? Math.min(100, (have / target) * 100) : (have > 0 ? 100 : 0);
  const met = target > 0 && have >= target;
  return `
    <div class="req">
      <span class="req-label">${icon} ${label}</span>
      <div class="meter ${capped && met ? 'full' : met ? 'met' : ''}"><i style="width:${pct}%"></i></div>
      <span class="req-count ${met ? (capped ? 'amber' : 'good') : ''}">${have}/${target}${capped && met ? ' · full' : ''}</span>
    </div>`;
}

function renderComposition(t) {
  // Roles carry no rule — plain make-up display.
  const roles = Object.entries(ROLE_SHORT)
      .map(([role, short]) => `
        <div class="rchip"><b>${t.roleCounts[role] || 0}</b>
        <span>${ROLE_ICON[role] || ''} ${short}</span></div>`)
      .join('');
  const rules = auctionConfig?.categoryRules || {};
  const groups = Object.entries(t.categoryCounts || {})
      .filter(([g]) => rules[g]?.maxPerTeam != null)
      .map(([g, n]) => meterRow('', 'Group ' + g, n, rules[g].maxPerTeam, true))
      .join('');
  document.getElementById('composition').innerHTML = `
    <div class="req-grid">
      <div><h3>Squad make-up · no role limits</h3><div class="role-chips">${roles}</div></div>
      <div><h3>Group quotas (max per team)</h3>${groups || '<p class="muted">No group rules configured.</p>'}</div>
    </div>`;
}

function renderSquad(squad, t) {
  document.getElementById('squad-summary').textContent =
      squad.length ? `· ${squad.length} player${squad.length > 1 ? 's' : ''} · ${fmtShort(squad.reduce((s, p) => s + (p.soldPrice || 0), 0))} spent` : '';
  const topPrice = Math.max(0, ...squad.map(p => p.soldPrice || 0));
  document.getElementById('squad-body').innerHTML = squad.length
      ? squad.map((p, i) => `
        <tr>
          <td class="muted">${i + 1}</td>
          <td><a class="plink" href="player.html?playerId=${p.playerId}"><b>${esc(p.name)}</b></a>
              ${p.overseas ? ' ✈' : ''}${p.retained ? '<span class="rtag">RETAINED</span>' : ''}${p.soldPrice === topPrice && squad.length > 1 ? ' 💎' : ''}</td>
          <td>${ROLE_ICON[p.role] || ''} ${ROLE_SHORT[p.role]}</td>
          <td><span class="chip">${p.category}</span></td>
          <td><b>${fmtINR(p.soldPrice)}</b></td>
        </tr>`).join('')
      : '<tr><td colspan="5" class="muted">No players bought yet — your signings will appear here live.</td></tr>';
}

(async function init() {
  // Wait for auth.js to resolve the current user so we can scope owners to their team.
  const me = window.authReady ? await window.authReady : null;
  myTeamId = me && me.teamId ? me.teamId : null;

  // Give a franchise owner a one-click link back to their own team.
  if (myTeamId) {
    const nav = document.querySelector('header nav');
    if (nav) {
      const mine = document.createElement('a');
      mine.href = 'team.html?teamId=' + myTeamId;
      mine.textContent = '⭐ My team';
      nav.prepend(mine);
    }
  }

  if (!teamId) {
    // Owners land straight on their own team; others get the picker.
    if (myTeamId) { location.replace('team.html?teamId=' + myTeamId); return; }
    showPicker();
  } else {
    getJSON('/api/config').then(c => { auctionConfig = c; refresh(); }).catch(() => refresh());
    setInterval(refresh, 3000);
  }
})();
