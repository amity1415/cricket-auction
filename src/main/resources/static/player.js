/* Player profile page — the whole picture for one player: identity, career
 * stats, auction state, and bid history. Opened via player.html?playerId=…
 * Polls every 3s so a live auction updates in place. */

const fmtINR = n => n == null ? '—'
    : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);
const esc = s => String(s ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const ROLE_NAME = { BATSMAN: 'Batsman', BOWLER: 'Bowler', ALL_ROUNDER: 'All-rounder', WICKETKEEPER: 'Wicketkeeper' };
const ROLE_ICON = { BATSMAN: '🏏', BOWLER: '🔴', ALL_ROUNDER: '🏏🔴', WICKETKEEPER: '🧤' };

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

function render(p, current, teams, bids) {
  const teamName = id => teams.find(t => t.teamId === id)?.name || '?';
  const st = p.stats || {};
  const hasBatting = st.matches != null || st.runs != null || st.battingAverage != null || st.strikeRate != null;
  const hasBowling = st.wickets != null || st.economyRate != null;

  document.title = `${p.name} — Player Profile`;
  document.getElementById('content').innerHTML = `
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
        </div>
        <div class="status-line">${statusLine(p, current, teamName)}</div>
      </div>
    </section>

    <section class="card">
      <h2>Career</h2>
      ${hasBatting || hasBowling ? `
        <div class="btile-grid">
          ${statTile('Matches', st.matches)}
          ${hasBatting ? statTile('Runs', st.runs) : ''}
          ${hasBatting ? statTile('Batting Avg', st.battingAverage) : ''}
          ${hasBatting ? statTile('Strike Rate', st.strikeRate) : ''}
          ${hasBowling ? statTile('Wickets', st.wickets) : ''}
          ${hasBowling ? statTile('Economy', st.economyRate) : ''}
        </div>`
      : '<p class="muted">No career stats on record for this player.</p>'}
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
}

if (!playerId) {
  document.getElementById('content').innerHTML =
      '<p class="muted">No player selected — open this page from a player name link.</p>';
} else {
  refresh();
  setInterval(refresh, 3000);
}
