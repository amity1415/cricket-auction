/* Shared auth chrome for the logged-in pages. Fetches the current user, adds a
 * "who am I + log out" badge to the header, and strips admin-only links from the
 * nav when a franchise owner is viewing. The server still enforces every rule;
 * this is UX so owners aren't shown doors they can't open. Also exposes the user
 * on window.currentUser for page scripts (e.g. team.js scoping). */

(function () {
  const escapeHtml = s => String(s ?? '').replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // Pages an owner has no business seeing — remove links to them.
  const ADMIN_ONLY = /^\/?(index\.html|auction\.html|swagger-ui\.html)(\?|#|$)/;

  window.authReady = (async function () {
    let me;
    try {
      me = await fetch('/api/auth/me').then(r => r.json());
    } catch (e) {
      return null;
    }
    window.currentUser = me && me.authenticated ? me : null;
    if (!window.currentUser) return window.currentUser;

    const isOwner = me.role === 'FRANCHISE_OWNER';
    const nav = document.querySelector('header nav');
    if (nav) {
      if (isOwner) {
        nav.querySelectorAll('a').forEach(a => {
          if (ADMIN_ONLY.test(a.getAttribute('href') || '')) a.remove();
        });
      }

      // Highlight the link for the page we're on.
      const here = (location.pathname.split('/').pop() || 'index.html');
      nav.querySelectorAll('a').forEach(a => {
        const target = (a.getAttribute('href') || '').split(/[?#]/)[0].split('/').pop();
        if (target && target === here) a.classList.add('active');
      });

      // Visual divider between page links and the user chrome.
      const sep = document.createElement('span');
      sep.className = 'nav-sep';
      nav.appendChild(sep);
      const badge = document.createElement('span');
      badge.className = 'auth-badge';
      badge.innerHTML = `👤 ${escapeHtml(me.displayName || me.username)}`
          + ` <span class="auth-role">${isOwner ? 'Owner' : 'Admin'}</span>`;

      const logout = document.createElement('button');
      logout.type = 'button';
      logout.className = 'ghost auth-logout';
      logout.textContent = 'Log out';
      logout.addEventListener('click', async () => {
        try { await fetch('/api/auth/logout', { method: 'POST' }); } catch (e) { /* ignore */ }
        location.href = '/login.html';
      });

      nav.appendChild(badge);
      nav.appendChild(logout);
    }
    return window.currentUser;
  })();
})();
