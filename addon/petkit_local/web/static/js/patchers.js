import { api, esc, toast, fmtBytes } from './core.js';
import { onAction } from './delegate.js';
import { patcherName, patcherDescription } from './zh.js';

// ---------------- Patchers ----------------
async function loadPatchers() {
  const ds = await api('devices');
  const v = document.getElementById('patchView');
  if (!ds.length) {
    v.innerHTML =
      '<div class="empty"><b>尚未连接设备</b><p class="mut">请先连接设备，再查看可用补丁。</p></div>';
    return;
  }
  let html = `<div class="card" style="border-color:var(--warn)">
    <p style="margin:0"><b>警告：修改设备固件</b> — 补丁会修改设备文件系统，操作风险由使用者承担。作者不承担设备损坏、无法启动或保修失效等责任。应用前请仔细阅读每项说明。</p>
    <p style="margin:4px 0 0;font-size:0.85em;opacity:0.8">项目说明：补丁使用可写存储与启动包装，移除补丁会删除相关文件并重启设备。实际恢复结果取决于设备和固件，使用前请确认恢复方式。</p></div>`;
  for (const dev of ds) {
    const p = await api('devices/' + dev.id + '/patcher');
    if (!p.supported) {
      html += `<div class="card"><h3>${esc(dev.name)} <span class="chip">${esc(dev.type.toUpperCase())} · #${esc(dev.id)}</span></h3><p class="mut">设备补丁仅支持 Linux 型号（T5、T6、T7、D4H、D4SH、W7H）；此设备使用其他平台。</p></div>`;
      continue;
    }
    const ip = p.device_ip || '?';
    html += `<div class="card"><h3>${esc(dev.name)} <span class="chip">${esc(dev.type.toUpperCase())} · #${esc(dev.id)}</span></h3>`;
    if (!ip || ip === '?')
      html += '<p class="badge warn">设备 IP 未知，请等待状态上报后再使用补丁。</p>';
    for (const [pid, pt] of Object.entries(p.patchers || {})) {
      // Real newlines in the description are rendered by `white-space:pre-line`;
      // only the literal two-character sequence "\n" becomes a <br>. Splitting
      // first and escaping each piece keeps that without ever un-escaping.
      const desc = String(pt.description ?? '')
        .split('\\n')
        .map(esc)
        .join('<br>');
      html += `<div class="card" style="margin:8px 0;background:var(--card2)">
        <div style="display:flex;align-items:center;gap:10px;margin-bottom:8px">
          <b>${esc(patcherName(pid, pt.name))}</b>
          ${pt.applied ? '<span class="badge ok">已应用</span>' : '<span class="badge">未应用</span>'}
          ${pt.unavailable ? `<span class="badge warn">${esc(pt.unavailable)}</span>` : pt.greyed ? '<span class="badge ok">MQTT 已连接</span>' : ''}
          <span class="grow"></span>
          <span class="badge" title="写入前会检查设备的可用空间，实际需求在应用时测量。">需要约 ${esc(fmtBytes(pt.needs_bytes))} 可用空间</span>
        </div>
        ${patcherDescription(pid) ? `<p class="sub">${esc(patcherDescription(pid))}</p><details class="adv"><summary>查看英文原始说明</summary><p class="sub" style="white-space:pre-line">${desc}</p></details>` : `<p class="sub" style="white-space:pre-line">${desc}</p>`}
        ${pt.needs_pubkey ? `<div style="margin-bottom:8px"><label class="mut">公钥</label><input type="text" class="ssh-pubkey" data-dev="${esc(dev.id)}" data-patcher="${esc(pid)}" placeholder="ssh-rsa AAAA... or ecdsa-sha2-..." value="${esc(pt.ssh_pubkey || '')}" aria-label="公钥"></div>` : ''}
        <div>
          <button class="act${pt.greyed ? ' ghost' : ''}" ${pt.greyed || !ip || ip === '?' ? 'disabled' : ''} data-action="apply-patch" data-id="${esc(dev.id)}" data-patcher="${esc(pid)}" data-name="${esc(patcherName(pid, pt.name))}">应用</button>
          <button class="ghost act" data-action="remove-patch" data-id="${esc(dev.id)}" data-patcher="${esc(pid)}" data-name="${esc(patcherName(pid, pt.name))}">移除</button>
        </div>
        <pre id="plog_${esc(dev.id)}_${esc(pid)}" style="display:none;margin-top:8px;font-size:11px;max-height:200px;overflow:auto"></pre>
      </div>`;
    }
    html += '</div>';
  }
  v.innerHTML = html;
}
onAction('apply-patch', el =>
  applyPatch(Number(el.dataset.id), el.dataset.patcher, el.dataset.name),
);
onAction('remove-patch', el =>
  removePatch(Number(el.dataset.id), el.dataset.patcher, el.dataset.name),
);
async function applyPatch(id, patcher, name) {
  if (!confirm('确定向此设备应用“' + name + '”吗？\n\n此操作会修改设备固件，请确认已理解其影响。'))
    return;
  const lg = document.getElementById('plog_' + id + '_' + patcher);
  if (lg) {
    lg.style.display = 'block';
    lg.textContent = '正在开始…\n';
  }
  toast('正在应用 ' + name + '…');
  const r = await api('devices/' + id + '/patcher', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      patcher,
      action: 'apply',
      pubkey:
        (document.querySelector(`.ssh-pubkey[data-patcher="${patcher}"][data-dev="${id}"]`) || {})
          .value || '',
    }),
  });
  if (!r.ok) {
    toast('错误：' + (r.error || '失败'));
    if (lg) lg.textContent += '错误：' + (r.error || '失败') + '\n';
  }
}
async function removePatch(id, patcher, name) {
  if (!confirm('确定从此设备移除“' + name + '”吗？\n\n设备将会重启。')) return;
  const lg = document.getElementById('plog_' + id + '_' + patcher);
  if (lg) {
    lg.style.display = 'block';
    lg.textContent = '正在移除…\n';
  }
  const r = await api('devices/' + id + '/patcher', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ patcher, action: 'remove' }),
  });
  if (!r.ok) {
    toast('错误：' + (r.error || '失败'));
  }
}

export { loadPatchers };
