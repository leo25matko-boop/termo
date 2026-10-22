'use strict';

const {contextBridge, ipcRenderer} = require('electron');

// Expose one narrow controller bridge. The renderer never receives Node or IPC.
contextBridge.exposeInMainWorld('termodom', Object.freeze({
  desktop: true,
  request: (requestPath, body) => ipcRenderer.invoke('termodom:request', requestPath, body)
}));
