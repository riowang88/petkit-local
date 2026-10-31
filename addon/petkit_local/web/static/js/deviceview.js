import { esc, relTime } from './core.js';
import { controlRow } from './entities.js';
import { fmtEntityValue } from './format.js';
import { thumbUrl } from './media.js';
import { entityName, eventLabel } from './zh.js';

// What a device page SAYS, as opposed to how devices.js fetches and repaints it.
// Everything here is a pure function of the detail payload.
//
// The tables below sort a device's entities by what a person is doing when they
// reach for them, and every one of them falls through to a visible default. An
// action or setting nobody listed still renders: it lands in the "More" menu or
// the "Other" group, so a new entity can be unfiled but never unreachable.

// ---------------- glyphs ----------------
const GLYPHS = {
  grid: '<rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/>',
  litter:
    '<path d="M4 15a8 8 0 0 1 16 0"/><path d="M3 15h18v2.5a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5z"/><circle cx="12" cy="12" r="2.2"/>',
  feeder:
    '<rect x="6" y="3.5" width="12" height="11" rx="3"/><path d="M4 15h16l-1.6 3.6A2.5 2.5 0 0 1 16.1 20H7.9a2.5 2.5 0 0 1-2.3-1.4z"/><circle cx="12" cy="9" r="2"/>',
  fountain:
    '<path d="M12 3.5s5.5 5.6 5.5 10a5.5 5.5 0 0 1-11 0c0-4.4 5.5-10 5.5-10z"/><path d="M9.5 14a2.5 2.5 0 0 0 2.5 2.5"/>',
  purifier:
    '<rect x="6.5" y="3.5" width="11" height="17" rx="3"/><path d="M9.5 8.5h5M9.5 12h5M9.5 15.5h5"/>',
  sparkle:
    '<path d="M12 4l1.8 5.2L19 11l-5.2 1.8L12 18l-1.8-5.2L5 11l5.2-1.8z"/><path d="M18.5 3.5v3M17 5h3"/>',
  wind: '<path d="M4 9h10a2.5 2.5 0 1 0-2.5-2.5"/><path d="M4 13h14a2.5 2.5 0 1 1-2.5 2.5"/><path d="M4 17h6"/>',
  level: '<path d="M4 8h16M4 12h16M4 16h16"/>',
  bulb: '<path d="M9 17.5h6M10 20.5h4"/><path d="M12 3.5a6 6 0 0 0-3.6 10.8c.4.3.6.8.6 1.2v2h6v-2c0-.4.2-.9.6-1.2A6 6 0 0 0 12 3.5z"/>',
  pause: '<path d="M9 5.5v13M15 5.5v13"/>',
  play: '<path d="M8 5l11 7-11 7z"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
  bowl: '<path d="M3.5 11h17a8.5 8.5 0 0 1-17 0z"/><path d="M8 7.5c0-1.2 1-1.2 1-2.5M12 7.5c0-1.2 1-1.2 1-2.5M16 7.5c0-1.2 1-1.2 1-2.5"/>',
  speaker: '<path d="M5 9.5v5h3.5L13 18.5v-13L8.5 9.5z"/><path d="M16 9a4 4 0 0 1 0 6"/>',
  video:
    '<rect x="3.5" y="6" width="12.5" height="12" rx="2.5"/><path d="M16 10.5l4.5-2.5v8L16 13.5z"/>',
  bag: '<path d="M6.5 8.5h11l1.2 11.5H5.3z"/><path d="M9 8.5V7a3 3 0 0 1 6 0v1.5"/>',
  door: '<rect x="6" y="3.5" width="12" height="17" rx="1.5"/><path d="M14.5 12h.5"/>',
  left: '<path d="M14.5 6l-6 6 6 6"/>',
  right: '<path d="M9.5 6l6 6-6 6"/>',
  flip: '<path d="M5 9a7.5 7.5 0 0 1 13-2l1.5 2M19 15a7.5 7.5 0 0 1-13 2l-1.5-2"/><path d="M19.5 4.5V9H15M4.5 19.5V15H9"/>',
  more: '<circle cx="6" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="18" cy="12" r="1.3"/>',
  dot: '<circle cx="12" cy="12" r="3"/>',
};
const glyph = name =>
  `<svg class="gl" viewBox="0 0 24 24" aria-hidden="true">${GLYPHS[name] || GLYPHS.dot}</svg>`;

