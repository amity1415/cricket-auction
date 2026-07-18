/* Users & access (app-admin only): list the auction-admin accounts, create new
 * ones, grant/revoke which auctions each may run, and remove them. Talks to
 * /api/admin/app-users (ADMIN-guarded) and reads the public auction list to
 * offer the grant checkboxes. */

const esc = s => String(s ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const $ = id => document.getElementById(id);

let toastTimer = null;
function toast(message, isError) {
  const el = $('toast');
  el.textContent = message;
  el.className = 'toast ' + (isError ? 'error' : 'ok');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.className = 'toast hidden', isError ? 5000 : 2500);
}

async function getJSON(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error('GET ' + url + ' → ' + res.status);
  return res.json();
}

async function send(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return {};
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const base = data.message || data.error || ('Request failed (' + res.status + ')');
    toast(data.detail ? base + ' — ' + data.detail : base, true);
    return null;
  }
  return data;
}

let AUCTIONS = [];  // [{id, name}] — every auction, for the grant checkboxes
let USERS = [];     // current auction-admin accounts

// ---- list -----------------------------------------------------------------

async function loadUsers() {
  const box = $('user-list');
  try {
    USERS = await getJSON('/api/admin/app-users');
  } catch (e) {
    box.innerHTML = '<p class="muted">Could not load users.</p>';
    return;
  }
  render();
}

function render() {
  const box = $('user-list');
  if (!USERS.length) {
    box.innerHTML = '<p class="muted">No auction admins yet — create one to delegate running an auction.</p>';
    return;
  }
  box.innerHTML = '';
  USERS.forEach(u => box.appendChild(card(u)));
}

function card(u) {
  const el = document.createElement('div');
  el.className = 'team-card';
  const grants = u.tournaments.length
    ? u.tournaments.map(t => `<span class="chip">${esc(t.name)}</span>`).join('')
    : '<span class="muted" style="font-size:.85rem;">No auctions granted — this admin can\'t run anything yet.</span>';
  el.innerHTML = `
    <div class="tt-head">
      <div>
        <h3 style="margin:0;">${esc(u.displayName)}</h3>
        <span class="muted" style="font-size:.85rem;">@${esc(u.username)}</span>
      </div>
    </div>
    <div style="margin:.6rem 0; display:flex; flex-wrap:wrap; gap:.35rem; align-items:center;">${grants}</div>
    <div class="tt-actions">
      <button class="ghost" data-act="access">Edit access</button>
      <button class="ghost tt-del" data-act="del">Remove</button>
    </div>`;
  el.querySelector('[data-act="access"]').addEventListener('click', () => openAccess(u));
  el.querySelector('[data-act="del"]').addEventListener('click', () => removeUser(u));
  return el;
}

// ---- editor (create + edit access) ----------------------------------------

/** Renders one checkbox per auction, pre-ticking the ids the admin already has. */
function buildGrantList(selectedIds) {
  const box = $('grant-list');
  if (!AUCTIONS.length) {
    box.innerHTML = '<p class="muted">No auctions exist yet — create one first, then grant access.</p>';
    return;
  }
  const sel = new Set(selectedIds || []);
  box.innerHTML = '';
  AUCTIONS.forEach(a => {
    const row = document.createElement('label');
    row.className = 'check-row';
    row.innerHTML = `<input type="checkbox" value="${esc(a.id)}"${sel.has(a.id) ? ' checked' : ''}>
      <span>${esc(a.name)}</span>`;
    box.appendChild(row);
  });
}

function selectedGrants() {
  return [...document.querySelectorAll('#grant-list input:checked')].map(i => i.value);
}

function showEditor(show) {
  $('editor').classList.toggle('hidden', !show);
  if (show) $('editor').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function openNew() {
  $('edit-id').value = '';
  $('editor-title').textContent = 'New auction admin';
  $('btn-save').textContent = 'Create admin';
  $('create-fields').style.display = 'contents';
  $('f-displayName').value = '';
  $('f-username').value = '';
  $('f-password').value = '';
  buildGrantList([]);
  showEditor(true);
}

function openAccess(u) {
  $('edit-id').value = u.id;
  $('editor-title').textContent = 'Access — ' + u.displayName;
  $('btn-save').textContent = 'Save access';
  $('create-fields').style.display = 'none';   // username/password fixed after creation
  buildGrantList(u.tournaments.map(t => t.id));
  showEditor(true);
}

async function submitEditor(e) {
  e.preventDefault();
  const id = $('edit-id').value;
  const tournamentIds = selectedGrants();
  if (id) {
    const r = await send('PUT', '/api/admin/app-users/' + id + '/access', { tournamentIds });
    if (r) { toast('Saved access for ' + r.displayName); showEditor(false); loadUsers(); }
    return;
  }
  const displayName = $('f-displayName').value.trim();
  const username = $('f-username').value.trim();
  const password = $('f-password').value;
  if (!displayName || !username) { toast('Display name and username are required', true); return; }
  if (password.length < 6) { toast('Password must be at least 6 characters', true); return; }
  const r = await send('POST', '/api/admin/app-users',
    { displayName, username, password, tournamentIds });
  if (r) { toast('Created ' + r.displayName); showEditor(false); loadUsers(); }
}

async function removeUser(u) {
  if (!confirm(`Remove auction admin "${u.displayName}" (@${u.username})?\n`
      + `They will no longer be able to log in.`)) return;
  const r = await send('DELETE', '/api/admin/app-users/' + u.id);
  if (r) { toast('Removed ' + u.displayName); loadUsers(); }
}

// ---- init -----------------------------------------------------------------

document.addEventListener('DOMContentLoaded', async () => {
  $('btn-new').addEventListener('click', openNew);
  $('btn-cancel').addEventListener('click', () => showEditor(false));
  $('form-editor').addEventListener('submit', submitEditor);

  try {
    AUCTIONS = (await getJSON('/api/tournaments')).map(t => ({ id: t.id, name: t.name }));
  } catch (e) { /* leave empty; the editor shows a helpful notice */ }

  loadUsers();
});
