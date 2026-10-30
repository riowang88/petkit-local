import { api, esc, toast } from './core.js';
import { onAction, onChange } from './delegate.js';
import { help } from './help.js';

// ---------------- Setup ----------------
// Categories with BOTH a size and an age cap, in display order. This is the
// single source for the retention table and for saveRetention — they were two
// hardcoded lists, so a category added to one rendered an input that silently
// never saved.
//
// `rawUpload` ("Staged uploads") is deliberately NOT here. Staged files are
// deleted twice over without anyone asking: inline by the media pipeline the
// moment an upload is filed, and by the sweeper for orphans whose metadata
// never arrived — with a code-level one-hour floor a panel edit cannot lower.
// It was a setting for a failure mode nobody can observe, over a dot-directory
// no screen in this app can browse. The server-side default stays.
//
// `wasteCheck` and `healthPic` ARE here now: both are photo galleries the
// Timeline renders, so they are the categories most likely to be the ones
// filling a disk, and they had no control at all while two internal caches did.
const RETENTION_LABELS = {
  fullVideo: '录像',
  eventImage: '照片',
  highLight: '精彩片段',
  dynamicVideo: '动态片段',
  cloudDouble: '延时录像',
  wasteCheck: '排泄物照片',
  healthPic: '健康照片',
  deviceLog: '设备日志',
  thumbnail: '缩略图缓存',
};
function numOrNull(id) {
  const v = document.getElementById(id).value;
  return v === '' ? null : Number(v);
}
onAction('save-retention', () => saveRetention());
async function saveRetention() {
  const body = {};
  for (const k of Object.keys(RETENTION_LABELS))
    body[k] = { max_mb: numOrNull('ret_' + k + '_mb'), max_days: numOrNull('ret_' + k + '_days') };
  // Age-only categories: rows of their own rather than RETENTION_LABELS
  // entries, which carry a size cap these two do not have.
  body.events = { max_days: numOrNull('ret_events_days') };
  body.blocked = { max_days: numOrNull('ret_blocked_days') };
  const r = await api('retention', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  toast(r.ok ? '保留设置已保存' : '错误：' + (r.error || 'failed'));
}

// The proxy guards stay VISIBLE but disabled while proxy mode is off: hiding
// "RCE guard is on" would be worse than showing it inactive. The upstream
// picker and the blocked-attempts table are hidden outright — they say nothing
// at all when nothing is being forwarded.
const CUSTOM_UPSTREAM = '__custom__';

async function loadSetup() {
  const i = await api('info');
  // No `api('devices')` here: this tab used to repeat the Devices tab's own
  // table verbatim from the same array. `api('info')` already carries the
  // device count, which is the only thing Setup actually needs to say.
  const ret = await api('retention');
  const v = document.getElementById('setupView');
  const s = i.settings || {};
  const w = i.settings_writable;
  const rd = ret.retention || {};
  const on = s.proxy_mode;
  const sw = (key, val, enabled) =>
    `<span class="sw"><input type="checkbox" ${val ? 'checked' : ''} ${w && enabled !== false ? '' : 'disabled'} data-change="save-setting" data-key="${esc(key)}"><span class="sl"></span></span>`;
  // A guard row that greys out with proxy mode, rather than disappearing.
  const guard = (key, val, title, hint) =>
    `<label class="ctrl${on ? '' : ' disabled'}"><span>${title}<br><span class="mut" style="font-size:11px">${hint}</span></span>${sw(key, val, on)}</label>`;

  // An empty setting means the server's default, which it flags for us — the
  // picker must not have to guess which key that is.
  const presets = i.upstreams || [];
  const fallback = (presets.find(u => u.default) || presets[0] || {}).key || '';
  const current = s.proxy_upstream || fallback;
  const sel = presets.some(u => u.key === current) ? current : CUSTOM_UPSTREAM;

  v.innerHTML = `
  <div class="card"><h3>设置${help(
    '代理模式和流量记录只在此处配置，修改立即生效并在重启后保留。',
  )}${w ? '' : ' <span class="badge warn">只读 — 当前不在 Home Assistant 内运行</span>'}</h3>
    <div class="ctrls">
      <label class="ctrl"><span>代理模式<br><span class="mut" style="font-size:11px">将设备请求转发到 PETKIT 云端，并使用云端回复</span></span>${sw('proxy_mode', s.proxy_mode)}</label>
      <label class="ctrl"><span>流量记录<br><span class="mut" style="font-size:11px">将 HTTP 和 MQTT 记录为 JSONL（见流量记录栏目）</span></span>${sw('capture', s.capture)}</label>
    </div>
    ${
      on
        ? `<div style="margin-top:14px"><label class="mut">上游服务器</label>
      <span class="cn" style="display:flex;gap:6px;margin-top:4px;flex-wrap:wrap">
        <select id="proxyUpSel" ${w ? '' : 'disabled'} data-change="save-upstream">
          ${presets.map(u => `<option value="${esc(u.key)}" ${sel === u.key ? 'selected' : ''}>${esc(u.key)} — ${esc(u.url)}</option>`).join('')}
          <option value="${CUSTOM_UPSTREAM}" ${sel === CUSTOM_UPSTREAM ? 'selected' : ''}>自定义…</option>
        </select>
      </span>
      ${
        sel === CUSTOM_UPSTREAM
          ? `<span class="cn" style="display:flex;gap:6px;margin-top:6px">
        <input id="proxyUp" value="${esc(s.proxy_upstream)}" ${w ? '' : 'disabled'} placeholder="https://api-eu.petkt.com/6/">
        <button class="mini" ${w ? '' : 'disabled'} data-action="save-proxy-upstream">设置</button></span>`
          : ''
      }
      <label class="mut" style="display:block;margin-top:10px">上游查询 DNS${help(
        'Only used to find the server above. Leave empty unless your router or Pi-hole points PetKit’s names at this app — then it would point them here for us too, and proxy mode would reach itself instead of the cloud.',
      )}</label>
      <span class="cn" style="display:flex;gap:6px;margin-top:4px">
        <input id="proxyDns" value="${esc(s.proxy_dns || '')}" ${w ? '' : 'disabled'} placeholder="empty = system resolver">
        <button class="mini" ${w ? '' : 'disabled'} data-action="save-proxy-dns">设置</button></span>
    </div>`
        : ''
    }
    <p class="sub" style="margin-top:14px">${on ? '从云端回复中移除：' : '代理模式防护：开启代理后才会生效。'}</p>
    <div class="ctrls">
      ${guard('proxy_block_run_cmd', 'proxy_block_run_cmd' in s ? s.proxy_block_run_cmd : true, '阻止 run_cmd <span class="badge warn">远程命令防护</span>', '移除云端尝试在设备上运行的 shell 命令')}
      ${guard('proxy_block_ota', 'proxy_block_ota' in s ? s.proxy_block_ota : true, '阻止 OTA 推送 <span class="badge warn">固件防护</span>', '在本地回应 OTA 接口，并丢弃其他位置发现的固件镜像')}
      ${guard('proxy_block_log_upload', 'proxy_block_log_upload' in s ? s.proxy_block_log_upload : true, '阻止日志上传 <span class="badge warn">隐私防护</span>', '不向设备提供上传调试日志到 PETKIT 所需的令牌')}
      ${guard('proxy_mqtt_bridge', 'proxy_mqtt_bridge' in s ? s.proxy_mqtt_bridge : true, '上游 MQTT 桥接', '同时将设备 MQTT 会话桥接至真实阿里云服务器')}
      ${guard('proxy_media_real_oss', !!s.proxy_media_real_oss, '媒体 → 原厂 OSS', '让设备将录像上传到 PETKIT，而不是保存到本地')}
      ${guard('proxy_local_cvr_window', !!s.proxy_local_cvr_window, '继续在本地录像', '忽略云端订阅时段并告知摄像头存储可用；否则订阅到期可能导致录像停止且不显示错误')}
    </div>
    ${on ? upstreamStatus(i.upstream || {}) : ''}
    ${on ? `<div id="blockedView" style="margin-top:14px"></div>` : ''}
  </div>
  <div class="card"><h3>连接 <span class="badge">修改后需重启</span></h3>
    <p class="sub">这些项目在启动时确定；请在<b>配置</b>中设置后重启。</p>
    <table><tbody>
      <tr><td>版本</td><td><code>${esc(i.version || '?')}</code></td><td class="mut">当前运行的代码版本。若更新未生效，此处仍会显示旧版本。</td></tr>
      <tr><td>API URL (apiServers)</td><td><code>${esc(i.api_url)}</code></td><td class="mut">设备访问的服务地址。多数型号通过蓝牙配网写入；不支持蓝牙配网的旧型号需要 DNS 重定向。</td></tr>
      <tr><td>MQTT 主机</td><td><span class="badge ok">自动</span></td><td class="mut">没有全局设置：设备使用根据 API 地址推导出的本地 MQTT 服务。修改过的 <code>ctrl</code> 可通过 MQTT 连接；未修改的设备使用 HTTP 心跳。MQTT 未连接时命令通过 HTTP 心跳排队。</td></tr>
      <tr><td>MQTT 服务端口</td><td><code>${esc(i.mqtt_port)}</code>${i.mqtt_tls ? ` · TLS <code>${esc(i.mqtt_tls_port)}</code>` : ''}</td><td class="mut">TLS 证书 ${i.cert_exists ? '<span class="badge ok">存在</span>' : '<span class="badge bad">缺失</span>'}${help('The self-signed certificate the broker presents. "missing" means the file named by cert_path is not there, and a device that expects TLS will not connect.')}</td></tr>
      <tr><td>MQTT 认证</td><td>${i.strict_auth ? '<span class="badge warn">严格 HMAC</span>' : '<span class="badge">接受所有连接</span>'}</td><td class="mut">接受所有连接仅适合可信局域网；严格模式校验阿里云 HMAC-SHA256 签名。</td></tr>
      <tr><td>桥接到 HA</td><td>${
        !i.ha_enabled
          ? '<span class="badge">已禁用</span>'
          : i.ha_publishing
            ? '<span class="badge ok">发布中</span>'
            : '<span class="badge bad">未连接</span>'
      }</td><td class="mut">当前是否正在将设备项目发布到 Home Assistant。${help('This is the connection to HOME ASSISTANT\'s broker, not the one your devices talk to — a device can be perfectly healthy while this is down, and vice versa. "disabled" means publishing was switched off (no ha_mqtt_host, or --no-ha), which is a valid way to run.')}</td></tr>
    </tbody></table>
  </div>
  <div class="card"><h3>媒体保留${help(
    '超过容量时先删除最旧文件；超过保留天数时无论大小都会删除。约每 10 分钟检查一次。留空或填 0 表示不限制，不是删除全部。',
  )}</h3>
    <table><thead><tr><th></th><th>最大容量（MB）</th><th>最长保留（天）</th></tr></thead><tbody>
      ${Object.keys(RETENTION_LABELS)
        .map(k => {
          const c = rd[k] || {};
          return `<tr><td>${RETENTION_LABELS[k]}</td>
        <td><input type="number" id="ret_${k}_mb" value="${esc(c.max_mb ?? '')}" min="0" style="width:100px"></td>
        <td><input type="number" id="ret_${k}_days" value="${esc(c.max_days ?? '')}" min="0" style="width:100px"></td></tr>`;
        })
        .join('')}
      <tr><td>事件（历史）</td><td class="mut">—</td><td><input type="number" id="ret_events_days" value="${esc((rd.events || {}).max_days ?? '')}" min="0" style="width:100px"></td></tr>
      <tr><td>已拦截的尝试${help('保留原厂云端发送但被拦截的指令记录的天数。')}</td><td class="mut">—</td><td><input type="number" id="ret_blocked_days" value="${esc((rd.blocked || {}).max_days ?? '')}" min="0" style="width:100px"></td></tr>
    </tbody></table>
    <div style="margin-top:10px"><button class="act" data-action="save-retention">保存保留设置</button></div>
  </div>`;
  if (on) loadBlocked(i.redactions || {});
}
onChange('save-setting', el => saveSetting(el.dataset.key, el.checked));
// "custom…" only reveals the text box; nothing is saved until a real value is
// picked, so switching to it does not blank a working upstream.
onChange('save-upstream', el =>
  el.value === CUSTOM_UPSTREAM ? renderCustomUpstream() : saveSetting('proxy_upstream', el.value),
);
onAction('save-proxy-dns', () =>
  saveSetting('proxy_dns', document.getElementById('proxyDns').value),
);
onAction('save-proxy-upstream', () =>
  saveSetting('proxy_upstream', document.getElementById('proxyUp').value),
);
function renderCustomUpstream() {
  const sel = document.getElementById('proxyUpSel');
  if (sel && !document.getElementById('proxyUp')) {
    sel.parentElement.insertAdjacentHTML(
      'afterend',
      '<span class="cn" style="display:flex;gap:6px;margin-top:6px"><input id="proxyUp" placeholder="https://api.eu-pet.com/6/">' +
        '<button class="mini" data-action="save-proxy-upstream">设置</button></span>',
    );
    document.getElementById('proxyUp').focus();
  }
}
async function saveSetting(key, value) {
  const r = await api('settings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ [key]: value }),
  });
  toast(r.ok ? '已保存 · ' + key : '错误：' + (r.error || 'failed'));
  loadSetup(); // re-render (guards ungrey, the upstream picker appears, …)
}

