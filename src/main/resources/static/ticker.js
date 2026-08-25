/* Auction ticker overlay — the bottom-of-screen lower-third for the live stream.
 * Reuses the public read APIs the broadcast board already uses:
 *   GET /api/dashboard      → who's on the block + team purses (for the sold FX)
 *   GET /api/admin/audit    → the last terminal result (SOLD / UNSOLD)
 *
 * Three visual states, all confined to a single band pinned to the bottom so the
 * rest of the frame is free for video:
 *   LIVE  — a player is on the block: base price, portrait + name, current bid +
 *           leading team, next bid, and a compact career line.
 *   SOLD  — a NEW sale just landed: the shared hammer→team-purse celebration
 *           (sold-fx.js) plays and the band shows the verdict for a few seconds.
 *   IDLE  — nothing on the block and no fresh sale: the band is fully hidden.
 *
 * Serialized polling (one request in flight at a time) mirrors broadcast.js so a
 * slow, stale response can never paint over a newer one. */

const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const fmtINR = n => n == null ? '—'
    : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);
// Compact money for the tight ticker cells: ₹1.5 Cr / ₹40 L / ₹8,000.
const fmtShort = n => {
  if (n == null) return '—';
  if (n >= 1e7) return '₹' + (n / 1e7).toFixed(2).replace(/\.?0+$/, '') + ' Cr';
  if (n >= 1e5) return '₹' + (n / 1e5).toFixed(1).replace(/\.0$/, '') + ' L';
  return fmtINR(n);
};

const ROLE_LABEL = { BATSMAN: '🏏 Batsman', BOWLER: '🔴 Bowler',
  ALL_ROUNDER: '🏏🔴 All-rounder', WICKETKEEPER: '🧤 Wicketkeeper' };

// Player group / category → the pill label shown next to the role.
const GROUP_LABEL = {
  ICON: 'Icon', OWNER: 'Owner', A: 'Group A', B: 'Group B', C: 'Group C', D: 'Group D', E: 'Group E',
  MIXED_UTILITY_BAG: 'Mixed Utility', WICKET_KEEPER: 'Wicket Keeper',
  BOWLER: 'Bowler', ALL_ROUNDER: 'All Rounder', MARKEE_PLAYER: 'Markee',
};
const groupLabel = c => c == null ? '' : (GROUP_LABEL[c] || String(c));
const initials = name => String(name || '?').split(/\s+/).filter(Boolean)
    .map(w => w[0]).slice(0, 2).join('').toUpperCase();

const $ = id => document.getElementById(id);
const setText = (id, v) => { const el = $(id); if (el) el.textContent = v; };
const setHTML = (id, v) => { const el = $(id); if (el) el.innerHTML = v; };

async function getJSON(url) {
  const res = await fetch(url, { cache: 'no-store' });
  if (!res.ok) throw new Error(url + ' -> ' + res.status);
  return res.json();
}

// A stable, vivid accent colour derived from a team name, used to tint the
// panel spine / portrait ring / glow so the overlay visibly "belongs" to the
// leading (or buying) team and shifts as the lead changes.
function teamColor(name) {
  const s = String(name || '');
  if (!s) return null;
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360;
  return `hsl(${h} 85% 62%)`;
}
function setTeamAccent(name) {
  const root = document.getElementById('tk-root');
  if (root) root.style.setProperty('--team', teamColor(name) || 'var(--tk-accent)');
}

// Leading-team crest: the franchise logo when we have one, else an initials tile.
function crest(name) {
  const url = window.TeamLogo ? TeamLogo.teamLogoUrl(name) : null;
  return url
    ? `<img class="tk-crest" src="${url}" alt="" onerror="this.replaceWith(Object.assign(document.createElement('span'),{className:'tk-crest initials',textContent:'${esc(initials(name))}'}))">`
    : `<span class="tk-crest initials">${esc(initials(name))}</span>`;
}

