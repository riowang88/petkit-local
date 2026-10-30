import { BASE, api, esc, toast } from './core.js';
import { onAction, onChange } from './delegate.js';
import { help } from './help.js';
import { controlRow } from './entities.js';
import { AI_ENTITY_KEYS, facesOnDevice, fetchDeviceDetail } from './devices.js';

onChange('set-ai', el => setAiEnabled(Number(el.dataset.id), el.checked));
async function setAiEnabled(id, on) {
  const r = await api('devices/' + id + '/ai', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ai_enabled: on }),
  });
  toast(r.ok ? '已保存' : '错误：' + (r.error || '失败'));
  // This toggle decides whether the pets section exists at all, so the view has
  // to be rebuilt — otherwise it saves and nothing visibly happens, which reads
  // as the switch not working.
  loadPets();
}

// ---------------- AI / Pets ----------------
// The device holds at most this many mugshots per pet. Mirrors
// events/store.py::MAX_FACES_PER_PET, and a test asserts the two agree.
const MAX_FACES = 6;

// ---- the tab ---------------------------------------------------------------
async function loadPets() {
  const v = document.getElementById('petsView');
  const [ds, pd, ub, info] = await Promise.all([
    api('devices'),
    api('pets'),
    api('pets/unbound').catch(() => ({})),
    api('info').catch(() => ({})),
  ]);
  const pets = pd.pets || [];
  const unbound = ub.unbound || [];
  const aiDevices = ds.filter(d => d.supports_ai);
  // Full detail per AI device: the summary in /api/devices carries no entities
  // and no ai_enabled. A household has one or two of these, so fetching them
  // together costs nothing.
  const aiDetails = (await Promise.all(aiDevices.map(d => fetchDeviceDetail(d.id)))).filter(
    d => d && !d.error,
  );
  const ourFaceIds = pets.flatMap(p => (p.faces || []).map(f => f.id));

  // Everything below the settings depends on at least one device actually
  // recognising: mugshots feed a matcher, so with every matcher off they are
  // configuration for nothing. `ai_enabled` defaults to on when unset, which is
  // what a device that has never been touched reports.
  // Per device, always: one box can be recognising while another is not, and a
  // pet assigned only to the switched-off one is not being matched by anything.
  // `ai_enabled` defaults to on when unset, which is what a device that has
  // never been touched reports.
  const recognisingIds = aiDetails
    .filter(d => !d.aiInfo || d.aiInfo.ai_enabled !== false)
    .map(d => d.id);
  const recognising = recognisingIds.length > 0;

  v.innerHTML = `
    ${aiDetails.map(d => deviceAiCard(d, ourFaceIds)).join('')}
    ${aiDevices.length ? '' : noAiDeviceCard(info.ai_device_names || [])}
    ${aiDevices.length && !recognising ? recognitionOffCard() : ''}
    ${recognising ? petsSection(pets, ds, aiDevices, recognisingIds) : ''}
    ${aiDevices.length ? importPetsCard(aiDevices) : ''}
    ${recognising && unbound.length ? unboundCard(unbound, pets) : ''}`;
}

// One button per recognising device. PetKit's account holds the reference
// photos its NPU matches against, and the pet id it reports them under.
//
// Asked only when pressed — this reaches out to PetKit and downloads
// photographs, which is not something a page load gets to do.
function importPetsCard(aiDevices) {
  return `<div class="card">
    <h3>从 PETKIT 导入${help(
      '向 PETKIT 请求此设备识别的宠物及参考照片。导入时为每只宠物创建本地 ID，并关联 PETKIT ID，以便识别已有事件。只有点击按钮后才会发送请求。',
    )}</h3>
    <p class="sub">导入宠物头像并关联原 PETKIT 宠物 ID 的历史记录。由于设备收到的数据不包含名称，导入后会显示为 <code>PetKit pet &lt;id&gt;</code>，可自行重命名。</p>
    ${aiDevices
      .map(
        d => `<div class="unbound">
      <b>${esc(d.name || '#' + d.id)}</b>
      <span class="grow"></span>
      <button class="mini" data-action="pets-cloud-import" data-id="${esc(d.id)}">从 PETKIT 导入</button>
    </div>`,
      )
      .join('')}
  </div>`;
}

