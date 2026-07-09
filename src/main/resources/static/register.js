/* Franchise-owner self-registration. Loads the team list into the dropdown
 * (teams already claimed are shown but disabled), creates the account, then
 * auto-signs-in and drops the owner on their team view. */

const form = document.getElementById('register-form');
const msg = document.getElementById('msg');
const btn = document.getElementById('submit-btn');
const teamSelect = document.getElementById('teamId');

const esc = s => String(s ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function showMessage(text, kind) {
  msg.className = 'auth-msg ' + kind;
  msg.textContent = text;
}

async function loadTeams() {
  try {
    const teams = await fetch('/api/auth/teams').then(r => r.json());
    if (!teams.length) {
      teamSelect.innerHTML = '<option value="" disabled selected>No teams set up yet</option>';
      return;
    }
    teamSelect.innerHTML = '<option value="" disabled selected>Choose your team…</option>'
      + teams.map(t => t.claimed
          ? `<option value="${t.teamId}" disabled>${esc(t.name)} — taken</option>`
          : `<option value="${t.teamId}">${esc(t.name)} — ${esc(t.ownerName)}</option>`).join('');
  } catch (e) {
    teamSelect.innerHTML = '<option value="" disabled selected>Could not load teams</option>';
  }
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  btn.disabled = true;
  const username = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value;
  const payload = {
    displayName: document.getElementById('displayName').value.trim(),
    username,
    password,
    teamId: teamSelect.value,
  };
  try {
    const res = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      showMessage(data.message || data.error || 'Registration failed', 'error');
      btn.disabled = false;
      return;
    }
    showMessage('Account created — signing you in…', 'ok');
    // Auto-login with the just-created credentials.
    const loginRes = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ username, password }),
    });
    const loginData = await loginRes.json().catch(() => ({}));
    location.href = loginRes.ok ? (loginData.redirect || '/team.html') : '/login.html?registered=1';
  } catch (err) {
    showMessage('Could not reach the server. Try again.', 'error');
    btn.disabled = false;
  }
});

loadTeams();
