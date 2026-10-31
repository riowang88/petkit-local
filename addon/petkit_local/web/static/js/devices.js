import { api, esc, toast, relTime, copyText } from './core.js';
import { onAction, onChange, onToggle, renderSection } from './delegate.js';
import { help } from './help.js';
import { SHOW_ALL, entityTable, controlRow } from './entities.js';
import { DEVLOG_REASONS } from './log.js';
import { entityName, streamProbeReason } from './zh.js';
import {
  activityList,
  deviceKind,
  deviceTile,
  glyph,
  overviewPane,
  settingsGroups,
} from './deviceview.js';

let DEVICES = [];
let ACCESSORIES = [];
//: The last detail payload per device, so a panel can repaint from memory
//: instead of flashing empty while a refresh is in flight.
const DEV_DETAIL = new Map();
//: Open/closed state, keyed "<deviceId>:<section>". A MAP rather than a Set
//: because the two defaults differ — a device panel starts open, every
//: `<details class="adv">` inside it starts closed — and a set can only say
//: "closed unless listed". Keying by device id is what makes it survive N
//: panels; without it, expanding diagnostics on one device would expand it on
//: all of them.
const DEV_OPEN = new Map();
try {
  for (const [k, v] of JSON.parse(localStorage.getItem('devOpen') || '[]')) DEV_OPEN.set(k, !!v);
} catch (_) {}

function secOpen(id, sec, dflt) {
  const k = id + ':' + sec;
  return DEV_OPEN.has(k) ? DEV_OPEN.get(k) : dflt;
}
function setSecOpen(id, sec, open) {
  DEV_OPEN.set(id + ':' + sec, !!open);
  try {
    localStorage.setItem('devOpen', JSON.stringify([...DEV_OPEN]));
  } catch (_) {}
}

//: Which device the page is showing: `d<id>`, `a<id>` for a BLE accessory, or
//: null for the overview. Remembered, so a reload lands where you were.
let SELECTED = null;
try {
  SELECTED = localStorage.getItem('devSelected') || null;
} catch (_) {}
//: The open pane per device. In memory only: coming back to a device on its
//: Diagnostics pane a week later is not what anyone meant by leaving it there.
const DEV_PANE = new Map();
//: Today's sessions per device id (`all` for the overview), fetched alongside
//: the detail and held briefly: a busy WebSocket refreshes the detail many times
//: a minute and the day's list does not change that fast.
const ACTIVITY = new Map();
const ACTIVITY_TTL_MS = 15000;

// ---------------- Devices ----------------
// The sidebar lists every device and the page shows one, or the overview of all
// of them. The body is rewritten in place on refresh rather than rebuilt, which
// is what keeps a WebSocket update from throwing away scroll position, open
// sections and half-typed input.
async function loadDevices() {
  const [ds, bleResp] = await Promise.all([api('devices'), api('ble')]);
  const box = document.getElementById('devPanels');
  // `/api/devices` answers a bare array, so an `{error}` is not an empty one:
  // the onboarding card below would tell someone whose devices are connected
  // and fine to go and provision one.
  if (ds.error) {
    if (box)
      box.innerHTML =
        '<div class="empty"><div class="big">⚠</div><b>无法加载设备</b>' +
        `<p class="mut">${esc(ds.error)}</p></div>`;
    return;
  }
  DEVICES = ds;
  // An accessory is its own device here, not a row in its parent's card: in
  // Home Assistant it already is one, and a CTW3 carries 21 entities and 8
  // controls — more than a purifier. What it does NOT get is the parent's
  // machinery (counters, queue, patchers, proxy): it has no network of its
  // own, so every one of those would be a zero pretending to be a reading.
  ACCESSORIES = (bleResp && bleResp.accessories) || [];
  if (!box) return;

  if (!ds.length && !ACCESSORIES.length) {
    renderNavDevices([]);
    box.innerHTML =
      '<div class="empty"><div class="big">🐾</div><b>尚未连接设备</b>' +
      '<p class="mut">通过蓝牙配置 PetKit 设备，或将设备的 <code>apiServers</code> 地址指向此服务。<br>打开<a data-action="goto-tab" data-tab="provision">蓝牙配网</a>栏目开始。</p></div>';
    return;
  }

  // Forget open-state for devices that no longer exist, so the stored map does
  // not grow forever across re-registrations.
  const live = new Set([
    ...ds.map(d => String(d.id)),
    ...ACCESSORIES.map(a => String(a.petkit_id)),
  ]);
  let pruned = false;
  for (const k of [...DEV_OPEN.keys()]) {
    if (!live.has(k.split(':')[0])) {
      DEV_OPEN.delete(k);
      pruned = true;
    }
  }
  if (pruned) {
    try {
      localStorage.setItem('devOpen', JSON.stringify([...DEV_OPEN]));
    } catch (_) {}
  }

  // Each accessory follows the device that relays for it, so the relationship
  // reads out of the order rather than out of nesting.
  const order = [];
  for (const d of ds) {
    order.push({ kind: 'dev', d });
    for (const a of ACCESSORIES.filter(a => a.link_with === d.id)) order.push({ kind: 'ble', a });
  }
  for (const a of ACCESSORIES.filter(a => !ds.some(d => d.id === a.link_with)))
    order.push({ kind: 'ble', a });

  renderNavDevices(order);
  const wanted = order.map(panelKey);
  if (SELECTED && !wanted.includes(SELECTED)) SELECTED = null;
  const tab = document.getElementById('tab-devices');
  if (tab.classList.contains('hidden')) return;
  syncNav();
  if (!SELECTED) {
    paintOverview(order);
    return;
  }
  const item = order.find(o => panelKey(o) === SELECTED);
  if (box.firstElementChild?.dataset.panelKey !== SELECTED) {
    box.innerHTML = item.kind === 'dev' ? panelShell(item.d) : accShell(item.a);
  }
  if (item.kind === 'ble') {
    setToolbar(item.a.name, accSubtitle(item.a));
    const body = box.querySelector('.devbody');
    if (!isEditing(body)) body.innerHTML = accBody(item.a);
    return;
  }
  setToolbar(item.d.name, deviceSubtitle(item.d), toolbarActions(DEV_DETAIL.get(item.d.id)));
  if (DEV_DETAIL.has(item.d.id)) paintPanelBody(item.d.id);
  scheduleDetail(item.d.id);
}

const panelKey = o => (o.kind === 'dev' ? 'd' + o.d.id : 'a' + o.a.petkit_id);

