/* Admin-only: create, list, and remove franchise-owner accounts. Renders into
 * the #owners-body table on the setup page. Loaded after setup.js, so it reuses
 * that page's toast() helper when present. */

(function () {
  const body = document.getElementById('owners-body');
  const count = document.getElementById('owners-count');
  if (!body) return; // not on the setup page

  const escapeHtml = s => String(s ?? '').replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const notify = (m, err) => (window.toast ? window.toast(m, err) : (err ? alert(m) : null));

  const form = document.getElementById('owner-form');
  const teamSel = form ? form.querySelector('select[name="teamId"]') : null;

  // Fill the team dropdown; teams that already have an owner are shown disabled.
  async function loadTeams() {
    if (!teamSel) return;
    let teams = [];
    try { teams = await fetch('/api/auth/teams').then(r => r.json()); } catch (e) { /* ignore */ }
    teamSel.innerHTML = '<option value="" disabled selected>Choose team…</option>'
      + teams.map(t => t.claimed
          ? `<option value="${t.teamId}" disabled>${escapeHtml(t.name)} — has owner</option>`
          : `<option value="${t.teamId}">${escapeHtml(t.name)}</option>`).join('');
  }

  if (form) {
    form.onsubmit = async e => {
      e.preventDefault();
      const f = new FormData(form);
      const btn = form.querySelector('button');
      btn.disabled = true;
      try {
        const res = await fetch('/api/auth/register', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            displayName: f.get('displayName').trim(),
            username: f.get('username').trim(),
            password: f.get('password'),
            teamId: f.get('teamId'),
          }),
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) { notify(data.message || data.error || 'Could not create owner', true); return; }
        notify(`Created owner ${data.username}`);
        form.reset();
        loadTeams();
        load();
      } catch (err) {
        notify('Could not reach the server', true);
      } finally {
        btn.disabled = false;
      }
    };
  }

  async function load() {
    let owners;
    try {
      const res = await fetch('/api/admin/users');
      if (!res.ok) throw new Error('status ' + res.status);
      owners = await res.json();
    } catch (e) {
      body.innerHTML = '<tr><td colspan="4" class="muted">Could not load owners.</td></tr>';
      return;
    }
    if (count) count.textContent = owners.length ? `· ${owners.length}` : '';
    body.innerHTML = owners.length ? owners.map(o => `
        <tr>
          <td><b>${escapeHtml(o.displayName)}</b><br><span class="muted">@${escapeHtml(o.username)}</span></td>
          <td>${escapeHtml(o.teamName)}</td>
          <td class="muted">${new Date(o.createdAt).toLocaleDateString()}</td>
          <td style="text-align:right"><button class="danger owner-del" data-id="${o.id}" data-name="${escapeHtml(o.displayName)}">Remove</button></td>
        </tr>`).join('')
      : '<tr><td colspan="4" class="muted">No franchise owners created yet.</td></tr>';

    body.querySelectorAll('.owner-del').forEach(btn => btn.addEventListener('click', () => remove(btn)));
  }

  async function remove(btn) {
    const id = btn.dataset.id;
    if (!confirm(`Remove franchise owner "${btn.dataset.name}"? They will no longer be able to sign in.`)) return;
    btn.disabled = true;
    try {
      const res = await fetch('/api/admin/users/' + id, { method: 'DELETE' });
      if (!res.ok && res.status !== 204) {
        const data = await res.json().catch(() => ({}));
        notify(data.message || 'Could not remove owner', true);
        btn.disabled = false;
        return;
      }
      notify('Franchise owner removed');
      load();
    } catch (e) {
      notify('Could not reach the server', true);
      btn.disabled = false;
    }
  }

  load();
  loadTeams();
})();
