/* Sold celebration FX — a ~3.2s overlay: an auction hammer swings down and
 * STRIKES (with an impact flash), then the SOLD reveal plays and the player's
 * photo flies into the buying team's "bag" (a pouch stamped with the team logo).
 * Shared by the live broadcast and the team dashboard. Self-contained: injects
 * its own CSS once, is pointer-events:none so it never blocks the page, honours
 * prefers-reduced-motion, and removes itself.
 *
 * Usage: playSoldToTeam({ playerName, playerId, teamName, amount, sound }).
 * Pass sound:true (the ticker does) to play a synthesized wooden-gavel knock at
 * the moment of impact; the on-page dashboards call it without sound.
 */
(function (global) {
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const initials = name => String(name || '?').split(/\s+/).filter(Boolean)
      .map(w => w[0]).slice(0, 2).join('').toUpperCase();

  const REVEAL_DELAY = '.78s';  // hammer (now slower) strikes first, then the reveal starts

  const CSS = `
  .sfx-overlay {
    position: fixed; inset: 0; z-index: 9990; pointer-events: none;
    display: flex; flex-direction: column; align-items: center; justify-content: center;
    gap: clamp(6px, 2vh, 22px);
    background: radial-gradient(60% 55% at 50% 42%, rgba(6,10,20,.8), rgba(6,10,20,.5) 70%, transparent);
    opacity: 0; animation: sfx-fade 3s ease forwards;
  }
  @keyframes sfx-fade { 0%{opacity:0} 7%{opacity:1} 86%{opacity:1} 100%{opacity:0} }

  /* --- Phase 1: the auction hammer swings down and strikes --- */
  .sfx-hammer {
    position: absolute; left: 50%; top: 30%; z-index: 3;
    font-size: clamp(64px, 13vh, 120px); line-height: 1; transform-origin: 85% 85%;
    filter: drop-shadow(0 8px 16px rgba(0,0,0,.6));
    animation: sfx-hammer 1.15s cubic-bezier(.5,0,.35,1) forwards;   /* slower swing */
  }
  @keyframes sfx-hammer {
    0%   { transform: translate(-6%, -34px) rotate(-74deg); opacity: 0; }
    22%  { transform: translate(-6%, -64px) rotate(-66deg); opacity: 1; }   /* raised */
    44%  { transform: translate(-26%, 18px)  rotate(16deg); opacity: 1; }   /* STRIKE */
    56%  { transform: translate(-24%, 4px)   rotate(3deg);  opacity: 1; }   /* recoil */
    70%  { transform: translate(-24%, 10px)  rotate(9deg);  opacity: 1; }
    100% { transform: translate(6%, -120px)  rotate(-52deg); opacity: 0; }  /* lift away */
  }
  .sfx-flash {
    position: absolute; left: 50%; top: 40%; z-index: 2;
    width: clamp(130px, 24vh, 240px); aspect-ratio: 1; transform: translate(-50%, -50%) scale(.2);
    border-radius: 50%; opacity: 0;
    background: radial-gradient(circle, rgba(255,255,255,.95), rgba(45,212,143,.55) 42%, transparent 70%);
    animation: sfx-flash .45s ease-out .47s forwards;   /* synced to the slower strike */
  }
  @keyframes sfx-flash {
    0%{transform:translate(-50%,-50%) scale(.2);opacity:0}
    28%{transform:translate(-50%,-50%) scale(1);opacity:1}
    100%{transform:translate(-50%,-50%) scale(2.4);opacity:0}
  }

  /* --- Phase 2: SOLD reveal + player flies into the team bag (delayed) --- */
  .sfx-stamp {
    font-weight: 900; letter-spacing: .14em; color: var(--green, #2dd48f);
    font-size: clamp(22px, 4vh, 44px); text-shadow: 0 3px 16px rgba(45,212,143,.5);
    border: 3px solid var(--green, #2dd48f); border-radius: 14px; padding: 4px 20px;
    transform: rotate(-7deg) scale(0);
    animation: sfx-stamp .45s cubic-bezier(.2,.9,.3,1.5) ${REVEAL_DELAY} both;
  }
  @keyframes sfx-stamp { 0%{transform:rotate(-7deg) scale(0)} 100%{transform:rotate(-7deg) scale(1)} }

  .sfx-player {
    width: clamp(130px, 24vh, 240px); aspect-ratio: 3/4; border-radius: 20px; overflow: hidden;
    background: linear-gradient(135deg, var(--accent, #5b8cff), var(--accent-2, #8b5cf6));
    box-shadow: 0 24px 64px -16px rgba(0,0,0,.75), inset 0 1px 0 rgba(255,255,255,.25);
    display: flex; align-items: center; justify-content: center;
    color: #fff; font-size: clamp(44px, 9vh, 88px); font-weight: 800;
    animation: sfx-fly 2s cubic-bezier(.55,0,.4,1) ${REVEAL_DELAY} both; will-change: transform, opacity;
  }
  .sfx-player img { width: 100%; height: 100%; object-fit: cover; display: block; }
  @keyframes sfx-fly {
    0%   { transform: translateY(-40px) scale(.6) rotate(-5deg); opacity: 0; }
    16%  { transform: translateY(0)     scale(1)  rotate(0);     opacity: 1; }
    52%  { transform: translateY(6px)   scale(1)  rotate(0);     opacity: 1; }
    85%  { transform: translateY(190px) scale(.12) rotate(16deg); opacity: .85; }
    100% { transform: translateY(215px) scale(.03) rotate(20deg); opacity: 0; }
  }

  .sfx-bag { display: flex; flex-direction: column; align-items: center;
    animation: sfx-catch 2s ease ${REVEAL_DELAY} both; will-change: transform, opacity; }
  @keyframes sfx-catch {
    0%   { transform: scale(.8); opacity: 0; }
    12%  { transform: scale(1);  opacity: 1; }
    58%  { transform: scale(1); }
    73%  { transform: scale(1.16) translateY(-5px); }
    86%  { transform: scale(.95); } 100% { transform: scale(1); }
  }
  .sfx-bag-handle {
    width: 46%; height: clamp(18px, 3vh, 32px); margin: 0 auto -4px;
    border: 5px solid rgba(91,140,255,.65); border-bottom: none; border-radius: 46px 46px 0 0;
  }
  .sfx-bag-body {
    width: clamp(116px, 19vh, 176px); height: clamp(104px, 17vh, 160px);
    border-radius: 12px 12px 20px 20px;
    background: linear-gradient(180deg, rgba(44,56,92,.96), rgba(22,30,52,.98));
    border: 2px solid rgba(91,140,255,.55);
    box-shadow: 0 22px 54px -14px rgba(0,0,0,.7), inset 0 2px 0 rgba(255,255,255,.08);
    display: flex; align-items: center; justify-content: center; overflow: hidden;
  }
  .sfx-bag-body img { width: 66%; height: 66%; object-fit: contain;
    filter: drop-shadow(0 4px 8px rgba(0,0,0,.55)); }
  .sfx-bag-crest {
    width: 62%; height: 62%; border-radius: 16px; display: flex; align-items: center; justify-content: center;
    background: linear-gradient(135deg, var(--accent, #5b8cff), var(--accent-2, #8b5cf6));
    color: #fff; font-weight: 800; font-size: clamp(26px, 5vh, 44px);
  }
  .sfx-bag-label {
    margin-top: clamp(8px, 1.4vh, 14px); font-weight: 800; color: #fff;
    font-size: clamp(15px, 2.4vh, 26px); text-align: center; max-width: 70vw;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    animation: sfx-appear .3s ease ${REVEAL_DELAY} both;
  }
  @keyframes sfx-appear { from{opacity:0} to{opacity:1} }
  .sfx-bag-amount { color: var(--green, #2dd48f); font-variant-numeric: tabular-nums; }

  @media (prefers-reduced-motion: reduce) {
    .sfx-overlay, .sfx-player, .sfx-bag, .sfx-stamp, .sfx-hammer, .sfx-flash, .sfx-bag-label {
      animation-duration: .01s; animation-delay: 0s;
    }
  }`;

  function injectCSS() {
    if (document.getElementById('sfx-style')) return;
    const s = document.createElement('style');
    s.id = 'sfx-style';
    s.textContent = CSS;
    document.head.appendChild(s);
  }

  const fmtShort = n => {
    if (n == null) return '';
    if (n >= 1e5) return '₹' + (n / 1e5).toFixed(1).replace(/\.0$/, '') + ' L';
    return '₹' + Number(n).toLocaleString('en-IN');
  };

  let active = false;

  function playSoldToTeam(opts) {
    const { playerName, playerId, teamName, amount } = opts || {};
    injectCSS();
    if (active) return;              // never stack two celebrations
    active = true;

    const overlay = document.createElement('div');
    overlay.className = 'sfx-overlay';

    const hammer = document.createElement('div');
    hammer.className = 'sfx-hammer';
    hammer.textContent = '🔨';
    const flash = document.createElement('div');
    flash.className = 'sfx-flash';

    const stamp = document.createElement('div');
    stamp.className = 'sfx-stamp';
    stamp.textContent = 'SOLD';

    const player = document.createElement('div');
    player.className = 'sfx-player';
    if (playerId) {
      const img = new Image();
      img.alt = '';
      img.onerror = () => { player.textContent = initials(playerName); };
      img.src = '/api/players/' + playerId + '/photo';
      player.appendChild(img);
    } else {
      player.textContent = initials(playerName);
    }

    const logoUrl = global.TeamLogo ? global.TeamLogo.teamLogoUrl(teamName) : null;
    const bag = document.createElement('div');
    bag.className = 'sfx-bag';
    bag.innerHTML =
      '<div class="sfx-bag-handle"></div>' +
      '<div class="sfx-bag-body">' +
        (logoUrl ? `<img src="${logoUrl}" alt="">`
                 : `<span class="sfx-bag-crest">${esc(initials(teamName))}</span>`) +
      '</div>';
    const label = document.createElement('div');
    label.className = 'sfx-bag-label';
    label.innerHTML = `🛍️ ${esc(teamName || '')}` +
      (amount != null ? ` · <span class="sfx-bag-amount">${fmtShort(amount)}</span>` : '');
    bag.appendChild(label);

    overlay.append(hammer, flash, stamp, player, bag);
    document.body.appendChild(overlay);

    // Wooden-gavel knock at the moment of impact — opt-in per call (the ticker
    // passes sound:true; the on-page dashboards stay silent). Synthesized via
    // Web Audio, so there's no asset to load.
    if (opts && opts.sound) {
      setTimeout(() => { try { playGavel(); } catch (e) { /* audio unavailable */ } }, 500);
    }

    setTimeout(() => { overlay.remove(); active = false; }, 3100);
  }

  // --- Wooden gavel sound (synthesized) -----------------------------------
  let sfxAudioCtx = null;
  function ensureAudio() {
    if (sfxAudioCtx) return sfxAudioCtx;
    const Ctx = global.AudioContext || global.webkitAudioContext;
    if (!Ctx) return null;
    sfxAudioCtx = new Ctx();
    // Browsers may suspend audio until a gesture; resume on any interaction so
    // it works outside OBS too (OBS browser sources autoplay audio already).
    const unlock = () => { if (sfxAudioCtx && sfxAudioCtx.state === 'suspended') sfxAudioCtx.resume(); };
    ['pointerdown', 'keydown', 'touchstart'].forEach(ev =>
      global.addEventListener(ev, unlock, { passive: true }));
    return sfxAudioCtx;
  }

  function playGavel() {
    const ctx = ensureAudio();
    if (!ctx) return;
    if (ctx.state === 'suspended') ctx.resume();
    const t0 = ctx.currentTime + 0.001;
    // Woody "thock": two quick, low-mid tones with fast exponential decay.
    [[190, 0.9], [300, 0.55]].forEach(([freq, gain]) => {
      const osc = ctx.createOscillator(), g = ctx.createGain();
      osc.type = 'triangle';
      osc.frequency.setValueAtTime(freq, t0);
      osc.frequency.exponentialRampToValueAtTime(freq * 0.65, t0 + 0.09);
      g.gain.setValueAtTime(0.0001, t0);
      g.gain.exponentialRampToValueAtTime(gain, t0 + 0.005);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.2);
      osc.connect(g).connect(ctx.destination);
      osc.start(t0); osc.stop(t0 + 0.22);
    });
    // Sharp attack "crack": a short band-passed noise burst.
    const dur = 0.05, sr = ctx.sampleRate;
    const buf = ctx.createBuffer(1, Math.max(1, Math.floor(sr * dur)), sr);
    const data = buf.getChannelData(0);
    for (let i = 0; i < data.length; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / data.length);
    const noise = ctx.createBufferSource(); noise.buffer = buf;
    const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = 1700; bp.Q.value = 0.8;
    const ng = ctx.createGain();
    ng.gain.setValueAtTime(0.7, t0); ng.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
    noise.connect(bp).connect(ng).connect(ctx.destination);
    noise.start(t0); noise.stop(t0 + dur);
  }

  global.playSoldToTeam = playSoldToTeam;
})(window);
