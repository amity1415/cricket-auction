/* Owner live-bidding console. A franchise owner opens this on their phone and
 * bids on whatever player is on the block. The team is taken from the logged-in
 * session server-side, so this page never sends a team id — it just says "bid"
 * (accept the ask) or "bid this amount". State is polled once a second from
 * /api/live/state, one request in flight at a time (same discipline as the
 * broadcast board). Every bid resolves against the in-memory session under the
 * server's auction lock, so ties are settled first-come server-side, not here. */

const fmtINR = n => n == null ? '—'
    : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

const $ = id => document.getElementById(id);

// --- Elements ---
const els = {
  teamName: $('team-name'), purse: $('purse'),
  idleCard: $('idle-card'), idleText: $('idle-text'), blockCard: $('block-card'),
  photo: $('photo'), name: $('player-name'), role: $('player-role'),
  cat: $('player-cat'), base: $('player-base'),
  countdown: $('countdown'), bar: $('countdown-bar'), secs: $('countdown-secs'),
  leader: $('leader'), current: $('current-amount'),
  accept: $('btn-accept'), custom: $('custom-amount'), place: $('btn-custom'),
  toast: $('toast'),
};

let me = null;
let latest = null;           // last /api/live/state payload
let clockOffsetMs = 0;       // serverNow - localNow, to render the countdown drift-free
let barMaxSecs = 0;          // largest remaining seconds seen for the current deadline
let currentDeadline = null;  // ISO string of the deadline the bar is scaled to
let busy = false;            // a bid POST is in flight — guard double taps

// --- Toast ---
let toastTimer = null;
function toast(msg, kind) {
  els.toast.textContent = msg;
  els.toast.className = 'toast show' + (kind ? ' ' + kind : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { els.toast.className = 'toast'; }, 3200);
}

// --- Bid POSTs ---
async function placeBid(amount) {
  if (busy) return;
  busy = true;
  els.accept.disabled = true;
  els.place.disabled = true;
  try {
    const res = await fetch('/api/live/bid', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(amount == null ? {} : { amount }),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      toast(body.message || 'Bid not accepted', 'err');
    } else {
      // No success popup — the status line (amount + "You're the highest bidder")
      // updates on the next poll, which is confirmation enough.
      els.custom.value = '';
    }
  } catch (e) {
    toast('Network error — try again', 'err');
  } finally {
    busy = false;
    // Re-enable happens on the next render() based on fresh state.
    refresh();
  }
}

els.accept.addEventListener('click', () => {
  if (!latest || !latest.playerOnBlock || latest.youLead) return;
  placeBid(null); // accept the current ask
});
els.place.addEventListener('click', () => {
  const v = Math.round(Number(els.custom.value));
  if (!Number.isFinite(v) || v <= 0) { toast('Enter a whole-rupee amount', 'err'); return; }
  placeBid(v);
});
els.custom.addEventListener('keydown', e => { if (e.key === 'Enter') els.place.click(); });

// --- Rendering ---
function showIdle(text) {
  els.blockCard.hidden = true;
  els.idleCard.hidden = false;
  els.idleText.textContent = text;
}

function render(state) {
  if (!state) return;
  latest = state;

  // Clock-skew correction so the countdown agrees with the server, not the phone.
  if (state.lastUpdated) {
    const serverNow = Date.parse(state.lastUpdated);
    if (!Number.isNaN(serverNow)) clockOffsetMs = serverNow - Date.now();
  }

  els.purse.textContent = state.yourRemainingPurse == null ? '—' : fmtINR(state.yourRemainingPurse);

  if (!state.playerOnBlock) {
    currentDeadline = null;
    showIdle(state.onlineBidding
      ? 'Waiting for the next player to come up for bidding…'
      : 'Live owner bidding is not enabled for this auction. The auctioneer is running the bids — watch the broadcast screen.');
    return;
  }

  els.idleCard.hidden = true;
  els.blockCard.hidden = false;

  els.name.textContent = state.playerName || '—';
  els.role.textContent = titleCase(state.role) || '';
  els.cat.textContent = state.category || '';
  els.base.textContent = fmtINR(state.basePrice);
  if (state.playerId) {
    els.photo.src = '/api/players/' + state.playerId + '/photo';
    els.photo.hidden = false;
    els.photo.onerror = () => { els.photo.hidden = true; };
  }

  const hasBid = state.currentBidAmount != null;
  els.current.textContent = hasBid ? fmtINR(state.currentBidAmount) : fmtINR(state.basePrice) + ' (opening)';
  if (!hasBid) {
    els.leader.textContent = 'No bids yet';
    els.leader.className = 'leader';
  } else if (state.youLead) {
    els.leader.textContent = "You're the highest bidder";
    els.leader.className = 'leader you';
  } else {
    els.leader.textContent = 'Leading: ' + (state.leadingTeamName || '—');
    els.leader.className = 'leader other';
  }

  // Accept-ask button
  const nextAsk = state.nextBidAmount;
  els.accept.textContent = state.youLead ? 'You are the highest bidder'
      : 'Bid ' + fmtINR(nextAsk);
  els.accept.disabled = busy || state.youLead || !state.onlineBidding || nextAsk == null;
  els.place.disabled = busy || !state.onlineBidding;
  els.custom.disabled = !state.onlineBidding;

  renderCountdown(state.biddingDeadline);
}

function renderCountdown(deadlineIso) {
  if (!deadlineIso) {
    els.countdown.hidden = true;
    currentDeadline = null;
    return;
  }
  els.countdown.hidden = false;
  if (deadlineIso !== currentDeadline) {
    currentDeadline = deadlineIso;
    barMaxSecs = 0; // recomputed below from the first reading of this deadline
  }
  tickCountdown();
}

function tickCountdown() {
  if (!currentDeadline) return;
  const deadline = Date.parse(currentDeadline);
  const serverNow = Date.now() + clockOffsetMs;
  const remainingMs = Math.max(0, deadline - serverNow);
  const secs = Math.ceil(remainingMs / 1000);
  if (secs > barMaxSecs) barMaxSecs = secs; // the window size, learned on first tick
  els.secs.textContent = secs + 's';
  const pct = barMaxSecs > 0 ? Math.max(0, Math.min(100, (remainingMs / (barMaxSecs * 1000)) * 100)) : 0;
  els.bar.style.width = pct + '%';
  els.countdown.classList.toggle('urgent', secs <= 5);
}

// Smooth 200ms countdown animation between the 1s state polls.
setInterval(() => { if (latest && latest.playerOnBlock && currentDeadline) tickCountdown(); }, 200);

function titleCase(s) {
  if (!s) return '';
  return String(s).charAt(0) + String(s).slice(1).toLowerCase();
}

// --- Polling (one request in flight at a time) ---
async function refresh() {
  try {
    const res = await fetch('/api/live/state');
    if (res.status === 401 || res.status === 403) { location.href = '/login.html'; return; }
    if (!res.ok) { showIdle('Reconnecting…'); return; }
    render(await res.json());
  } catch (e) {
    showIdle('Reconnecting…');
  }
}

async function pollForever() {
  await refresh();
  setTimeout(pollForever, 1000);
}

(async function init() {
  me = await window.authReady;
  if (!me) { location.href = '/login.html'; return; }
  els.teamName.textContent = me.teamName || me.displayName || me.username || 'You';
  if (!me.teamId) {
    // An admin/tournament-admin peeking: they can watch but not bid.
    els.accept.style.display = 'none';
    els.place.parentElement.style.display = 'none';
  }
  pollForever();
})();
