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
  const BASE = '/img/teams/';

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

  /** The logo image URL for a team name, or null if we don't have one. */
  function teamLogoUrl(name) {
    const slug = teamLogoSlug(name);
    return slug ? BASE + slug + '.png' : null;
  }

  /** True when we have a real crest for this team. */
  const hasTeamLogo = name => teamLogoSlug(name) != null;

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

  global.TeamLogo = { teamLogoUrl, teamLogoSlug, hasTeamLogo, teamCrest, teamInitials: initials };
})(window);
