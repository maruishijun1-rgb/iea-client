'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('iea', {
  // settings
  getSettings: () => ipcRenderer.invoke('settings:get'),
  saveSettings: (patch) => ipcRenderer.invoke('settings:save', patch),
  pickJava: () => ipcRenderer.invoke('dialog:pickJava'),
  pickDir: () => ipcRenderer.invoke('dialog:pickDir'),
  openGameDir: (dir) => ipcRenderer.invoke('dialog:openGameDir', dir),

  // auth
  loginOffline: (username) => ipcRenderer.invoke('auth:offline', username),
  loginMicrosoft: () => ipcRenderer.invoke('auth:microsoft'),
  logout: () => ipcRenderer.invoke('auth:logout'),

  // launch
  launch: (opts) => ipcRenderer.invoke('game:launch', opts || {}),
  stop: () => ipcRenderer.invoke('game:stop'),

  // events from main -> renderer
  on: (event, cb) => {
    const channel = 'game:' + event;
    const listener = (_e, payload) => cb(payload);
    ipcRenderer.on(channel, listener);
    return () => ipcRenderer.removeListener(channel, listener);
  },
  onAuthPrompt: (cb) => {
    const listener = (_e, payload) => cb(payload);
    ipcRenderer.on('auth:prompt', listener);
    return () => ipcRenderer.removeListener('auth:prompt', listener);
  },
});