// Which kind of machine this is, for its picture. A BLE accessory arrives with
// `ble_type`, a network device with `type`; both are PetKit's codename.
function deviceKind(d) {
  if (d.is_feeder) return 'feeder';
  const t = String(d.type || d.ble_type || '').toLowerCase();
  if (t.startsWith('t')) return 'litter';
  if (t.startsWith('k')) return 'purifier';
  if (t.startsWith('w') || t.startsWith('ctw')) return 'fountain';
  return 'dot';
}

// ---------------- reading entities ----------------
// A device from the list carries `entities` as a COUNT; only the detail payload
// has the array, and a tile can be drawn from either.
const entity = (d, key) => (Array.isArray(d.entities) ? d.entities.find(e => e.key === key) : null);
const hasValue = e => e && e.value !== null && e.value !== undefined && e.value !== '';
// fmtEntityValue returns markup with a dimmed unit; the same text, unstyled, is
// what a large number or an aria-label wants.
const plain = e => fmtEntityValue(e).replace(/<[^>]+>/g, '');
const when = v => {
  const t = typeof v === 'number' ? v : Date.parse(v) / 1000;
  return Number.isFinite(t) ? relTime(t) : String(v);
};

// `error` has no agreed "nothing wrong" spelling across the families, so every
// empty-looking one counts as healthy rather than raising a red banner.
const NO_ERROR = new Set(['', '0', 'none', 'no_error', 'normal', 'ok']);
function deviceFault(d) {
  const e = entity(d, 'error');
  if (!hasValue(e) || NO_ERROR.has(String(e.value).toLowerCase())) return null;
  return plain(e);
}

// The one word a device page leads with. A fault outranks the work state, which
// outranks plain presence, and offline outranks all of them: whatever the last
// report said, it is no longer known to be true.
function deviceStatus(d) {
  if (!d.online) return { text: '离线', tone: 'off' };
  const fault = deviceFault(d);
  if (fault) return { text: fault, tone: 'bad' };
  const st = entity(d, 'device_status');
  // -1 is what a box reports when no work is running: not one of the mapped
  // work modes, and printing it would put a bare "-1" in the page's largest type.
  if (hasValue(st) && Number(st.value) !== -1) return { text: plain(st), tone: 'ok' };
  return { text: st ? '待机' : '在线', tone: 'ok' };
}

//: Conditions that need a person, phrased as what to do about them. Worded here
//: rather than read off the entity name, because "Food Low: yes" states a fact
//: and "Refill the food" says why it is on the page.
const ALERTS = {
  waste_bin: '集便仓已满，请清空',
  food_low: '余粮不足，请添加粮食',
  water_lack: '水箱缺水，请加水',
  replace_filter: '请更换滤芯',
  low_battery: '电量低，请充电',
};
const deviceAlerts = d =>
  Object.keys(ALERTS).filter(k => {
    const e = entity(d, k);
    return hasValue(e) && e.value !== 0 && e.value !== '0' && e.value !== false;
  });

//: The figures under the status word, in reading order. A device shows only the
//: ones it has actually reported, so each family gets its own short list from
//: one table.
const HERO_STATS = [
  ['last_visit', '上次如厕', 'when'],
  ['last_clean', '上次清理', 'when'],
  ['last_feed', '上次喂食', 'when'],
  ['used_times', '今日使用'],
  ['pet_weight', '宠物体重', 'kg'],
  ['times_dispensed', '今日出粮'],
  ['amount_eaten', '今日进食'],
  ['drink_times', '今日饮水'],
  ['temperature', '水温'],
  ['battery', '电量'],
];
function heroStats(d) {
  return HERO_STATS.map(([key, label, fmt]) => {
    const e = entity(d, key);
    if (!hasValue(e)) return '';
    const v =
      fmt === 'when'
        ? when(e.value)
        : fmt === 'kg'
          ? (Number(e.value) / 1000).toFixed(2) + ' kg'
          : plain(e);
    return `<div><dt>${esc(label)}</dt><dd>${esc(v)}</dd></div>`;
  })
    .filter(Boolean)
    .slice(0, 4)
    .join('');
}

