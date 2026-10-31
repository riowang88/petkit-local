import { api, toast } from './core.js';
import { onAction, onChange } from './delegate.js';
import { PENDING_EDITS, editKey } from './entities.js';
import { loadDevices, scheduleRefresh, scheduleDetail } from './devices.js';

onChange('set-entity-switch', el =>
  setEntity(
    Number(el.dataset.id),
    el.dataset.key,
    el.checked ? 'ON' : 'OFF',
    null,
    el.dataset.kind,
  ),
);
onChange('set-entity-select', el =>
  setEntity(Number(el.dataset.id), el.dataset.key, el.value, null, el.dataset.kind),
);
onAction('set-entity-time', el => {
  const input = el.closest('.cn').querySelector('input');
  if (!input.value) return toast('请先选择时间');
  // Sent verbatim. The server parses it, and it is the only place that decides
  // what a valid time is — the browser's own picker is not the authority.
  setEntity(Number(el.dataset.id), el.dataset.key, input.value, null, el.dataset.kind);
});
onAction('set-entity-number', el => {
  const input = el.closest('.cn').querySelector('input');
  const v = Number(input.value);
  const min = Number(input.min),
    max = Number(input.max);
  // The min/max attributes only bound the spinner and the browser's own
  // validity flag; nothing stops a typed value going straight through. Out of
  // range is refused here rather than clamped, because a silently different
  // number is worse than being told no -- and the server refuses it too, since
  // the panel is not the only way in.
  if (input.value === '' || Number.isNaN(v)) return toast('请输入数字');
  if (v < min || v > max) return toast(`数值必须在 ${min} 和 ${max} 之间`);
  setEntity(
    Number(el.dataset.id),
    el.dataset.key,
    input.value,
    () => {
      PENDING_EDITS.delete(editKey(el.dataset.id, el.dataset.key));
      input.classList.remove('dirty');
    },
    el.dataset.kind,
  );
});

onAction('send-action', el =>
  sendAction(
    el,
    Number(el.dataset.id),
    el.dataset.key,
    el.dataset.destructive === '1',
    el.dataset.name,
  ),
);
async function sendAction(el, id, action, destructive, name) {
  if (destructive && !confirm('确定向设备发送“' + name + '”吗？')) return;
  post(id, { action }, r => {
    const msg = r.ok
      ? r.delivered === 'mqtt'
        ? '已通过 MQTT 发送'
        : '已排队，等待下次心跳发送（约 10 秒）'
      : '错误：' + r.error;
    // Found by walking up from the button that was pressed, not by a global id:
    // with a panel per device those ids would collide, and every device's
    // result would land in the first panel's slot.
    const card = el.closest('.devpanel');
    const o = card && card.querySelector('.cmd-out');
    if (o) o.textContent = msg;
    toast(msg);
  });
}
async function setEntity(id, key, value, onAccepted, kind) {
  post(
    id,
    { entity: key, value },
    r => {
      if (r.ok && onAccepted) onAccepted();
      toast(
        r.ok
          ? r.delivered === 'mqtt'
            ? '已通过 MQTT 更新'
            : r.delivered === 'local'
              ? '已保存'
              : r.delivered === 'ble'
                ? '已通过蓝牙发送'
                : '已排队，等待心跳发送'
          : '错误：' + r.error,
      );
    },
    kind,
  );
}
// A `button` entity, as opposed to a device ACTION (`send-action`). The two
// look identical on screen and are different things underneath: an action is
// looked up in `ALL_ACTIONS` and posted as `{action}`, while a button entity is
// one of the device's own entities and is posted as `{entity}` like every other
// control. A BLE accessory only has the second kind.
onAction('press-entity', el =>
  setEntity(Number(el.dataset.id), el.dataset.key, '', null, el.dataset.kind),
);
onAction('send-raw', el => sendRaw(el, Number(el.dataset.id)));
async function sendRaw(el, id) {
  const box = el.closest('details');
  const ta = box && box.querySelector('.cmd-raw');
  let b;
  try {
    b = JSON.parse(ta.value);
  } catch (e) {
    return toast('JSON 格式无效');
  }
  post(id, b, r => toast(r.ok ? '发送方式：' + r.delivered : '错误：' + r.error));
}
async function post(id, body, cb, kind) {
  // A BLE accessory has its own route: it is reachable only while its parent
  // is on MQTT, so there is no heartbeat queue to fall back to and the server
  // answers 409 rather than pretending to have queued something.
  const path = kind === 'ble' ? 'ble/' + id + '/command' : 'devices/' + id + '/command';
  const r = await api(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (cb) cb(r);
  if (kind === 'ble') {
    if (r && r.error) toast('错误：' + r.error);
    loadDevices();
    return;
  }
  // reflect optimistic changes + queue count
  scheduleRefresh();
  scheduleDetail(id);
}

export { setEntity };
