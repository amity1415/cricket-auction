/* Voice bidding — the auctioneer speaks; the console auto-updates the bid.
 * ---------------------------------------------------------------------------
 * Feature:
 *   - Auctioneer says only an AMOUNT ("fifty thousand!")  → the current bid
 *     rises to that amount and the leading-team LOGO is HIDDEN (we don't yet
 *     know who bid). This is held server-side as a "pending verbal bid" so the
 *     audience broadcast screen (a different device) shows it too.
 *   - Auctioneer names a TEAM ("... Warriors!")           → the pending amount
 *     is attributed to that team as a REAL bid, and the logo appears.
 *   - Says both together ("fifty thousand Warriors")       → placed at once.
 *   - Amount and team may arrive in EITHER order, across separate utterances.
 *
 * How it drives the real auction (per product decision): a recognized amount is
 * treated as the NEW TOTAL bid (not a delta), and once a team is known we place
 * a genuine bid through the existing /place-bid endpoint, so purse/feasibility/
 * confirm-sale all keep working. Amount-only is parked via /verbal-bid.
 *
 * Runs only on the auctioneer's console (this page). Uses the Web Speech API
 * (Chrome/Edge). It reads shared globals from app.js: lastBlock, lastTeams,
 * post(), refresh(), toast(), fmtINR().
 *
 * WHERE IT CAN FAIL / limitations:
 *   - Speech recognition is best-effort: accents, noise and homophones cause
 *     mis-hears. That is why nothing here is destructive — the worst case is a
 *     wrong pending amount, which the auctioneer fixes by saying the right
 *     number again, "cancel", or using the on-screen Undo/Manual bid.
 *   - Bare numbers are taken literally; say the UNIT ("forty THOUSAND", "one
 *     LAKH") so the amount scales correctly. Supported units: thousand/k,
 *     lakh/lac, crore/cr, plus hundred.
 *   - The Web Speech API needs Chrome/Edge and a mic permission; unsupported
 *     browsers just never see the control.
 */