// Swap the portrait between a real poster and the initials fallback without
// re-fetching the same player's image on every poll (no flicker).
function setPortrait(player) {
  const img = $('tk-poster'), ini = $('tk-initials');
  if (!img || !ini) return;
  const show = () => { img.style.display = ''; ini.style.display = 'none'; };
  const hide = () => { img.style.display = 'none'; ini.style.display = ''; };
  ini.textContent = initials(player && player.name);
  if (!player || !player.hasPhoto || !player.playerId) { img.removeAttribute('src'); delete img.dataset.pid; hide(); return; }
  if (img.dataset.pid === player.playerId) { if (img.complete && img.naturalWidth) show(); return; }
  img.dataset.pid = player.playerId;
  hide();
  img.onload = show; img.onerror = hide;
  img.src = '/api/players/' + player.playerId + '/photo';
}

// Career stats as blue Batting / red Bowling panels — the SAME format the live
// broadcast board uses (see broadcast.js statsStrip): a header plus a 5-tile row
// (value over label). Batting: Inns/Runs/HS/Avg/SR. Bowling: Inns/Wkts/Avg/Econ/BB.
function statsStrip(st) {
  if (!st) return '';
  const t = (l, v) => v == null ? '' : `<div class="btile"><b>${v}</b><span>${l}</span></div>`;
  const hasBat = st.battingInnings != null || st.runs != null || st.battingAverage != null
      || st.strikeRate != null || st.highestScore != null;
  const hasBowl = st.bowlingInnings != null || st.wickets != null || st.economyRate != null
      || st.bowlingAverage != null || st.bestBowling != null;
  if (!hasBat && !hasBowl) return '';
  const bat = t('Inns', st.battingInnings) + t('Runs', st.runs) + t('HS', st.highestScore)
      + t('Avg', st.battingAverage) + t('SR', st.strikeRate);
  const bowl = t('Inns', st.bowlingInnings) + t('Wkts', st.wickets) + t('Avg', st.bowlingAverage)
      + t('Econ', st.economyRate) + t('BB', st.bestBowling);
  return `<div class="stat-panels">
      ${hasBat ? `<div class="stat-panel batting"><div class="sp-head">🏏 Batting</div><div class="sp-grid">${bat}</div></div>` : ''}
      ${hasBowl ? `<div class="stat-panel bowling"><div class="sp-head">🎯 Bowling</div><div class="sp-grid">${bowl}</div></div>` : ''}
    </div>`;
}

const root = () => $('tk-root');

function reveal() {
  const r = root();
  if (!r) return;
  if (!r.classList.contains('show')) { r.classList.add('show', 'enter'); }
  const b = $('tk-brand'); if (b) b.classList.add('show');   // sponsor bug rides with the ticker
}
function hide() {
  const r = root();
  if (r) { r.classList.remove('show', 'enter', 'is-sold'); }
  const b = $('tk-brand'); if (b) b.classList.remove('show');
  stopStatsCycle();
  hideSides();
}

// Giant SOLD (green) / UNSOLD (blue) words in the empty side spaces. Restart the
// slide-in animation on each new sale; leave it running while the same result
// shows so it doesn't flicker on every poll.
let sideSaleId = null;
// Set each word's rebound travel (--travel) from the live band position so it
// swings between just outside the band (centre side) and the screen edge — and
// never slides fully behind the band, on any width.
function sizeSides() {
  const bar = $('tk-bar'); if (!bar) return;
  const b = bar.getBoundingClientRect(), vw = window.innerWidth;
  const l = $('tk-side-l'), r = $('tk-side-r');
  if (l) { const gap = parseFloat(getComputedStyle(l).left) || 20;
    l.style.setProperty('--travel', Math.max(24, Math.round(b.left - gap - l.offsetWidth - 8)) + 'px'); }
  if (r) { const gap = parseFloat(getComputedStyle(r).right) || 20;
    r.style.setProperty('--travel', Math.max(24, Math.round((vw - b.right) - gap - r.offsetWidth - 8)) + 'px'); }
}
function restartSides() {
  ['tk-side-l', 'tk-side-r'].forEach(id => {
    const el = $(id); if (el) { el.style.animation = 'none'; void el.offsetWidth; el.style.animation = ''; }
  });
}
function showSides(sale) {
  const box = $('tk-sides'); if (!box) return;
  const sold = sale.type === 'SOLD';
  if (sale.saleId !== sideSaleId) {
    sideSaleId = sale.saleId;
    const word = sold ? 'SOLD' : 'UNSOLD';
    const cls = 'tk-side ' + (sold ? 'green' : 'red');
    [['tk-side-l', 'left'], ['tk-side-r', 'right']].forEach(([id, side]) => {
      const el = $(id); if (!el) return;
      el.textContent = word;
      el.className = cls + ' ' + side;
    });
    box.classList.add('show');
    sizeSides();      // compute travel from the current word widths + band position
    restartSides();   // restart the swing so it picks up the fresh --travel
  }
  box.classList.add('show');
}
function hideSides() { const b = $('tk-sides'); if (b) b.classList.remove('show'); sideSaleId = null; }
window.addEventListener('resize', () => {
  if ($('tk-sides') && $('tk-sides').classList.contains('show')) { sizeSides(); restartSides(); }
});

