import { esc } from './core.js';
import { onInput } from './delegate.js';
import { fmtEntityValue } from './format.js';
import { help } from './help.js';
import { entityName, optionLabel } from './zh.js';

//: Device ids whose State card is showing every entity rather than just the
//: sensors. Not persisted: it is a "let me check something" mode, and coming
//: back later to a wall of 67 rows is not what anyone meant by it.
const SHOW_ALL = new Set();

// The State table, and — behind the toggle — the whole entity list. These were
// two tables: "State" and a collapsed "All Home Assistant entities" that was a
// strict superset of it, re-listing every sensor plus every control, button and
// event. One table with a switch says the same thing without the duplication.
function entityTable(d, sensors) {
  const all = SHOW_ALL.has(d.id);
  const rows = all ? d.entities || [] : sensors;
  return `<table class="stbl"><tbody>${rows
    .map(
      e => `<tr><td>${esc(entityName(e.key, e.name))}</td>
      <td>${fmtEntityValue(e)}</td>
      <td class="mut">${all ? `${esc(e.component)}<br>` : ''}<code>${esc(e.value_path || e.key)}</code></td></tr>`,
    )
    .join('')}</tbody></table>`;
}

// Both are Beta in PetKit's own app, and pH has a hardware prerequisite that is
// invisible from here — without the right litter the toggle is on and nothing
// is ever measured, which looks like a bug rather than a missing consumable.
//: Per-control explanations, keyed by entity key, rendered as a `?` beside the
//: label by `controlRow`. These used to be one paragraph sitting UNDER both
//: switches it described, with its bold words ("Yowling", "Urine pH") not even
//: matching the labels above it ("Yowling Detection", "Urine pH Detection") —
//: so the reader had to work out which sentence went with which toggle.
//:
//: Only add an entry where the label genuinely is not enough. A `?` on every
//: row is the same wall of text with extra clicks.
const ENTITY_HELP = {
  yowling_detection:
    '检测猫在猫砂盆内的叫声并记录。PETKIT 将持续叫声视为可能不适的信号；原厂 App 中为测试功能。',
  ph_detection: '通过摄像头读取 PETKIT 指示猫砂的颜色；普通猫砂无法测量。原厂 App 中为测试功能。',
  vomit_detection: '检测呕吐事件。关闭时，检测结果中的 `vomit_info` 列表保持为空。',
};

//: Values typed into a number control but not yet sent, keyed "<id>:<key>".
//:
//: The panel repaints every few seconds from the device's own state, replacing
//: the whole body. `paintPanelBody` refuses to do that while something inside
//: has focus, which covers typing, but not the moment you look away: the input
//: is then rebuilt from the device's value and whatever you typed is gone. Held
//: here instead, so a repaint re-renders the edit rather than discarding it.
//:
//: Cleared once the value is accepted, or when it matches the device again.
const PENDING_EDITS = new Map();
const editKey = (id, key) => id + ':' + key;

onInput('entity-number', el => {
  const k = editKey(el.dataset.id, el.dataset.key);
  if (el.value === '' || el.value === String(el.dataset.deviceValue)) PENDING_EDITS.delete(k);
  else PENDING_EDITS.set(k, el.value);
  // Mark it visibly unsaved: a number that survives a repaint but has not been
  // sent would otherwise be indistinguishable from the device's own state.
  el.classList.toggle('dirty', PENDING_EDITS.has(k));
});

// Seconds since midnight -> HH:MM:SS, or '' for anything that is not a time of
// day. Empty rather than 00:00:00 on purpose: none of these settings is seeded,
// so before the device reports one there is no hour to show, and a zero would
// read as midnight.
function secondsToClock(v) {
  const n = Number(v);
  if (!Number.isFinite(n) || n < 0 || n >= 86400) return '';
  const p = x => String(Math.floor(x)).padStart(2, '0');
  return `${p(n / 3600)}:${p((n / 60) % 60)}:${p(n % 60)}`;
}

