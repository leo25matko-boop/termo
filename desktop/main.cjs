'use strict';

const {app, BrowserWindow, dialog, ipcMain, Menu, Notification, shell} = require('electron');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {mkdir, readFile, writeFile} = require('node:fs/promises');

const CHANNEL = 'termodom:request';
const REPOSITORY = 'leo25matko-boop/termo';
const SOURCE = 'https://automatiziraj.se/centralno/php/novo.php';
const applicationRoot = path.resolve(__dirname, '..');
const indexFile = path.join(applicationRoot, 'public', 'index.html');
const indexUrl = pathToFileURL(indexFile).href;
let mainWindow;
let controller;
let AppError;
let allowClose = false;

app.setName('TermoDom');
app.setAppUserModelId('se.automatiziraj.termodom');

function ownPage(value) {
  try {
    const candidate = new URL(value);
    candidate.hash = '';
    return candidate.href === indexUrl;
  } catch { return false; }
}

function allowedExternal(value) {
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.port) return false;
    if (url.origin === 'https://automatiziraj.se') return url.pathname === new URL(SOURCE).pathname && !url.search;
    return url.origin === 'https://github.com' &&
      (url.pathname === '/' + REPOSITORY || url.pathname.startsWith('/' + REPOSITORY + '/'));
  } catch { return false; }
}

function openExternal(value) {
  if (allowedExternal(value)) void shell.openExternal(value).catch(() => {});
}

function authorizedSender(event) {
  return mainWindow && !mainWindow.isDestroyed() &&
    event.sender === mainWindow.webContents &&
    event.senderFrame === mainWindow.webContents.mainFrame &&
    ownPage(event.senderFrame.url);
}

async function handleRequest(event, requestPath, body) {
  if (!authorizedSender(event)) return {status: 403, body: {error: 'Nedopušteni poziv aplikacije.'}};
  try {
    if (typeof requestPath !== 'string' || !/^\/api\/[a-z]+(?:\/[a-z0-9-]+)?$/.test(requestPath)) {
      throw new AppError('Neispravna funkcija aplikacije.', 400);
    }
    const isWrite = body !== undefined;
    if (isWrite && (!body || typeof body !== 'object' || Array.isArray(body) || JSON.stringify(body).length > 16_384)) {
      throw new AppError('Neispravni ili preveliki podaci zahtjeva.', 400);
    }
    let data;
    if (!isWrite) {
      if (requestPath === '/api/health') data = {app: 'termodom', ok: true, desktop: true};
      else if (requestPath === '/api/session') data = {app: 'termodom', token: 'desktop'};
      else if (requestPath === '/api/status') data = await controller.status();
      else if (requestPath === '/api/settings') data = await controller.settings();
      else if (requestPath === '/api/catalog') data = controller.catalog();
      else if (/^\/api\/schedule\/(kat|prizemlje)$/.test(requestPath)) data = await controller.schedule(requestPath.split('/').at(-1));
      else if (/^\/api\/chart\/[a-z0-9-]+$/.test(requestPath)) data = await controller.chart(requestPath.split('/').at(-1));
      else throw new AppError('Funkcija aplikacije nije pronađena.', 404);
    } else {
      if (/^\/api\/schedule\/(kat|prizemlje)$/.test(requestPath)) data = await controller.saveSchedule(requestPath.split('/').at(-1), body.values);
      else if (requestPath === '/api/settings') data = await controller.saveSettings(body.values);
      else if (requestPath === '/api/logout') data = await controller.logout();
      else if (requestPath === '/api/shutdown') {
        data = {ok: true};
        setTimeout(() => app.quit(), 100);
      } else throw new AppError('Funkcija aplikacije nije pronađena.', 404);
    }
    return {status: 200, body: data};
  } catch (error) {
    return {
      status: error instanceof AppError ? error.status : 500,
      body: {error: error instanceof AppError ? error.message : 'Lokalna aplikacija naišla je na pogrešku.', ...(error instanceof AppError ? error.details : {})}
    };
  }
}

