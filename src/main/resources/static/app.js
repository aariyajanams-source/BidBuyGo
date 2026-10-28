// BidBuyGo frontend: one ES module, no framework, no build step, no external dependencies.
//
// Safety rule: server data (titles, descriptions, emails, URLs) reaches the DOM only as text nodes or plain
// attribute values set by el(). Nothing here assigns HTML strings, and <img src> only ever gets http(s) URLs.

export const DEMO_MODE = true; // dev only: shows the seeded demo accounts on the login screen

const DEMO_PASSWORD = 'password123';
const DEMO_ACCOUNTS = [
  ['Buyer 1', 'buyer1@bidbuygo.dev'],
  ['Buyer 2', 'buyer2@bidbuygo.dev'],
  ['Verified seller', 'seller.verified@bidbuygo.dev'],
  ['New seller', 'seller.new@bidbuygo.dev'],
  ['Admin', 'admin@bidbuygo.dev'],
];

const TOKEN_KEY = 'bidbuygo.token';
const POLL_MS = 2000;
const CONDITIONS = ['NEW', 'LIKE_NEW', 'GOOD', 'FAIR'];
const CONDITION_LABELS = { NEW: 'New', LIKE_NEW: 'Like new', GOOD: 'Good', FAIR: 'Fair' };
const STATUSES = ['ACTIVE', 'SOLD', 'CLOSED', 'REJECTED'];
// Suggestions only; the admin-editable auction policy decides what is actually auctionable.
const CATEGORY_SUGGESTIONS = ['collectibles', 'pre-owned', 'handmade', 'limited-edition', 'open-box'];

// ---------------------------------------------------------------------------------------------------------------
// Pure helpers (exported so they can be checked outside a browser)
// ---------------------------------------------------------------------------------------------------------------

const rupeeGrouping = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });

/** 123450 -> "₹1,234.50". Integer maths only; paise never pass through floating point. */
export function formatPaise(paise) {
  if (paise === null || paise === undefined) return '—';
  const sign = paise < 0 ? '-' : '';
  const abs = Math.abs(paise);
  return `${sign}₹${rupeeGrouping.format(Math.floor(abs / 100))}.${String(abs % 100).padStart(2, '0')}`;
}

/**
 * "1234.5" -> 123450. Accepts digits with an optional decimal part, rounds half-up to whole paise, and returns
 * null for anything else (blank, negative, letters), so callers never send fractions or NaN.
 */
export function rupeesToPaise(text) {
  const match = /^\s*(\d+)(?:\.(\d*))?\s*$/.exec(String(text ?? ''));
  if (!match) return null;
  const fraction = (match[2] ?? '').padEnd(3, '0');
  const paise = Number(match[1]) * 100 + Number(fraction.slice(0, 2)) + (Number(fraction[2]) >= 5 ? 1 : 0);
  return Number.isSafeInteger(paise) ? paise : null;
}

/** 123450 -> "1234.50", for pre-filling rupee inputs. */
export function paiseToRupeesInput(paise) {
  return `${Math.floor(paise / 100)}.${String(paise % 100).padStart(2, '0')}`;
}

/** Returns the normalized URL if it is http(s), otherwise null. Used for every <img src>. */
export function safeHttpUrl(value) {
  try {
    const url = new URL(String(value));
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null;
  } catch {
    return null;
  }
}

/** Milliseconds left -> "2d 3h", "1h 05m", "4m 09s" or "Ended". */
export function formatRemaining(ms) {
  if (!(ms > 0)) return 'Ended';
  let seconds = Math.floor(ms / 1000);
  const days = Math.floor(seconds / 86400);
  seconds -= days * 86400;
  const hours = Math.floor(seconds / 3600);
  seconds -= hours * 3600;
  const minutes = Math.floor(seconds / 60);
  seconds -= minutes * 60;
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${pad2(minutes)}m`;
  return `${minutes}m ${pad2(seconds)}s`;
}

/** "12", "-3" -> integer; "" -> null; anything else -> NaN. */
export function parseInteger(text) {
  const trimmed = String(text ?? '').trim();
  if (trimmed === '') return null;
  return /^-?\d+$/.test(trimmed) ? Number(trimmed) : NaN;
}

/** Date -> "YYYY-MM-DDTHH:MM:SS" in local time, the format a datetime-local input expects. */
export function toLocalInputValue(date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
    + `T${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

function pad2(n) {
  return String(n).padStart(2, '0');
}

function formatDateTime(iso) {
  return iso ? new Date(iso).toLocaleString() : '—';
}

// ---------------------------------------------------------------------------------------------------------------
// DOM helpers
// ---------------------------------------------------------------------------------------------------------------

/**
 * el('a', { class: 'x', href: '#/', text: 'Home', onclick: fn }, ...children). Children may be nodes or strings;
 * strings always become text nodes. Attribute values are set with setAttribute, never parsed as HTML.
 */
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = String(value);
    else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2), value);
    else if (value === true) node.setAttribute(key, '');
    else node.setAttribute(key, String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child === undefined || child === null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function field(labelText, control, hint) {
  return el('div', { class: 'field' }, el('label', {}, labelText, hint ? el('span', { class: 'hint', text: ` ${hint}` }) : null), control);
}

function stat(label, valueNode) {
  return el('div', { class: 'stat' }, el('span', { class: 'muted', text: label }), el('span', { class: 'value' }, valueNode));
}

function photoNode(url, className, alt) {
  const safe = safeHttpUrl(url);
  return safe
    ? el('img', { class: className, src: safe, alt, loading: 'lazy', referrerpolicy: 'no-referrer' })
    : el('div', { class: 'thumb-placeholder', text: 'No photo' });
}

function typeBadge(type) {
  return type === 'AUCTION'
    ? el('span', { class: 'badge badge-auction', text: 'Auction' })
    : el('span', { class: 'badge', text: 'Fixed price' });
}

function statusBadge(status) {
  if (status === 'ACTIVE') return null;
  return el('span', { class: status === 'REJECTED' ? 'badge badge-danger' : 'badge badge-muted', text: status });
}

function closeReasonText(listing) {
  switch (listing.closeReason) {
    case 'SOLD': return `Sold for ${formatPaise(listing.type === 'AUCTION' ? listing.currentHighBid : listing.fixedPrice)}`;
    case 'RESERVE_NOT_MET': return 'Closed: the reserve price was not met. Held bids were released.';
    case 'NO_BIDS': return 'Closed: the auction ended with no bids.';
    default: return listing.status === 'REJECTED'
      ? `Rejected by an admin: ${listing.rejectionReason ?? 'no reason given'}`
      : `This listing is ${listing.status}.`;
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Banner (errors and confirmations)
// ---------------------------------------------------------------------------------------------------------------

let bannerEl;
let bannerTimer;

function showBanner(kind, message, reasons = []) {
  clearTimeout(bannerTimer);
  bannerEl.replaceChildren(
    el('div', { class: 'banner-body' },
      el('strong', { text: message }),
      reasons.length ? el('ul', {}, reasons.map((reason) => el('li', { text: reason }))) : null),
    el('button', { class: 'banner-close', type: 'button', 'aria-label': 'Dismiss', onclick: hideBanner }, '×'));
  bannerEl.className = `banner banner-${kind}`;
  bannerEl.hidden = false;
  if (kind === 'info') bannerTimer = setTimeout(hideBanner, 6000);
}

function hideBanner() {
  clearTimeout(bannerTimer);
  bannerEl.hidden = true;
  bannerEl.replaceChildren();
}

function showInfo(message) {
  showBanner('info', message);
}

/** Shows any error visibly: the server's message and reasons for API errors, the exception text otherwise. */
function showError(error) {
  if (error?.handled) return;
  if (error instanceof ApiError) showBanner('error', error.message, error.reasons);
  else showBanner('error', 'Something went wrong', [String(error?.message ?? error)]);
  console.error(error);
}

// ---------------------------------------------------------------------------------------------------------------
// API client
// ---------------------------------------------------------------------------------------------------------------

class ApiError extends Error {
  constructor(status, message, reasons) {
    super(message);
    this.status = status;
    this.reasons = reasons;
    this.handled = false;
  }
}

function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    // Storage unavailable (private mode); the session then lasts until reload.
  }
}

async function api(method, path, body) {
  const headers = { Accept: 'application/json' };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let response;
  try {
    response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, 'Network error', ['Could not reach the server. Is it running?']);
  }
  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (response.ok) return data;

  const error = new ApiError(response.status, data?.message ?? `Request failed (HTTP ${response.status})`,
    Array.isArray(data?.reasons) ? data.reasons.map(String) : []);
  if (response.status === 401) handleUnauthorized(error, path);
  throw error;
}

