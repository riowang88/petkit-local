import { BASE, api, esc, toast, fmtBytes } from './core.js';
import { onAction } from './delegate.js';

// ---------------- Capture ----------------
async function loadCapture() {
  const c = await api('capture');
  const v = document.getElementById('capView');
  const what =
    '<p class="sub">流量记录将设备的 HTTP 请求、响应和 MQTT 帧原样写入 <code>.jsonl</code> 文件，用于协议分析或连接排查。</p>' +
    '<div class="card notice"><b>所有流量记录都包含敏感信息。</b> 文件原样记录设备收发的数据，可能包含 Wi-Fi 名称、局域网地址、设备序列号及签名密钥；<b>代理</b>记录还可能包含账号凭据。分享之前必须检查内容。</div>';
  if (!c.enabled) {
    v.innerHTML =
      '<h3>流量记录已关闭</h3>' +
      what +
      '<p class="sub">在<a data-action="goto-tab" data-tab="setup">设置</a>中开启<b>流量记录</b>，开始写入 <code>' +
      esc(c.dir || '/data/capture') +
      '</code>。设置立即生效，无需重启。</p>';
    return;
  }
  if (!c.files.length) {
    v.innerHTML =
      '<h3>流量记录已开启</h3>' +
      what +
      '<p class="mut">尚无记录文件：<code>' +
      esc(c.dir) +
      '</code> — 设备与服务通信后会出现记录。</p>';
    return;
  }
  v.innerHTML =
    '<h3>流量记录文件</h3>' +
    what +
    '<table><thead><tr><th>文件</th><th>行数</th><th>大小</th><th></th></tr></thead><tbody>' +
    c.files
      .map(
        f => `<tr><td><a data-action="view-capture" data-name="${esc(f.name)}">${esc(f.name)}</a></td><td>${esc(f.lines)}</td><td>${esc(fmtBytes(f.size))}</td>
      <td class="cap-acts"><a href="${esc(BASE + 'api/capture/' + encodeURIComponent(f.name) + '/download')}">下载</a>
      <a data-action="delete-capture" data-name="${esc(f.name)}" data-size="${esc(f.size)}" class="danger-link">删除</a></td></tr>`,
      )
      .join('') +
    '</tbody></table>' +
    `<p class="sub" style="margin-top:10px">流量记录不会自动清理，除非手动删除。磁盘合计： <b>${esc(fmtBytes(c.files.reduce((n, f) => n + f.size, 0)))}</b> in <code>${esc(c.dir)}</code>.</p>` +
    '<div id="capBody"></div>';
}
onAction('delete-capture', el => deleteCapture(el.dataset.name, Number(el.dataset.size)));
async function deleteCapture(name, size) {
  // Irreversible and there is no second copy — a capture is usually the only
  // record of whatever it was recorded to investigate.
  if (!confirm(`确定删除 ${name}（${fmtBytes(size)}）吗？\n\n此操作无法撤销。`)) return;
  const r = await api('capture/' + encodeURIComponent(name), { method: 'DELETE' });
  toast(r.ok ? '已删除 ' + name : '错误：' + (r.error || '失败'));
  // Clear the viewer if it was showing the file that just went away.
  const body = document.getElementById('capBody');
  if (body && body.textContent.includes(name)) body.innerHTML = '';
  loadCapture();
}
onAction('view-capture', el => viewCapture(el.dataset.name));
async function viewCapture(name) {
  // The name is a directory entry, so it can hold anything a filesystem allows —
  // percent-encode it rather than pasting it straight into the request path.
  const r = await api('capture/' + encodeURIComponent(name) + '?limit=60');
  document.getElementById('capBody').innerHTML =
    '<b>' +
    esc(name) +
    '</b> <span class="mut">(last ' +
    r.records.length +
    ' 条，共 ' +
    r.total +
    ')</span><pre>' +
    esc(r.records.map(x => JSON.stringify(x, null, 1)).join('\n')) +
    '</pre>';
}

export { loadCapture };
