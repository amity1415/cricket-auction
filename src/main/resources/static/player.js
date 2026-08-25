/* Player profile page — the whole picture for one player: identity, career
 * stats, auction state, and bid history. Opened via player.html?playerId=…
 * Polls every 3s so a live auction updates in place. */

const fmtINR = n => n == null ? '—'
    : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);
const esc = s => String(s ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const ROLE_NAME = { BATSMAN: 'Batsman', BOWLER: 'Bowler', ALL_ROUNDER: 'All-rounder', WICKETKEEPER: 'Wicketkeeper' };
const ROLE_ICON = { BATSMAN: '🏏', BOWLER: '🔴', ALL_ROUNDER: '🏏🔴', WICKETKEEPER: '🧤' };

// CricHeroes brand mark — a green hero shield with a white cricket bat & ball.
// Inline so the page stays self-contained (no external asset fetch).
const CRICHEROES_ICON =
    '<svg class="ch-ico" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">'
  + '<path d="M12 2.2 19.2 4.7V11c0 4.6-3 8.3-7.2 9.6C7.8 19.3 4.8 15.6 4.8 11V4.7L12 2.2Z" fill="#16a34a"/>'
  + '<path d="M14.3 7.4 16.6 9.7 10.9 15.4 8.6 13.1Z" fill="#fff"/>'
  + '<path d="M16.2 8 17.9 6.3" stroke="#fff" stroke-width="1.6" stroke-linecap="round"/>'
  + '<circle cx="9.2" cy="9.4" r="1.5" fill="#fff"/></svg>';

// A "view stats on CricHeroes" link, or '' when the player has no (valid) URL.
// Only http(s) URLs are rendered, so imported data can never inject a
// javascript: href; opens in a new tab, no referrer leaked.
function cricheroesLink(url) {
  if (!url || !/^https?:\/\//i.test(url)) return '';
  return `<a class="cricheroes-link" href="${esc(url)}" target="_blank" rel="noopener noreferrer"
             title="View this player's stats on CricHeroes">${CRICHEROES_ICON}<span>CricHeroes stats</span>
             <span class="ch-ext" aria-hidden="true">↗</span></a>`;
}

const playerId = new URLSearchParams(location.search).get('playerId');

async function getJSON(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(url + ' → ' + res.status);
  return res.json();
}

async function refresh() {
  try {
    const [p, current, dash, bids] = await Promise.all([
      getJSON(`/api/players/${playerId}`),
      getJSON(`/api/players/${playerId}/current-bid`),
      getJSON('/api/dashboard'),
      getJSON(`/api/admin/players/${playerId}/bids`).catch(() => []),
    ]);
    render(p, current, dash.teams, bids);
  } catch (e) {
    document.getElementById('content').innerHTML =
        '<p class="muted">Could not load this player — check the link or try again.</p>';
    lastHtml = null;   // force a fresh render once the player loads again
  }
}

function initials(name) {
  return name.split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase();
}

function statusLine(p, current, teamName) {
  switch (p.status) {
    case 'SOLD':
      return `🔨 Sold to <b>${esc(teamName(p.soldToTeamId))}</b> for <b class="good">${fmtINR(p.soldPrice)}</b>
              <span class="muted">· ${new Date(p.soldAt).toLocaleTimeString()}</span>`;
    case 'UNDER_AUCTION':
      return current.currentBidAmount != null
          ? `🔥 On the block — current bid <b class="good">${fmtINR(current.currentBidAmount)}</b>
             by <b>${esc(current.currentLeadingTeamName)}</b> · bid #${current.bidCount}
             · next ${fmtINR(current.nextBidAmount)}`
          : `🔥 On the block — opens at <b class="good">${fmtINR(p.basePrice)}</b>, no bids yet`;
    case 'RETAINED':
      return `📌 Retained by <b>${esc(teamName(p.soldToTeamId))}</b> at base price
              <b class="good">${fmtINR(p.soldPrice)}</b> — not in the auction pool`;
    case 'UNSOLD':
      return '⛔ Went unsold — out of this auction';
    default:
      return `🟢 In the pool — opens at <b class="good">${fmtINR(p.basePrice)}</b>`;
  }
}

function statTile(label, value) {
  return `<div class="btile"><b>${value ?? '—'}</b><span>${label}</span></div>`;
}

let lastHtml = null;   // skip the 3s redraw when nothing changed, so the poster
                       // image isn't torn down and refetched on every poll.

function render(p, current, teams, bids) {
  const teamName = id => teams.find(t => t.teamId === id)?.name || '?';
  const st = p.stats || {};
  const hasBatting = st.battingInnings != null || st.runs != null || st.battingAverage != null
      || st.strikeRate != null || st.highestScore != null;
  const hasBowling = st.bowlingInnings != null || st.wickets != null
      || st.economyRate != null || st.bowlingAverage != null || st.bestBowling != null;

  document.title = `${p.name} — Player Profile`;
  const html = `
    <section class="card profile-hero">
      ${p.hasPhoto ? `<img class="profile-poster" src="/api/players/${p.playerId}/photo" alt=""
         onerror="this.style.display='none';this.nextElementSibling.style.display='';">` : ''}
      <div class="avatar"${p.hasPhoto ? ' style="display:none"' : ''}>${initials(p.name)}</div>
      <div class="hero-main">
        <h2 class="pname">${esc(p.name)}</h2>
        <div class="chips">
          <span class="chip">${ROLE_ICON[p.role] || ''} ${ROLE_NAME[p.role] || p.role}</span>
          <span class="chip">Group ${p.category}</span>
          <span class="badge ${p.status}">${p.status.replace('_', ' ')}</span>
          ${cricheroesLink(p.cricheroesUrl)}
        </div>
        <div class="status-line">${statusLine(p, current, teamName)}</div>
      </div>
    </section>

    <section class="card">
      <h2>Career</h2>
      ${st.matches != null ? `<div class="career-lead"><b>${st.matches}</b><span>Matches played</span></div>` : ''}
      <div class="stat-panels">
        ${hasBatting ? `
          <div class="stat-panel batting">
            <div class="sp-head">🏏 Batting</div>
            <div class="sp-grid">
              ${statTile('Innings', st.battingInnings)}
              ${statTile('Runs', st.runs)}
              ${statTile('Highest', st.highestScore)}
              ${statTile('Average', st.battingAverage)}
              ${statTile('Strike Rate', st.strikeRate)}
            </div>
          </div>` : ''}
        ${hasBowling ? `
          <div class="stat-panel bowling">
            <div class="sp-head">🎯 Bowling</div>
            <div class="sp-grid">
              ${statTile('Innings', st.bowlingInnings)}
              ${statTile('Wickets', st.wickets)}
              ${statTile('Average', st.bowlingAverage)}
              ${statTile('Economy', st.economyRate)}
              ${statTile('Best Bowling', st.bestBowling)}
            </div>
          </div>` : ''}
      </div>
      ${!hasBatting && !hasBowling && st.matches == null
          ? '<p class="muted">No career stats on record for this player.</p>' : ''}
    </section>

    <section class="card">
      <h2>Auction</h2>
      <div class="btile-grid">
        ${statTile('Group', p.category)}
        ${statTile('Base price', fmtINR(p.basePrice))}
        ${p.status === 'SOLD' ? statTile('Sold for', fmtINR(p.soldPrice)) : ''}
        ${p.status === 'SOLD' ? statTile('Sold to', esc(teamName(p.soldToTeamId))) : ''}
        ${p.status === 'UNDER_AUCTION' && current.currentBidAmount != null
            ? statTile('Current bid', fmtINR(current.currentBidAmount)) : ''}
      </div>
      ${bids.length ? `
        <h2 style="margin-top:16px">Bid history</h2>
        <table class="pool">
          <thead><tr><th>#</th><th>Team</th><th>Amount</th><th>When</th></tr></thead>
          <tbody>
            ${bids.slice().reverse().map(b => `
              <tr>
                <td>${b.bidNumber}</td>
                <td><b>${esc(b.teamName)}</b></td>
                <td>${fmtINR(b.amount)}</td>
                <td class="muted">${new Date(b.recordedAt).toLocaleTimeString()}</td>
              </tr>`).join('')}
          </tbody>
        </table>`
      : '<p class="muted" style="margin-top:12px">No bids recorded yet.</p>'}
    </section>`;

  // Only touch the DOM when the rendered content actually changed — otherwise the
  // 3s live-poll rebuilds the section every tick and the poster <img> is recreated
  // and refetched, which reads as the page "reloading" every few seconds.
  if (html !== lastHtml) {
    document.getElementById('content').innerHTML = html;
    lastHtml = html;
  }
}

if (!playerId) {
  document.getElementById('content').innerHTML =
      '<p class="muted">No player selected — open this page from a player name link.</p>';
} else {
  refresh();
  setInterval(refresh, 3000);
}