function petsSection(pets, ds, aiDevices, recognisingIds) {
  return `
    <div class="card">
      <div class="row" style="align-items:baseline">
        <h3 style="flex:1;margin:0">宠物</h3>
        <span class="mut">${pets.length} 只宠物</span>
      </div>
      <p class="sub" style="margin-top:8px">
        每只猫最多添加 ${MAX_FACES} 张头像，由设备下载并进行识别；多角度照片有助于识别。
      </p>
      <div class="newpet">
        <input id="newPetName" placeholder="名称" aria-label="宠物名称">
        <select id="newPetDevice" aria-label="分配给设备">${aiDevices
          .map(d => `<option value="${esc(d.id)}">${esc(d.name)}</option>`)
          .join('')}</select>
        <button class="act" data-action="add-pet">添加宠物</button>
      </div>
    </div>
    ${pets.length ? pets.map(p => petCard(p, ds, recognisingIds)).join('') : emptyPets()}`;
}

// Recognition is off everywhere. The pets are still stored — this only hides a
// section that would be configuring a matcher nobody is running, and the toggle
// that changes it is the card directly above.
// No registered device can recognise anything, so there is nothing to
// configure. The product list comes from the server rather than being spelled
// out here, which is what let the old copy go stale when a fountain joined it.
function noAiDeviceCard(names) {
  return `<div class="card"><h3>没有支持 AI 的设备</h3>
    <p class="sub" style="margin:0">当前没有设备启用设备端识别${
      names.length ? `。已知支持的型号：${names.map(esc).join('、')}` : ''
    }。首次请求人脸数据的设备也会被记为支持识别。</p></div>`;
}

function recognitionOffCard() {
  return `<div class="card empty">
    <div class="big">\u{1F634}</div>
    <b>宠物识别已关闭</b>
    <p class="sub" style="margin:6px 0 0">头像仍然保留，但设备不会进行匹配。请在上方重新开启<b>识别宠物</b>后管理。</p>
  </div>`;
}

// The per-device AI settings. They sit here rather than on the device page
// because they are about the pets: recognition, whether the box listens for
// yowling, whether it reads urine pH. The device page keeps a pointer.
function deviceAiCard(d, ourFaceIds) {
  const ents = (d.entities || []).filter(e => AI_ENTITY_KEYS.includes(e.key));
  const enabled = d.aiInfo ? d.aiInfo.ai_enabled : true;
  return `<div class="card">
    <div class="row" style="align-items:baseline">
      <h3 style="flex:1;margin:0">设备端 AI</h3>
      <span class="mut">${esc(d.name)}</span>
    </div>
    <p class="sub" style="margin-top:8px">此设备的摄像头监测和聆听的内容。</p>
    <div class="ctrls">
      <label class="ctrl"><span>识别宠物</span><span class="sw"><input type="checkbox" ${
        enabled ? 'checked' : ''
      } data-change="set-ai" data-id="${esc(d.id)}"><span class="sl"></span></span></label>
      ${ents.map(e => controlRow(d.id, e)).join('')}
    </div>
    ${facesOnDevice(d, ourFaceIds)}
  </div>`;
}

function emptyPets() {
  return `<div class="card empty">
    <div class="big">🐈</div>
    <b>暂无宠物</b>
    <p class="sub" style="margin:6px 0 0">请先在上方添加宠物和头像；此前设备只会记录使用事件，不会标明是哪只宠物。</p>
  </div>`;
}

