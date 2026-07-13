/* Binds a screen to one auction. The auction id travels in the URL
 * (?tournamentId=…); this script (a) tags every /api/ request with an
 * X-Tournament-Id header so the server scopes to that auction, (b) carries the
 * id through internal navigation links, and (c) labels the header with the
 * auction name. Loaded before auth.js on every auction-scoped screen.
 *
 * No id in the URL → no header → the server uses the default auction. The
 * Auctions hub (auctions.html) deliberately does NOT load this: it lists all. */
(function () {
  const id = new URLSearchParams(location.search).get('tournamentId');
  window.TOURNAMENT_ID = id || null;
  if (!id) return;

  // (a) Attach the auction id to every API call.
  const origFetch = window.fetch.bind(window);
  window.fetch = function (input, init) {
    try {
      const url = typeof input === 'string' ? input : (input && input.url) || '';
      if (url.indexOf('/api/') !== -1) {
        init = init || {};
        const headers = new Headers(
          init.headers || (typeof input !== 'string' && input.headers) || {});
        if (!headers.has('X-Tournament-Id')) headers.set('X-Tournament-Id', id);
        init = Object.assign({}, init, { headers });
      }
    } catch (e) { /* fall through with original args */ }
    return origFetch(input, init);
  };

  // (b) Keep the id on internal navigation. The hub and login are context-free.
  const SKIP = new Set(['login.html', 'auctions.html', '']);
  function rewriteLinks() {
    document.querySelectorAll('a[href]').forEach(a => {
      const href = a.getAttribute('href');
      if (!href || /^(https?:|mailto:|#|javascript:)/i.test(href)) return;
      const page = href.split(/[?#]/)[0].split('/').pop();
      if (SKIP.has(page) || !page.endsWith('.html')) return;
      const u = new URL(href, location.href);
      if (u.origin === location.origin && !u.searchParams.has('tournamentId')) {
        u.searchParams.set('tournamentId', id);
        a.setAttribute('href', u.pathname.slice(1) + u.search + u.hash);
      }
    });
  }

  // (c) Show which auction this screen is on.
  function showName() {
    origFetch('/api/tournaments/' + encodeURIComponent(id))
      .then(r => (r.ok ? r.json() : null))
      .then(t => {
        if (!t) return;
        const h1 = document.querySelector('header h1');
        if (h1 && !h1.querySelector('.auction-pill')) {
          const pill = document.createElement('span');
          pill.className = 'auction-pill';
          pill.textContent = '🏆 ' + t.name;
          h1.appendChild(pill);
        }
        document.title = t.name + ' · ' + document.title;
      })
      .catch(() => {});
  }

  document.addEventListener('DOMContentLoaded', () => {
    rewriteLinks();
    showName();
    // The nav menu (auth.js) and modals are built asynchronously after load, so
    // watch for added links and tag them too. Debounced; self-guards against its
    // own href edits by skipping links that already carry the id.
    let queued = false;
    const observer = new MutationObserver(() => {
      if (queued) return;
      queued = true;
      setTimeout(() => { queued = false; rewriteLinks(); }, 0);
    });
    observer.observe(document.body, { childList: true, subtree: true });
  });
})();