(function () {
  'use strict';

  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;

  const el = {
    wrap:   document.getElementById('voice-bid'),
    toggle: document.getElementById('vb-toggle'),
    status: document.getElementById('vb-status'),
    heard:  document.getElementById('vb-heard'),
  };
  if (!el.wrap || !el.toggle) return;

  // Unsupported browser: reveal a disabled hint instead of a dead button.
  if (!SR) {
    el.wrap.style.display = 'flex';
    el.toggle.disabled = true;
    el.toggle.textContent = '🎙 Voice N/A';
    el.status.textContent = 'Use Chrome or Edge for voice bidding';
    return;
  }
  el.wrap.style.display = 'flex';

  // --- number-word parsing (English + Indian units) ------------------------
  const SMALL = {
    zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7,
    eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, thirteen: 13,
    fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18,
    nineteen: 19, twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60,
    seventy: 70, eighty: 80, ninety: 90,
  };
  // Scale words that "flush" the running value into the total.
  const MAG = { thousand: 1e3, k: 1e3, lakh: 1e5, lac: 1e5, lakhs: 1e5, crore: 1e7, cr: 1e7, crores: 1e7 };

  /**
   * Parse a spoken amount from free text. Handles "fifty thousand",
   * "one lakh twenty thousand", "1.5 crore", "40000". Returns whole rupees, or
   * null if no number is present. Non-number words (team names, filler) are
   * ignored, so "fifty thousand warriors" still yields 50000.
   */
  function parseAmount(text) {
    const words = text.toLowerCase().replace(/,/g, '').replace(/[-]/g, ' ').split(/\s+/);
    let total = 0, current = 0, found = false;
    for (const w of words) {
      if (w === '') continue;
      if (/^\d+(\.\d+)?$/.test(w)) { current += parseFloat(w); found = true; continue; }
      if (SMALL[w] != null) { current += SMALL[w]; found = true; continue; }
      if (w === 'hundred' || w === 'hundreds') { current = (current || 1) * 100; found = true; continue; }
      if (MAG[w] != null) { current = (current || 1) * MAG[w]; total += current; current = 0; found = true; continue; }
      // any other word (team name, "for", "going once"...) is ignored
    }
    total += current;
    return found ? Math.round(total) : null;
  }

  // --- team matching -------------------------------------------------------
  const lettersOnly = s => String(s || '').toLowerCase().replace(/[^a-z\s]/g, ' ');

  /**
   * Find which registered team the auctioneer named, by matching significant
   * words of the team name against the transcript. Returns a teamId or null.
   * Longer keyword matches win, so "kolkata knight riders" beats a stray short
   * word. Falls back to null when no team is clearly named.
   */
  function parseTeam(text) {
    const said = ' ' + lettersOnly(text) + ' ';
    let best = null, bestScore = 0;
    for (const t of (typeof lastTeams !== 'undefined' ? lastTeams : [])) {
      const words = lettersOnly(t.name).split(/\s+/).filter(w => w.length >= 3);
      let score = 0;
      for (const w of words) {
        if (said.includes(' ' + w + ' ')) score = Math.max(score, w.length);
      }
      if (score > bestScore) { bestScore = score; best = t.teamId; }
    }
    return best;
  }

  // --- reconciliation state ------------------------------------------------
  // Local mirror so team/amount spoken in separate utterances line up even
  // before the 2s dashboard poll refreshes lastBlock.
  const state = { playerId: null, pendingTeamId: null, pendingAmount: null };

  function resetPending() { state.pendingTeamId = null; state.pendingAmount = null; }

  function syncPlayer(block) {
    const pid = block ? block.playerId : null;
    if (pid !== state.playerId) { state.playerId = pid; resetPending(); }
  }

  async function placeBid(playerId, teamId, amount) {
    const r = await post(`/api/admin/players/${playerId}/place-bid`, { teamId, amount });
    if (r) {
      toast(`🎙 Bid #${r.bidNumber}: ${r.currentLeadingTeamName} → ${fmtINR(r.currentBidAmount)}`);
      resetPending();
      refresh();
    }
    // On failure post() already toasted; keep pending so the auctioneer can retry.
  }

  async function setVerbalBid(playerId, amount) {
    const r = await post(`/api/admin/players/${playerId}/verbal-bid`, { amount });
    if (r) {
      state.pendingAmount = amount;
      toast(`🎙 ${fmtINR(amount)} — awaiting team…`);
      refresh();
    }
  }

  async function clearVerbal(playerId) {
    resetPending();
    await post(`/api/admin/players/${playerId}/clear-verbal-bid`);
    toast('🎙 Pending bid cleared');
    refresh();
  }

  /** Turn one final utterance into an action. */
  function handleUtterance(text) {
    const block = (typeof lastBlock !== 'undefined') ? lastBlock : null;
    syncPlayer(block);

    // Explicit correction keywords.
    if (/\b(cancel|clear|reset|scratch)\b/i.test(text)) {
      if (block) clearVerbal(block.playerId);
      return;
    }

    const amount = parseAmount(text);
    const teamId = parseTeam(text);
    if (amount == null && teamId == null) return; // nothing biddable in this phrase

    if (!block) { toast('No player is under auction — put one on the block first', true); return; }

    const effTeam = teamId || state.pendingTeamId;
    const effAmount = amount != null ? amount
        : (state.pendingAmount != null ? state.pendingAmount : block.pendingBidAmount);

    if (effAmount != null && effTeam) {
      placeBid(block.playerId, effTeam, effAmount);           // both known → real bid
    } else if (amount != null) {
      state.pendingAmount = amount;                           // remember now (beat the async POST/poll)
      setVerbalBid(block.playerId, amount);                   // amount only → park it, hide logo
    } else if (teamId) {
      state.pendingTeamId = teamId;                           // team first → wait for the amount
      const name = (lastTeams.find(t => t.teamId === teamId) || {}).name || 'Team';
      toast(`🎙 ${name} noted — say the bid amount`);
    }
  }

  // --- Web Speech API wiring ----------------------------------------------
  let listening = false;
  const rec = new SR();
  rec.lang = 'en-IN';           // Indian English + rupee-scale numbers
  rec.continuous = true;         // keep listening across pauses
  rec.interimResults = true;     // show what's being heard live

  rec.onresult = (e) => {
    let interim = '';
    for (let i = e.resultIndex; i < e.results.length; i++) {
      const res = e.results[i];
      if (res.isFinal) {
        el.heard.textContent = '“' + res[0].transcript.trim() + '”';
        handleUtterance(res[0].transcript);
      } else {
        interim += res[0].transcript;
      }
    }
    if (interim) el.heard.textContent = '“' + interim.trim() + '”…';
  };

  rec.onerror = (e) => {
    if (e.error === 'no-speech' || e.error === 'aborted') return; // benign, keep going
    if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
      toast('Microphone blocked — allow mic access to use voice bidding', true);
      stop();
      return;
    }
    toast('Voice error: ' + e.error, true);
  };

  // Chrome ends recognition periodically; restart while the user wants it on.
  rec.onend = () => { if (listening) { try { rec.start(); } catch (_) {} } };

  function start() {
    try { rec.start(); } catch (_) { /* already starting */ }
    listening = true;
    el.toggle.textContent = '🔴 Stop';
    el.toggle.classList.add('listening');
    el.status.textContent = 'Listening — say the amount and/or team';
    el.status.classList.remove('muted');
  }

  function stop() {
    listening = false;
    try { rec.stop(); } catch (_) {}
    el.toggle.textContent = '🎙 Listen';
    el.toggle.classList.remove('listening');
    el.status.textContent = 'Voice off';
    el.status.classList.add('muted');
    el.heard.textContent = '';
  }

  el.toggle.addEventListener('click', () => (listening ? stop() : start()));
})();
