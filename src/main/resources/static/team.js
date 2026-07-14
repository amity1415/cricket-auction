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
const ROLE_ICON = { BATSMAN: '🏏', BOWLER: '🔴', ALL_ROUNDER: '🏏🔴', WICKETKEEPER: '🧤' };
const initials = name => String(name || '?').split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase();

// Only rewrite a container when its HTML actually changed. Re-writing innerHTML
// on every 3s poll forces a repaint (and, for animated elements, re-runs their
// entrance animation) — that is the flicker. Caching the last HTML per element
// makes an unchanged poll a no-op.
const _htmlCache = {};
function setHTMLIfChanged(id, html) {
  if (_htmlCache[id] === html) return;
  const el = document.getElementById(id);
  if (!el) return;
  el.innerHTML = html;
  _htmlCache[id] = html;
}

const teamId = new URLSearchParams(location.search).get('teamId');
let auctionConfig = null;
let myTeamId = null; // the logged-in owner's own team, if any (set on init)

async function getJSON(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(url + ' → ' + res.status);
  return res.json();
}

// ---- Teams overview (the landing when no specific team is chosen) ----------
// A wide, live grid of every team. Each card shows the essentials up front and,
// on hover, pops a glassy panel with a full snapshot (purse, max bid, squad,
// role & group make-up). Clicking opens that team's full dashboard.

const CRESTS = [
  'linear-gradient(135deg,#5b8cff,#8b5cf6)',
  'linear-gradient(135deg,#2dd48f,#1f8f5c)',
  'linear-gradient(135deg,#f5b942,#f0564a)',
  'linear-gradient(135deg,#22d3ee,#5b8cff)',
  'linear-gradient(135deg,#f472b6,#8b5cf6)',
  'linear-gradient(135deg,#8b5cf6,#f0564a)',
];
const crestFor = id => {
  let h = 0; for (const c of String(id)) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return CRESTS[h % CRESTS.length];
};
const tParam = () => window.TOURNAMENT_ID ? '&tournamentId=' + encodeURIComponent(window.TOURNAMENT_ID) : '';

function teamCard(t) {
  const total = t.squadFilled + t.squadOpenSlots;
  const spent = t.startingPurse - t.remainingPurse;
  const pct = t.startingPurse > 0 ? (t.remainingPurse / t.startingPurse) * 100 : 0;
  const mine = t.teamId === myTeamId;
  const roles = ['BATSMAN', 'BOWLER', 'ALL_ROUNDER', 'WICKETKEEPER'];
  return `
    <a class="team-tile${mine ? ' mine' : ''}" href="team.html?teamId=${t.teamId}${tParam()}"
       style="--crest:${crestFor(t.teamId)}">
      <div class="tile-front">
        <div class="tile-top">
          <span class="crest">${esc(initials(t.name))}</span>
          <span class="tile-id">
            <b class="tt-name">${esc(t.name)}</b>
            <span class="muted">${esc(t.ownerName)}${mine ? ' · ⭐ you' : ''}</span>
          </span>
        </div>
        <div class="tile-mid muted">💰 Max bid <b>${fmtShort(t.maxAffordableBid)}</b> · ${t.remainingMandatorySlots ?? 0} to fill</div>
        <div class="tile-purse">
          <div class="tp-amount">${fmtShort(t.remainingPurse)}</div>
          <div class="purse-bar"><i style="width:${pct}%"></i></div>
          <div class="tp-sub muted">${t.squadFilled}/${total} squad · ${fmtShort(spent)} spent</div>
        </div>
      </div>
      <div class="tile-detail" aria-hidden="true">
        <div class="td-head"><span class="crest sm">${esc(initials(t.name))}</span>${esc(t.name)}</div>
        <div class="td-purse">
          <span class="td-remain">${fmtShort(t.remainingPurse)}</span>
          <span class="td-remain-cap">remaining of ${fmtShort(t.startingPurse)}</span>
        </div>
        <div class="td-line">🧢 Squad <b>${t.squadFilled}/${total}</b> · 💰 Max <b>${fmtShort(t.maxAffordableBid)}</b> · ${t.remainingMandatorySlots ?? 0} to fill</div>
        <div class="td-block">
          <span class="td-cap">Roles</span>
          <div class="pill-row">${roles.map(r =>
            `<span class="rpill">${ROLE_SHORT[r]}<b>${t.roleCounts?.[r] || 0}</b></span>`).join('')}</div>
        </div>
        <div class="td-block">
          <span class="td-cap">Groups</span>
          <div class="pill-row">${['A', 'B', 'C', 'D', 'E'].map(g =>
            `<span class="gpill">${g}<b>${t.categoryCounts?.[g] || 0}</b></span>`).join('')}</div>
        </div>
        <div class="td-open">Open full dashboard →</div>
      </div>
    </a>`;
}

