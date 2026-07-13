/* Shared top-nav for every app page: a compact dropdown menu that renders well
 * on mobile. It replaces each page's inline links with one unified menu built
 * from the current user's role — admin, franchise owner, or guest — highlights
 * the current page, and handles login/logout. Also exposes window.currentUser
 * and window.authReady (a promise) for page scripts that scope by team. */

(function () {
  const esc = s => String(s ?? '').replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // Menu entries per role. Everyone gets the players analysis; the rest depends
  // on what that role is allowed to reach (the server enforces it regardless).
  function menuFor(role, me) {
    const items = [{ href: 'players.html', label: '📊 Players & analysis' }];
    if (role === 'ADMIN') {
      items.push(
        { href: 'auctions.html', label: '🏆 Auctions' },
        { href: 'index.html', label: '⚙️ Setup' },
        { href: 'auction.html', label: '🔨 Auction console' },
        { href: 'team.html', label: '👥 Team dashboards' },
        { href: 'broadcast.html', label: '📺 Live broadcast' },
        { href: 'swagger-ui.html', label: '📖 API docs' },
      );
    } else if (role === 'FRANCHISE_OWNER') {
      items.push(
        { href: me && me.teamId ? 'team.html?teamId=' + me.teamId : 'team.html', label: '⭐ My team' },
        { href: 'team.html', label: '👥 Browse teams' },
        { href: 'broadcast.html', label: '📺 Live broadcast' },
      );
    } else { // guest — only the public read-only screens
      items.push(
        { href: 'team.html', label: '👥 Team dashboards' },
        { href: 'broadcast.html', label: '📺 Live broadcast' },
      );
    }
    return items;
  }

  function build(me) {
    const role = me ? me.role : 'GUEST';
    const header = document.querySelector('header');
    if (!header) return;
    let nav = header.querySelector('nav');
    if (!nav) { nav = document.createElement('nav'); header.appendChild(nav); }
    nav.innerHTML = '';

    const here = (location.pathname.split('/').pop() || 'index.html');

    // Hamburger toggle.
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'menu-btn';
    btn.setAttribute('aria-label', 'Menu');
    btn.setAttribute('aria-expanded', 'false');
    btn.innerHTML = '<span></span><span></span><span></span>';

    // Dropdown panel.
    const panel = document.createElement('div');
    panel.className = 'menu-panel';

    const head = document.createElement('div');
    head.className = 'menu-user';
    head.innerHTML = me
      ? `<span class="menu-avatar">👤</span><span class="menu-uinfo">
           <b>${esc(me.displayName || me.username)}</b>
           <span class="menu-role">${role === 'ADMIN' ? 'Admin' : 'Owner'}</span></span>`
      : `<span class="menu-avatar">👁</span><span class="menu-uinfo">
           <b>Guest</b><span class="menu-role muted">read-only</span></span>`;
    panel.appendChild(head);

    menuFor(role, me).forEach(it => {
      const a = document.createElement('a');
      a.href = it.href;
      a.className = 'menu-item';
      a.textContent = it.label;
      const target = it.href.split(/[?#]/)[0].split('/').pop();
      if (target === here) a.classList.add('active');
      panel.appendChild(a);
    });

    const foot = document.createElement('div');
    foot.className = 'menu-foot';
    if (me) {
      const out = document.createElement('button');
      out.type = 'button';
      out.className = 'menu-item menu-logout';
      out.textContent = '⎋ Log out';
      out.addEventListener('click', async () => {
        try { await fetch('/api/auth/logout', { method: 'POST' }); } catch (e) { /* ignore */ }
        location.href = '/login.html';
      });
      foot.appendChild(out);
    } else {
      const login = document.createElement('a');
      login.href = 'login.html';
      login.className = 'menu-item menu-login';
      login.textContent = '🔑 Log in';
      foot.appendChild(login);
    }
    panel.appendChild(foot);

    nav.appendChild(btn);
    nav.appendChild(panel);

    const close = () => { nav.classList.remove('open'); btn.setAttribute('aria-expanded', 'false'); };
    btn.addEventListener('click', e => {
      e.stopPropagation();
      const open = nav.classList.toggle('open');
      btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    document.addEventListener('click', e => { if (!nav.contains(e.target)) close(); });
    document.addEventListener('keydown', e => { if (e.key === 'Escape') close(); });
  }

  window.authReady = (async function () {
    let me = null;
    try {
      const r = await fetch('/api/auth/me').then(x => x.json());
      me = r && r.authenticated ? r : null;
    } catch (e) {
      me = null;
    }
    window.currentUser = me;
    build(me);
    return me;
  })();
})();
