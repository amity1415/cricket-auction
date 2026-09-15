/* Team logos — shared across every surface (broadcast board, owner dashboard,
 * auction console, admin). Logos live in /img/teams/<slug>.png and are matched
 * to a team by NAME (there is no logo column on the team row — see CLAUDE.md's
 * "no new DB columns" rule), so a renamed or non-KCPL franchise simply falls
 * back to its initials crest and nothing breaks.
 *
 * Matching is keyword-based, not exact: "Warriors", "Kolkata Warriors" and
 * "The Warriors" all resolve to warriors.png. Each entry lists every keyword
 * that should map to it; the first entry with a keyword found in the (letters-
 * only, lower-cased) team name wins. Keep more specific keywords listed on the
 * franchise they belong to so e.g. "strikers" never leaks onto another crest. */
(function (global) {
  // Version the logo URLs so a client that cached a pre-launch failure for the
  // bare path (e.g. the 302→login served before /img/** was public) fetches a
  // brand-new URL instead of reusing that stale entry. Bump on any logo change.
  const BASE = '/img/teams/';
  const VER = '?v=2';

  // slug -> keywords that identify the franchise, in priority order.
  const LOGOS = [
    ['warriors',    ['warriors', 'warrior']],
    ['thunders',    ['thunder', 'thunders', 'strikers']],
    ['titans',      ['titans', 'titan']],
    ['predators',   ['predators', 'predator']],
    ['lions',       ['lions', 'lion']],
    ['indians',     ['indians', 'indian']],
    ['challengers', ['challengers', 'challenger']],
    ['honey-b',     ['honeyb', 'honey', 'badger', 'badgers']],
    ['fighters',    ['fighters', 'fighter']],
    ['knights',     ['knights', 'knight']],
  ];

  const _cache = new Map();

  const normalize = name => String(name == null ? '' : name).toLowerCase().replace(/[^a-z]/g, '');

  /** The logo slug for a team name, or null if we don't have one. */
  function teamLogoSlug(name) {
    const key = normalize(name);
    if (!key) return null;
    if (_cache.has(key)) return _cache.get(key);
    let hit = null;
    for (const [slug, keywords] of LOGOS) {
      if (keywords.some(k => key.includes(k))) { hit = slug; break; }
    }
    _cache.set(key, hit);
    return hit;
  }

  // Admin-provided team images (name -> URL), registered per page from the team
  // list. These take priority over the name-matched static crest, so a team whose
  // logo was set in setup shows that image everywhere its crest renders.
  const _images = new Map();

  /** Register admin-set team images from a list of {name, imageUrl} objects. */
  function registerImages(teams) {
    if (!Array.isArray(teams)) return;
    teams.forEach(t => {
      const key = normalize(t && t.name);
      if (key && t && t.imageUrl) _images.set(key, t.imageUrl);
    });
  }

  /** The logo image URL for a team name, or null if we don't have one. */
  function teamLogoUrl(name) {
    const custom = _images.get(normalize(name));
    if (custom) return custom;
    const slug = teamLogoSlug(name);
    return slug ? BASE + slug + '.png' + VER : null;
  }

  /** True when we have a real crest for this team. */
  const hasTeamLogo = name => teamLogoUrl(name) != null;

  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  const initials = name => String(name || '?').split(/\s+/).filter(Boolean)
      .map(w => w[0]).slice(0, 2).join('').toUpperCase();

  /**
   * A drop-in team crest: the franchise logo when we have one, otherwise the
   * existing initials tile so unknown/renamed teams still render. `cls` is added
   * to the wrapper; `gradient` (a CSS value) tints the initials fallback.
   */
  function teamCrest(name, { cls = '', gradient = '' } = {}) {
    const url = teamLogoUrl(name);
    if (url) {
      return `<span class="team-logo ${cls}"><img src="${url}" alt="${esc(name)} logo"
                loading="lazy" decoding="async" onerror="this.closest('.team-logo')?.classList.add('no-img')"></span>`;
    }
    const style = gradient ? ` style="--crest:${gradient}"` : '';
    return `<span class="crest ${cls}"${style}>${esc(initials(name))}</span>`;
  }

  global.TeamLogo = { teamLogoUrl, teamLogoSlug, hasTeamLogo, teamCrest, teamInitials: initials, registerImages };
})(window);