let pickerTimer = null;
async function showPicker() {
  document.querySelector('main').classList.add('wide');
  document.getElementById('picker').style.display = '';
  await renderOverview();
  if (!pickerTimer) pickerTimer = setInterval(renderOverview, 3000);
}

async function renderOverview() {
  let dash;
  try { dash = await getJSON('/api/dashboard'); } catch (e) { return; }
  const teams = dash.teams || [];
  const totalSpent = teams.reduce((s, t) => s + (t.startingPurse - t.remainingPurse), 0);
  const signed = teams.reduce((s, t) => s + t.squadFilled, 0);
  setHTMLIfChanged('overview-stats', `
    <div class="ostat"><b>${teams.length}</b><span>Teams</span></div>
    <div class="ostat"><b>${signed}</b><span>Signed</span></div>
    <div class="ostat"><b>${fmtShort(totalSpent)}</b><span>Spent</span></div>`);
  setHTMLIfChanged('teams-grid', teams.length
    ? teams.map(teamCard).join('')
    : '<p class="muted">No teams registered yet.</p>');
}

async function refresh() {
  try {
    const [detail, dash, audit] = await Promise.all([
      getJSON(`/api/dashboard/teams/${teamId}`),
      getJSON('/api/dashboard'),
      getJSON('/api/admin/audit').catch(() => []),
    ]);
    document.getElementById('dashboard').style.display = '';
    renderBanner(dash.onTheBlock, detail.team);
    renderHead(detail.team, detail.squad);
    renderComposition(detail.team);
    renderSquad(detail.squad, detail.team);
    updateLastResult(audit);
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

// The player currently drawn in the banner. We rebuild the banner element only
// when the player changes (so the entrance animation runs once per player); for
// the same player we patch just the bid line in place — no flicker on each poll.
let bannerPlayerId = null;

function bannerBidHtml(block, leading) {
  return block.currentBidAmount != null
    ? `current bid <b>${fmtINR(block.currentBidAmount)}</b> by <b>${esc(block.currentLeadingTeamName)}</b>${leading ? ' — 👑 that\'s you!' : ''}`
    : `opens at <b>${fmtINR(block.basePrice)}</b>`;
}

function renderBanner(block, team) {
  const el = document.getElementById('block-banner');
  if (!block) {
    if (bannerPlayerId !== null) { el.innerHTML = ''; bannerPlayerId = null; }
    return;
  }
  const leading = block.currentLeadingTeamId === team.teamId;
  if (block.playerId !== bannerPlayerId) {
    // New player on the block — build once (this is when the animation should run).
    el.innerHTML = `
      <div class="banner ${leading ? 'leading' : ''}">
        <a class="plink" href="player.html?playerId=${block.playerId}"><b>${esc(block.name)}</b></a>
        (${ROLE_SHORT[block.role]}, Group ${block.category})
        is on the block —
        <span class="bblock-bid">${bannerBidHtml(block, leading)}</span>
        ${profileStats(block.stats)}
      </div>`;
    bannerPlayerId = block.playerId;
  } else {
    // Same player — patch only the volatile bits, leaving the element (and its
    // animation) untouched.
    const banner = el.querySelector('.banner');
    if (banner) banner.classList.toggle('leading', leading);
    const bid = el.querySelector('.bblock-bid');
    if (bid) bid.innerHTML = bannerBidHtml(block, leading);
  }
}

function renderHead(t, squad) {
  const spent = t.startingPurse - t.remainingPurse;
  const pct = t.startingPurse > 0 ? (t.remainingPurse / t.startingPurse) * 100 : 0;
  const retained = squad.filter(p => p.retained).length;
  const maxRetained = auctionConfig?.retention?.maxPerTeam ?? 3;
  setHTMLIfChanged('team-head', `
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
    </div>`);
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
  setHTMLIfChanged('composition', `
    <div class="req-grid">
      <div><h3>Squad make-up · no role limits</h3><div class="role-chips">${roles}</div></div>
      <div><h3>Group quotas (max per team)</h3>${groups || '<p class="muted">No group rules configured.</p>'}</div>
    </div>`);
}

function renderSquad(squad, t) {
  document.getElementById('squad-summary').textContent =
      squad.length ? `· ${squad.length} player${squad.length > 1 ? 's' : ''} · ${fmtShort(squad.reduce((s, p) => s + (p.soldPrice || 0), 0))} spent` : '';
  const topPrice = Math.max(0, ...squad.map(p => p.soldPrice || 0));
  setHTMLIfChanged('squad-body', squad.length
      ? squad.map((p, i) => `
        <tr>
          <td class="muted">${i + 1}</td>
          <td><a class="plink" href="player.html?playerId=${p.playerId}"><b>${esc(p.name)}</b></a>
              ${p.retained ? '<span class="rtag">RETAINED</span>' : ''}${p.soldPrice === topPrice && squad.length > 1 ? ' 💎' : ''}</td>
          <td>${ROLE_ICON[p.role] || ''} ${ROLE_SHORT[p.role]}</td>
          <td><span class="chip">${p.category}</span></td>
          <td><b>${fmtINR(p.soldPrice)}</b></td>
        </tr>`).join('')
      : '<tr><td colspan="5" class="muted">No players bought yet — your signings will appear here live.</td></tr>');
}

// ---------------------------------------------------------------------------
// Sold / unsold result popup. On a NEW terminal result (a SOLD or UNSOLD audit
// entry we haven't shown), play a broadcast-style reveal centred for 4s, then
// shrink it into a small card pinned to the bottom-right corner that persists as
// "the last player sold/unsold". On first page load we don't replay the reveal —
// we just seed the corner card with the most recent result.
let lastResultKey = null; // saleId of the last result we've shown
let resultBaselineSet = false; // has the first poll established the "already seen" baseline?
let revealTimer = null;
let revealCloseTimer = null;

function resultBig(r) {
  const sold = r.type === 'SOLD';
  return `
    <span class="rv-badge ${sold ? 'sold' : 'unsold'}">${sold ? '✅ SOLD' : '🚫 UNSOLD'}</span>
    <div class="rv-avatar">${esc(initials(r.playerName))}</div>
    <div class="rv-name">${esc(r.playerName)}</div>
    ${sold
      ? `<div class="rv-team">to <b>${esc(r.teamName)}</b></div>
         <div class="rv-amount">${fmtINR(r.amount)}</div>`
      : `<div class="rv-team">went unsold</div>`}`;
}

function resultMini(r) {
  const sold = r.type === 'SOLD';
  return `
    <div class="mini-head">Last ${sold ? 'sold' : 'result'}</div>
    <div class="mini-badge ${sold ? 'sold' : 'unsold'}">${sold ? 'SOLD' : 'UNSOLD'}</div>
    <div class="mini-name" title="${esc(r.playerName)}">${esc(r.playerName)}</div>
    ${sold
      ? `<div class="mini-team" title="${esc(r.teamName)}">${esc(r.teamName)}</div>
         <div class="mini-amount">${fmtShort(r.amount)}</div>`
      : `<div class="mini-team">Unsold</div>`}`;
}

function showMini(r) {
  const mini = document.getElementById('last-result-mini');
  if (!mini) return;
  mini.innerHTML = resultMini(r);
  mini.style.display = '';
  mini.classList.remove('pop');
  void mini.offsetWidth;   // restart the pop animation
  mini.classList.add('pop');
}

function playReveal(r) {
  const overlay = document.getElementById('reveal-overlay');
  const card = document.getElementById('reveal-card');
  if (!overlay || !card) { showMini(r); return; }
  clearTimeout(revealTimer);
  clearTimeout(revealCloseTimer);

  card.className = 'reveal-card';
  overlay.classList.remove('closing');
  card.innerHTML = resultBig(r);
  overlay.style.display = '';
  void card.offsetWidth;          // reflow so the entrance transition runs
  card.classList.add('enter');

  revealTimer = setTimeout(() => {
    // Shrink toward the corner and pop the persistent mini card into place.
    card.classList.add('shrink');
    overlay.classList.add('closing');
    showMini(r);
    revealCloseTimer = setTimeout(() => {
      overlay.style.display = 'none';
      card.className = 'reveal-card';
    }, 650);
  }, 4000);
}

function updateLastResult(audit) {
  const results = (audit || []).filter(a => a.type === 'SOLD' || a.type === 'UNSOLD');
  const last = results[results.length - 1];
  const key = last ? last.saleId : null;
  if (!resultBaselineSet) {
    // First poll establishes what's "already happened" — seed the corner card
    // from any pre-existing result, but never replay its reveal on load. Set the
    // baseline even when there is no result yet, so the NEXT sale is a reveal.
    resultBaselineSet = true;
    lastResultKey = key;
    if (last) showMini(last);
    return;
  }
  if (key && key !== lastResultKey) {
    lastResultKey = key;
    playReveal(last);
  }
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