// The sidebar's device rows. Rebuilt only when the set of devices changes;
// otherwise just the status dots move, so a refresh cannot steal keyboard focus
// or a hover out from under someone using the list.
function renderNavDevices(order) {
  const box = document.getElementById('navDevices');
  if (!box) return;
  const keys = order.map(panelKey).join(',');
  if (box.dataset.keys !== keys) {
    box.dataset.keys = keys;
    box.innerHTML = order
      .map(o => {
        const x = o.kind === 'dev' ? o.d : o.a;
        return `<button role="tab" aria-selected="false" data-tab="devices" data-device="${esc(panelKey(o))}" class="nav-dev">
          <span class="nav-gl">${glyph(deviceKind(x))}</span><span class="lbl">${esc(x.name)}</span><span class="dot"></span></button>`;
      })
      .join('');
  }
  for (const o of order) {
    const on = o.kind === 'dev' ? o.d.online : o.a.parent_online;
    const dot = box.querySelector(`[data-device="${CSS.escape(panelKey(o))}"] .dot`);
    if (dot) dot.className = 'dot ' + (on ? 'on' : 'off');
  }
}

// The toolbar names the device and carries its one window-level control. Its
// `<h2>` is shared with every other section; nav.js resets it on the way out.
function setToolbar(title, sub, actions) {
  document.getElementById('pageTitle').textContent = title;
  document.getElementById('pageSub').textContent = sub || '';
  document.getElementById('pageActions').innerHTML = actions || '';
}
const typeLabel = d => (d.type || d.ble_type || '').toUpperCase();
function deviceSubtitle(d) {
  const link = !d.online ? '离线' : d.mqtt_connected ? 'MQTT' : 'HTTP 心跳';
  const queued = d.queue ? `，${d.queue} 条命令待发送` : '';
  return `${typeLabel(d)}，${link}${queued}`;
}
function accSubtitle(a) {
  return `${typeLabel(a)}，${a.parent_name ? '经 ' + a.parent_name + ' 中继' : '没有中继设备'}`;
}
function toolbarActions(d) {
  if (!d || !d.is_camera) return '';
  return `<button class="act" data-action="open-live" data-id="${esc(d.id)}" data-name="${esc(typeLabel(d) + ' · #' + d.id)}" ${d.state?.streamAvailable ? '' : 'disabled'}>查看实时画面</button>`;
}

// The sidebar's selection is two questions: which section, and within Devices
// which row. nav.js answers the first; this answers the second, including on
// the first paint after a reload, before anyone has clicked anything.
function syncNav() {
  document.querySelectorAll('nav button').forEach(b => {
    const on = b.dataset.tab === 'devices' && (b.dataset.device || null) === SELECTED;
    b.classList.toggle('on', on);
    b.setAttribute('aria-selected', on ? 'true' : 'false');
  });
}

function selectDevice(key) {
  SELECTED = key || null;
  try {
    if (SELECTED) localStorage.setItem('devSelected', SELECTED);
    else localStorage.removeItem('devSelected');
  } catch (_) {}
  document.getElementById('devPanels').innerHTML = '';
  loadDevices();
}
onAction('open-device', el => selectDevice(el.dataset.key));
// A menu closes on any click but the one that opened it, like a native one. An
// item's own click still runs: this only flips `open` on the way past.
document.addEventListener('click', ev => {
  document.querySelectorAll('.menu[open]').forEach(m => {
    if (!m.querySelector('summary').contains(ev.target)) m.open = false;
  });
});

async function fetchActivity(id) {
  const k = id == null ? 'all' : id;
  const held = ACTIVITY.get(k);
  if (held && Date.now() - held.at < ACTIVITY_TTL_MS) return held.sessions;
  const r = await api('timeline' + (id == null ? '' : '?device=' + id));
  const sessions = (r && r.sessions) || [];
  ACTIVITY.set(k, { at: Date.now(), sessions });
  return sessions;
}

// Every device at a glance, then the day. Tiles draw from whatever detail is
// already held and fill in as the rest arrive, so the page is never blank
// waiting for the slowest device.
function paintOverview(order) {
  const box = document.getElementById('devPanels');
  setToolbar('概览', onlineSummary(order));
  const held = ACTIVITY.get('all');
  box.innerHTML = `<div class="overview">
    <div class="dev-tiles">${order
      .map(o =>
        o.kind === 'dev'
          ? deviceTile(o.d, panelKey(o), DEV_DETAIL.get(o.d.id))
          : deviceTile(o.a, panelKey(o), null),
      )
      .join('')}</div>
    <section class="sgroup"><div class="sgroup-head"><h3>今天</h3><span class="grow"></span><a data-action="goto-tab" data-tab="timeline">查看时间线</a></div>
      <div class="sbox" id="ovActivity">${activityList(held && held.sessions, true)}</div></section>
  </div>`;
  fetchActivity(null).then(sessions => {
    const slot = document.getElementById('ovActivity');
    if (slot) slot.innerHTML = activityList(sessions, true);
  });
  for (const o of order) if (o.kind === 'dev' && !DEV_DETAIL.has(o.d.id)) scheduleDetail(o.d.id);
}
function onlineSummary(order) {
  const on = order.filter(o => (o.kind === 'dev' ? o.d.online : o.a.parent_online)).length;
  return on === order.length ? `${on} 台设备全部在线` : `${on} / ${order.length} 台设备在线`;
}

const devPanel = id =>
  document.querySelector('.devpanel[data-id="' + CSS.escape(String(id)) + '"]');

function panelShell(d) {
  return `<article class="devpanel" data-panel-key="d${esc(d.id)}" data-id="${esc(d.id)}"><div class="dev-tabs"></div><div class="devbody"></div></article>`;
}

function accShell(a) {
  return `<article class="devpanel accpanel" data-panel-key="a${esc(a.petkit_id)}" data-id="${esc(a.petkit_id)}"><div class="devbody"></div></article>`;
}
// The stream addresses are the one thing on this page you paste somewhere else,
// and they are too long to select by hand in a scrolling <code>.
onAction('copy-url', el => {
  copyText(el.dataset.url || '');
  toast('已复制');
});