// ---------------- actions ----------------
//: What a person came to the device to do. Big tiles, with a picture.
const ACTION_TILES = {
  cleaning_start: 'sparkle',
  deodorize: 'wind',
  level_litter: 'level',
  pack_waste: 'bag',
  open_sealed_door: 'door',
  light: 'bulb',
  feed: 'bowl',
  feed_hopper_1: 'bowl',
  feed_hopper_2: 'bowl',
  play_sound: 'speaker',
  fountain_flush: 'fountain',
  fountain_refill: 'fountain',
  fountain_water_change: 'level',
};
//: Stopping what is running. Kept together and always visible: it is tempting to
//: show them only while the device works, but a state report can lag the motor,
//: and "the stop button is not there yet" is the worst moment to learn that.
const RUN_CONTROLS = {
  pause: 'pause',
  pause_fountain: 'pause',
  resume: 'play',
  resume_fountain: 'play',
  reset: 'stop',
  cancel_manual_feed: 'stop',
};
//: The T6's camera head. Aimed while looking at the picture, so it stays on the
//: page rather than in the More menu.
const CAMERA_CONTROLS = { ptz_left: 'left', ptz_right: 'right', ptz_flip: 'flip' };
//: Actions that belong to one consumable, drawn on that consumable's row: "reset
//: N50" means nothing in a row of buttons and is obvious next to "N50: 3 days".
const CONSUMABLE_ACTIONS = {
  reset_n50: 'n50_durability',
  reset_n60: 'n60_spray_days',
  reset_desiccant: 'desiccant_days',
  reset_filter: 'filter_percent',
  reset_bagbox: 'package_count',
  food_replenished: 'hopper_level',
};

function actionAttrs(d, a) {
  const name = entityName(a.key, a.name);
  return `data-action="send-action" data-id="${esc(d.id)}" data-key="${esc(a.key)}" data-destructive="${a.destructive ? 1 : 0}" data-name="${esc(name)}"`;
}

// The row a consumable's action lands on, or null when the device has no such
// row. An entity is enough: a cartridge that has not reported yet must still be
// resettable, and from where it would otherwise be looked for.
function actionRow(d, key) {
  const row = CONSUMABLE_ACTIONS[key];
  if (!row) return null;
  if (entity(d, row)) return row;
  return key === 'reset_filter' && entity(d, 'filter_days') ? 'filter_days' : null;
}

function splitActions(d) {
  const tiles = [],
    run = [],
    camera = [],
    more = [];
  for (const a of d.actions || []) {
    if (actionRow(d, a.key)) continue;
    if (CAMERA_CONTROLS[a.key]) camera.push(a);
    else if (RUN_CONTROLS[a.key]) run.push(a);
    else if (ACTION_TILES[a.key] && !a.destructive) tiles.push(a);
    else more.push(a);
  }
  return { tiles, run, camera, more };
}

// Where a press goes. Stated on the page rather than tucked behind a `?`,
// because it changes what pressing means: MQTT is now, a queue is "if it comes
// back".
function transportNote(d) {
  if (!d.online) return '设备离线，命令会排队，重新上线后才执行';
  return d.mqtt_connected ? '命令通过 MQTT 立即送达' : '命令在下次心跳时送达，通常约 10 秒';
}

function moreMenu(d, more) {
  if (!more.length) return '';
  const safe = more.filter(a => !a.destructive),
    risky = more.filter(a => a.destructive);
  const item = a =>
    `<button class="menu-item${a.destructive ? ' danger' : ''}" role="menuitem" ${actionAttrs(d, a)}>${esc(entityName(a.key, a.name))}</button>`;
  return `<details class="menu"><summary class="act" aria-label="更多操作">${glyph('more')}</summary>
    <div class="menu-pop" role="menu">${safe.map(item).join('')}${
      safe.length && risky.length ? '<div class="menu-sep"></div>' : ''
    }${risky.map(item).join('')}</div></details>`;
}

function actionsBlock(d) {
  const { tiles, run, camera, more } = splitActions(d);
  if (!tiles.length && !run.length && !camera.length && !more.length) return '';
  const group = (label, list, icons) =>
    list.length
      ? `<div class="run-ctl" role="group" aria-label="${esc(label)}">${list
          .map(
            a =>
              `<button ${actionAttrs(d, a)}>${glyph(icons[a.key])}<span>${esc(entityName(a.key, a.name))}</span></button>`,
          )
          .join('')}</div>`
      : '';
  return `<section class="sgroup">
    <div class="sgroup-head"><h3>操作</h3><span class="grow"></span>${moreMenu(d, more)}</div>
    ${
      tiles.length
        ? `<div class="tiles-act">${tiles
            .map(
              a =>
                `<button class="tile-act" ${actionAttrs(d, a)}>${glyph(ACTION_TILES[a.key])}<span>${esc(entityName(a.key, a.name))}</span></button>`,
            )
            .join('')}</div>`
        : ''
    }
    <div class="run-row">
      ${group('运行控制', run, RUN_CONTROLS)}
      ${group('摄像头方向', camera, CAMERA_CONTROLS)}
      <span class="cmd-out mut">${esc(transportNote(d))}</span>
    </div>
  </section>`;
}