/** Any 401: drop the token and show the login form. A failed login stays on the form with its reason. */
function handleUnauthorized(error, path) {
  setToken(null);
  session.me = null;
  session.wallet = null;
  renderHeader();
  if (path === '/auth/login') return;
  error.handled = true;
  pendingBanner = { kind: 'error', message: 'Please log in', reasons: error.reasons.length ? error.reasons : [error.message] };
  const current = parseHash().path;
  if (current === '/login') route();
  else location.hash = `#/login?next=${encodeURIComponent(current)}`;
}

// ---------------------------------------------------------------------------------------------------------------
// Session and header
// ---------------------------------------------------------------------------------------------------------------

const session = { me: null, wallet: null };
let headerEl;

async function refreshSession() {
  if (!getToken()) {
    session.me = null;
    session.wallet = null;
    renderHeader();
    return;
  }
  try {
    const [me, wallet] = await Promise.all([api('GET', '/me'), api('GET', '/wallet')]);
    session.me = me;
    session.wallet = wallet;
  } catch (error) {
    showError(error);
  }
  renderHeader();
}

function navLinks() {
  const role = session.me?.role;
  const links = [['#/', 'Browse', '/']];
  if (role === 'SELLER') links.push(['#/sell', 'Sell', '/sell']);
  if (role) links.push(['#/orders', 'Orders', '/orders'], ['#/wallet', 'Wallet', '/wallet']);
  if (role === 'ADMIN') links.push(['#/admin', 'Admin', '/admin']);
  return links;
}

function renderHeader() {
  if (!headerEl) return;
  const current = parseHash().path;
  const nav = el('nav', { class: 'nav' }, navLinks().map(([href, label, path]) =>
    el('a', { href, class: current === path ? 'active' : null, text: label })));

  let right;
  if (session.me) {
    const wallet = session.wallet;
    right = el('div', { class: 'session' },
      el('span', { class: 'email', text: session.me.email }),
      el('span', { class: 'badge', text: session.me.role }),
      wallet ? el('span', { class: 'balances' },
        'Available ', el('strong', { text: formatPaise(wallet.availableBalance), 'data-testid': 'available' }),
        ' · Held ', el('strong', { text: formatPaise(wallet.heldBalance), 'data-testid': 'held' })) : null,
      el('button', { class: 'secondary small', type: 'button', onclick: logout }, 'Log out'));
  } else {
    right = el('div', { class: 'session' }, el('a', { class: 'button small', href: '#/login', text: 'Log in' }));
  }

  headerEl.replaceChildren(el('div', { class: 'inner' },
    el('a', { class: 'brand', href: '#/' }, 'BidBuy', el('span', { text: 'Go' })), nav, right));
}

function logout() {
  setToken(null);
  session.me = null;
  session.wallet = null;
  renderHeader();
  showInfo('Logged out');
  location.hash = '#/';
}

// ---------------------------------------------------------------------------------------------------------------
// Router (hash based). Each navigation clears every timer the previous view registered.
// ---------------------------------------------------------------------------------------------------------------

let mainEl;
let routeSeq = 0;
let pendingBanner = null;
const timers = new Set();

function every(ms, fn) {
  const id = setInterval(fn, ms);
  timers.add(id);
  return id;
}

function later(ms, fn) {
  const id = setTimeout(() => {
    timers.delete(id);
    fn();
  }, ms);
  timers.add(id);
  return id;
}

function cancel(id) {
  clearInterval(id);
  clearTimeout(id);
  timers.delete(id);
}

function clearTimers() {
  for (const id of timers) {
    clearInterval(id);
    clearTimeout(id);
  }
  timers.clear();
}