onAction('ble-read-now', async el => {
  const r = await api('ble/' + el.dataset.id + '/poll', { method: 'POST' });
  toast(r && r.ok ? 'Asked the relay for a reading' : '错误：' + ((r && r.error) || '?'));
  loadDevices();
});
function accBody(a) {
  const ents = a.entities || [];
  const sensors = ents.filter(e => e.component === 'sensor' || e.component === 'binary_sensor');
  const controls = ents.filter(e => ['switch', 'number', 'select'].includes(e.component));
  const actions = ents.filter(e => e.component === 'button');
  return `<p class="acc-link">${a.parent_online ? '<span class="badge ble">BLE</span>' : '<span class="badge off">离线</span>'}${a.parent_name ? '' : ' <span class="badge bad">没有中继设备</span>'}</p>
  <div class="meta">
    <div><div class="m-k">蓝牙地址</div><div class="m-v"><code>${esc(a.mac)}</code></div></div>
    <div><div class="m-k">中继设备</div><div class="m-v">${a.parent_name ? esc(a.parent_name) + ' #' + esc(a.link_with) : '—'}</div></div>
    <div><div class="m-k">序列号</div><div class="m-v">${esc(a.serial_number || '—')}</div></div>
    <div><div class="m-k">最近读取</div><div class="m-v">${a.last_seen ? esc(relTime(a.last_seen)) : '从未'}</div></div>
    <div><div class="m-k">轮询间隔</div><div class="m-v">${esc(a.interval)}s</div></div>
    <div><div class="m-k">扫描类型</div><div class="m-v">${esc(a.wire_entry ? a.wire_entry.type : '—')}</div></div>
  </div>
  ${
    a.scan_type_is_guessed
      ? `<div class="card notice"><b>The scan type for this model is a guess.</b>
    <p class="sub" style="margin:6px 0 0">Nobody has captured what a real ${esc(a.ble_type.toUpperCase())} pairing carries, so
    <code>${esc(a.wire_entry ? a.wire_entry.type : '?')}</code> is borrowed from its product line. If this accessory never reports,
    that number is the first thing to change — unpair and pair again with a different <b>Scan type</b> under Advanced,
    and please say which value worked.</p></div>`
      : ''
  }
  <div class="card"><h3>设备操作${help(
    'Read now is about the relay rather than the accessory: it speaks only when its parent is told to open a Bluetooth session, and otherwise that happens on a timer up to the poll interval away. The rest are the accessory’s own — a filter reset carries no settings and is the one write that works before it has ever reported.',
  )}</h3>
    <div><button class="act" data-action="ble-read-now" data-id="${esc(a.petkit_id)}">立即读取</button>${actions
      .map(
        e =>
          `<button class="act" data-action="press-entity" data-id="${esc(a.petkit_id)}" data-key="${esc(e.key)}" data-kind="ble" data-name="${esc(e.name)}">${esc(e.name)}</button>`,
      )
      .join('')}</div></div>
  ${
    sensors.length
      ? `<div class="card"><h3>设备状态</h3>
    ${entityTable({ id: a.petkit_id, entities: ents }, sensors)}
    <details class="adv"><summary>原始状态 JSON</summary><pre>${esc(JSON.stringify(a.state, null, 2))}</pre></details></div>`
      : `<div class="card"><h3>设备状态</h3><p class="mut" style="margin:0">Nothing reported yet. An accessory only speaks when its parent is asked to open a Bluetooth session to it.</p></div>`
  }
  ${
    controls.length
      ? `<div class="card"><h3>设备设置</h3><div class="ctrls">${controls
          .map(e => controlRow(a.petkit_id, e, 'ble'))
          .join('')}</div>
    <p class="sub" style="margin:8px 0 0">Settings travel as one block, so a change needs a reading first — before the accessory has reported, a write is refused rather than guessed at.</p></div>`
      : ''
  }`;
}

onToggle('dev-sec', el => {
  setSecOpen(Number(el.dataset.id), el.dataset.sec, el.open);
  if (el.dataset.sec === 'sounds' && el.open) loadSounds(el.dataset.id);
  if (el.dataset.sec === 'deferred' && el.open) loadDeferred(el.dataset.id);
});

// One request per device: the sidecars the detail view needs are folded into
// `api_device_detail` server-side. They used to be three extra round trips,
// which with a panel per device refreshing on every tick was four times the
// traffic for data that changes only when the user changes it.
async function fetchDeviceDetail(id) {
  return api('devices/' + id);
}

// Typing is what a repaint must not interrupt. A focused BUTTON or checkbox does
// not count: testing for any focused element left the body frozen after every
// click, until something else took focus. An open menu counts because replacing
// it closes it under the pointer.
function isEditing(body) {
  const el = document.activeElement;
  return (
    (body.contains(el) && el.matches('input:not([type="checkbox"]), textarea, select')) ||
    !!body.querySelector('.menu[open]')
  );
}

function paintPanelBody(id, force) {
  const panel = devPanel(id);
  if (!panel) return;
  const body = panel.querySelector('.devbody');
  const d = DEV_DETAIL.get(id);
  if (!body || !d) return;
  // Never redraw under the user's cursor: the panel auto-refreshes every ~10s
  // and a repaint would silently wipe a half-typed raw command or schedule.
  if (!force && isEditing(body)) return;
  const panes = devicePanes(d);
  if (!panes[DEV_PANE.get(id)]) DEV_PANE.delete(id);
  const pane = DEV_PANE.get(id) || 'overview';
  panel.querySelector('.dev-tabs').innerHTML = `<div class="chips" role="tablist">${Object.entries(
    panes,
  )
    .map(
      ([k, p]) =>
        `<button class="chip-btn${k === pane ? ' on' : ''}" role="tab" aria-selected="${k === pane}" data-action="dev-pane" data-id="${esc(id)}" data-pane="${esc(k)}">${esc(p.label)}</button>`,
    )
    .join('')}</div>`;
  body.innerHTML = panes[pane].render();
  body.dataset.pane = pane;
}
onAction('dev-pane', el => {
  DEV_PANE.set(Number(el.dataset.id), el.dataset.pane);
  paintPanelBody(Number(el.dataset.id), true);
});

// The device echoes back which mugshots it actually holds, as the
// `discernPic` array of face ids in its state report. That readback is the one
// proof a dev_discern_pic payload was accepted: ids it does not recognise mean
// it is still matching against photos cached from PetKit's cloud.
function facesOnDevice(d, ourFaceIds) {
  const cached = (d.state || {}).discernPic;
  if (!Array.isArray(cached)) return '';
  if (!cached.length)
    return `<p class="sub">No mugshots cached on the device yet. It re-reads them at boot and
      about once an hour; a reboot forces it.</p>`;
  // Comparing against our own ids is the whole value of this readback: matching
  // ids prove the device took OUR mugshots, foreign ones say it is still
  // matching against PetKit's — which is exactly what proxy mode serves it.
  const ours = ourFaceIds || [];
  const foreign = cached.filter(id => !ours.includes(id));
  let verdict = '';
  if (ours.length && !foreign.length) {
    verdict = ' — yours, so the device is matching against your mugshots.';
  } else if (ours.length) {
    verdict = ` — <b>not yours</b>. It is still using photos from PetKit's cloud, which is what
      proxy mode hands it. Turn proxy off and reboot the device to switch it to your
      ${ours.length} mugshot${ours.length === 1 ? '' : 's'}.`;
  }
  return `<p class="sub">Cached on device: ${cached.length} mugshot${
    cached.length === 1 ? '' : 's'
  } <code>${esc(cached.join(', '))}</code>${verdict}</p>`;
}

const CAP_LABELS = {
  fullVideo: '录像',
  eventImage: '照片',
  highLight: '精彩片段',
  dynamicVideo: '动态片段',
};
function capRow(id, ct, on) {
  return `<label class="ctrl"><span>${esc(CAP_LABELS[ct] || ct)}</span>
    <span class="sw"><input type="checkbox" ${on ? 'checked' : ''} data-change="set-capability" data-id="${esc(id)}" data-cap="${esc(ct)}"><span class="sl"></span></span></label>`;
}
onChange('set-capability', el => setCapability(Number(el.dataset.id), el.dataset.cap, el.checked));
async function setCapability(id, ct, on) {
  const r = await api('devices/' + id + '/capabilities', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ [ct]: on }),
  });
  toast(r.ok ? '已保存' : '错误：' + (r.error || '失败'));
}
onAction('ble-pair', async el => {
  const box = el.closest('details');
  const val = c => (box.querySelector('.' + c) || {}).value || '';
  const r = await api('ble', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ble_type: val('ble-type'),
      petkit_id: Number(val('ble-id')) || 0,
      mac: val('ble-mac'),
      secret: val('ble-secret'),
      interval: Number(val('ble-interval')) || 240,
      serial_number: val('ble-sn'),
      scan_type: Number(val('ble-scan-type')) || 0,
      link_with: Number(el.dataset.id),
    }),
  });
  if (r.error) return toast('错误：' + r.error);
  toast('Paired — the device picks it up on its next poll');
  loadDevices();
});