function createWindow() {
  mainWindow = new BrowserWindow({
    title: 'TermoDom', icon: path.join(__dirname, 'icon.png'), width: 1450, height: 980, minWidth: 900, minHeight: 650,
    backgroundColor: '#f5f6f2', show: false, autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true,
      nodeIntegration: false, sandbox: true, webSecurity: true,
      allowRunningInsecureContent: false, webviewTag: false, spellcheck: false
    }
  });
  Menu.setApplicationMenu(null);
  const contents = mainWindow.webContents;
  contents.session.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  contents.session.setPermissionCheckHandler(() => false);
  contents.setWindowOpenHandler(({url}) => { openExternal(url); return {action: 'deny'}; });
  contents.on('will-attach-webview', event => event.preventDefault());
  contents.on('will-navigate', (event, url) => {
    if (!ownPage(url)) { event.preventDefault(); openExternal(url); }
  });
  contents.on('will-redirect', (event, url) => { if (!ownPage(url)) event.preventDefault(); });
  contents.on('will-prevent-unload', event => {
    const answer = allowClose ? 1 : dialog.showMessageBoxSync(mainWindow, {
      type: 'question', title: 'Zatvoriti TermoDom?',
      message: 'Postoje nespremljene promjene.',
      detail: 'Zatvaranjem aplikacije odbacuju se promjene koje nisu spremljene u sustav grijanja.',
      buttons: ['Ostani', 'Zatvori'], defaultId: 0, cancelId: 0, noLink: true
    });
    if (answer === 1) { allowClose = true; event.preventDefault(); }
  });
  mainWindow.once('ready-to-show', () => mainWindow?.show());
  mainWindow.on('closed', () => { mainWindow = null; });
  void mainWindow.loadFile(indexFile);
}

async function autoDownload() {
  let config;
  try { config = JSON.parse(await readFile(path.join(applicationRoot, 'github.json'), 'utf8')); }
  catch { return; }
  if (!config.autoDownloadOnStart || config.repository !== REPOSITORY) return;
  const directory = app.getPath('userData');
  const statusDirectory = path.join(directory, 'downloads');
  try {
    const {downloadLatest} = await import(pathToFileURL(path.join(applicationRoot, 'lib', 'github-release.mjs')).href);
    const result = await downloadLatest(config, {directory});
    await mkdir(statusDirectory, {recursive: true});
    await writeFile(path.join(statusDirectory, 'status.txt'), `${new Date().toISOString()}\n${result.message}\n${result.file || ''}\n`, 'utf8');
    if (result.status === 'downloaded' && Notification.isSupported()) {
      new Notification({title: 'TermoDom', body: 'Najnoviji ZIP aplikacije preuzet je u lokalnu mapu podataka TermoDom.'}).show();
    }
  } catch (error) {
    await mkdir(statusDirectory, {recursive: true});
    await writeFile(path.join(statusDirectory, 'status.txt'), `${new Date().toISOString()}\nPreuzimanje nije uspjelo: ${error.message}\n`, 'utf8').catch(() => {});
  }
}

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.show(); mainWindow.focus();
  });
  app.whenReady().then(async () => {
    const modules = await Promise.all([
      import(pathToFileURL(path.join(applicationRoot, 'lib', 'controller.mjs')).href),
      import(pathToFileURL(path.join(applicationRoot, 'lib', 'parsers.mjs')).href)
    ]);
    controller = new modules[0].HeatingController();
    AppError = modules[1].AppError;
    ipcMain.handle(CHANNEL, handleRequest);
    createWindow();
    void autoDownload().catch(() => {});
  }).catch(error => {
    dialog.showErrorBox('TermoDom se nije pokrenuo', error.message);
    app.quit();
  });
  app.on('window-all-closed', () => app.quit());
}