// Once a taken-over device settles down it polls only the heartbeat, PetKit
// refuses that, and we answer locally — so nothing in the UI would otherwise
// differ from proxy being off. This line is what says it is running.
function upstreamStatus(counts) {
  const total = Object.values(counts).reduce((a, b) => a + b, 0);
  if (!total)
    return '<p class="sub" style="margin-top:14px"><span class="mut">No calls forwarded yet — the device polls every ~10s.</span></p>';
  const ok = counts.ok || 0;
  const refused = Object.entries(counts).filter(([k]) => k.startsWith('error_'));
  const failed = Object.entries(counts).filter(([k]) => k.startsWith('http_'));
  let html =
    `<p class="sub" style="margin-top:14px">Forwarded <b>${total}</b> calls · ` +
    `<span class="badge ${ok ? 'ok' : ''}">${ok} answered</span> ` +
    refused
      .map(([k, n]) => `<span class="badge warn">${n} refused (${esc(k.slice(6))})</span>`)
      .join(' ') +
    failed
      .map(([k, n]) => `<span class="badge bad">${n} × HTTP ${esc(k.slice(5))}</span>`)
      .join(' ') +
    '</p>';
  if (refused.length && !ok)
    html +=
      '<p class="sub mut">The cloud is refusing every session-bearing call — expected once this app ' +
      'has taken the device over, since the session it presents is one we issued. The device is served our own answers throughout.</p>';
  return html;
}