// Batting/bowling panels rotate on a timer: shown 5s, hidden 10s, repeating.
// When they fold away the bottom-anchored band slides DOWN (the upper ticker is
// "pulled down"); when they return it slides back UP. The sponsor bug is
// position:fixed and never moves with this. The cycle only runs while a player
// is live — it's stopped (and the panels stay hidden) on a sale or when idle.
let statsTimer = null, statsCycleOn = false;
function setStatsCollapsed(collapsed) {
  const r = root(); if (r) r.classList.toggle('stats-collapsed', collapsed);
}
function startStatsCycle() {
  if (statsCycleOn) return;
  statsCycleOn = true;
  const step = visible => {
    setStatsCollapsed(!visible);
    statsTimer = setTimeout(() => step(!visible), visible ? 5000 : 10000);
  };
  step(true);   // start visible for 5s, then hidden for 10s, and so on
}
function stopStatsCycle() {
  statsCycleOn = false;
  clearTimeout(statsTimer);
  setStatsCollapsed(true);
}

// Restart a CSS animation on an element by toggling a class across a reflow.
function pulse(id, cls) {
  const el = $(id);
  if (!el) return;
  el.classList.remove(cls);
  void el.offsetWidth;   // force reflow so the animation can replay
  el.classList.add(cls);
}

let lastBid = null, lastBidPlayer = null;
function renderLive(p) {
  const r = root();
  if (!r) return;
  r.classList.remove('is-sold');
  hideSides();
  $('tk-bar').classList.remove('sold', 'unsold');
  const live = $('tk-live');
  if (live) live.className = 'tk-live';
  setText('tk-live-text', 'On the block');

  setPortrait(p);
  setText('tk-name', p.name);
  setText('tk-role', ROLE_LABEL[p.role] || String(p.role || '').replace('_', ' '));
  setText('tk-base', fmtShort(p.basePrice));

  // Lot / serial number (crown badge) + player group pill.
  const serial = $('tk-serial');
  if (serial) { serial.textContent = p.serial != null ? '#' + p.serial : '';
                serial.style.display = p.serial != null ? '' : 'none'; }
  const grp = $('tk-group');
  if (grp) { grp.textContent = groupLabel(p.category); grp.style.display = p.category ? '' : 'none'; }

  const teamBox = $('tk-team');
  if (p.currentBidAmount != null) {
    setText('tk-current-label', 'Current Bid');
    setText('tk-current', fmtShort(p.currentBidAmount));
    setText('tk-leadname', p.currentLeadingTeamName || '');
    setHTML('tk-leadlogo', crest(p.currentLeadingTeamName));
    if (teamBox) teamBox.style.display = '';
    setTeamAccent(p.currentLeadingTeamName);
    // Pop the figure whenever the bid climbs (same player, higher number).
    if (lastBidPlayer === p.playerId && p.currentBidAmount !== lastBid) pulse('tk-current-wrap', 'bumped');
  } else {
    setText('tk-current-label', 'Opening');
    setText('tk-current', fmtShort(p.basePrice));
    if (teamBox) teamBox.style.display = 'none';   // no bids yet → no team display
    setTeamAccent(null);
  }
  lastBid = p.currentBidAmount; lastBidPlayer = p.playerId;
  setHTML('tk-stats', statsStrip(p.stats));
  startStatsCycle();   // rotate the batting/bowling panels while live

  reveal();
}

