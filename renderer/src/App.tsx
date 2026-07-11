import React, { useState, useEffect, useRef } from 'react';
import { Account, ResourcePack, ClientLogs } from './types';
import TabPlay from './components/TabPlay';
import TabAccounts from './components/TabAccounts';
import TabPacks from './components/TabPacks';
import TabSettings from './components/TabSettings';
import TabLogs from './components/TabLogs';
import TabServers from './components/TabServers';
import TabNews from './components/TabNews';
import {
  Gamepad2, Users, Package, Settings, Terminal, Minus, Square, X,
  Server, Newspaper
} from 'lucide-react';
import { LangProvider, makeT } from './lang';

const iea = (window as any).iea;

// Parse a raw game log line ("[HH:MM:SS] [thread/LEVEL]: message") into parts.
function parseLogLine(raw: string): ClientLogs {
  const now = new Date();
  const ts = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`;
  const m = /^\[(\d\d:\d\d:\d\d)\]\s*\[([^/\]]+)\/(\w+)\]:?\s?(.*)$/.exec(raw);
  if (m) {
    const lvl = /WARN|ERROR|FATAL/.test(m[3]) ? (m[3].includes('WARN') ? 'WARN' : 'ERROR') : 'INFO';
    return { timestamp: m[1], level: lvl as any, thread: m[2], message: m[4] };
  }
  const level = /error|exception|fatal|failed/i.test(raw) ? 'ERROR' : /warn/i.test(raw) ? 'WARN' : 'INFO';
  return { timestamp: ts, level: level as any, thread: 'Game', message: raw };
}

export default function App() {
  const [currentTab, setCurrentTab] = useState<'play' | 'news' | 'servers' | 'accounts' | 'packs' | 'settings' | 'logs'>('play');

  // account state (loaded from the launcher)
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [currentAccount, setCurrentAccount] = useState<Account | null>(null);

  // resource packs (installed folder listing)
  const [packs, setPacks] = useState<ResourcePack[]>([]);

  // saved servers
  const [servers, setServers] = useState<{ id: string; name: string; address: string }[]>([]);

  // settings
  const [language, setLanguage] = useState<'jp' | 'en'>('jp');
  const [ram, setRam] = useState<number>(4.0);
  const [javaPath, setJavaPath] = useState<string>('');
  const [gamePath, setGamePath] = useState<string>('');
  const [ieaEnabled, setIeaEnabled] = useState<boolean>(true);
  const [discordEnabled, setDiscordEnabled] = useState<boolean>(true);
  const [limitLogs, setLimitLogs] = useState<boolean>(true);
  const [hypixelApiKey, setHypixelApiKey] = useState<string>('');
  const [appVersion, setAppVersion] = useState<string>('');

  // logs
  const [logs, setLogs] = useState<ClientLogs[]>([]);
  const [autoScroll, setAutoScroll] = useState<boolean>(true);
  const [isPaused, setIsPaused] = useState<boolean>(false);
  const pausedRef = useRef(false);
  const limitRef = useRef(true);
  useEffect(() => { pausedRef.current = isPaused; }, [isPaused]);
  useEffect(() => { limitRef.current = limitLogs; }, [limitLogs]);

  // launch
  const [isLaunching, setIsLaunching] = useState<boolean>(false);
  const [launchProgress, setLaunchProgress] = useState<number>(0);
  const [launchStatus, setLaunchStatus] = useState<string>('Idle');

  // real face avatar for the active account
  const [faceUrl, setFaceUrl] = useState<string>('');
  useEffect(() => {
    let cancel = false;
    const uuid = currentAccount && (currentAccount as any).uuid;
    if (!uuid) { setFaceUrl(''); return; }
    (async () => {
      try { const url = await iea.skinFace(uuid); if (!cancel && url) setFaceUrl(url); }
      catch (_) { /* keep letter fallback */ }
    })();
    return () => { cancel = true; };
  }, [currentAccount && (currentAccount as any).uuid]);

  const addLog = (level: 'INFO' | 'WARN' | 'ERROR', message: string) => {
    if (pausedRef.current) return;
    const now = new Date();
    const ts = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`;
    setLogs(prev => {
      const next = [...prev, { timestamp: ts, level, thread: 'Launcher', message }];
      return limitRef.current && next.length > 300 ? next.slice(next.length - 300) : next;
    });
  };
  const addRaw = (raw: string) => {
    if (pausedRef.current) return;
    setLogs(prev => {
      const next = [...prev, parseLogLine(raw)];
      return limitRef.current && next.length > 300 ? next.slice(next.length - 300) : next;
    });
  };

  // ---- load launcher data + subscribe to game events (once) ----
  const reloadAccounts = async () => {
    const { accounts: list, activeId } = await iea.listAccounts();
    const mapped: Account[] = (list || []).map((a: any) => ({
      id: a.id, username: a.name, type: a.type, createdAt: '', uuid: a.uuid,
    } as any));
    setAccounts(mapped);
    setCurrentAccount(mapped.find(a => a.id === activeId) || mapped[0] || null);
  };
  const reloadPacks = async () => {
    const list = await iea.listPacks();
    setPacks((list || []).map((p: any) => ({
      id: p.name, name: p.name, description: '', size: p.folder ? 'Folder' : 'ZIP', isActive: !!p.active, version: '1.8.9',
    })));
  };
  const reloadServers = async () => { setServers((await iea.listServers()) || []); };

  useEffect(() => {
    (async () => {
      try {
        const s = await iea.getSettings();
        setLanguage(s.language === 'en' ? 'en' : 'jp');
        setRam((s.maxRamMB || 2048) / 1024);
        setJavaPath(s.javaPath || '');
        setGamePath(s.gameDir || '');
        setIeaEnabled(s.injectClient !== false);
        setDiscordEnabled(s.discordRpc !== false);
        setLimitLogs(s.capLog !== false);
        setHypixelApiKey(s.hypixelKey || '');
        setAppVersion(await iea.appVersion());
        await reloadAccounts();
        await reloadPacks();
        await reloadServers();
      } catch (e) { /* ignore */ }
    })();

    const offStatus = iea.on('status', (t: string) => setLaunchStatus(t));
    const offProgress = iea.on('progress', (p: any) => {
      if (p && p.total) setLaunchProgress(Math.round((p.done / p.total) * 100));
    });
    const offLog = iea.on('log', (line: string) => addRaw(line));
    const offExit = iea.on('exit', () => {
      setIsLaunching(false); setLaunchProgress(0); setLaunchStatus('Game exited');
    });
    return () => { offStatus && offStatus(); offProgress && offProgress(); offLog && offLog(); offExit && offExit(); };
  }, []);

  // ---- accounts ----
  const handleAddAccount = async (username: string, type: 'microsoft' | 'offline') => {
    try {
      if (type === 'microsoft') { await iea.loginMicrosoft(); }
      else { await iea.loginOffline(username); }
      await reloadAccounts();
      addLog('INFO', `Profile ${type === 'microsoft' ? 'signed in' : 'added'}`);
    } catch (e: any) { addLog('ERROR', 'Login failed: ' + (e?.message || e)); }
  };
  const handleRemoveAccount = async (id: string) => {
    await iea.removeAccount(id); await reloadAccounts();
  };
  const handleSelectAccount = async (account: Account) => {
    await iea.selectAccount(account.id); await reloadAccounts();
  };

  // ---- resource packs (import real file / remove / cosmetic toggle) ----
  const handleImportPack = async () => {
    const res = await iea.importPack();
    if (res && res.ok) { addLog('INFO', `Resource pack added: ${res.name}`); await reloadPacks(); }
    else if (res && !res.canceled) addLog('ERROR', 'Import failed: ' + (res.error || res.kind || ''));
  };
  const handleRemovePack = async (id: string) => { await iea.removePack(id); await reloadPacks(); };
  const handleTogglePack = async (id: string) => {
    const p = packs.find((x) => x.id === id);
    if (!p) return;
    await iea.togglePack(id, !p.isActive); // writes options.txt
    await reloadPacks();
  };

  // ---- settings (persist immediately) ----
  const save = (patch: any) => { iea.saveSettings(patch); };
  const changeLanguage = (l: 'jp' | 'en') => { setLanguage(l); save({ language: l === 'en' ? 'en' : 'ja' }); };
  const changeRam = (v: number) => { setRam(v); save({ maxRamMB: Math.round(v * 1024) }); };
  const changeJava = (v: string) => { setJavaPath(v); save({ javaPath: v }); };
  const changeGame = (v: string) => { setGamePath(v); save({ gameDir: v }); };
  const changeHypixel = (v: string) => { setHypixelApiKey(v); save({ hypixelKey: v }); };
  const toggleIea = () => { const v = !ieaEnabled; setIeaEnabled(v); save({ injectClient: v }); };
  const toggleDiscord = () => { const v = !discordEnabled; setDiscordEnabled(v); save({ discordRpc: v }); iea.setDiscord(v); };
  const toggleLimit = () => { const v = !limitLogs; setLimitLogs(v); save({ capLog: v }); };
  const browseJava = async () => { const p = await iea.pickJava(); if (p) changeJava(p); };
  const browseGame = async () => { const p = await iea.pickDir(); if (p) changeGame(p); };

  // ---- servers ----
  const handleAddServer = async (name: string, address: string) => { setServers(await iea.addServer(name, address)); };
  const handleRemoveServer = async (id: string) => { setServers(await iea.removeServer(id)); };

  // ---- launch (optionally auto-connecting to a server) ----
  const handleStartLaunch = async (server?: string) => {
    if (isLaunching) return;
    if (!currentAccount) { setCurrentTab('accounts'); addLog('ERROR', 'Log in first (Profiles tab).'); return; }
    // Guard: a button onClick may pass a (non-cloneable) event as `server`.
    const srv = (typeof server === 'string' && server.trim()) ? server.trim() : undefined;
    setIsLaunching(true); setLaunchProgress(0); setLaunchStatus('Preparing…');
    try {
      const res = await iea.launch(srv ? { server: srv } : {});
      if (!res || !res.ok) { setIsLaunching(false); setLaunchStatus('Error'); addLog('ERROR', (res && res.error) || 'Launch failed'); }
    } catch (e: any) { setIsLaunching(false); addLog('ERROR', 'Launch failed: ' + (e?.message || e)); }
  };
  const handleKillLaunch = async () => { await iea.stop(); setLaunchStatus('Stopping…'); };

  const displayAccount: Account = currentAccount || { id: '-', username: 'Not logged in', type: 'offline', createdAt: '' };
  const lang: 'en' | 'ja' = language === 'en' ? 'en' : 'ja';
  const t = makeT(lang);

  return (
    <LangProvider lang={lang}>
    <div className="min-h-screen bg-[#06070a] geometric-grid text-[#e7e9ee] flex items-center justify-center selection:bg-lime-500/30 selection:text-lime-400">

      <div className="w-full h-screen bg-[#0e0f14] flex flex-col overflow-hidden relative z-10">

        {/* ウィンドウ上部タイトルバー (ドラッグ可能) */}
        <div className="app-drag h-12 bg-[#16181f] border-b border-[#262a36] flex items-center justify-between px-5 select-none relative">
          <div className="flex items-center gap-2">
            <span className="text-[13px] font-semibold text-[#8a8f9c] tracking-wider font-mono flex items-center gap-1.5">
              IEA Client Launcher
              <span className="text-[11px] bg-lime-500/10 text-lime-400 border border-lime-400/20 px-1.5 py-0.5 rounded-sm font-bold">
                1.8.9 Dedicated
              </span>
            </span>
          </div>

          <div className="app-no-drag flex items-center gap-1.5">
            <button onClick={() => iea.winMinimize()} className="p-1.5 rounded text-[#8a8f9c] hover:bg-[#1c1f29] hover:text-[#e7e9ee] transition-all cursor-pointer" title="Minimize">
              <Minus size={15} />
            </button>
            <button onClick={() => iea.winMaximize()} className="p-1.5 rounded text-[#8a8f9c] hover:bg-[#1c1f29] hover:text-[#e7e9ee] transition-all cursor-pointer" title="Maximize">
              <Square size={12} />
            </button>
            <button onClick={() => iea.winClose()} className="p-1.5 rounded text-[#8a8f9c] hover:bg-red-500/20 hover:text-red-400 transition-all cursor-pointer" title="Close">
              <X size={15} />
            </button>
          </div>
        </div>

        {/* ウィンドウコンテンツ */}
        <div className="flex-1 flex overflow-hidden">

          {/* 左サイドバー */}
          <div className="w-[264px] bg-[#16181f] border-r border-[#262a36] flex flex-col justify-between p-5 select-none">
            <div className="space-y-6">
              <div className="flex items-center gap-3 bg-[#0e0f14] border border-[#262a36] p-3 rounded-lg">
                <div className="w-10 h-10 rounded bg-lime-400 flex items-center justify-center text-[#0e0f14] font-bold text-xl">
                  IEA
                </div>
                <div>
                  <h1 className="text-sm font-bold tracking-widest text-[#e7e9ee] font-display">IEA CLIENT</h1>
                  <p className="text-[11px] font-mono text-lime-400 font-semibold mt-0.5 tracking-wider">Minecraft 1.8.9</p>
                </div>
              </div>

              <nav className="space-y-1.5">
                {([
                  ['play', 'GAME PLAY', Gamepad2],
                  ['news', 'CLIENT NEWS', Newspaper],
                  ['servers', 'SERVERS', Server],
                  ['accounts', 'PROFILES', Users],
                  ['packs', 'RESOURCES', Package],
                  ['settings', 'SETTINGS', Settings],
                  ['logs', 'CONSOLE', Terminal],
                ] as const).map(([tab, label]) => (
                  <button
                    key={tab}
                    data-tab={tab}
                    onClick={() => setCurrentTab(tab as any)}
                    className={`w-full flex items-center px-3.5 py-2.5 rounded-lg text-[13px] font-bold tracking-wide transition-colors cursor-pointer border
                      ${currentTab === tab
                        ? 'bg-[#1c1f29] border-[#262a36] text-lime-400 border-l-4 border-l-lime-400 pl-2.5'
                        : 'border-transparent text-[#8a8f9c] hover:text-[#e7e9ee] hover:bg-[#1c1f29]/40'
                      }`}
                  >
                    {t(label)}
                  </button>
                ))}
              </nav>
            </div>

            {/* 下部: 選択中アカウント & クライアントステータス */}
            <div className="space-y-3 pt-3 border-t border-[#262a36]">
              <div className="flex items-center gap-2.5 bg-[#0e0f14] p-2 rounded-lg border border-[#262a36]">
                <div className="w-7 h-7 rounded overflow-hidden bg-lime-500/10 flex items-center justify-center text-lime-400 font-bold text-sm select-none border border-lime-400/20 font-mono">
                  {faceUrl
                    ? <img src={faceUrl} alt="" className="w-full h-full object-cover" style={{ imageRendering: 'pixelated' }} />
                    : displayAccount.username.charAt(0).toUpperCase()}
                </div>
                <div className="min-w-0 flex-1">
                  <h4 className="text-[12px] font-bold text-[#e7e9ee] font-mono truncate">{displayAccount.username}</h4>
                  <p className="text-[10px] font-mono text-[#8a8f9c] mt-0.5 flex items-center gap-1">
                    <span className={`w-1.5 h-1.5 rounded-full ${displayAccount.type === 'microsoft' ? 'bg-blue-400' : 'bg-gray-500'}`} />
                    {t(displayAccount.type === 'microsoft' ? 'Microsoft Account' : 'Offline Mode')}
                  </p>
                </div>
              </div>

              <div className="flex items-center justify-between text-[12px] font-mono text-[#8a8f9c] px-1 select-none">
                <span className="flex items-center gap-1">
                  <span className={`w-1.5 h-1.5 rounded-full ${isLaunching ? 'bg-amber-400' : 'bg-[#46d28b]'}`} />
                  {t(isLaunching ? 'LAUNCHING' : 'CLIENT READY')}
                </span>
                <span>v{appVersion}</span>
              </div>
            </div>
          </div>

          {/* 右メインエリア */}
          <main className="flex-1 bg-[#0e0f14] p-7 overflow-hidden flex flex-col">
            <div className="flex-1 overflow-hidden">
              {currentTab === 'play' && (
                <TabPlay
                  currentAccount={displayAccount}
                  isLaunching={isLaunching}
                  launchProgress={launchProgress}
                  launchStatus={launchStatus}
                  onStartLaunch={handleStartLaunch}
                  onKillLaunch={handleKillLaunch}
                  onNavigateToLogs={() => setCurrentTab('logs')}
                  faceUrl={faceUrl}
                  appVersion={appVersion}
                />
              )}
              {currentTab === 'news' && <TabNews />}
              {currentTab === 'servers' && (
                <TabServers
                  servers={servers}
                  onConnect={(address: string) => handleStartLaunch(address)}
                  onAddServer={handleAddServer}
                  onRemoveServer={handleRemoveServer}
                  onPing={(address: string) => iea.pingServer(address)}
                  isLaunching={isLaunching}
                  addLog={addLog}
                />
              )}
              {currentTab === 'accounts' && (
                <TabAccounts
                  accounts={accounts}
                  currentAccount={displayAccount}
                  onSelectAccount={handleSelectAccount}
                  onAddAccount={handleAddAccount}
                  onRemoveAccount={handleRemoveAccount}
                />
              )}
              {currentTab === 'packs' && (
                <TabPacks
                  packs={packs}
                  onTogglePack={handleTogglePack}
                  onAddPack={handleImportPack}
                  onRemovePack={handleRemovePack}
                />
              )}
              {currentTab === 'settings' && (
                <TabSettings
                  language={language}
                  onChangeLanguage={changeLanguage}
                  ram={ram}
                  onChangeRam={changeRam}
                  javaPath={javaPath}
                  onChangeJavaPath={changeJava}
                  gamePath={gamePath}
                  onChangeGamePath={changeGame}
                  ieaEnabled={ieaEnabled}
                  onToggleIea={toggleIea}
                  discordEnabled={discordEnabled}
                  onToggleDiscord={toggleDiscord}
                  limitLogs={limitLogs}
                  onToggleLimitLogs={toggleLimit}
                  hypixelApiKey={hypixelApiKey}
                  onChangeHypixelApiKey={changeHypixel}
                  onBrowseJavaPath={browseJava}
                  onBrowseGamePath={browseGame}
                />
              )}
              {currentTab === 'logs' && (
                <TabLogs
                  logs={logs}
                  onClearLogs={() => setLogs([])}
                  autoScroll={autoScroll}
                  onToggleAutoScroll={() => setAutoScroll(!autoScroll)}
                  isPaused={isPaused}
                  onTogglePaused={() => setIsPaused(!isPaused)}
                />
              )}
            </div>
          </main>
        </div>
      </div>
    </div>
    </LangProvider>
  );
}