function parseHash() {
  const raw = location.hash.replace(/^#/, '') || '/';
  const [path, query = ''] = raw.split('?');
  return { path: path || '/', params: new URLSearchParams(query) };
}

async function route() {
  clearTimers();
  const seq = ++routeSeq;
  const ctx = { isCurrent: () => seq === routeSeq };
  const { path, params } = parseHash();

  if (pendingBanner) {
    showBanner(pendingBanner.kind, pendingBanner.message, pendingBanner.reasons);
    pendingBanner = null;
  } else if (!bannerEl.classList.contains('banner-info')) {
    hideBanner();
  }
  renderHeader();
  mainEl.replaceChildren(el('p', { class: 'muted', text: 'Loading…' }));

  try {
    const listingMatch = /^\/listing\/([^/]+)$/.exec(path);
    if (path === '/') await browseView(ctx, params);
    else if (listingMatch) await listingView(ctx, listingMatch[1]);
    else if (path === '/login') loginView(ctx, params);
    else if (path === '/sell') await requireRole(ctx, 'SELLER', () => sellView(ctx));
    else if (path === '/orders') await requireLogin(ctx, () => ordersView(ctx));
    else if (path === '/wallet') await requireLogin(ctx, () => walletView(ctx));
    else if (path === '/admin') await requireRole(ctx, 'ADMIN', () => adminView(ctx));
    else render(ctx, el('div', { class: 'card' }, el('h1', { text: 'Page not found' }), el('a', { href: '#/', text: 'Back to listings' })));
  } catch (error) {
    if (!ctx.isCurrent()) return;
    showError(error);
    render(ctx, el('div', { class: 'card' }, el('p', { class: 'muted', text: 'This page could not be loaded.' }),
      el('a', { href: '#/', text: 'Back to listings' })));
  }
}

function render(ctx, ...nodes) {
  if (ctx.isCurrent()) mainEl.replaceChildren(...nodes);
}

async function requireLogin(ctx, view) {
  if (!getToken()) {
    loginView(ctx, new URLSearchParams({ next: parseHash().path }));
    return;
  }
  if (!session.me) await refreshSession();
  if (!ctx.isCurrent() || !session.me) return;
  await view();
}

async function requireRole(ctx, role, view) {
  await requireLogin(ctx, async () => {
    if (session.me.role !== role) {
      render(ctx, el('div', { class: 'card' },
        el('h1', { text: 'Not available for your account' }),
        el('p', { class: 'muted', text: `This page is for ${role.toLowerCase()}s. You are signed in as ${session.me.role.toLowerCase()}.` }),
        el('a', { href: '#/', text: 'Back to listings' })));
      return;
    }
    await view();
  });
}

function loginLink(text) {
  return el('a', { href: `#/login?next=${encodeURIComponent(parseHash().path)}`, text });
}

/** Disables a button while an async action runs, so double clicks don't send twice. */
async function busy(button, action) {
  button.disabled = true;
  try {
    return await action();
  } finally {
    button.disabled = false;
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Login and register
// ---------------------------------------------------------------------------------------------------------------

function loginView(ctx, params) {
  const next = params.get('next');
  const target = next && next.startsWith('/') && next !== '/login' ? `#${next}` : '#/';

  if (session.me) {
    render(ctx, el('div', { class: 'card' },
      el('p', {}, 'You are logged in as ', el('strong', { text: session.me.email }), '.'),
      el('a', { href: target, text: 'Continue' })));
    return;
  }

  const loginEmail = el('input', { type: 'email', name: 'email', required: true, autocomplete: 'username' });
  const loginPassword = el('input', { type: 'password', name: 'password', required: true, autocomplete: 'current-password' });
  const loginButton = el('button', { type: 'submit' }, 'Log in');
  const loginForm = el('form', { class: 'stack', 'data-testid': 'login-form' },
    field('Email', loginEmail), field('Password', loginPassword), loginButton);
  loginForm.addEventListener('submit', (event) => {
    event.preventDefault();
    busy(loginButton, async () => {
      try {
        const token = await api('POST', '/auth/login', { email: loginEmail.value, password: loginPassword.value });
        setToken(token.accessToken);
        await refreshSession();
        showInfo(`Welcome, ${session.me?.email ?? loginEmail.value}`);
        location.hash = target;
      } catch (error) {
        showError(error);
      }
    });
  });

  const regEmail = el('input', { type: 'email', required: true, autocomplete: 'username' });
  const regPassword = el('input', { type: 'password', required: true, minlength: 8, maxlength: 72, autocomplete: 'new-password' });
  const regRole = el('select', {}, el('option', { value: 'BUYER', text: 'Buyer' }), el('option', { value: 'SELLER', text: 'Seller' }));
  const regButton = el('button', { type: 'submit' }, 'Create account');
  const registerForm = el('form', { class: 'stack' },
    field('Email', regEmail), field('Password', regPassword, '(8 to 72 characters)'), field('I want to', regRole), regButton);
  registerForm.addEventListener('submit', (event) => {
    event.preventDefault();
    busy(regButton, async () => {
      try {
        await api('POST', '/auth/register', { email: regEmail.value, password: regPassword.value, role: regRole.value });
        const token = await api('POST', '/auth/login', { email: regEmail.value, password: regPassword.value });
        setToken(token.accessToken);
        await refreshSession();
        showInfo('Account created. You are logged in.');
        location.hash = target;
      } catch (error) {
        showError(error);
      }
    });
  });

  const loginCard = el('section', { class: 'card stack' }, el('h2', { text: 'Log in' }), loginForm);
  if (DEMO_MODE) {
    loginCard.append(el('div', { class: 'demo stack' },
      el('div', {}, el('strong', { text: 'Demo accounts (dev only)' }),
        el('div', { class: 'hint', text: `Fills in a seeded account; password ${DEMO_PASSWORD}.` })),
      el('div', { class: 'row' }, DEMO_ACCOUNTS.map(([label, email]) =>
        el('button', {
          type: 'button',
          class: 'secondary small',
          'data-demo': email,
          onclick: () => {
            loginEmail.value = email;
            loginPassword.value = DEMO_PASSWORD;
            loginButton.focus();
          },
        }, label)))));
  }

  render(ctx, el('div', { class: 'two-col' },
    loginCard,
    el('section', { class: 'card stack' }, el('h2', { text: 'New here?' }), registerForm)));
}

// ---------------------------------------------------------------------------------------------------------------
// Browse
// ---------------------------------------------------------------------------------------------------------------

async function browseView(ctx, params) {
  const type = params.get('type') ?? '';
  const category = params.get('category') ?? '';

  const typeSelect = el('select', { name: 'type' },
    el('option', { value: '', text: 'All types' }),
    el('option', { value: 'FIXED', text: 'Fixed price' }),
    el('option', { value: 'AUCTION', text: 'Auction' }));
  typeSelect.value = type;
  const categoryInput = el('input', { type: 'text', name: 'category', list: 'category-suggestions', placeholder: 'Any category', value: category });
  const filters = el('form', { class: 'card row', 'data-testid': 'filters' },
    el('div', { class: 'field spacer' }, el('label', { text: 'Type' }), typeSelect),
    el('div', { class: 'field spacer' }, el('label', { text: 'Category' }), categoryInput),
    el('datalist', { id: 'category-suggestions' }, CATEGORY_SUGGESTIONS.map((c) => el('option', { value: c }))),
    el('div', { class: 'row' }, el('button', { type: 'submit' }, 'Filter'),
      el('a', { class: 'button secondary', href: '#/', text: 'Clear' })));
  filters.addEventListener('submit', (event) => {
    event.preventDefault();
    const query = new URLSearchParams();
    if (typeSelect.value) query.set('type', typeSelect.value);
    if (categoryInput.value.trim()) query.set('category', categoryInput.value.trim());
    location.hash = `#/${query.toString() ? `?${query}` : ''}`;
  });

  const query = new URLSearchParams();
  if (type) query.set('type', type);
  if (category) query.set('category', category);
  const listings = await api('GET', `/listings${query.toString() ? `?${query}` : ''}`);
  if (!ctx.isCurrent()) return;

  const countdowns = [];
  const cards = listings.map((listing) => {
    const priceRow = listing.type === 'AUCTION'
      ? el('div', { class: 'price' },
        formatPaise(listing.currentHighBid ?? listing.startPrice),
        el('span', { class: 'price-label', text: listing.currentHighBid !== null ? ' current bid' : ' starting bid' }))
      : el('div', { class: 'price' }, formatPaise(listing.fixedPrice));
    let timeLeft = null;
    if (listing.type === 'AUCTION') {
      timeLeft = el('span', { class: 'small muted' });
      countdowns.push({ node: timeLeft, endsAt: listing.endsAt });
    }
    return el('a', { class: 'card listing-card', href: `#/listing/${Number(listing.id)}`, 'data-listing-id': Number(listing.id) },
      photoNode(listing.photoUrls?.[0], 'thumb', ''),
      el('div', { class: 'body' },
        el('div', { class: 'row' }, typeBadge(listing.type),
          listing.type === 'AUCTION' ? el('span', { class: 'small muted', text: `${listing.bidCount} bid${listing.bidCount === 1 ? '' : 's'}` }) : null),
        el('div', { class: 'title', text: listing.title }),
        priceRow,
        el('div', { class: 'small muted', text: `${listing.category} · ${CONDITION_LABELS[listing.conditionGrade] ?? listing.conditionGrade}` }),
        timeLeft));
  });

  const tick = () => countdowns.forEach(({ node, endsAt }) => {
    const remaining = Date.parse(endsAt) - Date.now();
    node.textContent = remaining > 0 ? `${formatRemaining(remaining)} left` : 'Ending…';
  });
  tick();
  every(1000, tick);

  render(ctx,
    el('h1', { text: 'Listings' }),
    filters,
    el('div', { style: 'height: 1rem' }),
    cards.length ? el('div', { class: 'grid', 'data-testid': 'listing-grid' }, cards)
      : el('div', { class: 'card muted', text: 'No active listings match these filters.' }));
}

// ---------------------------------------------------------------------------------------------------------------
// Listing detail
// ---------------------------------------------------------------------------------------------------------------

async function listingView(ctx, rawId) {
  if (!/^\d+$/.test(rawId)) {
    render(ctx, el('div', { class: 'card' }, el('h1', { text: 'Listing not found' })));
    return;
  }
  const id = Number(rawId);
  const listing = await api('GET', `/listings/${id}`);
  if (!ctx.isCurrent()) return;

  const statusSlot = el('span');
  const setStatus = (l) => statusSlot.replaceChildren(statusBadge(l.status) ?? '');
  setStatus(listing);

  const photos = (listing.photoUrls ?? []).map((url) => safeHttpUrl(url)).filter(Boolean);
  const details = el('section', { class: 'card stack' },
    el('div', { class: 'row' }, typeBadge(listing.type), statusSlot),
    el('h1', { text: listing.title }),
    el('div', { class: 'muted small', text: `${listing.category} · ${CONDITION_LABELS[listing.conditionGrade] ?? listing.conditionGrade} · Seller #${listing.sellerId} · Listed ${formatDateTime(listing.createdAt)}` }),
    photos.length ? el('div', { class: 'gallery' }, photos.map((url, i) => photoNode(url, '', `Photo ${i + 1} of ${listing.title}`)))
      : el('div', { class: 'thumb-placeholder', text: 'No photos' }),
    listing.description ? el('p', { class: 'description', text: listing.description }) : el('p', { class: 'muted', text: 'No description.' }));

  const panel = el('section', { class: 'card stack', 'data-testid': 'action-panel' });
  render(ctx, el('p', {}, el('a', { href: '#/', text: '← All listings' })), el('div', { class: 'two-col' }, details, panel));

  if (listing.type === 'FIXED') renderFixedPanel(ctx, panel, listing, setStatus);
  else startAuctionPanel(ctx, panel, listing, setStatus);
}

/** Who may act on a listing, and if not, why. */
function actionBlocker(listing, verb) {
  if (!session.me) return el('p', { class: 'notice' }, loginLink(`Log in to ${verb}`), ` on this listing.`);
  if (session.me.id === listing.sellerId) return el('p', { class: 'notice', text: `This is your listing, so you can't ${verb} on it.` });
  if (session.me.role === 'ADMIN') return el('p', { class: 'notice', text: `Admin accounts can't ${verb}.` });
  return null;
}

function renderFixedPanel(ctx, panel, listing, setStatus) {
  panel.replaceChildren(el('div', { class: 'price', text: formatPaise(listing.fixedPrice) }));
  if (listing.status !== 'ACTIVE') {
    panel.append(el('p', { class: listing.status === 'SOLD' ? 'notice' : 'notice notice-danger', text: closeReasonText(listing) }));
    return;
  }
  const blocker = actionBlocker(listing, 'buy');
  if (blocker) {
    panel.append(blocker);
    return;
  }

  const buyButton = el('button', { type: 'button', 'data-testid': 'buy' }, 'Buy now');
  const confirmBox = el('div', { class: 'stack', hidden: true });
  const confirmButton = el('button', { type: 'button', 'data-testid': 'confirm-buy' }, `Confirm: pay ${formatPaise(listing.fixedPrice)}`);
  const cancelButton = el('button', { type: 'button', class: 'secondary' }, 'Cancel');
  confirmBox.append(el('p', { text: `Buy "${listing.title}" for ${formatPaise(listing.fixedPrice)} from your available balance?` }),
    el('div', { class: 'row' }, confirmButton, cancelButton));

  buyButton.addEventListener('click', () => {
    buyButton.hidden = true;
    confirmBox.hidden = false;
  });
  cancelButton.addEventListener('click', () => {
    confirmBox.hidden = true;
    buyButton.hidden = false;
  });
  confirmButton.addEventListener('click', () => busy(confirmButton, async () => {
    try {
      const order = await api('POST', `/listings/${listing.id}/buy`);
      await refreshSession();
      if (!ctx.isCurrent()) return;
      const sold = { ...listing, status: 'SOLD', closeReason: 'SOLD' };
      setStatus(sold);
      panel.replaceChildren(
        el('div', { class: 'price', text: formatPaise(listing.fixedPrice) }),
        el('div', { class: 'notice notice-ok', 'data-testid': 'order-confirmation' },
          el('strong', { text: `Order #${order.id} placed.` }),
          ` You paid ${formatPaise(order.amountPaise)}.`),
        el('a', { href: '#/orders', text: 'View your orders' }));
      showInfo(`Bought "${listing.title}"`);
    } catch (error) {
      showError(error);
    }
  }));
  panel.append(buyButton, confirmBox);
}

/**
 * Live auction panel: polls the listing and its bids every 2 s while ACTIVE, ticks a countdown from the latest
 * endsAt (so anti-sniping extensions show up), and stops everything once the auction has a final state.
 */
function startAuctionPanel(ctx, panel, initial, setStatus) {
  let listing = initial;
  let bids = [];
  let wasLeader = initial.viewerIsHighBidder === true;
  let pollTimer = null;
  let countdownTimer = null;
  let inFlight = false;

  const currentValue = el('span', { 'data-testid': 'current-bid' });
  const currentLabel = el('span', { class: 'muted' });
  const bidCountValue = el('span', { 'data-testid': 'bid-count' });
  const reserveValue = el('span');
  const countdownValue = el('span', { class: 'countdown', 'data-testid': 'countdown' });
  const endsAtValue = el('span', { class: 'small' });
  const extendedNotice = el('div', { class: 'notice flash', hidden: true, 'data-testid': 'extended-notice' });
  const leaderNotice = el('div', { class: 'notice notice-ok', hidden: true, 'data-testid': 'leader' }, 'You are the highest bidder.');
  const outbidNotice = el('div', { class: 'notice notice-danger', hidden: true }, "You've been outbid.");
  const finalNotice = el('div', { class: 'notice', hidden: true, 'data-testid': 'final-state' });
  const formBox = el('div', { class: 'stack' });
  const historyList = el('ol', { class: 'bid-list', 'data-testid': 'bid-history' });

  const amountInput = el('input', { type: 'text', inputmode: 'decimal', placeholder: 'Amount in ₹', 'data-testid': 'bid-amount', autocomplete: 'off' });
  const minHint = el('div', { class: 'hint', 'data-testid': 'min-bid' });
  const bidButton = el('button', { type: 'submit', 'data-testid': 'place-bid' }, 'Place bid');
  const bidForm = el('form', { class: 'stack' }, field('Your bid (₹)', amountInput), minHint, bidButton);
  if (initial.currentHighBid === null) amountInput.value = paiseToRupeesInput(initial.startPrice);

  const blocker = actionBlocker(initial, 'bid');
  formBox.append(blocker ?? bidForm);

  panel.replaceChildren(
    el('div', {}, currentLabel, el('div', { class: 'price' }, currentValue)),
    stat('Bids', bidCountValue),
    stat('Reserve', reserveValue),
    stat('Time left', countdownValue),
    stat('Ends', endsAtValue),
    extendedNotice, leaderNotice, outbidNotice, finalNotice,
    formBox,
    el('h3', { text: 'Bid history' }),
    historyList);

  bidForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const amount = rupeesToPaise(amountInput.value);
    if (amount === null || amount <= 0) {
      showBanner('error', 'Enter a bid amount in rupees', ['For example 250 or 250.50']);
      return;
    }
    busy(bidButton, async () => {
      try {
        const placed = await api('POST', `/listings/${listing.id}/bids`, { amount });
        showInfo(`Bid of ${formatPaise(placed.amountPaise)} placed${placed.extended ? `. Anti-sniping extended the auction to ${formatDateTime(placed.endsAt)}` : ''}.`);
        amountInput.value = '';
        await Promise.all([poll(), refreshSession()]);
      } catch (error) {
        showError(error);
        poll();
      }
    });
  });

  function apply(next, nextBids) {
    const previousEnd = listing.endsAt;
    listing = next;
    bids = nextBids;
    setStatus(listing);

    if (previousEnd && Date.parse(listing.endsAt) > Date.parse(previousEnd)) {
      const seconds = Math.round((Date.parse(listing.endsAt) - Date.parse(previousEnd)) / 1000);
      extendedNotice.textContent = `Extended by ${seconds}s: a late bid triggered anti-sniping.`;
      extendedNotice.hidden = false;
      extendedNotice.classList.remove('flash');
      void extendedNotice.offsetWidth; // restart the flash animation
      extendedNotice.classList.add('flash');
      later(6000, () => { extendedNotice.hidden = true; });
    }

    const hasBids = listing.currentHighBid !== null;
    currentLabel.textContent = hasBids ? 'Current bid' : 'Starting price';
    currentValue.textContent = formatPaise(hasBids ? listing.currentHighBid : listing.startPrice);
    bidCountValue.textContent = String(listing.bidCount);
    reserveValue.textContent = hasBids && listing.currentHighBid >= listing.reservePrice ? 'Met' : 'Not met yet';
    endsAtValue.textContent = formatDateTime(listing.endsAt);
    minHint.textContent = hasBids
      ? `Must beat ${formatPaise(listing.currentHighBid)} by at least the minimum increment.`
      : `Minimum bid: ${formatPaise(listing.startPrice)} (the starting price).`;

    const isLeader = listing.viewerIsHighBidder === true;
    leaderNotice.hidden = !isLeader || listing.status !== 'ACTIVE';
    outbidNotice.hidden = !(wasLeader && !isLeader && listing.status === 'ACTIVE');
    wasLeader = isLeader;

    historyList.replaceChildren(...(bids.length ? bids.map((bid) => el('li', {},
      el('span', { text: formatPaise(bid.amountPaise) }),
      el('span', { class: 'muted small', text: `${bid.bidder} · ${formatDateTime(bid.createdAt)}` })))
      : [el('li', { class: 'muted', text: 'No bids yet.' })]));

    if (listing.status !== 'ACTIVE') finish();
    else tick();
  }

  function tick() {
    const remaining = Date.parse(listing.endsAt) - Date.now();
    if (remaining > 0) {
      countdownValue.textContent = formatRemaining(remaining);
      formBox.hidden = false;
    } else {
      // Past endsAt but not yet settled: bids would be rejected, so hide the form and keep polling.
      countdownValue.textContent = 'Ended, settling…';
      formBox.hidden = true;
    }
  }

  function finish() {
    if (pollTimer !== null) cancel(pollTimer);
    if (countdownTimer !== null) cancel(countdownTimer);
    pollTimer = null;
    countdownTimer = null;
    countdownValue.textContent = 'Ended';
    formBox.hidden = true;
    leaderNotice.hidden = true;
    outbidNotice.hidden = true;
    const won = listing.closeReason === 'SOLD' && listing.viewerIsHighBidder === true;
    finalNotice.className = listing.closeReason === 'SOLD' ? 'notice notice-ok' : 'notice notice-danger';
    finalNotice.textContent = won ? `${closeReasonText(listing)}. You won this auction.` : closeReasonText(listing);
    finalNotice.hidden = false;
    refreshSession(); // settlement moved money: captured, credited or released
  }

  let lastPollError = null;
  async function poll() {
    if (inFlight || !ctx.isCurrent()) return;
    inFlight = true;
    try {
      const [next, nextBids] = await Promise.all([
        api('GET', `/listings/${listing.id}`),
        api('GET', `/listings/${listing.id}/bids`),
      ]);
      if (!ctx.isCurrent()) return;
      lastPollError = null;
      apply(next, nextBids);
    } catch (error) {
      // Show each distinct failure once instead of re-showing it every 2 seconds.
      if (ctx.isCurrent() && error?.message !== lastPollError) {
        lastPollError = error?.message;
        showError(error);
      }
    } finally {
      inFlight = false;
    }
  }

  apply(initial, []);
  if (initial.status === 'ACTIVE') {
    pollTimer = every(POLL_MS, poll);
    countdownTimer = every(250, tick);
  }
  poll(); // load the bid history straight away
}

// ---------------------------------------------------------------------------------------------------------------
// Sell
// ---------------------------------------------------------------------------------------------------------------

function sellView(ctx) {
  const title = el('input', { type: 'text', maxlength: 200, required: true, 'data-testid': 'sell-title' });
  const description = el('textarea', { maxlength: 5000 });
  const category = el('input', { type: 'text', list: 'sell-categories', required: true, 'data-testid': 'sell-category' });
  const condition = el('select', { 'data-testid': 'sell-condition' }, CONDITIONS.map((c) => el('option', { value: c, text: CONDITION_LABELS[c] })));
  const fixedRadio = el('input', { type: 'radio', name: 'type', value: 'FIXED', checked: true });
  const auctionRadio = el('input', { type: 'radio', name: 'type', value: 'AUCTION', 'data-testid': 'sell-type-auction' });
  const fixedPrice = el('input', { type: 'text', inputmode: 'decimal', placeholder: 'e.g. 899.00', 'data-testid': 'sell-fixed-price' });
  const startPrice = el('input', { type: 'text', inputmode: 'decimal', placeholder: 'e.g. 200', 'data-testid': 'sell-start-price' });
  const reservePrice = el('input', { type: 'text', inputmode: 'decimal', placeholder: 'e.g. 500', 'data-testid': 'sell-reserve-price' });
  const endsAt = el('input', { type: 'datetime-local', step: 1, 'data-testid': 'sell-ends-at' });
  const photos = el('textarea', { placeholder: 'https://example.com/photo-1.jpg\nhttps://example.com/photo-2.jpg', 'data-testid': 'sell-photos' });

  const preset = (label, ms) => el('button', {
    type: 'button',
    class: 'secondary small',
    onclick: () => { endsAt.value = toLocalInputValue(new Date(Date.now() + ms)); },
  }, label);

  const fixedFields = el('fieldset', {}, el('legend', { text: 'Fixed price' }), field('Price (₹)', fixedPrice));
  const auctionFields = el('fieldset', { hidden: true }, el('legend', { text: 'Auction' }),
    el('div', { class: 'stack' },
      field('Starting price (₹)', startPrice),
      field('Reserve price (₹)', reservePrice, '(must be at least the starting price)'),
      field('Ends at', endsAt, '(your local time, at most 14 days ahead)'),
      el('div', { class: 'row' }, preset('+2 min', 2 * 60_000), preset('+1 hour', 3_600_000), preset('+1 day', 86_400_000))));

  const result = el('div', { 'data-testid': 'eligibility-result' });
  const checkButton = el('button', { type: 'button', class: 'secondary', hidden: true, 'data-testid': 'check-eligibility' }, 'Check auction eligibility');
  const createButton = el('button', { type: 'submit', 'data-testid': 'create-listing' }, 'Create listing');

  const syncType = () => {
    const auction = auctionRadio.checked;
    fixedFields.hidden = auction;
    auctionFields.hidden = !auction;
    checkButton.hidden = !auction;
    result.replaceChildren();
  };
  fixedRadio.addEventListener('change', syncType);
  auctionRadio.addEventListener('change', syncType);

  /** Builds the API body. Returns client-side reasons for money/date inputs that can't be converted. */
  function buildDraft() {
    const problems = [];
    const money = (input, label) => {
      if (input.value.trim() === '') return null;
      const paise = rupeesToPaise(input.value);
      if (paise === null) problems.push(`${label} must be an amount in rupees, like 899 or 899.50`);
      return paise;
    };
    const auction = auctionRadio.checked;
    const draft = {
      title: title.value,
      description: description.value || null,
      category: category.value,
      conditionGrade: condition.value,
      photoUrls: photos.value.split('\n').map((line) => line.trim()).filter(Boolean),
      type: auction ? 'AUCTION' : 'FIXED',
      fixedPrice: auction ? null : money(fixedPrice, 'Price'),
      startPrice: auction ? money(startPrice, 'Starting price') : null,
      reservePrice: auction ? money(reservePrice, 'Reserve price') : null,
      endsAt: null,
    };
    if (auction && endsAt.value) {
      const date = new Date(endsAt.value);
      if (Number.isNaN(date.getTime())) problems.push('Ends at is not a valid date and time');
      else draft.endsAt = date.toISOString();
    }
    return { draft, problems };
  }

  function showEligibility(eligible, reasons) {
    result.replaceChildren(eligible
      ? el('div', { class: 'result-ok', text: 'Eligible: this auction meets the current policy.' })
      : el('div', { class: 'result-fail' }, el('strong', { text: 'Not eligible for auction:' }),
        el('ul', {}, reasons.map((reason) => el('li', { text: reason })))));
  }

  checkButton.addEventListener('click', () => busy(checkButton, async () => {
    const { draft, problems } = buildDraft();
    if (problems.length) {
      showBanner('error', 'Fix these fields first', problems);
      return;
    }
    try {
      const outcome = await api('POST', '/listings/check', draft);
      showEligibility(outcome.eligible, outcome.reasons);
    } catch (error) {
      result.replaceChildren();
      showError(error);
    }
  }));

  const form = el('form', { class: 'card stack', 'data-testid': 'sell-form' },
    el('h1', { text: 'Create a listing' }),
    field('Title', title),
    field('Description', description),
    el('div', { class: 'two-col' }, field('Category', category), field('Condition', condition)),
    el('datalist', { id: 'sell-categories' }, CATEGORY_SUGGESTIONS.map((c) => el('option', { value: c }))),
    el('div', {}, el('label', { text: 'Sell as' }), el('div', {},
      el('label', { class: 'choice' }, fixedRadio, 'Fixed price'),
      el('label', { class: 'choice' }, auctionRadio, 'Auction'))),
    fixedFields,
    auctionFields,
    field('Photo URLs', photos, '(one per line, http or https)'),
    result,
    el('div', { class: 'row' }, checkButton, createButton));

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    busy(createButton, async () => {
      const { draft, problems } = buildDraft();
      if (problems.length) {
        showBanner('error', 'Fix these fields first', problems);
        return;
      }
      try {
        const created = await api('POST', '/listings', draft);
        showInfo(`Listing "${created.title}" created`);
        location.hash = `#/listing/${Number(created.id)}`;
      } catch (error) {
        if (error instanceof ApiError && error.status === 422) showEligibility(false, error.reasons);
        showError(error);
      }
    });
  });

  render(ctx, form);
}