onAction('ble-cloud-import', async el => {
  el.disabled = true;
  toast('Asking PetKit…');
  const r = await api('ble/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ device_id: Number(el.dataset.id) }),
  });
  el.disabled = false;
  if (r.error) return toast('错误：' + r.error);
  // Every refusal is named rather than swallowed: an import that quietly
  // covers four of five accessories is worse than one that says so.
  const refused = (r.results || []).filter(
    x => !['imported', 'updated', 'unchanged'].includes(x.outcome),
  );
  toast(
    (r.imported
      ? `Imported ${r.imported} accessor${r.imported === 1 ? 'y' : 'ies'}`
      : 'Nothing new to import') +
      (refused.length
        ? ` — ${refused.length} skipped: ${refused.map(x => x.outcome).join('; ')}`
        : ''),
  );
  loadDevices();
});

onAction('delete-device', async el => {
  if (
    !confirm(
      `确定删除 ${el.dataset.name}？\n\n此操作会从设备列表移除该设备及其 Home Assistant 项目，事件和媒体文件会保留。`,
    )
  )
    return;
  const r = await api('devices/' + encodeURIComponent(el.dataset.id), { method: 'DELETE' });
  toast(r.ok ? '设备已移除' : '错误：' + (r.error || '失败'));
  loadDevices();
});

// --- deferred feeds ---

async function loadDeferred(deviceId) {
  const el = document.getElementById('deferred-' + deviceId);
  if (!el) return;
  const r = await api('devices/' + encodeURIComponent(deviceId) + '/deferred-feed');
  const items = r.deferred || [];
  if (!items.length) {
    el.innerHTML = '<p class="sub">暂无计划喂食。</p>';
    return;
  }
  el.innerHTML = items
    .map(d => {
      const dt = new Date(d.fire_at * 1000);
      const when =
        dt.toLocaleDateString() +
        ' ' +
        dt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      return `<div class="row" style="align-items:center;gap:8px;margin:4px 0">
      <span style="flex:1">${esc(when)} — H1: ${d.a1}, H2: ${d.a2}</span>
      <button class="act mini-ex danger" data-action="delete-deferred" data-id="${esc(String(deviceId))}" data-fid="${esc(d.id)}">删除</button>
    </div>`;
    })
    .join('');
}

onAction('add-deferred', async el => {
  const form = document.getElementById('deferred-form-' + el.dataset.id);
  if (!form) return;
  const date = form.querySelector('[data-field="date"]').value;
  const time = form.querySelector('[data-field="time"]').value;
  const a1 = parseInt(form.querySelector('[data-field="a1"]').value) || 0;
  const a2 = parseInt(form.querySelector('[data-field="a2"]').value) || 0;
  if (!date || !time) {
    toast('请选择日期和时间');
    return;
  }
  const r = await api('devices/' + encodeURIComponent(el.dataset.id) + '/deferred-feed', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ date, time, a1, a2 }),
  });
  toast(r.ok ? '已安排喂食' : '错误：' + (r.error || '失败'));
  loadDeferred(el.dataset.id);
});

onAction('delete-deferred', async el => {
  const r = await api(
    'devices/' +
      encodeURIComponent(el.dataset.id) +
      '/deferred-feed/' +
      encodeURIComponent(el.dataset.fid),
    { method: 'DELETE' },
  );
  toast(r.ok ? '已移除' : '错误：' + (r.error || '失败'));
  loadDeferred(el.dataset.id);
});

// --- custom sounds ---

async function loadSounds(deviceId) {
  const el = document.getElementById('sounds-' + deviceId);
  if (!el) return;
  const r = await api('devices/' + encodeURIComponent(deviceId) + '/sounds');
  const sounds = r.sounds || [];
  if (!sounds.length) {
    el.innerHTML = '<p class="sub">尚未上传自定义声音。</p>';
    return;
  }
  el.innerHTML = sounds
    .map(
      s =>
        `<div class="row" style="align-items:center;gap:8px;margin:4px 0">
      <span style="flex:1">${esc(s.name || 'Sound ' + s.id)} <span class="sub">(${(s.size / 1024).toFixed(0)} KB)</span></span>
      <button class="act mini-ex" data-action="play-sound" data-id="${esc(String(deviceId))}" data-sid="${s.id}">播放</button>
      <button class="act mini-ex" data-action="select-sound" data-id="${esc(String(deviceId))}" data-sid="${s.id}">选择</button>
      <button class="act mini-ex danger" data-action="delete-sound" data-id="${esc(String(deviceId))}" data-sid="${s.id}">删除</button>
    </div>`,
    )
    .join('');
}

document.addEventListener('change', async e => {
  const el = e.target;
  if (!el.dataset || el.dataset.action !== 'upload-sound') return;
  const file = el.files[0];
  if (!file) return;
  const fd = new FormData();
  fd.append('file', file);
  const r = await api('devices/' + encodeURIComponent(el.dataset.id) + '/sounds', {
    method: 'POST',
    body: fd,
  });
  toast(r.ok ? '已上传' : '错误：' + (r.error || '失败'));
  el.value = '';
  loadSounds(el.dataset.id);
});

onAction('play-sound', async el => {
  const r = await api(
    'devices/' + encodeURIComponent(el.dataset.id) + '/sounds/' + el.dataset.sid + '/play',
    { method: 'POST' },
  );
  toast(r.ok ? '正在播放' : '错误：' + (r.error || '失败'));
});

onAction('select-sound', async el => {
  const r = await api(
    'devices/' + encodeURIComponent(el.dataset.id) + '/sounds/' + el.dataset.sid + '/select',
    { method: 'POST' },
  );
  toast(r.ok ? '已选择' : '错误：' + (r.error || '失败'));
});

onAction('delete-sound', async el => {
  if (!confirm('确定删除这个声音吗？')) return;
  const r = await api(
    'devices/' + encodeURIComponent(el.dataset.id) + '/sounds/' + el.dataset.sid,
    { method: 'DELETE' },
  );
  toast(r.ok ? '已删除' : '错误：' + (r.error || '失败'));
  loadSounds(el.dataset.id);
});

onAction('ble-unpair', async el => {
  if (
    !confirm(
      `确定解除 ${el.dataset.name} 的配对吗？\n\n其 Home Assistant 项目会保留，直到你在那里删除设备。`,
    )
  )
    return;
  const r = await api('ble/' + encodeURIComponent(el.dataset.id), { method: 'DELETE' });
  toast(r.ok ? '已解除配对' : '错误：' + (r.error || '失败'));
  loadDevices();
});