function controlRow(id, e, kind) {
  const k = kind ? ` data-kind="${esc(kind)}"` : '';
  const nm = esc(entityName(e.key, e.name)) + (ENTITY_HELP[e.key] ? help(ENTITY_HELP[e.key]) : '');
  if (e.component === 'switch') {
    const on = e.value === 1 || e.value === true || e.value === '1';
    // A setting nobody has set and the device has not reported is unknown, not
    // off: several are deliberately never seeded (a seed would be pushed to the
    // device), and an unchecked box alone would claim a state nobody chose.
    const unknown = e.value === null || e.value === undefined;
    return `<label class="ctrl"><span>${nm}${unknown ? ' <span class="mut">（未知）</span>' : ''}</span>
      <span class="sw"><input type="checkbox" ${on ? 'checked' : ''} data-change="set-entity-switch" data-id="${esc(id)}" data-key="${esc(e.key)}"${k}><span class="sl"></span></span></label>`;
  }
  if (e.component === 'number') {
    // The Set button finds its input by walking the DOM rather than by an id
    // built from the entity key — one less device-supplied string in markup.
    // The range is SHOWN, not just enforced by the input: a bare spinner gives
    // no clue what the maximum is, and typing past it silently does nothing.
    const range =
      e.min != null && e.max != null
        ? ` <span class="mut">(${esc(e.min)}–${esc(e.max)}${e.unit ? ' ' + esc(e.unit) : ''})</span>`
        : e.unit
          ? ` <span class="mut">(${esc(e.unit)})</span>`
          : '';
    const pending = PENDING_EDITS.get(editKey(id, e.key));
    const shown = pending !== undefined ? pending : (e.value ?? '');
    return `<label class="ctrl"><span>${nm}${range}</span>
      <span class="cn"><input type="number" class="${pending !== undefined ? 'dirty' : ''}" value="${esc(shown)}" min="${esc(e.min)}" max="${esc(e.max)}" step="${esc(e.step)}"
        data-input="entity-number" data-id="${esc(id)}" data-key="${esc(e.key)}" data-device-value="${esc(e.value ?? '')}">
      <button class="mini" data-action="set-entity-number" data-id="${esc(id)}" data-key="${esc(e.key)}"${k}>设置</button></span></label>`;
  }
  if (e.component === 'time') {
    // The device stores seconds since midnight; the control shows a clock. Same
    // conversion Home Assistant gets from ha/discovery.py::_time_value_template,
    // and the payload sent back is the same HH:MM:SS string an HA time entity
    // publishes — so both paths land on ha/commands.py::_coerce_time.
    const pending = PENDING_EDITS.get(editKey(id, e.key));
    const device = secondsToClock(e.value);
    const shown = pending !== undefined ? pending : device;
    // `step` in SECONDS, and a minute by default: every value these fields have
    // ever carried is minute-aligned (13:00, 12:00, 08:15), so a seconds
    // spinner offered a precision the schedule does not have.
    return `<label class="ctrl"><span>${nm}</span>
      <span class="cn"><input type="time" step="${esc(e.step || 60)}" class="${pending !== undefined ? 'dirty' : ''}" value="${esc(shown)}"
        data-input="entity-number" data-id="${esc(id)}" data-key="${esc(e.key)}" data-device-value="${esc(device)}">
      <button class="mini" data-action="set-entity-time" data-id="${esc(id)}" data-key="${esc(e.key)}"${k}>设置</button></span></label>`;
  }
  // select — device value maps to an option via option_values, or by bare
  // index when an entity declares none (ha/discovery.py::_select_value_template
  // does the same fallback server-side: `option_values or range(len(options))`).
  const values =
    e.option_values && e.option_values.length
      ? e.option_values
      : (e.options || []).map((_, idx) => idx);
  const sel = values.findIndex(v => String(v) === String(e.value));
  const opts = (e.options || [])
    .map(
      (o, idx) =>
        `<option value="${esc(o)}" ${idx === sel ? 'selected' : ''}>${esc(optionLabel(e.key, o))}</option>`,
    )
    .join('');
  return `<label class="ctrl"><span>${nm}</span>
    <span class="cn"><select data-change="set-entity-select" data-id="${esc(id)}" data-key="${esc(e.key)}"${k}>${opts}</select></span></label>`;
}

export { SHOW_ALL, entityTable, controlRow, PENDING_EDITS, editKey };
