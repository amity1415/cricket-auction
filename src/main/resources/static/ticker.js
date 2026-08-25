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

// Compact career line, IPL-broadcast style: "CAREER · MTS 111 · RUNS 3284 · SR 149".
function statsLine(st) {
  if (!st) return '';
  const bits = [];
  const add = (l, v) => { if (v != null) bits.push(`${l} <b>${v}</b>`); };
  add('MTS', st.matches); add('RUNS', st.runs); add('HS', st.highestScore);
  add('AVG', st.battingAverage); add('SR', st.strikeRate);
  add('WKTS', st.wickets); add('ECON', st.economyRate);
  if (!bits.length) return '';
  return '<span class="tk-seg">Career</span>' + bits.slice(0, 5).join(' · ');
}

const root = () => $('tk-root');

function reveal() {
  const r = root();
  if (!r) return;
  if (!r.classList.contains('show')) { r.classList.add('show', 'enter'); }
}
function hide() {
  const r = root();
  if (r) { r.classList.remove('show', 'enter', 'is-sold'); }
}

function renderLive(p) {
  const r = root();
  if (!r) return;
  r.classList.remove('is-sold');
  $('tk-bar').classList.remove('sold', 'unsold');

  setPortrait(p);
  setText('tk-name', p.name);
  setText('tk-role', ROLE_LABEL[p.role] || String(p.role || '').replace('_', ' '));
  setText('tk-base', fmtShort(p.basePrice));

  if (p.currentBidAmount != null) {
    setText('tk-current-label', 'Current Bid');
    setText('tk-current', fmtShort(p.currentBidAmount));
    setHTML('tk-leader', crest(p.currentLeadingTeamName)
        + `<span class="tk-lname">${esc(p.currentLeadingTeamName)}</span>`);
  } else {
    setText('tk-current-label', 'Opening');
    setText('tk-current', fmtShort(p.basePrice));
    setHTML('tk-leader', '<span class="tk-lname">No bids yet</span>');
  }
  setText('tk-next', fmtShort(p.nextBidAmount));
  setHTML('tk-stats', statsLine(p.stats)
      + (p.bidCount ? `<span class="tk-bidcount">Bid #${p.bidCount}</span>` : ''));

  reveal();
}

function renderSold(sale) {
  const r = root();
  if (!r) return;
  const sold = sale.type === 'SOLD';
  r.classList.add('is-sold');
  $('tk-bar').classList.toggle('sold', sold);
  $('tk-bar').classList.toggle('unsold', !sold);

  setPortrait({ playerId: sale.playerId, name: sale.playerName, hasPhoto: true });
  setText('tk-name', sale.playerName);
  setText('tk-role', '');
  setText('tk-stamp', sold ? 'SOLD' : 'UNSOLD');
  setHTML('tk-deal', sold
      ? crest(sale.teamName) + `<span>${esc(sale.teamName)}</span>`
        + `<span class="tk-amount">${fmtShort(sale.amount)}</span>`
      : '<span>—</span>');
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
                       teamName: result.teamName, amount: result.amount });
    }
    return true;   // this is a fresh result
  }
  return false;
}

let pollSeq = 0;
async function refresh() {
  const mySeq = ++pollSeq;
  const current = () => mySeq === pollSeq;
  try {
    const dash = await getJSON('/api/dashboard');
    if (!current()) return;

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