// An empty box means "no override", which is a real value here and not a
// missing one — it hands the device back to whatever it reported for itself.
onAction('set-timezone', el => {
  const box = el.closest('.ctrl').querySelector('[data-input="tz-offset"]');
  const raw = (box.value || '').trim();
  setTimezone(Number(el.dataset.id), raw === '' ? null : Number(raw));
});
async function setTimezone(id, offset) {
  const r = await api('devices/' + id + '/timezone', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ timezone: offset }),
  });
  if (!r.ok) return toast('错误：' + (r.error || '失败'));
  // "Sent" is not "visible": the device applies it at once but only echoes it
  // back on its next property post, which arrives on its own schedule.
  toast(r.delivered === 'mqtt' ? '已发送到设备' : '已保存，将在设备下次连接时生效');
  loadDevices();
}

onChange('set-log-upload', el => setLogUpload(Number(el.dataset.id), el.checked));
async function setLogUpload(id, on) {
  const r = await api('devices/' + id + '/logs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ log_upload_enabled: on }),
  });
  toast(r.ok ? '已保存' : '错误：' + (r.error || '失败'));
}

// Which card each HA component lands in. Declared rather than implied by a
// chain of filters, so a component that gains no home shows up as a visible
// warning instead of silently vanishing from the panel while still being
// published to Home Assistant — which is exactly what happened to `text`,
// `event`, `camera` and `image`.
const ENTITY_SECTION = {
  sensor: 'state',
  binary_sensor: 'state',
  switch: 'controls',
  number: 'controls',
  select: 'controls',
  // A single time of day — the W7H's drain and flush hours. It sits with the
  // Schedules, because from the owner's side "when does it flush" and "when is
  // it quiet" are one question, and splitting them across two cards described
  // OUR storage rather than their fountain. The split it used to follow is
  // real, but it is a Home Assistant limitation: HA has no entity for a list
  // of ranges, so only the single-point fields can be entities at all. The
  // panel is under no such constraint.
  time: 'schedules',
  button: 'actions',
  text: 'schedules',
  // No card of its own. An event entity is momentary — it fires and keeps no
  // value — so the card that used to list them could only ever print the
  // event_types they declare, never anything that happened. What happened is
  // the Timeline's job. They are still reachable, under "Show every entity".
  event: 'entity-list-only',
  camera: 'camera',
  image: 'camera',
};

// One button, one request, one answer. PetKit's account knows which
// accessories are bound to this device and with which pairing secret — a value
// that exists nowhere else and that a person otherwise has to find and retype.
//
// Asked only when pressed. The add-on never polls PetKit on a schedule of its
// own; see `http/cloud_fetch.py`.
// Why the live view is still waiting, from the sidecar's last probe. Empty
// until there has been one: "waiting" alone is the honest answer then.
function liveWaitReason(probe) {
  if (!probe) return '';
  const reason = probe.busy ? 'busy' : probe.reason;
  return reason ? `（${esc(streamProbeReason(reason))}）` : '';
}

function importFromCloudBlock(id) {
  // `.act`, not `.mini`: a device panel has no filled backgrounds anywhere
  // else (see styles.css), and this is a peer of the "Pair accessory" button
  // below it, not something louder. The explanation goes in the same `?` chip
  // the rest of this card uses rather than trailing off to the right.
  return `<div style="margin:0 0 4px">
    <button class="act" data-action="ble-cloud-import" data-id="${esc(id)}">从 PETKIT 导入</button>
    ${help(
      'Asks PetKit which accessories this device owns, and copies each one’s Bluetooth address and pairing secret across. The secret exists nowhere else — it cannot be worked out here — so this is the one way to get it without reading it off the accessory. Nothing is sent to PetKit until you press this.',
    )}
  </div>`;
}