// ---------------------------------------------------------------------------------------------------------------
// Wallet and orders
// ---------------------------------------------------------------------------------------------------------------

async function walletView(ctx) {
  const balances = el('div');
  const renderBalances = () => {
    const w = session.wallet;
    balances.replaceChildren(
      stat('Available', el('span', { text: formatPaise(w?.availableBalance), 'data-testid': 'wallet-available' })),
      stat('Held for bids', el('span', { text: formatPaise(w?.heldBalance), 'data-testid': 'wallet-held' })),
      stat('Total', el('span', { text: w ? formatPaise(w.availableBalance + w.heldBalance) : '—' })));
  };
  await refreshSession();
  if (!ctx.isCurrent()) return;
  renderBalances();

  const amount = el('input', { type: 'text', inputmode: 'decimal', placeholder: 'e.g. 1000', 'data-testid': 'topup-amount' });
  const topupButton = el('button', { type: 'submit', 'data-testid': 'topup' }, 'Add money');
  const topupForm = el('form', { class: 'stack' }, field('Top up (₹)', amount, '(simulated deposit, up to ₹1,00,000.00 at a time)'), topupButton);
  topupForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const paise = rupeesToPaise(amount.value);
    if (paise === null || paise <= 0) {
      showBanner('error', 'Enter an amount in rupees', ['For example 1000 or 1000.50']);
      return;
    }
    busy(topupButton, async () => {
      try {
        await api('POST', '/wallet/topup', { amount: paise });
        amount.value = '';
        await refreshSession();
        renderBalances();
        showInfo(`Added ${formatPaise(paise)} to your wallet`);
      } catch (error) {
        showError(error);
      }
    });
  });

  const orders = await ordersSection(ctx);
  render(ctx,
    el('h1', { text: 'Wallet' }),
    el('div', { class: 'two-col' },
      el('section', { class: 'card stack' }, el('h2', { text: 'Balances' }), balances),
      el('section', { class: 'card stack' }, el('h2', { text: 'Add money' }), topupForm)),
    el('div', { style: 'height: 1rem' }),
    orders);
}