function petCard(p, ds, recognisingIds) {
  let devIds = [];
  try {
    devIds = JSON.parse(p.device_ids_json || '[]');
  } catch (e) {
    /* a pet linked to nothing reads better than a broken card */
  }
  // A chip per device this pet is assigned to, dimmed when that particular box
  // is not recognising — the assignment is still real, it is just not doing
  // anything there. Without this a pet on two devices looks equally active on
  // both when only one is matching.
  const off = id => Array.isArray(recognisingIds) && !recognisingIds.includes(id);
  const chips = devIds
    .map(id => {
      const d = ds.find(x => x.id === id);
      const name = esc(d ? d.name : '#' + id);
      return off(id)
        ? `<span class="badge off" title="此设备上的识别已关闭">${name} · 已关闭</span>`
        : `<span class="badge">${name}</span>`;
    })
    .join(' ');
  const faces = p.faces || [];
  const full = faces.length >= MAX_FACES;

  return `<div class="card petcard">
    <div class="pet-head">
      <b class="pet-name" data-action="rename-pet" data-id="${esc(p.id)}"
         title="点击重命名">${esc(p.name)}</b>
      <span class="pet-devs">${chips || '<span class="mut">未分配设备</span>'}</span>
      <button class="ghost act" data-action="delete-pet" data-id="${esc(p.id)}">删除</button>
    </div>
    <div class="tiles">
      ${faces
        .map(
          f => `<div class="tile">
        <img src="${BASE}${esc(f.url)}" alt="头像 ${esc(f.id)}">
        <button class="tile-x" title="移除这张照片"
                data-action="delete-face" data-id="${esc(p.id)}" data-face="${esc(f.id)}">✕</button>
      </div>`,
        )
        .join('')}
      ${
        full
          ? ''
          : `<button class="tile tile-add" data-action="crop-open"
                     data-id="${esc(p.id)}" data-name="${esc(p.name)}">
               <span class="plus">+</span><span>添加头像</span>
             </button>`
      }
    </div>
    <div class="pet-foot mut">
      ${faces.length} / ${MAX_FACES} 张头像${full ? ' · 已满' : ''}
    </div>
  </div>`;
}

// Identities the device reports that match no pet. Almost always ids a box
// cached from PetKit's cloud before the takeover — we show them and let the
// user say who they are, rather than guessing (with one pet in the table a
// guess would even look right, and be wrong the day a second animal appears).
function unboundCard(unbound, pets) {
  if (!pets.length) return '';
  return `<div class="card">
    <h3>未识别的身份</h3>
    <p class="sub">设备上报的这些宠物 ID 不属于本地记录，可能仍在使用 PETKIT 云端缓存的头像。绑定后可为已有事件关联宠物；设备使用本地照片后会改为上报本地 ID。</p>
    ${unbound
      .map(
        u => `<div class="unbound">
      <code>#${esc(u.pet_ref)}</code>
      <span class="mut">${esc(u.count)} event${u.count === 1 ? '' : 's'}${
        u.last_ts ? ', last ' + esc(new Date(u.last_ts * 1000).toLocaleString()) : ''
      }</span>
      <span class="grow"></span>
      <select data-role="bind-pet">${pets
        .map(p => `<option value="${esc(p.id)}">${esc(p.name)}</option>`)
        .join('')}</select>
      <button class="mini" data-action="bind-pet-ref" data-ref="${esc(u.pet_ref)}">绑定</button>
    </div>`,
      )
      .join('')}
  </div>`;
}

// Rename in place. An input rather than a dialog because this panel has no
// prompt() anywhere and a text field is what it uses for every other edit —
// and because a pet imported from PetKit arrives as "PetKit pet 101392625",
// which is a name nobody wants to keep and the payload gave us nothing better.
onAction('rename-pet', el => {
  const id = el.dataset.id;
  const before = el.textContent;
  const input = document.createElement('input');
  input.className = 'pet-name-edit';
  input.value = before;

  // Enter, Escape and blur can all arrive for one edit — blur fires again when
  // the input is removed — so the first one to finish wins and the rest are
  // no-ops. Without the latch a rename saves twice and Escape saves anyway.
  let done = false;
  const finish = async save => {
    if (done) return;
    done = true;
    const name = input.value.trim();
    input.replaceWith(el);
    if (!save || !name || name === before) return;
    const r = await api('pets/' + encodeURIComponent(id), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    });
    if (r.error) return toast('错误：' + r.error);
    toast('已重命名');
    loadPets();
  };

  input.addEventListener('keydown', e => {
    if (e.key === 'Enter') finish(true);
    else if (e.key === 'Escape') finish(false);
  });
  input.addEventListener('blur', () => finish(true));
  el.replaceWith(input);
  input.focus();
  input.select();
});

