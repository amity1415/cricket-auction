/* Live auction broadcast — spectator view. Three states, driven by polling
 * every 2s:
 *   LIVE  — a player is on the block: show current bid, next bid, leader.
 *   SOLD  — nobody on the block and the last result was a sale: celebrate the
 *           player, the buyer and the price until the next player goes up.
 *   IDLE  — nothing has happened yet, or we can't reach the server.
 * Putting a new player on the block automatically flips SOLD → LIVE.
 *
 * Design note: every DOM write is null-guarded and every render is wrapped so a
 * single failure (e.g. a laptop running a stale cached page) can NEVER leave the
 * board blank — the worst case falls back to the visible idle/status card. */

const fmtINR = n => n == null ? '—'
    : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);
const fmtShort = n => {
  if (n == null) return '—';
  if (n >= 1e7) return '₹' + (n / 1e7).toFixed(2).replace(/\.?0+$/, '') + ' Cr';
  if (n >= 1e5) return '₹' + (n / 1e5).toFixed(1).replace(/\.0$/, '') + ' L';
  return fmtINR(n);
};
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const ROLE_ICON = { BATSMAN: '🏏', BOWLER: '🔴', ALL_ROUNDER: '🏏🔴', WICKETKEEPER: '🧤' };

const initials = name => String(name || '?').split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase();

// Null-safe DOM writers — never throw if an element is missing.
const setText = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
const setHTML = (id, v) => { const el = document.getElementById(id); if (el) el.innerHTML = v; };
const setDisp = (id, v) => { const el = document.getElementById(id); if (el) el.style.display = v; };

async function getJSON(url) {
  const res = await fetch(url, { cache: 'no-store' });
  if (!res.ok) throw new Error(url + ' -> ' + res.status);
  return res.json();
}

function setConn(ok) {
  const el = document.getElementById('conn-status');
  if (!el) return;
  el.className = 'conn-status ' + (ok ? 'ok' : 'bad');
  el.textContent = ok ? '● live' : '● reconnecting…';
}

function metaChips(p) {
  return `
    <span class="chip">${ROLE_ICON[p.role] || ''} ${String(p.role || '').replace('_', ' ')}</span>
    <span class="chip">Group ${p.category}</span>`;
}

function statsStrip(stats) {
  if (!stats) return '';
  const items = [
    ['Matches', stats.matches], ['Runs', stats.runs], ['Avg', stats.battingAverage],
    ['SR', stats.strikeRate], ['Wickets', stats.wickets], ['Econ', stats.economyRate],
  ].filter(([, v]) => v != null);
  if (!items.length) return '';
  return `<div class="pstats">${items.map(([l, v]) =>
      `<div class="pstat"><b>${v}</b><span>${l}</span></div>`).join('')}</div>`;
}

function showState(which) {
  setDisp('idle-state', which === 'idle' ? '' : 'none');
  setDisp('live-state', which === 'live' ? '' : 'none');
  setDisp('sold-state', which === 'sold' ? '' : 'none');
  setDisp('teams-section', which === 'idle' ? 'none' : '');
  fitToScreen();
}

// Broadcast is shown on a big TV — it must fit on one screen with no scroll.
// Measure the content's natural height and, if it exceeds the space below the
// header, scale the whole board down uniformly so everything stays visible.
let fitTimer = null;
function fitToScreen() {
  const wrap = document.querySelector('.broadcast-wrap');
  if (!wrap) return;
  wrap.style.transform = 'none';                       // reset to measure natural size
  // Only fit-to-screen on TV / desktop-sized displays; small screens scroll
  // normally (scaling a full board down on a phone would make it unreadable).
  if (window.innerWidth < 900) return;
  const headerH = document.querySelector('header')?.offsetHeight || 0;
  const avail = window.innerHeight - headerH;
  const natural = wrap.scrollHeight;
  const scale = natural > avail && natural > 0 ? avail / natural : 1;
  wrap.style.transformOrigin = 'top center';
  wrap.style.transform = scale < 1 ? `scale(${scale})` : 'none';
}

