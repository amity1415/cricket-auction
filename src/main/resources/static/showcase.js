/* Post-auction team showcase.
 *
 * Renders one Instagram-portrait (4:5) card per team — team crest + name, the
 * OWNER and ICON featured, then the rest of the squad by photo + name only (their
 * pool/tier is intentionally not shown). The page is public but the roster is
 * served only once the admin marks the auction complete; an admin sees a preview
 * plus a Publish/Unpublish toggle. A focus lightbox shows a single clean card to
 * screenshot straight onto Instagram. */
(function () {
  'use strict';

  const $ = id => document.getElementById(id);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // Forward a ?tournamentId= from the page URL onto every API call so the page
  // can showcase a specific auction (defaults to the active one otherwise).
  const TID = new URLSearchParams(location.search).get('tournamentId');
  const api = path => TID ? path + (path.includes('?') ? '&' : '?') + 'tournamentId=' + encodeURIComponent(TID) : path;

  let state = { complete: false, teams: [], name: 'Auction', isAdmin: false };
  let focusIndex = -1;

  // A stable, pleasant accent colour per team (hashed from the name).
  function accentFor(name) {
    let h = 0;
    const s = String(name || '');
    for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360;
    return `hsl(${h} 68% 60%)`;
  }

  function avatar(player, ringCls) {
    const ini = TeamLogo.teamInitials(player.name);
    const src = api('/api/players/' + player.playerId + '/photo');
    // initials sit underneath; the photo overlays and, if it fails to load, is
    // removed so the initials show through.
    return `<span class="ava ${ringCls || ''}"><span class="ini">${esc(ini)}</span>`
      + `<img src="${esc(src)}" alt="${esc(player.name)}" loading="lazy" decoding="async"`
      + ` onerror="this.remove()"></span>`;
  }

  function featured(player) {
    const isOwner = player.label === 'OWNER';
    return `<div class="feat ${isOwner ? 'owner' : 'icon'}">`
      + avatar(player, isOwner ? 'ring-gold' : 'ring-cyan')
      + `<div class="who"><span class="badge ${isOwner ? 'owner' : 'icon'}">${esc(player.label)}</span>`
      + `<div class="nm">${esc(player.name)}</div></div></div>`;
  }

  function squareCell(player) {
    return `<div class="plr">${avatar(player)}<div class="nm">${esc(player.name)}</div></div>`;
  }

  function cardEl(team, index) {
    const owners = team.players.filter(p => p.label === 'OWNER');
    const icons = team.players.filter(p => p.label === 'ICON');
    const rest = team.players.filter(p => !p.label);

    const featuredHtml = [...owners, ...icons].map(featured).join('');
    const cols = rest.length > 12 ? 5 : (rest.length <= 6 ? 3 : 4);
    const gridHtml = rest.map(squareCell).join('');
    const crest = TeamLogo.teamCrest(team.name, { gradient: accentFor(team.name) });

    const el = document.createElement('article');
    el.className = 'team-card';
    el.style.setProperty('--accent', accentFor(team.name));
    el.dataset.index = index;
    el.innerHTML =
      `<button class="tc-shot" title="Open a clean card to screenshot">📸 Focus</button>`
      + `<div class="tc-head"><span class="tc-crest">${crest}</span>`
      + `<div class="tc-titles"><div class="tc-kicker">${esc(state.name)}</div>`
      + `<div class="tc-name">${esc(team.name)}</div>`
      + `<div class="tc-count">${team.players.length} players${team.ownerName ? ' • ' + esc(team.ownerName) : ''}</div>`
      + `</div></div>`
      + `<div class="tc-body">`
      + (featuredHtml ? `<div class="tc-featured">${featuredHtml}</div>` : '')
      + (gridHtml ? `<div class="tc-squad-label">Squad</div>`
          + `<div class="tc-grid ${cols === 5 ? 'cols-5' : ''}" style="--cols:${cols}">${gridHtml}</div>` : '')
      + `</div>`
      + `<div class="tc-foot"><span class="brand"><img src="img/csaitech-logo.png" alt="CSAITECH">Powered by CSAITECH</span>`
      + `<span class="event">${esc(state.name)} • Final Squad</span></div>`;

    el.addEventListener('click', () => openFocus(index));
    return el;
  }

  function render() {
    $('sc-title').textContent = state.name;
    const content = $('sc-content');
    const jump = $('sc-jump');
    jump.innerHTML = '';

    // Admin bar
    const adminBar = $('sc-admin');
    if (state.isAdmin) {
      adminBar.style.display = 'flex';
      const st = $('sc-status');
      st.textContent = state.complete ? 'Published' : 'Draft';
      st.className = 'pill ' + (state.complete ? 'live' : 'draft');
      $('sc-publish').textContent = state.complete ? 'Unpublish' : 'Publish showcase';
    } else {
      adminBar.style.display = 'none';
    }

    // Coming-soon / empty
    if (!state.teams.length) {
      $('sc-sub').textContent = state.complete ? 'No squads to show yet' : 'Not published yet';
      content.innerHTML =
        `<div class="sc-hero"><div class="trophy">🏆</div>`
        + `<h2>${state.complete ? 'Squads coming up' : 'The reveal is almost here'}</h2>`
        + `<p>${state.complete
              ? 'Final squads will appear here as soon as teams are set.'
              : 'Once the auction wraps up and the admin publishes the results, every team’s final squad will be revealed here.'}</p></div>`;
      return;
    }

    const totalPlayers = state.teams.reduce((n, t) => n + t.players.length, 0);
    $('sc-sub').textContent = `${state.teams.length} teams • ${totalPlayers} players`
      + (state.isAdmin && !state.complete ? ' • PREVIEW (not public yet)' : '');

    const stage = document.createElement('div');
    stage.id = 'sc-stage';
    state.teams.forEach((team, i) => {
      stage.appendChild(cardEl(team, i));
      const b = document.createElement('button');
      b.textContent = team.name;
      b.onclick = e => { e.stopPropagation(); stage.children[i].scrollIntoView({ behavior: 'smooth', block: 'start' }); };
      jump.appendChild(b);
    });
    content.innerHTML = '';
    content.appendChild(stage);
  }

  // ---- Focus lightbox ------------------------------------------------------
  function openFocus(index) {
    focusIndex = index;
    const stage = $('sc-focus-stage');
    stage.innerHTML = '';
    stage.appendChild(cardEl(state.teams[index], index));
    // inside the lightbox the card shouldn't re-open the lightbox on click
    stage.firstChild.replaceWith(stage.firstChild.cloneNode(true));
    $('sc-focus').classList.add('on');
    document.body.style.overflow = 'hidden';
  }
  function closeFocus() { $('sc-focus').classList.remove('on'); document.body.style.overflow = ''; focusIndex = -1; }
  function step(d) {
    if (focusIndex < 0) return;
    openFocus((focusIndex + d + state.teams.length) % state.teams.length);
  }

  async function togglePublish() {
    const btn = $('sc-publish');
    btn.disabled = true;
    try {
      const res = await fetch(api('/api/admin/showcase'), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ complete: !state.complete })
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      await load();
    } catch (e) {
      alert('Could not update the showcase: ' + e.message);
    } finally {
      btn.disabled = false;
    }
  }

  async function load() {
    // who am I (for the admin toggle + preview)
    try {
      const me = await (await fetch('/api/auth/me')).json();
      state.isAdmin = me && me.role === 'ADMIN';
    } catch (_) { state.isAdmin = false; }

    const data = await (await fetch(api('/api/dashboard/showcase'))).json();
    state.complete = !!data.complete;
    state.name = data.tournamentName || 'Auction';
    state.teams = Array.isArray(data.teams) ? data.teams : [];
    render();
  }

  // ---- wire up -------------------------------------------------------------
  $('sc-publish').addEventListener('click', togglePublish);
  $('sc-prev').addEventListener('click', () => step(-1));
  $('sc-next').addEventListener('click', () => step(1));
  $('sc-close').addEventListener('click', closeFocus);
  $('sc-focus').addEventListener('click', e => { if (e.target.id === 'sc-focus' || e.target.id === 'sc-focus-stage') closeFocus(); });
  document.addEventListener('keydown', e => {
    if (!$('sc-focus').classList.contains('on')) return;
    if (e.key === 'Escape') closeFocus();
    else if (e.key === 'ArrowLeft') step(-1);
    else if (e.key === 'ArrowRight') step(1);
  });

  load().catch(err => {
    $('sc-content').innerHTML = `<div class="sc-hero"><div class="trophy">⚠️</div>`
      + `<h2>Couldn’t load the showcase</h2><p>${esc(err.message)}</p></div>`;
  });
})();
