import 'dotenv/config';
import express from 'express';
import session from 'express-session';

const UI_ADDR = process.env.UI_ADDR || '127.0.0.1:3000';
const API_BASE_URL = process.env.API_BASE_URL || 'http://127.0.0.1:8080';
const SESSION_SECRET = process.env.SESSION_SECRET || 'change-me';
const DEFAULT_ADMIN_TOKEN = (process.env.MQLM_ADMIN_TOKEN || '').trim();

const [host, portStr] = UI_ADDR.split(':');
const port = Number(portStr || 3000);

const app = express();
app.set('view engine', 'ejs');
app.set('views', new URL('./views', import.meta.url).pathname);

app.use(express.urlencoded({ extended: true }));
app.use(express.static(new URL('./public', import.meta.url).pathname));

app.use(
  session({
    secret: SESSION_SECRET,
    resave: false,
    saveUninitialized: false,
    cookie: { httpOnly: true }
  })
);

function requireLogin(req, res, next) {
  if (!req.session.loggedIn) {
    return res.redirect('/login');
  }
  next();
}

async function apiFetch(req, path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set('Accept', 'application/json');
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (req.session.adminToken) {
    headers.set('Authorization', `Bearer ${req.session.adminToken}`);
  }

  const resp = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers
  });

  const contentType = resp.headers.get('content-type') || '';
  const isJSON = contentType.includes('application/json');
  const data = isJSON ? await resp.json() : await resp.text();

  if (!resp.ok) {
    const message = typeof data === 'string' ? data : (data?.error || 'API error');
    throw new Error(message);
  }
  return data;
}

app.get('/', (req, res) => {
  if (!req.session.loggedIn) return res.redirect('/login');
  return res.redirect('/users');
});

app.get('/login', (req, res) => {
  res.render('login', { error: null, apiBaseUrl: API_BASE_URL, defaultToken: DEFAULT_ADMIN_TOKEN });
});

app.post('/login', async (req, res) => {
  let token = (req.body.token || '').trim();
  if (token.toLowerCase().startsWith('bearer ')) {
    token = token.slice('bearer '.length).trim();
  }

  // basic login check by hitting /api/users.
  // If backend auth is disabled (localhost dev), this works without a token.
  req.session.adminToken = token || null;
  req.session.loggedIn = true;
  try {
    await apiFetch(req, '/api/users', { method: 'GET' });
    return res.redirect('/users');
  } catch (e) {
    req.session.adminToken = null;
    req.session.loggedIn = false;
    return res.render('login', { error: e.message, apiBaseUrl: API_BASE_URL, defaultToken: DEFAULT_ADMIN_TOKEN });
  }
});

app.post('/logout', (req, res) => {
  req.session.destroy(() => res.redirect('/login'));
});

app.get('/users', requireLogin, async (req, res) => {
  try {
    const users = await apiFetch(req, '/api/users', { method: 'GET' });
    res.render('users', { users, error: null });
  } catch (e) {
    res.render('users', { users: [], error: e.message });
  }
});

app.get('/users/new', requireLogin, (req, res) => {
  res.render('user_new', { error: null });
});

app.post('/users/new', requireLogin, async (req, res) => {
  const username = (req.body.username || '').trim();
  const displayName = (req.body.displayName || '').trim();
  try {
    const u = await apiFetch(req, '/api/users', {
      method: 'POST',
      body: JSON.stringify({ username, displayName })
    });
    res.redirect(`/users/${u.id}`);
  } catch (e) {
    res.render('user_new', { error: e.message });
  }
});

app.get('/users/:id', requireLogin, async (req, res) => {
  const id = Number(req.params.id);
  try {
    const user = await apiFetch(req, `/api/users/${id}`, { method: 'GET' });
    const subscriptions = await apiFetch(req, `/api/users/${id}/subscriptions`, { method: 'GET' });
    res.render('user_detail', { user, subscriptions, error: null });
  } catch (e) {
    res.status(400).render('user_detail', { user: null, subscriptions: [], error: e.message });
  }
});

app.post('/users/:id/delete', requireLogin, async (req, res) => {
  const id = Number(req.params.id);
  try {
    await apiFetch(req, `/api/users/${id}`, { method: 'DELETE' });
    res.redirect('/users');
  } catch (e) {
    res.status(400).send(e.message);
  }
});

app.post('/users/:id/subscriptions', requireLogin, async (req, res) => {
  const id = Number(req.params.id);
  const plan = (req.body.plan || '').trim();
  const expiresAt = (req.body.expiresAt || '').trim();
  try {
    await apiFetch(req, `/api/users/${id}/subscriptions`, {
      method: 'POST',
      body: JSON.stringify({ plan, expiresAt })
    });
    res.redirect(`/users/${id}`);
  } catch (e) {
    res.status(400).send(e.message);
  }
});

app.post('/subscriptions/:id/delete', requireLogin, async (req, res) => {
  const id = Number(req.params.id);
  const userId = Number(req.body.userId);
  try {
    await apiFetch(req, `/api/subscriptions/${id}`, { method: 'DELETE' });
    res.redirect(`/users/${userId}`);
  } catch (e) {
    res.status(400).send(e.message);
  }
});

app.listen(port, host, () => {
  console.log(`mql_manager UI listening on http://${UI_ADDR}`);
  console.log(`Using API base: ${API_BASE_URL}`);
});