// Lay out the team cards so every row is full — no lopsided last row. The column
// count is always a divisor of the team count (so N/cols is a whole number), and
// we pick the largest such count whose cards still meet a minimum width for the
// current screen. This keeps the grid symmetrical at any screen size.
let lastTeamCount = 0;

function layoutTeams(n) {
  const el = document.getElementById('bc-teams');
  if (!el || !n) return;
  const gap = 14, minCard = 150;
  const width = el.clientWidth || el.parentElement?.clientWidth || window.innerWidth;
  let cols = 1;
  for (let d = 1; d <= n; d++) {
    if (n % d === 0 && (width - gap * (d - 1)) / d >= minCard) cols = d;
  }
  el.style.gridTemplateColumns = `repeat(${cols}, minmax(0, 1fr))`;
}

let resizeTimer = null;
window.addEventListener('resize', () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => { layoutTeams(lastTeamCount); fitToScreen(); }, 120);
});

// Refit whenever the board's content size changes (new player, teams loaded,
// async stats arriving). Transform doesn't alter the observed box, so no loop.
document.addEventListener('DOMContentLoaded', () => {
  const wrap = document.querySelector('.broadcast-wrap');
  if (wrap && window.ResizeObserver) new ResizeObserver(() => fitToScreen()).observe(wrap);
  fitToScreen();
});

function renderTeams(teams, highlightTeamId, block) {
  lastTeamCount = (teams || []).length;
  setHTML('bc-teams', (teams || []).map(t => {
    const pct = t.startingPurse > 0 ? (t.remainingPurse / t.startingPurse) * 100 : 0;
    const hot = t.teamId === highlightTeamId;
    const mbCan = block && t.maxBidForBlockPlayer != null && t.maxBidForBlockPlayer >= block.nextBidAmount;
    const maxBidHtml = block
      ? `<div class="bc-maxbid${mbCan ? '' : ' none'}"><span>🔨 Max next bid</span><b>${mbCan ? fmtShort(t.maxBidForBlockPlayer) : "Can't bid"}</b></div>`
      : '';
    return `
      <div class="team-broadcast ${hot ? 'leading' : ''}">
        <h3>${esc(t.name)}</h3>
        <div class="purse-bar"><i style="width:${pct}%"></i></div>
        <div class="purse-amount">${fmtShort(t.remainingPurse)}</div>
        <div class="purse-detail">${fmtShort(t.remainingPurse)} of ${fmtShort(t.startingPurse)}</div>
        <div class="squad-info">Squad ${t.squadFilled}/${t.squadFilled + t.squadOpenSlots}</div>
        ${maxBidHtml}
        ${hot ? '<div class="leading-badge">👑</div>' : ''}
      </div>`;
  }).join(''));
  layoutTeams(lastTeamCount);
}

function renderLive(player, teams) {
  setText('bc-avatar', initials(player.name));
  setText('bc-name', player.name);
  setHTML('bc-meta', metaChips(player));
  setHTML('bc-status', statsStrip(player.stats));

  if (player.currentBidAmount) {
    setText('bc-current-bid', fmtINR(player.currentBidAmount));
    setText('bc-leading-team', esc(player.currentLeadingTeamName));
  } else {
    setText('bc-current-bid', fmtINR(player.basePrice) + ' (opening)');
    setText('bc-leading-team', 'No bids yet');
  }
  setText('bc-next-bid', fmtINR(player.nextBidAmount));
  setHTML('bc-bid-count', player.bidCount ? `<span class="bid-count">Bid #${player.bidCount}</span>` : '');

  renderTeams(teams, player.currentLeadingTeamId, player);
  showState('live');
}

// Cache full player details for the sold screen so we don't re-fetch each tick.
let soldDetailCache = { id: null, player: null };