onAction('add-pet', () => addPet());
onAction('delete-pet', el => deletePet(el.dataset.id));
onAction('delete-face', el => deleteFace(el.dataset.id, el.dataset.face));

onAction('bind-pet-ref', el =>
  bindPetRef(el.dataset.ref, el.closest('.unbound').querySelector('[data-role=bind-pet]').value),
);

onAction('pets-cloud-import', async el => {
  el.disabled = true;
  toast('正在向 PETKIT 请求并获取照片…');
  const r = await api('pets/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ device_id: Number(el.dataset.id) }),
  });
  el.disabled = false;
  if (r.error) return toast('错误：' + r.error);
  const imported = (r.results || []).filter(x => x.outcome === 'imported');
  // Both numbers matter and neither is obvious: a photo can be refused after
  // it downloads (it has to be a real JPEG), and the bound count is history
  // that just got named.
  const photos = imported.reduce((n, x) => n + x.faces_imported, 0);
  const offered = imported.reduce((n, x) => n + x.faces_offered, 0);
  const named = imported.reduce((n, x) => n + x.bound_events, 0);
  toast(
    imported.length
      ? `Imported ${imported.length} pet${imported.length === 1 ? '' : 's'}, ` +
          `${photos} of ${offered} photo${offered === 1 ? '' : 's'}` +
          (named ? `, named ${named} past event${named === 1 ? '' : 's'}` : '')
      : 'Nothing new — PetKit lists no pets this device does not already have',
  );
  loadPets();
});

async function addPet() {
  const name = document.getElementById('newPetName').value.trim();
  if (!name) return toast('请填写名称');
  const devSel = document.getElementById('newPetDevice');
  const device_ids = devSel.value ? [Number(devSel.value)] : [];
  const r = await api('pets', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: name, device_ids: device_ids }),
  });
  toast(r.pet ? '已添加宠物' : '错误：' + (r.error || '失败'));
  loadPets();
}

async function deletePet(id) {
  if (!confirm('确定删除这只宠物及其所有头像吗？')) return;
  await api('pets/' + encodeURIComponent(id), { method: 'DELETE' });
  loadPets();
}

async function deleteFace(petId, faceId) {
  // Confirmed like delete-pet: this unlinks a file, and the device keeps
  // matching against it until its next poll, so it is not trivially undone.
  if (!confirm('Remove this mugshot?')) return;
  await api('pets/' + encodeURIComponent(petId) + '/faces/' + encodeURIComponent(faceId), {
    method: 'DELETE',
  });
  loadPets();
}

async function bindPetRef(ref, petId) {
  // Additive: read the pet's current aliases first, or binding a second
  // identity would silently drop the first.
  const cur = (await api('pets/' + encodeURIComponent(petId))).pet || {};
  let ids = [];
  try {
    ids = JSON.parse(cur.device_pet_ids_json || '[]');
  } catch (e) {
    /* an unreadable alias list is replaced, not extended */
  }
  if (!ids.includes(Number(ref))) ids.push(Number(ref));
  const r = await api('pets/' + encodeURIComponent(petId), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ device_pet_ids: ids }),
  });
  toast(
    r.pet
      ? `Bound — ${r.bound_events} past event${r.bound_events === 1 ? '' : 's'} attributed`
      : '错误：' + (r.error || '失败'),
  );
  loadPets();
}

export { loadPets };
