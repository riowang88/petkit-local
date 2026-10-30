// Isolated browser contract checks: no real device requests are made.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  });
  const page = await browser.newPage();
  const posted = [];
  await page.route('**/api/**', async route => {
    if (route.request().method() === 'POST') posted.push(route.request().postDataJSON());
    await route.fulfill({ json: { ok: true, delivered: 'local' } });
  });
  await page.goto('http://127.0.0.1:18221/');
  await page.evaluate(async () => {
    document.body.innerHTML = '<input id="hideHb" type="checkbox"><div id="toast"></div>';
    const { controlRow } = await import('/js/entities.js');
    await import('/js/commands.js');
    document.body.innerHTML += controlRow(123, {
      component: 'select',
      key: 'sand_type',
      name: 'Litter Type',
      value: 1,
      options: ['bentonite', 'tofu', 'mixed'],
      option_values: [1, 2, 3],
    });
  });
  assert.match(await page.locator('label').innerText(), /猫砂类型/);
  assert.equal(await page.locator('option').nth(1).textContent(), '豆腐猫砂');
  await page.locator('select').selectOption({ label: '豆腐猫砂' });
  await page.waitForTimeout(200);
  assert.deepEqual(posted[0], { entity: 'sand_type', value: 'tofu' });
  const names = await page.evaluate(async () => {
    const { entityName, optionLabel } = await import('/js/zh.js');
    return [
      entityName('cleaning_start', 'Scoop'),
      entityName('dump_litter', 'Dump Litter'),
      entityName('unknown_key', 'Unrecognized value'),
      optionLabel('cleaning_interval', '1h15min'),
      entityName('cleaning_event', 'Work Event'),
      optionLabel('device_status', 'odor removal'),
      entityName('reset_bagbox', 'Reset Bag Counter'),
    ];
  });
  assert.deepEqual(names, [
    '开始清理',
    '排空猫砂',
    'Unrecognized value',
    '1 小时 15 分钟',
    '工作事件',
    '除臭中',
    '重置垃圾袋计数',
  ]);
  const escaped = await page.evaluate(async () => {
    const { controlRow } = await import('/js/entities.js');
    const unsafe = '<img src=x onerror=alert(1)>';
    const host = document.createElement('div');
    host.innerHTML = controlRow(123, {
      component: 'select',
      key: 'custom_key',
      name: unsafe,
      value: unsafe,
      options: [unsafe],
      option_values: [unsafe],
    });
    return {
      images: host.querySelectorAll('img').length,
      option: host.querySelector('option').value,
      label: host.textContent,
    };
  });
  assert.equal(escaped.images, 0);
  assert.equal(escaped.option, '<img src=x onerror=alert(1)>');
  assert.match(escaped.label, /<img src=x onerror=alert\(1\)>/);
  const reasons = await page.evaluate(async () => {
    const { streamProbeReason } = await import('/js/zh.js');
    const { liveWaitReason } = await import('/js/devices.js');
    return {
      mapped: ['unreachable', 'timeout', 'http_404', 'bad_signature', 'busy', 'no_ip', 'odd'].map(
        streamProbeReason,
      ),
      none: liveWaitReason(null),
      busy: liveWaitReason({ ok: true, reason: 'ok', busy: true }),
      unsafe: liveWaitReason({ ok: false, reason: '<img src=x onerror=alert(1)>', busy: false }),
    };
  });
  assert.deepEqual(reasons.mapped, [
    'Mac 无法连接设备（网络不可达）',
    '连接设备超时',
    '设备返回 HTTP 404',
    '设备未输出视频流',
    '正在观看中',
    '设备尚未上报 IP',
    'odd',
  ]);
  assert.equal(reasons.none, '');
  assert.equal(reasons.busy, '（正在观看中）');
  // An unknown reason is shown as-is, and so must be escaped like any other text.
  assert.equal(reasons.unsafe, '（&lt;img src=x onerror=alert(1)&gt;）');
  await browser.close();
  console.log('PASS: translated display preserves original control values and action identities');
})().catch(e => {
  console.error(e);
  process.exit(1);
});