async function renderResult(sale, teams) {
  // Handles both terminal results — SOLD and UNSOLD — on the same panel. Paint
  // the core facts IMMEDIATELY from the audit entry (no await) so a slow stats
  // fetch can't leave the previous result on screen.
  const sold = sale.type === 'SOLD';
  const section = document.querySelector('#sold-state .broadcast-sold');
  if (section) section.classList.toggle('unsold', !sold);
  const stamp = document.querySelector('#sold-state .sold-stamp');
  if (stamp) stamp.textContent = sold ? 'SOLD' : 'UNSOLD';
  const deal = document.querySelector('#sold-state .sold-deal');
  if (deal) deal.style.display = sold ? '' : 'none';

  setText('sold-avatar', initials(sale.playerName));
  setText('sold-name', sale.playerName);
  if (sold) {
    setText('sold-team', sale.teamName);
    setText('sold-amount', fmtINR(sale.amount));
  }
  renderTeams(teams, sold ? sale.teamId : null);
  showState('sold');

  // Enrich with role/stats asynchronously. Clear the previous player's chips
  // first so they can't linger under a new name while the fetch is in flight.
  if (soldDetailCache.id !== sale.playerId) {
    setHTML('sold-meta', '');
    setHTML('sold-stats', '');
    soldDetailCache = { id: sale.playerId, player: null };
    try { soldDetailCache.player = await getJSON('/api/players/' + sale.playerId); }
    catch (e) { /* audit-only data is already on screen; that's fine */ }
  }
  // Guard against a slow response arriving after the board has moved to a newer
  // sale: only apply the enrichment if this is still the player being shown.
  if (soldDetailCache.id === sale.playerId) {
    const p = soldDetailCache.player;
    setHTML('sold-meta', p ? metaChips(p) : '');
    setHTML('sold-stats', p ? statsStrip(p.stats) : '');
  }
}

function showError() {
  // Never blank: keep whatever was last drawn, but if we've never drawn a real
  // state, show the idle card with a reconnecting message.
  const anyLive = document.getElementById('live-state');
  const anySold = document.getElementById('sold-state');
  const liveVisible = anyLive && anyLive.style.display !== 'none';
  const soldVisible = anySold && anySold.style.display !== 'none';
  if (!liveVisible && !soldVisible) {
    setText('idle-title', '📡 Reconnecting to the auction…');
    setText('idle-sub', 'The live board will appear as soon as the server responds.');
    showState('idle');
  }
}

// Monotonic poll counter. The dashboard/audit fetches can take 1–3s against a
// remote DB; if polls overlap, a slow OLDER response can resolve AFTER a newer
// one and paint stale data (e.g. the previously-sold player) over the correct
// board. Every render checks it's still the latest poll before touching the DOM.
let pollSeq = 0;

async function refreshLoop() {
  const mySeq = ++pollSeq;
  const current = () => mySeq === pollSeq; // false once a newer poll has started
  try {
    const dash = await getJSON('/api/dashboard');
    if (!current()) return;
    setConn(true);

    // A player on the block always wins — this replaces the sold screen. No
    // audit fetch needed here, which also keeps the live board snappy.
    if (dash.onTheBlock) { renderLive(dash.onTheBlock, dash.teams); return; }

    // Nobody on the block: show the most recent terminal result — sold OR unsold.
    const audit = await getJSON('/api/admin/audit').catch(() => []);
    if (!current()) return;
    const lastResult = [].concat(audit).reverse().find(a => a.type === 'SOLD' || a.type === 'UNSOLD');
    if (lastResult) { await renderResult(lastResult, dash.teams); return; }

    setText('idle-title', '⏳ Waiting for the auction to begin…');
    setText('idle-sub', 'Check back when the admin puts a player on the block.');
    showState('idle');
  } catch (e) {
    if (!current()) return;
    setConn(false);
    showError();
  }
}

// Serialized polling: wait for each cycle to fully finish, then schedule the
// next after a short gap. This guarantees only one poll is ever in flight, so
// responses can't arrive out of order — the core fix for the stale-player flash.
async function pollForever() {
  await refreshLoop();
  setTimeout(pollForever, 250);
}
pollForever();
