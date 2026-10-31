import { onAction } from './delegate.js';
import { selectDevice } from './devices.js';
import { loadDeviceLogs } from './log.js';
import { loadCapture } from './capture.js';
import { loadPatchers } from './patchers.js';
import { loadSetup } from './setup.js';
import { loadProvision } from './provision.js';
import { loadTimeline } from './timeline.js';
import { loadPets } from './pets.js';

const TABS = ['devices', 'timeline', 'pets', 'provision', 'log', 'capture', 'patchers', 'setup'];

// Delegated rather than bound per button: the sidebar's device rows are drawn
// and redrawn by devices.js after this module has run.
document.querySelector('nav').addEventListener('click', ev => {
  const b = ev.target.closest('button');
  if (!b) return;
  document.querySelectorAll('nav button').forEach(x => {
    x.classList.remove('on');
    x.setAttribute('aria-selected', 'false');
  });
  b.classList.add('on');
  b.setAttribute('aria-selected', 'true');
  TABS.forEach(t =>
    document.getElementById('tab-' + t).classList.toggle('hidden', t !== b.dataset.tab),
  );
  // The toolbar names the section, as a native window's title does. Devices
  // writes its own, with the device's name and state.
  document.getElementById('pageTitle').textContent = b.querySelector('.lbl').textContent;
  document.getElementById('pageSub').textContent = '';
  document.getElementById('pageActions').innerHTML = '';
  // Devices needs an entry now: refreshes are skipped while the tab is
  // hidden, so coming back to it has to repaint rather than wait for a tick.
  if (b.dataset.tab === 'devices') selectDevice(b.dataset.device);
  if (b.dataset.tab === 'log') loadDeviceLogs();
  if (b.dataset.tab === 'capture') loadCapture();
  if (b.dataset.tab === 'patchers') loadPatchers();
  if (b.dataset.tab === 'setup') loadSetup();
  if (b.dataset.tab === 'provision') loadProvision();
  if (b.dataset.tab === 'timeline') loadTimeline();
  if (b.dataset.tab === 'pets') loadPets();
});

function gotoTab(t) {
  document.querySelector('nav button[data-tab="' + t + '"]:not([data-device])').click();
}
onAction('goto-tab', el => gotoTab(el.dataset.tab));
