'use strict';
window.__termodomReady = window.MobileBridge
  ? import('./android-bootstrap.mjs').then(module => {
      if (!module.androidReady) throw new Error('Android veza nije pokrenuta.');
    })
  : Promise.resolve();
// api() presents the startup failure in the normal connection screen.
window.__termodomReady.catch(() => {});
