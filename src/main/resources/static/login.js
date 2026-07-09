/* Login page. Posts form-encoded credentials to Spring Security's login filter
 * (/api/auth/login); on success the server returns {role, redirect} and we go
 * there (admins → setup, franchise owners → their team view). */

const form = document.getElementById('login-form');
const msg = document.getElementById('msg');
const btn = document.getElementById('submit-btn');

// Surface a friendly note when arriving fresh from registration.
if (new URLSearchParams(location.search).get('registered') === '1') {
  msg.className = 'auth-msg ok';
  msg.textContent = 'Account created — signing you in…';
}

function showError(text) {
  msg.className = 'auth-msg error';
  msg.textContent = text;
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  btn.disabled = true;
  const body = new URLSearchParams({
    username: document.getElementById('username').value.trim(),
    password: document.getElementById('password').value,
  });
  try {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    const data = await res.json().catch(() => ({}));
    if (res.ok) {
      location.href = data.redirect || '/team.html';
    } else {
      showError(data.error || 'Sign in failed');
      btn.disabled = false;
    }
  } catch (err) {
    showError('Could not reach the server. Try again.');
    btn.disabled = false;
  }
});