// ---------------- consumables ----------------
//: Reported levels. Only a real percentage draws a bar: a PetKit "days left" has
//: no maximum in its payload, and a bar scaled to a guessed one would be a
//: measurement this add-on does not have.
const CONSUMABLES = [
  ['litter_percent', '猫砂余量'],
  ['filter_percent', '滤芯寿命'],
  ['filter_days', '滤芯剩余'],
  ['n50_durability', 'N50 除臭片'],
  ['n60_spray_days', 'N60 除臭液'],
  ['desiccant_days', '干燥剂'],
  ['hopper_level', '粮仓'],
  ['package_count', '剩余垃圾袋'],
  ['battery', '电量'],
];
const LOW_PERCENT = 20;

function consumableRow(d, key, label) {
  const e = entity(d, key);
  if (!e) return '';
  const action = (d.actions || []).find(a => actionRow(d, a.key) === key);
  if (!hasValue(e) && !action) return '';
  const pct = e.unit === '%' && hasValue(e) ? Math.max(0, Math.min(100, Number(e.value))) : null;
  return `<div class="supply">
    <div class="supply-line"><span>${esc(label)}</span><b>${esc(plain(e))}</b>${
      action
        ? `<button class="link" ${actionAttrs(d, action)}>${action.key === 'food_replenished' ? '已加粮' : '重置'}</button>`
        : ''
    }</div>
    ${pct === null || Number.isNaN(pct) ? '' : `<div class="meter${pct <= LOW_PERCENT ? ' low' : ''}"><i style="width:${pct}%"></i></div>`}
  </div>`;
}
function suppliesBlock(d) {
  const rows = CONSUMABLES.map(([k, l]) => consumableRow(d, k, l)).filter(Boolean);
  return rows.length
    ? `<section class="sgroup"><h3>耗材</h3><div class="sbox supplies">${rows.join('')}</div></section>`
    : '';
}

// ---------------- activity ----------------
//: Same words as the Timeline's kind pills, so the two pages agree.
const ACTIVITY_KIND = {
  toilet_visit: '如厕',
  pet_appeared: '宠物出现',
  cleaning: '清理',
  feeding: '喂食',
  drinking: '饮水',
};
function activityText(s) {
  if (s.kind === 'visit') {
    const bits = ['如厕'];
    if (s.duration_sec != null) bits.push(`${Math.round(s.duration_sec)} 秒`);
    if (s.weight != null) bits.push(`${(s.weight / 1000).toFixed(2)} kg`);
    return bits.join('，');
  }
  return eventLabel(s.label || ACTIVITY_KIND[s.event_kind] || s.event_kind || '事件');
}
function activityRow(s, showDevice) {
  const t = new Date((s.display_ts ?? s.ts) * 1000);
  const hhmm = t.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return `<li><time>${esc(hhmm)}</time><span class="act-text">${s.pet_name ? `<b>${esc(s.pet_name)}</b> ` : ''}${esc(activityText(s))}</span>${
    showDevice ? `<span class="mut act-dev">${esc(s.device_name || '')}</span>` : ''
  }</li>`;
}
function activityList(sessions, showDevice) {
  if (!sessions || !sessions.length) return '<p class="mut act-empty">今天还没有活动。</p>';
  return `<ol class="activity">${sessions
    .slice(0, 6)
    .map(s => activityRow(s, showDevice))
    .join('')}</ol>`;
}
function activityBlock(sessions) {
  return `<section class="sgroup">
    <div class="sgroup-head"><h3>今天</h3><span class="grow"></span><a data-action="goto-tab" data-tab="timeline">查看时间线</a></div>
    <div class="sbox">${activityList(sessions, false)}</div>
  </section>`;
}

// The newest picture this device has taken of anything, if it has taken one:
// for a camera model that says more about "how is it" than any glyph.
function latestPicture(sessions) {
  for (const s of sessions || []) {
    const u = s.media && (s.media.snapshot_url || s.media.highlight_url);
    if (u) return thumbUrl(u);
  }
  return null;
}