function renderSold(sale) {
  const r = root();
  if (!r) return;
  const sold = sale.type === 'SOLD';
  r.classList.add('is-sold');
  stopStatsCycle();   // sold → don't show this player's stats anymore
  $('tk-bar').classList.toggle('sold', sold);
  $('tk-bar').classList.toggle('unsold', !sold);
  const live = $('tk-live');
  if (live) live.className = 'tk-live ' + (sold ? 'sold' : 'unsold');
  setText('tk-live-text', sold ? 'Sold' : 'Unsold');

  setPortrait({ playerId: sale.playerId, name: sale.playerName, hasPhoto: true });
  setText('tk-name', sale.playerName);
  setText('tk-role', '');
  // Audit result carries no group/serial — hide those so nothing goes stale.
  const grp = $('tk-group'); if (grp) grp.style.display = 'none';
  const serial = $('tk-serial'); if (serial) serial.style.display = 'none';
  const teamCard = $('tk-team'); if (teamCard) teamCard.style.display = 'none';  // team shown in the verdict
  setText('tk-stamp', sold ? 'SOLD' : 'UNSOLD');
  setTeamAccent(sold ? sale.teamName : null);
  lastBid = null; lastBidPlayer = null;   // next player on the block starts fresh
  setHTML('tk-deal', sold
      ? crest(sale.teamName) + `<span>${esc(sale.teamName)}</span>`
        + `<span class="tk-amount">${fmtShort(sale.amount)}</span>`
      : '<span>—</span>');
  showSides(sale);   // giant SOLD/UNSOLD in the side spaces
  reveal();
}

// Play the shared hammer→purse celebration exactly once per NEW sale. The first
// result seen only sets a baseline (never replays an old sale on a page refresh).
let fxBaseline = false, lastFxSaleId = null;
let soldBandUntil = 0;   // keep the verdict band up briefly after a sale, then hide

function noteResult(result) {
  const id = result.saleId;
  if (!fxBaseline) { fxBaseline = true; lastFxSaleId = id; return false; }
  if (id && id !== lastFxSaleId) {
    lastFxSaleId = id;
    soldBandUntil = Date.now() + 7000;
    if (result.type === 'SOLD' && typeof playSoldToTeam === 'function') {
      playSoldToTeam({ playerName: result.playerName, playerId: result.playerId,
                       teamName: result.teamName, amount: result.amount, sound: true });
    }
    return true;   // this is a fresh result
  }
  return false;
}

let pollSeq = 0;
let baselineDone = false;
async function refresh() {
  const mySeq = ++pollSeq;
  const current = () => mySeq === pollSeq;
  try {
    const dash = await getJSON('/api/dashboard');
    if (!current()) return;

    // Seed the sold-FX baseline ONCE, from the audit at load time, so the very
    // next sale animates — including the first sale of the session while a
    // player is on the block (otherwise that first sale was silently treated as
    // the baseline and skipped, so no hammer/purse animation ever played).
    if (!baselineDone) {
      const seed = await getJSON('/api/admin/audit').catch(() => []);
      if (!current()) return;
      const lastSeed = [].concat(seed).reverse().find(a => a.type === 'SOLD' || a.type === 'UNSOLD');
      lastFxSaleId = lastSeed ? lastSeed.saleId : null;
      fxBaseline = true;
      baselineDone = true;
    }

    // A player on the block always wins — show the live band.
    if (dash.onTheBlock) { renderLive(dash.onTheBlock); return; }

    // Nobody on the block: react to the most recent terminal result.
    const audit = await getJSON('/api/admin/audit').catch(() => []);
    if (!current()) return;
    const last = [].concat(audit).reverse().find(a => a.type === 'SOLD' || a.type === 'UNSOLD');
    if (last) {
      const fresh = noteResult(last);
      if (fresh || Date.now() < soldBandUntil) { renderSold(last); return; }
    }
    hide();   // idle → fully hidden, screen free for video
  } catch (e) {
    if (!current()) return;
    // On an error keep whatever is showing; don't blank a live band on one blip.
  }
}

async function pollForever() {
  await refresh();
  setTimeout(pollForever, 1000);
}
pollForever();
