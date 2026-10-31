import { BASE, toast } from './core.js';
import { onAction } from './delegate.js';
import { VideoRTC } from './go2rtc-video-rtc.js';

// Pinned go2rtc 1.9.14 player handles MSE and Apple's ManagedMediaSource.
// Only MSE is enabled: signalling and media use the existing HTTPS origin.
class PetkitLiveVideo extends VideoRTC {
  oninit() {
    super.oninit();
    this.video.muted = true;
    this.video.addEventListener('playing', () => setStatus('正在播放', this));
    this.video.addEventListener('waiting', () => setStatus('正在缓冲…', this));
    this.video.addEventListener('error', () => setStatus('播放中断，可关闭后重新观看', this));
  }

  onopen() {
    const modes = super.onopen();
    this.onmessage['petkit-status'] = message => {
      if (message.type === 'error') {
        setStatus('视频暂不可用，正在重试…', this);
        if (this === player) this.ws?.close();
      }
    };
    return modes;
  }

  onclose() {
    if (this.wsState !== WebSocket.CLOSED) setStatus('连接中断，正在重连…', this);
    return super.onclose();
  }
}
customElements.define('petkit-live-video', PetkitLiveVideo);

let dialog = null;
let player = null;

function setStatus(text, owner = null) {
  if (owner && owner !== player) return;
  if (dialog && dialog.open) dialog.querySelector('.live-status').textContent = text;
}

function stopPlayer() {
  if (!player) return;
  const current = player;
  player = null;
  if (current instanceof PetkitLiveVideo) {
    clearTimeout(current.reconnectTID);
    clearTimeout(current.disconnectTID);
    current.reconnectTID = current.disconnectTID = 0;
    current.video?.pause();
    current.ondisconnect();
    current.video?.removeAttribute('src');
    current.video?.load();
  } else {
    current.pause();
    current.removeAttribute('src');
    current.load();
  }
  current.remove();
}

function ensureDialog() {
  if (dialog) return dialog;
  dialog = document.createElement('dialog');
  dialog.className = 'live-dialog';
  dialog.setAttribute('aria-labelledby', 'live-title');
  dialog.innerHTML = `
    <div class="live-heading"><h3 id="live-title"></h3>
      <button class="act" data-action="close-live">停止观看</button></div>
    <p class="mut live-status" role="status">正在连接…</p>
    <div class="live-screen"></div>
    <p class="mut live-note">默认静音，可通过画面控件开启声音。</p>`;
  document.body.appendChild(dialog);
  dialog.addEventListener('close', () => {
    if (!dialog.open) stopPlayer();
  });
  dialog.addEventListener('click', event => {
    if (event.target === dialog) closeLive();
  });
  return dialog;
}

function closeLive() {
  stopPlayer();
  if (dialog?.open) dialog.close();
}

function openLive(identity, name) {
  const id = Number(identity);
  if (!Number.isSafeInteger(id) || id <= 0) return toast('设备标识无效');
  stopPlayer();
  const window = ensureDialog();
  window.querySelector('h3').textContent = '实时画面 · ' + (name || '#' + id);
  if (!window.open) window.showModal();
  setStatus('正在连接…');

  if ('MediaSource' in globalThis || 'ManagedMediaSource' in globalThis) {
    player = document.createElement('petkit-live-video');
    player.mode = 'mse';
    player.media = 'video,audio';
    // Our one page-level listener owns lifecycle. Per-player document or
    // IntersectionObserver handlers would retain each discarded player.
    player.visibilityCheck = false;
    player.visibilityThreshold = 0;
    player.src = new URL(BASE + 'api/devices/' + id + '/live.ws', location.origin);
  } else {
    player = document.createElement('video');
    player.controls = player.playsInline = player.muted = true;
    player.src = BASE + 'api/devices/' + id + '/live.mp4';
    const video = player;
    video.addEventListener('playing', () => setStatus('正在播放', video));
    video.addEventListener('error', () =>
      setStatus('此浏览器无法播放，请使用新版 Safari 或 Chrome', video),
    );
  }
  window.querySelector('.live-screen').appendChild(player);
  if (player instanceof HTMLVideoElement) player.play().catch(() => setStatus('请点击画面播放'));
}

onAction('open-live', element => openLive(element.dataset.id, element.dataset.name));
onAction('close-live', closeLive);
document.addEventListener('visibilitychange', () => {
  if (document.hidden) closeLive();
});
// Delegated: the sidebar's device rows come and go after this module has run.
document.addEventListener('click', ev => {
  if (ev.target.closest?.('nav button')) closeLive();
});
globalThis.addEventListener('pagehide', closeLive);

export { openLive, closeLive };