// ---------------- the overview pane ----------------
function heroBlock(d, sessions) {
  const st = deviceStatus(d);
  const pic = latestPicture(sessions);
  return `<section class="hero">
    <div class="hero-pic hero-${esc(deviceKind(d))}">${
      pic ? `<img class="hide-on-error" src="${esc(pic)}" alt="">` : glyph(deviceKind(d))
    }</div>
    <div class="hero-text">
      <div class="hero-state ${esc(st.tone)}">${esc(st.text)}</div>
      <dl class="hero-stats">${heroStats(d) || '<div><dd class="mut">设备还没有上报数据</dd></div>'}</dl>
      ${
        d.is_camera && !d.state?.streamAvailable
          ? '<p class="sub hero-note">设备没有提供本地视频流，实时画面需要先应用<a data-action="goto-tab" data-tab="patchers">设备补丁</a>中的“本地摄像头视频”。</p>'
          : ''
      }
    </div>
  </section>`;
}

function alertsBlock(d) {
  const keys = deviceAlerts(d);
  return keys.length
    ? `<div class="alerts">${keys.map(k => `<div class="alert">${esc(ALERTS[k])}</div>`).join('')}</div>`
    : '';
}

function overviewPane(d, sessions) {
  return `${alertsBlock(d)}<div class="ov-grid">
    <div class="ov-main">${heroBlock(d, sessions)}${actionsBlock(d)}</div>
    <div class="ov-side">${suppliesBlock(d)}${activityBlock(sessions)}</div>
  </div>`;
}

// ---------------- the settings pane ----------------
//: Settings by what they are about. HA's own config/diagnostic split answers a
//: different question, and a single list of 35 switches answers none.
const SETTING_GROUPS = [
  [
    '清理',
    'auto_work avoid_repeat cleaning_delay cleaning_interval deep_clean kitten underweight bury downpos sand_saving fixed_time_clear sand_type',
  ],
  ['除臭', 'auto_spray fixed_time_spray deep_spray'],
  ['喂食', 'food_warn surplus_level feed_sound voice_dispense feed_picture eat_video'],
  [
    '饮水',
    'flow_mode auto_refill auto_flush auto_water_change heater fountain_time sleep_time water_change_cycle flush_cycle',
  ],
  [
    '摄像头',
    'camera camera_upload microphone micro_light night_vision smart_frame video_timestamp time_display',
  ],
  ['隐私', 'home_mode camera_inward camera_off no_sound'],
  [
    '检测',
    'pet_detection wander_detection toilet_detection move_detection eat_detection drink_detection vomit_detection yowling_detection ph_detection',
  ],
  [
    '灯光',
    'light_assist toilet_light camera_light indicator_light display clean_water_lack_light clean_water_empty_light waste_water_full_light wifi_light_assist',
  ],
  ['声音', 'voice_prompt voice_language voice_disturb_mode volume selected_sound'],
  ['通用', 'child_lock click_ok disturb_mode refill_disturb_mode water_level_disturb_mode'],
].map(([name, keys]) => [name, keys.split(' ')]);

function settingsGroups(d, controls) {
  const filed = new Set(SETTING_GROUPS.flatMap(([, keys]) => keys));
  const groups = SETTING_GROUPS.map(([name, keys]) => [
    name,
    keys.map(k => controls.find(e => e.key === k)).filter(Boolean),
  ]);
  groups.push(['其他', controls.filter(e => !filed.has(e.key))]);
  return groups
    .filter(([, rows]) => rows.length)
    .map(
      ([name, rows]) =>
        `<section class="sgroup"><h3>${esc(name)}</h3><div class="sbox">${rows
          .map(e => controlRow(d.id, e))
          .join('')}</div></section>`,
    );
}

// ---------------- the overview page ----------------
function deviceTile(d, key, detail) {
  const st = deviceStatus(detail || d);
  const supplies = detail
    ? CONSUMABLES.map(([k, l]) => consumableRow({ ...detail, actions: [] }, k, l))
        .filter(Boolean)
        .slice(0, 2)
        .join('')
    : '';
  const first = detail ? splitActions(detail).tiles[0] : null;
  return `<article class="dev-tile" data-action="open-device" data-key="${esc(key)}">
    <div class="dev-tile-top">
      <span class="dev-gl">${glyph(deviceKind(d))}</span>
      ${
        first && d.online
          ? `<button class="act" ${actionAttrs(detail, first)}>${esc(entityName(first.key, first.name))}</button>`
          : ''
      }
    </div>
    <button class="dev-tile-name">${esc(d.name)}</button>
    <div class="dev-tile-state ${esc(st.tone)}">${esc(st.text)}</div>
    <div class="supplies">${supplies}</div>
  </article>`;
}

export { glyph, deviceKind, deviceStatus, deviceTile, overviewPane, settingsGroups, activityList };
