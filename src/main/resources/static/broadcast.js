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

const photoUrl = id => `/api/players/${id}/photo`;

// Show a player's portrait poster when they have one, falling back to the
// initials avatar on a missing/failed image so the board is NEVER blank. Keeps
// the avatar visible until the poster has actually loaded, and skips reloading
// the same player's image on every 2s poll (no flicker).
function setPoster(posterId, avatarId, player) {
  const poster = document.getElementById(posterId);
  const avatar = document.getElementById(avatarId);
  if (!poster || !avatar) return;
  const show = () => { poster.style.display = ''; avatar.style.display = 'none'; };
  const hide = () => { poster.style.display = 'none'; avatar.style.display = ''; };
  if (!player || !player.hasPhoto || !player.playerId) {
    poster.removeAttribute('src'); delete poster.dataset.pid; hide(); return;
  }
  if (poster.dataset.pid === player.playerId) {           // already handled this player
    if (poster.complete && poster.naturalWidth) show();
    return;
  }
  poster.dataset.pid = player.playerId;
  hide();                                                  // initials until the image is ready
  poster.onload = show;
  poster.onerror = hide;
  poster.src = photoUrl(player.playerId);
}

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

// Lay the team cards out for a projector: up to 6 teams sit on ONE row; a
// larger field (7, 8, 9 … up to a dozen) splits into a balanced TWO rows
// (12→6×2, 10→5×2, 9→5+4, 8→4×2, 7→4+3) so cards stay wide and readable rather
// than shrinking into a thin single strip. On a narrow screen that can't fit
// that many across at a readable width, it falls back to more rows.
let lastTeamCount = 0;

function layoutTeams(n) {
  const el = document.getElementById('bc-teams');
  if (!el || !n) return;
  const gap = 16, minCard = 170;
  const width = el.clientWidth || el.parentElement?.clientWidth || window.innerWidth;
  const maxCols = Math.max(1, Math.floor((width + gap) / (minCard + gap)));
  const target = n <= 6 ? n : Math.ceil(n / 2);   // one row up to 6, else two rows
  const cols = Math.min(target, maxCols);
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
      <div class="team-broadcast ${hot ? 'leading' : ''}" data-team-id="${t.teamId}"
           role="button" tabindex="0" title="Click to see ${esc(t.name)}'s squad">
        <div class="tb-head">
          <h3>${esc(t.name)}</h3>
          ${hot ? '<span class="leading-badge">👑</span>' : ''}
        </div>
        <div class="purse-amount">${fmtShort(t.remainingPurse)}</div>
        <div class="purse-bar"><i style="width:${pct}%"></i></div>
        <div class="tb-meta">Squad ${t.squadFilled}/${t.squadFilled + t.squadOpenSlots} · ${fmtShort(t.remainingPurse)} of ${fmtShort(t.startingPurse)}</div>
        ${maxBidHtml}
      </div>`;
  }).join(''));
  layoutTeams(lastTeamCount);
}

function renderLive(player, teams) {
  setText('bc-avatar', initials(player.name));
  setPoster('bc-poster', 'bc-avatar', player);
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
    setPoster('sold-poster', 'sold-avatar', p ? { playerId: p.playerId, hasPhoto: p.hasPhoto } : null);
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

// ---------------------------------------------------------------------------
// Team squad drill-down. Clicking a team purse card opens a modal listing every
// player that team has signed and for how much — the same squad the team-owner
// dashboard shows (/api/dashboard/teams/{id}), mirrored here for spectators. It
// refreshes every 2.5s while open so a live signing appears in place.
let teamModalId = null;
let teamModalTimer = null;

function teamModalHtml(t, squad) {
  const spent = t.startingPurse - t.remainingPurse;
  const rows = squad.length
    ? squad.map((p, i) => `
        <tr>
          <td class="muted">${i + 1}</td>
          <td class="btm-player">
            ${p.hasPhoto ? `<img class="squad-thumb" src="${photoUrl(p.playerId)}" alt="" loading="lazy" onerror="this.remove()">` : ''}
            <b>${esc(p.name)}</b>${p.retained ? ' <span class="rtag">RETAINED</span>' : ''}
          </td>
          <td>${ROLE_ICON[p.role] || ''} ${String(p.role || '').replace('_', ' ')}</td>
          <td><span class="chip">${esc(p.category)}</span></td>
          <td><b>${fmtINR(p.soldPrice)}</b></td>
        </tr>`).join('')
    : `<tr><td colspan="5" class="muted">No players signed yet.</td></tr>`;
  return `
    <div class="btm-summary">
      <div><b>${fmtShort(t.remainingPurse)}</b><span>Purse left</span></div>
      <div><b>${fmtShort(spent)}</b><span>Spent</span></div>
      <div><b>${t.squadFilled}/${t.squadFilled + t.squadOpenSlots}</b><span>Squad</span></div>
    </div>
    <div class="btm-scroll">
      <table class="pool btm-table">
        <thead><tr><th>#</th><th>Player</th><th>Role</th><th>Group</th><th>Price</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>`;
}

async function renderTeamModal() {
  if (!teamModalId) return;
  try {
    const detail = await getJSON('/api/dashboard/teams/' + teamModalId);
    const t = detail.team;
    setText('btm-title', t.name + ' — Squad');
    setHTML('btm-body', teamModalHtml(t, detail.squad || []));
  } catch (e) {
    setHTML('btm-body', '<p class="muted">Could not load this team — try again.</p>');
  }
}

function openTeamModal(teamId) {
  teamModalId = teamId;
  const dlg = document.getElementById('bc-team-modal');
  setHTML('btm-body', '<p class="muted">Loading…</p>');
  setText('btm-title', 'Team squad');
  if (dlg && !dlg.open) dlg.showModal();
  renderTeamModal();
  clearInterval(teamModalTimer);
  teamModalTimer = setInterval(renderTeamModal, 2500);
}

(function wireTeamModal() {
  const dlg = document.getElementById('bc-team-modal');
  document.addEventListener('click', e => {
    if (e.target.id === 'btm-close') { dlg && dlg.close(); return; }
    const card = e.target.closest?.('.team-broadcast[data-team-id]');
    if (card) openTeamModal(card.getAttribute('data-team-id'));
  });
  document.addEventListener('keydown', e => {                 // keyboard-open a focused card
    if ((e.key === 'Enter' || e.key === ' ') && e.target.classList?.contains('team-broadcast')) {
      e.preventDefault();
      openTeamModal(e.target.getAttribute('data-team-id'));
    }
  });
  if (dlg) {
    dlg.addEventListener('close', () => { teamModalId = null; clearInterval(teamModalTimer); });
    dlg.addEventListener('click', e => { if (e.target === dlg) dlg.close(); }); // backdrop
  }
})();

// Serialized polling: wait for each cycle to fully finish, then schedule the
// next after a short gap. This guarantees only one poll is ever in flight, so
// responses can't arrive out of order — the core fix for the stale-player flash.
async function pollForever() {
  await refreshLoop();
  setTimeout(pollForever, 250);
}
pollForever();