function devicePanes(d) {
  const ents = d.entities || [];
  const section = e => ENTITY_SECTION[e.component];
  // Capability toggles get their own card below, and the AI detections live on
  // the AI / Pets tab next to the pets they are about — same underlying
  // entities/API, friendlier grouping. Both excluded here or they appear twice.
  //
  // The AI half is conditional on this device HAVING that card: a feeder shares
  // `pet_detection` with the fountain but has no on-device AI, so relocating it
  // there would delete the control rather than move it.
  const relocated = e =>
    e.value_path.startsWith('capabilities.') || (d.supports_ai && AI_ENTITY_KEYS.includes(e.key));
  // `config`/`diagnostic` sort last within their card, the way HA groups them.
  // Grouping only — filtering any of it out would re-create the divergence in
  // the other direction.
  const byCategory = (a, b) => (a.entity_category ? 1 : 0) - (b.entity_category ? 1 : 0);

  const sensors = ents
    .filter(e => section(e) === 'state' && !e.key.startsWith('hall_'))
    .sort(byCategory);
  const controls = ents.filter(e => section(e) === 'controls' && !relocated(e)).sort(byCategory);
  const schedules = ents.filter(e => section(e) === 'schedules');
  const media = ents.filter(e => section(e) === 'camera');
  const unrouted = ents.filter(e => !section(e));
  const hbAgo = d.last_heartbeat ? relTime(d.last_heartbeat) : '从未';
  const info = `
  <div class="card">
    <div class="meta">
      <div><div class="m-k">序列号</div><div class="m-v">${esc(d.sn) || '—'}</div></div>
      <div><div class="m-k">MAC</div><div class="m-v">${esc(d.mac) || '—'}</div></div>
      <div><div class="m-k">固件版本</div><div class="m-v">${esc(d.firmware) || '—'}</div></div>
      <div><div class="m-k">最近心跳</div><div class="m-v">${hbAgo}</div></div>
      <div><div class="m-k">最近状态</div><div class="m-v">${d.last_state_report ? relTime(d.last_state_report) : '从未'}</div></div>
      <div><div class="m-k">待发送命令</div><div class="m-v">${esc(d.queue)}</div></div>
      <div><div class="m-k">HTTP / MQTT</div><div class="m-v">${esc(d.http_count)} / ${esc(d.mqtt_count)}</div></div>
      <div><div class="m-k">ProductKey</div><div class="m-v"><code>${esc(d.pk)}</code></div></div>
      <div><div class="m-k">DeviceName</div><div class="m-v"><code>${esc(d.dn)}</code></div></div>
    </div>
  </div>`;

  const unknownType =
    !d.actions.length && !controls.length && !sensors.length
      ? `<div class="card">
    <p class="sub" style="margin:0">This device type (<code>${esc(d.type.toUpperCase())}</code>) isn\'t recognized, so no Home Assistant entities are defined for it. It still connects, appears here, and its traffic is logged — you can send raw commands under Diagnostics.</p></div>`
      : '';
  const state = sensors.length
    ? `<div class="card"><h3>设备状态${help(
        '设备当前上报的状态。“显示全部项目”还会列出控制项、按钮和事件。',
      )}</h3>
    ${entityTable(d, sensors)}
    <label class="showall"><input type="checkbox" data-change="show-all-entities" data-id="${esc(d.id)}" ${SHOW_ALL.has(d.id) ? 'checked' : ''}> <span class="mut">显示全部项目 (${esc(ents.length)})</span></label>
    <details class="adv" data-toggle="dev-sec" data-id="${esc(d.id)}" data-sec="rawstate" ${secOpen(d.id, 'rawstate', false) ? 'open' : ''}><summary>原始状态 JSON</summary><pre>${esc(JSON.stringify(d.state, null, 2))}</pre></details></div>`
    : '';

  const capabilities =
    d.is_camera && d.capInfo
      ? `<section class="sgroup"><div class="sgroup-head"><h3>媒体上传${help(
          '从源头关闭上传：设备将停止发送该媒体类型。设置会在设备下次查询 STS 时生效。',
        )}</h3></div>
    <div class="sbox">${Object.entries(d.capInfo.capabilities)
      .map(([ct, on]) => capRow(d.id, ct, on))
      .join('')}</div></section>`
      : '';

  const timezone = d.timezoneInfo
    ? `<section class="sgroup"><div class="sgroup-head"><h3>时区${help(
        '设备使用配网时提供的时区偏移生成视频水印。此处设置会发送到已配对设备。设备只上报一位小数，例如 5.75 会显示为 5.8。',
      )}</h3></div>
    <div class="sbox"><p class="sub sbox-note">当前使用 <b>UTC${d.timezoneInfo.effective >= 0 ? '+' : ''}${esc(d.timezoneInfo.effective)}</b>，来源：${
      {
        override: '此处设置',
        device: '设备上报',
        server: '服务器时钟',
      }[d.timezoneInfo.source] || d.timezoneInfo.source
    }${d.timezoneInfo.locale ? ` · ${esc(d.timezoneInfo.locale)}` : ''}${
      d.timezoneInfo.reported != null ? ` · 设备上报 ${esc(d.timezoneInfo.reported)}` : ''
    }</p>
    <label class="ctrl"><span>UTC 偏移</span>
      <span class="cn"><input type="number" min="-12" max="14" step="0.25" placeholder="自动"
        value="${d.timezoneInfo.override == null ? '' : esc(d.timezoneInfo.override)}"
        data-input="tz-offset" data-id="${esc(d.id)}">
      <button class="mini" data-action="set-timezone" data-id="${esc(d.id)}">设置</button></span></label></div></section>`
    : '';

  const logs = d.logInfo
    ? `<section class="sgroup"><div class="sgroup-head"><h3>调试日志${help(
        '设备可将 devRun.log 上传到本地而非 PETKIT。上传使用 HTTPS 自签证书，需要 CA 证书补丁；上传时间由设备决定，通常在重启时。',
      )}</h3></div>
    <div class="sbox"><p class="sub sbox-note">设备自身的运行记录可在<a data-action="goto-tab" data-tab="log">日志</a>栏目查看。${
      d.logInfo.reason
        ? ` <b>当前无法收集：</b> ${esc(DEVLOG_REASONS[d.logInfo.reason] || d.logInfo.reason)}.`
        : ''
    }</p>
    <label class="ctrl"><span>收集调试日志</span>
      <span class="sw"><input type="checkbox" ${d.logInfo.log_upload_enabled ? 'checked' : ''} data-change="set-log-upload" data-id="${esc(d.id)}"><span class="sl"></span></span></label></div></section>`
    : '';

  const accessories = `<div class="card"><h3>蓝牙配件${help(
    "The device never goes looking for accessories: it asks us for a list of Bluetooth addresses and scans for exactly those, and no firmware can report a new one back. Pairing normally happens in PetKit's app, so with the app gone it has to be entered here.",
  )}</h3>
    <p class="sub">Pura Air 喷雾器和 EverSweet 饮水机没有独立网络连接，由此设备通过蓝牙中继。</p>
    ${
      (d.ble || []).length
        ? `<table class="stbl"><tbody>${d.ble
            .map(
              b => `<tr><td><b>${esc(b.type.toUpperCase())}</b> <span class="mut">#${esc(b.id)}</span></td>
        <td><code>${esc(b.mac)}</code></td>
        <td><a class="danger-link" data-action="ble-unpair" data-id="${esc(b.id)}" data-name="${esc(b.type.toUpperCase())}">解除配对</a></td></tr>`,
            )
            .join('')}</tbody></table>`
        : '<p class="mut" style="margin:0 0 10px">尚未配对。</p>'
    }
    ${importFromCloudBlock(d.id)}
    <details class="adv" data-toggle="dev-sec" data-id="${esc(d.id)}" data-sec="blepair" ${secOpen(d.id, 'blepair', false) ? 'open' : ''}>
      <summary>配对配件</summary>
      <p class="sub">需要配件的<b>蓝牙地址</b>和<b>配对密钥</b>。它们来自配件或 PETKIT App；本服务无法推算，密钥不正确时配件会拒绝连接。</p>
      <div class="row">
        <div class="col"><label class="mut">配件</label>
          <select class="ble-type"><option value="w5">EverSweet 3 Pro / Solo (W5)</option><option value="w4">EverSweet (W4)</option><option value="ctw2">EverSweet Solo 2 (CTW2)</option><option value="ctw3">EverSweet Max Cordless (CTW3)</option><option value="k3">Pura Air spray (K3)</option></select></div>
        <div class="col"><label class="mut">蓝牙地址（MAC）</label><input class="ble-mac" placeholder="AA:BB:CC:DD:EE:FF"></div>
      </div>
      <div class="row" style="margin-top:8px">
        <div class="col"><label class="mut">配对密钥${help(
          'Eight bytes, written into the accessory once and kept there until a factory reset. PetKit’s cloud generates it the first time the app sets the fountain up, and the accessory then refuses any session that presents a different one — so a fountain that has already been through PetKit’s app needs THAT secret, not a new one. A factory-fresh accessory has none and accepts eight zero bytes, which is what a reset gets you back to.',
        )}</label><input class="ble-secret" placeholder="来自配件"></div>
        <div class="col"><label class="mut">名称（可选）</label><input class="ble-sn" placeholder="序列号或名称"></div>
      </div>
      <details class="adv" style="margin-top:10px"><summary>高级设置</summary>
        <div class="row" style="margin-top:8px">
          <div class="col"><label class="mut">标识${help(
            "A number this app uses to refer to the accessory; it becomes its Home Assistant device id. The relay only ever reports back by Bluetooth address, so this one is ours to pick. Leave it blank unless you want to reuse the id PetKit's app gave it.",
          )}</label><input class="ble-id" placeholder="自动选择"></div>
          <div class="col"><label class="mut">轮询间隔（秒）${help(
            'How often the relay opens a Bluetooth session to read the accessory.',
          )}</label><input class="ble-interval" value="240"></div>
          <div class="col"><label class="mut">扫描类型${help(
            'The number the relay is told to scan for. 14 is the only value anybody has read off a real pairing, and that was a W5 — the other fountains reuse it because they are the same Bluetooth family, which is a guess. If one of them never reports, this is the field to try another number in. Leave blank to use the default, and please say which value worked.',
          )}</label><input class="ble-scan-type" placeholder="默认"></div>
        </div>
      </details>
      <div style="margin-top:10px"><button class="act primary" data-action="ble-pair" data-id="${esc(d.id)}">配对配件</button></div>
    </details>
  </div>`;

  const camera = media.length
    ? `<div class="card"><h3>摄像头与媒体${help(
        'Live view comes from this app’s own go2rtc, which reads the device and republishes it as RTSP. It only pulls from the device while somebody is watching. Recordings arrive whether or not any of this is set up.',
      )}</h3>
    <p class="sub">录像片段和照片可在<a data-action="goto-tab" data-tab="timeline">时间线</a>及 Home Assistant 媒体浏览器中查看。</p>
    ${d.is_camera ? `<p><button class="act" data-action="open-live" data-id="${esc(d.id)}" data-name="${esc((d.type || '').toUpperCase() + ' · #' + d.id)}" ${d.state?.streamAvailable ? '' : 'disabled'}>查看实时画面</button>${d.state?.streamAvailable ? '' : ` <span class="mut">等待设备视频就绪${liveWaitReason(d.streamProbe)}</span>`}</p>` : ''}
    ${
      // The stream addresses live HERE and not on the Patchers tab: that card is
      // about applying and undoing a change to the firmware, and once it is
      // applied the address is just another fact about the device.
      d.streams && d.streams.rtsp
        ? `<div style="margin:6px 0 10px"><label class="mut">实时视频地址 — 供 Home Assistant 使用${help(
            'Add it as a Generic Camera. Do NOT use the device’s own address below for that: Home Assistant opens a stream with PyAV, and the device’s FLV segfaults libav and restarts the whole of HA. go2rtc stands between the two.',
          )}</label>
      <div style="display:flex;gap:6px;margin-top:4px;align-items:center"><code style="flex:1;overflow-x:auto">${esc(d.streams.rtsp)}</code><button class="mini" data-action="copy-url" data-url="${esc(d.streams.rtsp)}">复制</button></div>
      <details class="adv" style="margin-top:6px"><summary>设备直连地址（适用于 VLC、ffmpeg，不适用于 Home Assistant）</summary>
        ${Object.entries(d.streams)
          .filter(([k]) => k !== 'rtsp')
          .map(
            ([k, url]) =>
              `<div style="display:flex;gap:6px;margin-top:4px;align-items:center"><span class="mut" style="min-width:56px">${esc(k)}</span><code style="flex:1;overflow-x:auto">${esc(url)}</code><button class="mini" data-action="copy-url" data-url="${esc(url)}">复制</button></div>`,
          )
          .join('')}</details></div>`
        : `<p class="sub mut">没有实时视频流。服务每隔几分钟检查一次；项目说明设备应用<a data-action="goto-tab" data-tab="patchers">设备补丁</a>中的<b>本地摄像头视频</b>后才会提供视频流${d.state && d.state.ip ? '' : '；设备也尚未上报 IP 地址'}。补丁会修改设备，请先了解风险。</p>`
    }
    <table class="stbl"><tbody>${
      // The camera/image entities themselves are deliberately NOT listed with a
      // value: they carry no `value_path` (their bytes go to their own topic),
      // so every row read "—" and the table said nothing. What is worth showing
      // is where the media actually is.
      d.state && d.state.lastClipPath
        ? `<tr><td>最近录像</td><td colspan="2"><code>${esc(d.state.lastClipPath)}</code></td></tr>`
        : ''
    }<tr><td>设备项目</td><td colspan="2" class="mut">${media.map(e => `<code>${esc(e.key)}</code>`).join(' ')}</td></tr></tbody></table></div>`
    : '';

  const orphans = unrouted.length
    ? `<div class="card" style="border-color:var(--warn)"><h3>未在上方显示的项目</h3>
    <p class="sub">These entities are published to Home Assistant but this panel has no card for them, which means <code>ENTITY_SECTION</code> in <code>devices.js</code> is missing an entry. Please report it.</p>
    <table class="stbl"><tbody>${unrouted
      .map(
        e => `<tr><td>${esc(e.name)}</td><td>${esc(e.component)}</td><td>${esc(e.key)}</td></tr>`,
      )
      .join('')}</tbody></table></div>`
    : '';

  const deferred = d.is_feeder
    ? `<div class="card"><details class="adv" data-toggle="dev-sec" data-id="${esc(d.id)}" data-sec="deferred" ${secOpen(d.id, 'deferred', false) ? 'open' : ''}><summary>安排一次喂食</summary>
    <div id="deferred-${esc(d.id)}"><p class="sub">加载中…</p></div>
    <div style="margin-top:10px" class="row" id="deferred-form-${esc(d.id)}">
      <input type="date" data-field="date" style="flex:1">
      <input type="time" data-field="time" style="flex:1">
      <input type="number" data-field="a1" min="0" max="20" value="1" style="width:60px" placeholder="H1">
      <input type="number" data-field="a2" min="0" max="20" value="0" style="width:60px" placeholder="H2">
      <button class="act" data-action="add-deferred" data-id="${esc(d.id)}">添加</button>
    </div>
    <p class="sub" style="margin-top:4px">日期、时间、料仓 1 份数、料仓 2 份数。</p>
  </details></div>`
    : '';

  const sounds =
    d.is_camera && d.is_feeder
      ? `<div class="card"><details class="adv" data-toggle="dev-sec" data-id="${esc(d.id)}" data-sec="sounds" ${secOpen(d.id, 'sounds', false) ? 'open' : ''}><summary>自定义声音</summary>
    <div id="sounds-${esc(d.id)}"><p class="sub">加载中…</p></div>
    <div style="margin-top:10px">
      <label class="act" style="cursor:pointer">上传声音
        <input type="file" accept="audio/*" style="display:none" data-action="upload-sound" data-id="${esc(d.id)}">
      </label>
    </div>
  </details></div>`
      : '';

  const diagnostics = `<div class="card"><details class="adv" data-toggle="dev-sec" data-id="${esc(d.id)}" data-sec="diag" ${secOpen(d.id, 'diag', false) ? 'open' : ''}><summary>诊断与原始数据</summary>
    <div class="row" style="margin-top:8px">
      <div class="col"><b>最近 HTTP 状态上报</b><pre>${esc(JSON.stringify(d.diag.last_state_report || { note: 'none yet' }, null, 2))}</pre></div>
      <div class="col"><b>最近 MQTT 属性上报</b><pre>${esc(JSON.stringify(d.diag.last_property || { note: 'none yet' }, null, 2))}</pre></div>
    </div>
    <b>MQTT 连接</b><pre>${esc(JSON.stringify(d.diag.last_connect || { note: 'no MQTT connect seen — device is on HTTP heartbeat' }, null, 2))}</pre>
  </details></div>

  <div class="card"><details class="adv" data-toggle="dev-sec" data-id="${esc(d.id)}" data-sec="danger" ${secOpen(d.id, 'danger', false) ? 'open' : ''}><summary>移除设备</summary>
    <p class="sub">从设备列表永久删除此设备并移除其 Home Assistant 项目；事件和媒体文件会保留。</p>
    <button class="act danger" data-action="delete-device" data-id="${esc(d.id)}" data-name="${esc((d.type || '').toUpperCase() + ' ' + (d.serial_number || d.id))}">删除设备</button>
  </details></div>

  <div class="card"><details class="adv" data-toggle="dev-sec" data-id="${esc(d.id)}" data-sec="raw" ${secOpen(d.id, 'raw', false) ? 'open' : ''}><summary>高级：发送原始命令</summary>
    <p class="sub">原样发送，不会自动补充字段。固件根据 <code>method</code> 分发命令，缺少该字段时不会执行。有 MQTT 会话时经 MQTT 发送，否则等待心跳。</p>
    <textarea class="cmd-raw" rows="3" spellcheck="false"></textarea>
    <div style="margin-top:8px"><button class="act" data-action="send-raw" data-id="${esc(d.id)}">发送原始命令</button></div>
    <p class="sub" style="margin-top:10px">示例 — 点击后载入：</p>
    <div>${RAW_EXAMPLES.map(
      x =>
        `<button class="act mini-ex" data-action="load-raw-example" data-body="${esc(JSON.stringify(x.body))}">${esc(x.label)}</button>`,
    ).join('')}</div>
  </details></div>`;

  const panes = {
    overview: {
      label: '概览',
      render: () => unknownType + overviewPane(d, (ACTIVITY.get(d.id) || {}).sessions),
    },
  };
  if (controls.length || capabilities || timezone || logs)
    panes.settings = {
      label: '设置',
      render: () =>
        `<div class="sgroups">${[...settingsGroups(d, controls), capabilities, timezone, logs].join(
          '',
        )}</div>${sounds}`,
    };
  if (schedules.length || d.is_feeder)
    panes.schedules = {
      label: '计划',
      render: () => renderSection('schedules', d, schedules) + deferred,
    };
  if (camera) panes.camera = { label: '摄像头', render: () => camera };
  panes.accessories = { label: '配件', render: () => accessories };
  panes.diagnostics = {
    label: '诊断',
    render: () => info + state + orphans + diagnostics,
  };
  return panes;
}

