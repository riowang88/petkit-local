// Controller tests use a DOM/VideoRTC double; real MSE decoding is checked in CUA.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const queued = [];
  const classes = new Map();
  class Element extends EventTarget {
    constructor() {
      super();
      this.children = [];
      this.open = false;
      this.textContent = '';
    }
    setAttribute() {}
    removeAttribute() {}
    set innerHTML(_) {
      this.parts = {
        h3: new Element(),
        '.live-status': new Element(),
        '.live-screen': new Element(),
      };
    }
    querySelector(selector) {
      return this.parts[selector];
    }
    appendChild(child) {
      this.children.push(child);
      child.parent = this;
      child.isConnected = true;
      child.connectedCallback?.();
      return child;
    }
    remove() {
      this.parent.children = this.parent.children.filter(child => child !== this);
      this.parent = null;
      this.isConnected = false;
    }
    showModal() {
      this.open = true;
    }
    close() {
      this.open = false;
      queued.push(() => this.dispatchEvent(new Event('close')));
    }
  }
  class Video extends Element {
    pause() {
      this.paused = true;
    }
    load() {}
  }
  class Player extends Element {
    constructor() {
      super();
      this.closeCount = 0;
      this.wsState = 1;
      this.ws = {
        close: () => {
          this.closeCount++;
          this.wsState = 3;
        },
      };
    }
    set src(value) {
      this.url = String(value);
    }
    connectedCallback() {
      this.oninit();
    }
    oninit() {
      this.video = new Video();
      this.appendChild(this.video);
    }
    onopen() {
      this.onmessage = {};
      return ['mse'];
    }
    onclose() {}
    ondisconnect() {
      this.ws?.close();
      this.ws = null;
    }
  }
  const body = new Element();
  const document = new EventTarget();
  document.body = body;
  document.querySelectorAll = () => [];
  document.createElement = name =>
    name === 'petkit-live-video'
      ? new (classes.get(name))()
      : name === 'video'
        ? new Video()
        : new Element();
  const context = vm.createContext({
    document,
    VideoRTC: Player,
    HTMLVideoElement: Video,
    URL,
    MediaSource: function () {},
    location: { origin: 'https://nas.example' },
    BASE: '/',
    WebSocket: { CLOSED: 3 },
    clearTimeout() {},
    addEventListener() {},
    customElements: { define: (name, type) => classes.set(name, type) },
    onAction() {},
    toast() {},
  });
  const source = fs
    .readFileSync(path.join(__dirname, '../petkit_local/web/static/js/live.js'), 'utf8')
    .replace(/^import .*;\n/gm, '')
    .replace(/^export \{.*\};?$/gm, '');
  vm.runInContext(source + '\nglobalThis.controller = { openLive, closeLive };', context);
  return {
    controller: context.controller,
    dialog: () => body.children[0],
    player: () => body.children[0].querySelector('.live-screen').children[0],
    flushCloseEvents: () => queued.splice(0).forEach(fn => fn()),
  };
}

test('MSE error closes the socket so the pinned player can reconnect', () => {
  const ui = setup();
  ui.controller.openLive(10000001, 'First');
  const player = ui.player();
  player.onopen();
  player.onmessage['petkit-status']({ type: 'error', value: 'mse: producer unavailable' });
  assert.equal(player.closeCount, 1);
});

test('delayed close event cannot remove a newly opened second camera', () => {
  const ui = setup();
  ui.controller.openLive(10000001, 'First');
  ui.controller.closeLive();
  ui.controller.openLive(10000002, 'Second');
  ui.flushCloseEvents();
  assert.equal(ui.dialog().open, true);
  assert.equal(ui.dialog().querySelector('.live-screen').children.length, 1);
  assert.match(ui.player().url, /10000002\/live\.ws$/);
});

test('late events from a stopped camera cannot overwrite the current status', () => {
  const ui = setup();
  ui.controller.openLive(10000001, 'First');
  const old = ui.player();
  ui.controller.closeLive();
  ui.flushCloseEvents();
  ui.controller.openLive(10000002, 'Second');
  const status = ui.dialog().querySelector('.live-status');
  const before = status.textContent;
  old.video.dispatchEvent(new Event('error'));
  assert.equal(status.textContent, before);
});
