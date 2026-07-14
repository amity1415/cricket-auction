/* Shared player-profile popup. Included on every page: intercepts clicks on
 * player-name links (a.plink → player.html?playerId=…) and opens the profile
 * in a modal instead of navigating away. Close via ✕, Esc, or clicking the
 * backdrop. Cmd/Ctrl/middle-click still opens the full page. Content refreshes
 * every 3s while open, so a live auction updates in place. */
(() => {
  const fmtINR = n => n == null ? '—'
      : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);
  const esc = s => String(s ?? '').replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const ROLE_NAME = { BATSMAN: 'Batsman', BOWLER: 'Bowler', ALL_ROUNDER: 'All-rounder', WICKETKEEPER: 'Wicketkeeper' };
  const ROLE_ICON = { BATSMAN: '🏏', BOWLER: '🔴', ALL_ROUNDER: '🏏🔴', WICKETKEEPER: '🧤' };

  const dialog = document.createElement('dialog');
  dialog.className = 'modal profile-modal';
  dialog.innerHTML = `
    <div class="modal-head">
      <h3 id="ppm-title">Player profile</h3>
      <span>
        <a id="ppm-full" class="modal-x" href="#" title="Open as full page" target="_blank">↗</a>
        <button type="button" class="modal-x" id="ppm-close" title="Close (Esc)">✕</button>
      </span>
    </div>
    <div class="modal-body" id="ppm-body"><p class="muted">Loading…</p></div>`;
  document.addEventListener('DOMContentLoaded', () => document.body.appendChild(dialog));
  if (document.body) document.body.appendChild(dialog);

  let currentId = null;
  let timer = null;
  let lastBodyHtml = null;   // skip the 3s redraw when nothing actually changed

  async function getJSON(url) {
    const res = await fetch(url);
    if (!res.ok) throw new Error(url);
    return res.json();
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
               by <b>${esc(current.currentLeadingTeamName)}</b> · bid #${current.bidCount}`
            : `🔥 On the block — opens at <b class="good">${fmtINR(p.basePrice)}</b>, no bids yet`;
      case 'RETAINED':
        return `📌 Retained by <b>${esc(teamName(p.soldToTeamId))}</b> at <b class="good">${fmtINR(p.soldPrice)}</b>`;
      case 'UNSOLD':
        return '⛔ Went unsold — out of this auction';
      default:
        return `🟢 In the pool — opens at <b class="good">${fmtINR(p.basePrice)}</b>`;
    }
  }

  const tile = (label, value) => `<div class="btile"><b>${value ?? '—'}</b><span>${label}</span></div>`;

  async function render() {
    if (!currentId) return;
    try {
      const [p, current, dash, bids] = await Promise.all([
        getJSON(`/api/players/${currentId}`),
        getJSON(`/api/players/${currentId}/current-bid`),
        getJSON('/api/dashboard'),
        getJSON(`/api/admin/players/${currentId}/bids`).catch(() => []),
      ]);
      const teamName = id => dash.teams.find(t => t.teamId === id)?.name || '(removed team)';
      const st = p.stats || {};
      const hasBatting = st.runs != null || st.battingAverage != null || st.strikeRate != null;
      const hasBowling = st.wickets != null || st.economyRate != null;

      document.getElementById('ppm-title').textContent = p.name;
      document.getElementById('ppm-full').href = `player.html?playerId=${p.playerId}`;
      const html = `
        <div class="pm-hero">
          <div class="avatar">${initials(p.name)}</div>
          <div class="hero-main">
            <div class="chips">
              <span class="chip">${ROLE_ICON[p.role] || ''} ${ROLE_NAME[p.role] || p.role}</span>
              <span class="chip">Group ${p.category}</span>
              <span class="badge ${p.status}">${p.status.replace('_', ' ')}</span>
            </div>
            <div class="status-line">${statusLine(p, current, teamName)}</div>
          </div>
        </div>

        ${hasBatting || hasBowling || st.matches != null ? `
          <div class="pm-label">Career</div>
          <div class="btile-grid">
            ${tile('Matches', st.matches)}
            ${hasBatting ? tile('Runs', st.runs) + tile('Batting Avg', st.battingAverage) + tile('Strike Rate', st.strikeRate) : ''}
            ${hasBowling ? tile('Wickets', st.wickets) + tile('Economy', st.economyRate) : ''}
          </div>` : '<p class="muted">No career stats on record.</p>'}

        <div class="pm-label">Auction</div>
        <div class="btile-grid">
          ${tile('Group', p.category)}
          ${tile('Base price', fmtINR(p.basePrice))}
          ${p.status === 'SOLD' ? tile('Sold for', fmtINR(p.soldPrice)) + tile('Sold to', esc(teamName(p.soldToTeamId))) : ''}
          ${p.status === 'RETAINED' ? tile('Retained by', esc(teamName(p.soldToTeamId))) : ''}
          ${p.status === 'UNDER_AUCTION' && current.currentBidAmount != null ? tile('Current bid', fmtINR(current.currentBidAmount)) : ''}
        </div>

        ${bids.length ? `
          <div class="pm-label">Bid history</div>
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
          </table>` : ''}`;
      // Only touch the DOM when the rendered content actually changed, so the
      // 3s live-poll doesn't visibly re-flash a player whose data is unchanged.
      if (html !== lastBodyHtml) {
        document.getElementById('ppm-body').innerHTML = html;
        lastBodyHtml = html;
      }
    } catch (e) {
      document.getElementById('ppm-body').innerHTML =
          '<p class="muted">Could not load this player — try again.</p>';
      lastBodyHtml = null;
    }
  }

  function open(playerId) {
    currentId = playerId;
    lastBodyHtml = null;   // force a fresh render for the newly opened player
    document.getElementById('ppm-body').innerHTML = '<p class="muted">Loading…</p>';
    document.getElementById('ppm-title').textContent = 'Player profile';
    dialog.showModal();
    render();
    clearInterval(timer);
    timer = setInterval(render, 3000); // live updates while open
  }

  window.openPlayerProfile = open;

  dialog.addEventListener('close', () => { clearInterval(timer); currentId = null; });
  dialog.addEventListener('click', e => { if (e.target === dialog) dialog.close(); }); // backdrop
  document.addEventListener('click', e => {
    if (e.target.id === 'ppm-close') { dialog.close(); return; }
    const link = e.target.closest?.('a.plink[href*="player.html?playerId="]');
    if (!link || e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    open(new URL(link.href, location.href).searchParams.get('playerId'));
  });
})();