//: Worked examples for the raw-command box. They were a `placeholder` before,
//: which vanishes the moment you type and cannot be got back — so the one
//: reference for the envelope shape disappeared exactly when it was needed.
//: Every one of these is a shape this add-on really sends (see ha/commands.py).
const RAW_EXAMPLES = [
  {
    label: 'Change a setting',
    body: {
      suffix: 'property/set',
      payload: {
        method: 'thing.service.property.set',
        id: '1',
        version: '1.0.0',
        params: { autoWork: 1 },
      },
    },
  },
  {
    label: 'Start an action',
    body: {
      suffix: 'start',
      payload: {
        method: 'thing.service.start',
        id: '1',
        version: '1.0.0',
        params: { start_action: 0 },
      },
    },
  },
  {
    label: 'Reset the N60 deodorant',
    body: {
      suffix: 'start',
      payload: {
        method: 'thing.service.start',
        id: '1',
        version: '1.0.0',
        params: { start_action: 10 },
      },
    },
  },
];

onAction('load-raw-example', el => {
  const box = el.closest('details');
  const ta = box && box.querySelector('.cmd-raw');
  if (!ta) return;
  try {
    ta.value = JSON.stringify(JSON.parse(el.dataset.body), null, 2);
  } catch (_) {
    return;
  }
  ta.focus();
});