async function ordersView(ctx) {
  const orders = await ordersSection(ctx);
  render(ctx, el('h1', { text: 'Orders' }), orders);
}

async function ordersSection(ctx) {
  const orders = await api('GET', '/orders/mine');
  if (!ctx.isCurrent()) return el('div');
  const body = orders.length
    ? el('div', { class: 'table-wrap' }, el('table', { 'data-testid': 'orders' },
      el('thead', {}, el('tr', {}, ['Date', 'Item', 'You', 'How', 'Amount'].map((h, i) => el('th', { class: i === 4 ? 'num' : null, text: h })))),
      el('tbody', {}, orders.map((order) => el('tr', {},
        el('td', { text: formatDateTime(order.createdAt) }),
        el('td', {}, el('a', { href: `#/listing/${Number(order.listingId)}`, text: order.listingTitle ?? `Listing #${order.listingId}` })),
        el('td', { text: order.role === 'BUYER' ? 'Bought' : 'Sold' }),
        el('td', { text: order.type === 'AUCTION_WIN' ? 'Auction win' : 'Buy now' }),
        el('td', { class: 'num', text: formatPaise(order.amountPaise) }))))))
    : el('p', { class: 'muted', text: 'No orders yet.' });
  return el('section', { class: 'card stack' }, el('h2', { text: 'Your orders' }), body);
}

