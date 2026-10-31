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
  let html = `<div class="card notice"><b>补丁会修改设备固件。</b> 风险由使用者承担，可能导致设备损坏、无法启动或失去保修。移除补丁会删除相关文件并重启设备，实际恢复结果取决于设备和固件。</div>`;
  for (const dev of ds) {
    const head = `<h3>${esc(dev.name)} <span class="chip">${esc(dev.type.toUpperCase())}，#${esc(dev.id)}</span></h3>`;
    const p = await api('devices/' + dev.id + '/patcher');
    if (!p.supported) {
      html += `<section class="sgroup">${head}<div class="sbox"><p class="sub sbox-note">设备补丁仅支持 Linux 型号（T5、T6、T7、D4H、D4SH、W7H）；此设备使用其他平台。</p></div></section>`;
      continue;
    }
    const ip = p.device_ip || '?';
    html += `<section class="sgroup">${head}`;
    if (!ip || ip === '?')
      html += '<p class="sub patch-wait">设备 IP 未知，等设备下次上报状态后才能应用补丁。</p>';
    html += '<div class="sbox">';
    for (const [pid, pt] of Object.entries(p.patchers || {})) {
      // Real newlines in the description are rendered by `white-space:pre-line`;
      // only the literal two-character sequence "\n" becomes a <br>. Splitting
      // first and escaping each piece keeps that without ever un-escaping.
      const desc = String(pt.description ?? '')
        .split('\\n')
        .map(esc)
        .join('<br>');
      const name = esc(patcherName(pid, pt.name));
      // One action, the one that changes something: applied patches offer
      // Remove, and the others Apply. Both used to sit on every row, with
      // Remove live on patches that were never applied.
      const action = pt.applied
        ? `<button class="act" data-action="remove-patch" data-id="${esc(dev.id)}" data-patcher="${esc(pid)}" data-name="${name}">移除</button>`
        : `<button class="act" ${pt.greyed || !ip || ip === '?' ? 'disabled' : ''} data-action="apply-patch" data-id="${esc(dev.id)}" data-patcher="${esc(pid)}" data-name="${name}">应用</button>`;
      html += `<div class="patch">
        <div class="patch-head">
          <span class="dot ${pt.applied ? 'on' : 'off'}" title="${pt.applied ? '已应用' : '未应用'}"></span>
          <b>${name}</b>
          ${pt.unavailable ? `<span class="badge warn">${esc(pt.unavailable)}</span>` : pt.greyed ? '<span class="badge ok">MQTT 已连接</span>' : ''}
          <span class="grow"></span>
          ${action}
        </div>
        ${patcherDescription(pid) ? `<p class="sub">${esc(patcherDescription(pid))}</p>` : `<p class="sub" style="white-space:pre-line">${desc}</p>`}
        <details class="adv"><summary>详细信息</summary>
          <p class="sub">写入前会检查设备可用空间，需要约 ${esc(fmtBytes(pt.needs_bytes))}。</p>
          ${patcherDescription(pid) ? `<p class="sub" style="white-space:pre-line">${desc}</p>` : ''}
        </details>
        ${pt.needs_pubkey ? `<div style="margin:8px 0"><label class="mut">公钥</label><input type="text" class="ssh-pubkey" data-dev="${esc(dev.id)}" data-patcher="${esc(pid)}" placeholder="ssh-rsa AAAA... or ecdsa-sha2-..." value="${esc(pt.ssh_pubkey || '')}" aria-label="公钥"></div>` : ''}
        <pre id="plog_${esc(dev.id)}_${esc(pid)}" style="display:none;margin-top:8px;font-size:11px;max-height:200px;overflow:auto"></pre>
      </div>`;
    }
    html += '</div></section>';
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