onChange('show-all-entities', el => {
  const id = Number(el.dataset.id);
  if (el.checked) SHOW_ALL.add(id);
  else SHOW_ALL.delete(id);
  paintPanelBody(id, true);
});

// Settings the AI card owns. They are ordinary entities — the card just gives
// them a home next to the recognition toggle they belong with, the way
// capability switches get their own card.
//
// The three detections are what the NPU is asked to look for, not settings of
// the machine around it: each one decides whether a class of event is raised at
// all. `pet_detection` gates `pet_detect` and the `pet_time` stamp,
// `drink_detection` gates `drink_start` and `drink_time`, and
// `vomit_detection` fills the `vomit_info` array a `pet_discern` result
// carries. Sitting in Controls they read like Auto Flush and Heater, which are
// plumbing.
//
// `relocate` GUARDS ON supports_ai, and must. `pet_detection` also belongs to
// the feeders (D4H/D4SH), which have a camera but no on-device AI and so get no
// AI card — moving it unconditionally took the switch out of Controls and gave
// it nowhere to go, removing it from the panel entirely.
const AI_ENTITY_KEYS = [
  'yowling_detection',
  'ph_detection',
  'pet_detection',
  'drink_detection',
  'vomit_detection',
];

let _refreshT = null,
  _detailT = null;
//: Device ids with a refresh pending. One shared timer coalesces a burst of
//: WebSocket messages into a single round of fetches, and only expanded panels
//: on the visible tab actually cost anything.
const DIRTY = new Set();
function scheduleRefresh() {
  clearTimeout(_refreshT);
  _refreshT = setTimeout(loadDevices, 300);
}
function scheduleDetail(id) {
  if (id == null) DEVICES.forEach(d => DIRTY.add(d.id));
  else DIRTY.add(id);
  clearTimeout(_detailT);
  _detailT = setTimeout(flushDetail, 350);
}
async function flushDetail() {
  const tab = document.getElementById('tab-devices');
  if (!tab || tab.classList.contains('hidden')) {
    DIRTY.clear(); // nobody is looking; loadDevices() repaints on the way back
    return;
  }
  const ids = [...DIRTY];
  DIRTY.clear();
  await Promise.all(ids.map(refreshDevice));
}
async function refreshDevice(id) {
  const selected = SELECTED === 'd' + id;
  if (SELECTED && !selected) return; // another device's page: it costs nothing
  const [d] = await Promise.all([fetchDeviceDetail(id), selected ? fetchActivity(id) : null]);
  if (d.error) return;
  DEV_DETAIL.set(id, d);
  if (selected) {
    setToolbar(d.name, deviceSubtitle(d), toolbarActions(d));
    paintPanelBody(id);
  } else {
    const tile = document.querySelector(`.dev-tile[data-key="d${CSS.escape(String(id))}"]`);
    if (tile) tile.outerHTML = deviceTile(d, 'd' + id, d);
  }
}

export {
  DEV_DETAIL,
  devPanel,
  selectDevice,
  ENTITY_SECTION,
  AI_ENTITY_KEYS,
  loadDevices,
  fetchDeviceDetail,
  facesOnDevice,
  scheduleRefresh,
  scheduleDetail,
  liveWaitReason,
};
