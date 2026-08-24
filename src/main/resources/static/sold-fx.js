/* Sold celebration FX — a ~3.2s overlay: an auction hammer swings down and
 * STRIKES (with an impact flash), then the SOLD reveal plays and the player's
 * photo flies into the buying team's "bag" (a pouch stamped with the team logo).
 * Shared by the live broadcast and the team dashboard. Self-contained: injects
 * its own CSS once, is pointer-events:none so it never blocks the page, honours
 * prefers-reduced-motion, and removes itself.
 *
 * Usage: playSoldToTeam({ playerName, playerId, teamName, amount }).
 */
(function (global) {
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const initials = name => String(name || '?').split(/\s+/).filter(Boolean)
      .map(w => w[0]).slice(0, 2).join('').toUpperCase();

  const REVEAL_DELAY = '.6s';   // hammer strikes first, then the reveal starts

  const CSS = `
  .sfx-overlay {
    position: fixed; inset: 0; z-index: 9990; pointer-events: none;
    display: flex; flex-direction: column; align-items: center; justify-content: center;
    gap: clamp(6px, 2vh, 22px);
    background: radial-gradient(60% 55% at 50% 42%, rgba(6,10,20,.8), rgba(6,10,20,.5) 70%, transparent);
    opacity: 0; animation: sfx-fade 2.7s ease forwards;
  }
  @keyframes sfx-fade { 0%{opacity:0} 7%{opacity:1} 86%{opacity:1} 100%{opacity:0} }

  /* --- Phase 1: the auction hammer swings down and strikes --- */
  .sfx-hammer {
    position: absolute; left: 50%; top: 30%; z-index: 3;
    font-size: clamp(64px, 13vh, 120px); line-height: 1; transform-origin: 85% 85%;
    filter: drop-shadow(0 8px 16px rgba(0,0,0,.6));
    animation: sfx-hammer .8s cubic-bezier(.5,0,.35,1) forwards;
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
    animation: sfx-flash .45s ease-out .32s forwards;
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

    setTimeout(() => { overlay.remove(); active = false; }, 2800);
  }

  global.playSoldToTeam = playSoldToTeam;
})(window);