// Blocked attempts are the persisted ones only. The routine rewrites — every
// dev_serverinfo poll substitutes our address — are counters, not rows.
const REDACT_LABELS = {
  rce: 'shell 命令',
  ota: '固件推送',
  secret: '凭据替换',
  log_upload: '日志上传',
  server: '服务器地址',
  mqtt: 'MQTT 地址',
  oss_sts: '媒体上传',
  locale: '时区',
};
async function loadBlocked(counts) {
  const v = document.getElementById('blockedView');
  if (!v) return;
  let r;
  try {
    r = await api('blocked?limit=25');
  } catch (e) {
    v.innerHTML = '';
    return;
  }
  if (r.error) {
    v.innerHTML = '';
    return;
  }
  // Only rendered when there is something to render. The counter chips that
  // used to head this section were dominated by routine rewrites — the server
  // address, the MQTT host, STS, the timezone — which fire on every poll, so
  // they scrolled a permanent "nothing is wrong" banner past the one thing
  // worth seeing. A blocked attempt is a cloud instruction we REFUSED (a shell
  // command, a firmware push, a credential swap); on a healthy session there
  // are none and this whole card should be absent, not saying so at length.
  const rows = r.records || [];
  if (!rows.length) {
    v.innerHTML = '';
    return;
  }
  v.innerHTML =
    `<h3 style="margin-top:0">已拦截的尝试 <span class="badge bad">${esc(rows.length)}</span>${help(
      '原厂 PETKIT 云端发送但本服务拒绝转发给设备的指令；记录会保存在数据库中。',
    )}</h3>
    <table><thead><tr><th>时间</th><th>类型</th><th>接口</th><th>数据</th></tr></thead><tbody>` +
    rows
      .map(
        b => `<tr><td class="mut">${esc(new Date((b.created_at || 0) * 1000).toLocaleString())}</td>
        <td><span class="badge bad">${esc(REDACT_LABELS[b.kind] || b.kind)}</span></td>
        <td><code>${esc(b.endpoint || '')}</code></td>
        <td class="mut">${esc(b.payload_json || '')}</td></tr>`,
      )
      .join('') +
    '</tbody></table>';
}

export { loadSetup };