// ---------------------------------------------------------------------------------------------------------------
// Admin
// ---------------------------------------------------------------------------------------------------------------

async function adminView(ctx) {
  const policy = await api('GET', '/admin/policy');
  if (!ctx.isCurrent()) return;

  const categories = el('input', { type: 'text', 'data-testid': 'policy-categories' });
  const conditionBoxes = Object.fromEntries(CONDITIONS.map((c) => [c, el('input', { type: 'checkbox', value: c })]));
  const minOrders = el('input', { type: 'number', step: 1, 'data-testid': 'policy-min-orders' });
  const requireVerified = el('input', { type: 'checkbox', 'data-testid': 'policy-require-verified' });
  const minPhotos = el('input', { type: 'number', step: 1, 'data-testid': 'policy-min-photos' });
  const minReserve = el('input', { type: 'text', inputmode: 'decimal', 'data-testid': 'policy-min-reserve' });
  const minIncrement = el('input', { type: 'text', inputmode: 'decimal', 'data-testid': 'policy-min-increment' });

  const fill = (p) => {
    categories.value = p.allowedCategories.join(', ');
    CONDITIONS.forEach((c) => { conditionBoxes[c].checked = p.allowedConditions.includes(c); });
    minOrders.value = String(p.minSellerCompletedOrders);
    requireVerified.checked = p.requireVerifiedSeller;
    minPhotos.value = String(p.minPhotos);
    minReserve.value = paiseToRupeesInput(p.minReservePaise);
    minIncrement.value = paiseToRupeesInput(p.minIncrementPaise);
  };
  fill(policy);

  const saveButton = el('button', { type: 'submit', 'data-testid': 'save-policy' }, 'Save policy');
  const policyForm = el('form', { class: 'card stack', 'data-testid': 'policy-form' },
    el('h2', { text: 'Auction eligibility policy' }),
    field('Allowed categories', categories, '(comma-separated)'),
    el('div', {}, el('label', { text: 'Allowed conditions' }),
      el('div', {}, CONDITIONS.map((c) => el('label', { class: 'choice' }, conditionBoxes[c], CONDITION_LABELS[c])))),
    el('div', { class: 'two-col' },
      field('Min seller completed orders', minOrders),
      field('Min photos', minPhotos)),
    el('label', { class: 'choice' }, requireVerified, 'Require a verified seller'),
    el('div', { class: 'two-col' },
      field('Min reserve (₹)', minReserve),
      field('Min bid increment (₹)', minIncrement)),
    el('div', { class: 'row' }, saveButton));

  policyForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const problems = [];
    const integer = (input, label) => {
      const value = parseInteger(input.value);
      if (Number.isNaN(value)) problems.push(`${label} must be a whole number`);
      return Number.isNaN(value) ? null : value;
    };
    const money = (input, label) => {
      if (input.value.trim() === '') return null;
      const paise = rupeesToPaise(input.value);
      if (paise === null) problems.push(`${label} must be an amount in rupees, like 100 or 100.50`);
      return paise;
    };
    const body = {
      allowedCategories: categories.value.split(',').map((c) => c.trim()).filter(Boolean),
      allowedConditions: CONDITIONS.filter((c) => conditionBoxes[c].checked),
      minSellerCompletedOrders: integer(minOrders, 'Min seller completed orders'),
      requireVerifiedSeller: requireVerified.checked,
      minPhotos: integer(minPhotos, 'Min photos'),
      minReservePaise: money(minReserve, 'Min reserve'),
      minIncrementPaise: money(minIncrement, 'Min bid increment'),
    };
    if (problems.length) {
      showBanner('error', 'Fix these fields first', problems);
      return;
    }
    busy(saveButton, async () => {
      try {
        const saved = await api('PUT', '/admin/policy', body);
        fill(saved);
        showInfo('Auction policy saved');
      } catch (error) {
        showError(error);
      }
    });
  });

  const statusFilter = el('select', { 'data-testid': 'admin-status' },
    el('option', { value: '', text: 'All statuses' }), STATUSES.map((s) => el('option', { value: s, text: s })));
  const tableSlot = el('div', { class: 'table-wrap' });

  async function loadListings() {
    const query = statusFilter.value ? `?status=${encodeURIComponent(statusFilter.value)}` : '';
    const listings = await api('GET', `/admin/listings${query}`);
    if (!ctx.isCurrent()) return;
    tableSlot.replaceChildren(listings.length ? el('table', { 'data-testid': 'admin-listings' },
      el('thead', {}, el('tr', {}, ['ID', 'Title', 'Type', 'Status', 'Seller', 'Price / high bid', 'Bids', ''].map((h) => el('th', { text: h })))),
      el('tbody', {}, listings.map((l) => el('tr', {},
        el('td', { text: String(l.id) }),
        el('td', {}, el('a', { href: `#/listing/${Number(l.id)}`, text: l.title })),
        el('td', { text: l.type === 'AUCTION' ? 'Auction' : 'Fixed' }),
        el('td', { text: l.closeReason && l.closeReason !== 'SOLD' ? `${l.status} (${l.closeReason})` : l.status }),
        el('td', { text: `#${l.sellerId}` }),
        el('td', { class: 'num', text: formatPaise(l.type === 'AUCTION' ? (l.currentHighBid ?? l.startPrice) : l.fixedPrice) }),
        el('td', { class: 'num', text: l.type === 'AUCTION' ? String(l.bidCount) : '—' }),
        el('td', {}, l.status === 'ACTIVE' ? rejectButton(l) : null)))))
      : el('p', { class: 'muted', text: 'No listings with this status.' }));
  }

  function rejectButton(listing) {
    const button = el('button', { type: 'button', class: 'danger small', 'data-reject': listing.id }, 'Reject');
    button.addEventListener('click', () => {
      const reason = window.prompt(`Reason for rejecting "${listing.title}"?`);
      if (reason === null) return;
      if (!reason.trim()) {
        showBanner('error', 'A reason is required to reject a listing');
        return;
      }
      busy(button, async () => {
        try {
          await api('POST', `/admin/listings/${listing.id}/reject`, { reason: reason.trim() });
          showInfo(`Rejected "${listing.title}"`);
          await loadListings();
        } catch (error) {
          showError(error);
        }
      });
    });
    return button;
  }

  statusFilter.addEventListener('change', () => loadListings().catch(showError));
  await loadListings();

  render(ctx,
    el('h1', { text: 'Admin' }),
    policyForm,
    el('div', { style: 'height: 1rem' }),
    el('section', { class: 'card stack' },
      el('div', { class: 'row' }, el('h2', { class: 'spacer', text: 'All listings' }), el('label', { class: 'row' }, 'Status', statusFilter)),
      tableSlot));
}

// ---------------------------------------------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------------------------------------------

async function boot() {
  headerEl = document.getElementById('header');
  mainEl = document.getElementById('main');
  bannerEl = document.getElementById('banner');
  window.addEventListener('hashchange', route);
  window.addEventListener('unhandledrejection', (event) => showError(event.reason));
  renderHeader();
  await refreshSession();
  await route();
}

if (typeof document !== 'undefined') {
  boot();
}
