import React, { useState, useEffect } from 'react';
import { Server, Zap, Wifi, Play, Plus, Trash2, CheckCircle2 } from 'lucide-react';
import { useT } from '../lang';

interface SavedServer { id: string; name: string; address: string; }
interface PingResult { online: boolean; ping?: number; players?: number; max?: number; motd?: string; version?: string; }

interface TabServersProps {
  servers: SavedServer[];
  onConnect: (address: string) => void;
  onAddServer: (name: string, address: string) => void;
  onRemoveServer: (id: string) => void;
  onPing: (address: string) => Promise<PingResult>;
  isLaunching: boolean;
  addLog: (level: 'INFO' | 'WARN' | 'ERROR', message: string) => void;
}

export default function TabServers({ servers, onConnect, onAddServer, onRemoveServer, onPing, isLaunching, addLog }: TabServersProps) {
  const t = useT();
  const [directAddress, setDirectAddress] = useState('');
  const [customName, setCustomName] = useState('');
  const [customIp, setCustomIp] = useState('');
  const [selectedId, setSelectedId] = useState<string>('');
  const [pings, setPings] = useState<Record<string, PingResult | 'loading'>>({});

  // Ping all saved servers on mount / when the list changes, then refresh periodically.
  useEffect(() => {
    let cancelled = false;
    const run = () => {
      servers.forEach((s) => {
        setPings((p) => (p[s.id] && p[s.id] !== 'loading' ? p : { ...p, [s.id]: 'loading' }));
        onPing(s.address).then((r) => { if (!cancelled) setPings((p) => ({ ...p, [s.id]: r })); })
          .catch(() => { if (!cancelled) setPings((p) => ({ ...p, [s.id]: { online: false } })); });
      });
    };
    run();
    const t = setInterval(run, 20000);
    return () => { cancelled = true; clearInterval(t); };
  }, [servers.map((s) => s.id + s.address).join(',')]);

  const handleLaunchDirect = (address: string, name?: string) => {
    if (isLaunching || !address.trim()) return;
    addLog('INFO', `[DirectConnect] Launching and connecting to: ${name || address}`);
    onConnect(address.trim());
  };

  const handleAdd = (e: React.FormEvent) => {
    e.preventDefault();
    if (!customName.trim() || !customIp.trim()) return;
    onAddServer(customName.trim(), customIp.trim());
    setCustomName(''); setCustomIp('');
  };

  const activeServer = servers.find((s) => s.id === selectedId);

  return (
    <div className="flex flex-col gap-4 h-full animate-fadeIn overflow-hidden">
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-4 flex-1 overflow-hidden h-full">

        {/* 左: ダイレクト接続 & 新規追加 */}
        <div className="lg:col-span-5 flex flex-col gap-4 overflow-y-auto pr-1">
          <div className="bg-[#16181f] border border-[#262a36] rounded-xl p-5 shadow-lg flex flex-col gap-4">
            <div>
              <h3 className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider font-display">{t("Direct Connection")}</h3>
              <p className="text-[13px] text-[#8a8f9c] mt-0.5">{t("Launch straight into a server without saving it")}</p>
            </div>
            <div className="space-y-3">
              <div className="space-y-1">
                <label htmlFor="direct-ip" className="text-[12px] font-mono text-[#8a8f9c] uppercase tracking-wider block font-bold">{t("Server Address (IP:Port)")}</label>
                <input
                  id="direct-ip" type="text" placeholder="e.g. hypixel.net or 127.0.0.1:25565"
                  value={directAddress} onChange={(e) => setDirectAddress(e.target.value)}
                  className="w-full bg-[#0e0f14] border border-[#262a36] rounded-lg px-3 py-2 text-sm text-[#e7e9ee] focus:border-lime-500 outline-none transition-colors placeholder:text-[#8a8f9c]/30 font-mono"
                />
              </div>
              <button
                onClick={() => handleLaunchDirect(directAddress, 'Direct Connect')}
                disabled={isLaunching || !directAddress.trim()}
                className="w-full py-3 rounded-lg font-bold text-[13px] tracking-wider transition-colors duration-150 bg-lime-600 hover:bg-lime-500 text-white disabled:opacity-40 disabled:pointer-events-none active:scale-[0.98] cursor-pointer flex items-center justify-center gap-2 border border-lime-500/10"
              >
                <Play size={13} fill="currentColor" />
                {t("CONNECT & LAUNCH GAME")}
              </button>
            </div>
          </div>

          <div className="bg-[#16181f] border border-[#262a36] rounded-xl p-5 shadow-lg flex flex-col gap-4">
            <div>
              <h3 className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider font-display">{t("Add Saved Server")}</h3>
              <p className="text-[13px] text-[#8a8f9c] mt-0.5">{t("Save your favorite servers for quick access")}</p>
            </div>
            <form onSubmit={handleAdd} className="space-y-3">
              <div className="space-y-1">
                <label htmlFor="srv-name" className="text-[12px] font-mono text-[#8a8f9c] uppercase tracking-wider block font-bold">{t("Server Name")}</label>
                <input
                  id="srv-name" type="text" placeholder="e.g. My Practice Server"
                  value={customName} onChange={(e) => setCustomName(e.target.value)}
                  className="w-full bg-[#0e0f14] border border-[#262a36] rounded-lg px-3 py-2 text-sm text-[#e7e9ee] focus:border-lime-500 outline-none transition-colors placeholder:text-[#8a8f9c]/30"
                />
              </div>
              <div className="space-y-1">
                <label htmlFor="srv-ip" className="text-[12px] font-mono text-[#8a8f9c] uppercase tracking-wider block font-bold">{t("IP Address / Host")}</label>
                <input
                  id="srv-ip" type="text" placeholder="e.g. play.myserver.org"
                  value={customIp} onChange={(e) => setCustomIp(e.target.value)}
                  className="w-full bg-[#0e0f14] border border-[#262a36] rounded-lg px-3 py-2 text-sm text-[#e7e9ee] focus:border-lime-500 outline-none transition-colors placeholder:text-[#8a8f9c]/30 font-mono"
                />
              </div>
              <button type="submit" className="w-full py-2.5 rounded-lg font-bold text-[13px] tracking-wider transition-all duration-200 bg-transparent border border-lime-500/30 hover:border-lime-400 hover:bg-lime-500/5 text-lime-400 active:scale-[0.98] cursor-pointer flex items-center justify-center gap-1.5">
                <Plus size={14} />
                {t("REGISTER SERVER")}
              </button>
            </form>
          </div>
        </div>

        {/* 右: サーバー一覧 */}
        <div className="lg:col-span-7 flex flex-col bg-[#16181f] border border-[#262a36] rounded-xl p-5 shadow-lg min-h-[350px] overflow-hidden">
          <div className="mb-4 border-b border-[#262a36]/50 pb-3 flex justify-between items-center">
            <div>
              <h3 className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider font-display">{t("Saved Servers")}</h3>
              <p className="text-[13px] text-[#8a8f9c] mt-0.5">{t('Live ping · one-click connect')}</p>
            </div>
            {activeServer && (
              <span className="text-[11px] font-mono text-lime-400 bg-lime-500/5 border border-lime-500/20 px-2 py-0.5 rounded font-bold uppercase">
                {activeServer.name.split(' ')[0]}
              </span>
            )}
          </div>

          <div className="flex-1 overflow-y-auto pr-1 space-y-2.5 scrollbar-thin">
            {servers.length === 0 && (
              <div className="h-full flex flex-col items-center justify-center text-center text-[#8a8f9c] gap-2 py-10">
                <Server size={26} className="opacity-40" />
                <p className="text-sm">{t("No saved servers yet.")}</p>
                <p className="text-[12px] opacity-70">{t("Add one on the left, or use Direct Connect.")}</p>
              </div>
            )}
            {servers.map((srv) => {
              const isSelected = srv.id === selectedId;
              const pr = pings[srv.id];
              const loading = pr === 'loading' || pr === undefined;
              const info = (pr && pr !== 'loading') ? pr : undefined;
              const online = !!(info && info.online);
              return (
                <div
                  key={srv.id}
                  onClick={() => setSelectedId(srv.id)}
                  className={`group/item border rounded-lg p-3 flex flex-col gap-2 transition-all duration-200 cursor-pointer relative overflow-hidden select-none
                    ${isSelected ? 'bg-lime-500/5 border-lime-500/30' : 'bg-[#1c1f29] border-[#262a36] hover:bg-[#262a36]/50 hover:border-lime-500/20'}`}
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className={`p-2 rounded ${isSelected ? 'bg-lime-500/10 text-lime-400' : 'bg-[#0e0f14] text-[#8a8f9c]'}`}>
                        <Server size={14} />
                      </div>
                      <div>
                        <h4 className="text-sm font-bold text-[#e7e9ee] group-hover/item:text-lime-400 transition-colors flex items-center gap-1.5">
                          {srv.name}
                          {isSelected && <CheckCircle2 size={12} className="text-lime-400 fill-lime-400/10" />}
                        </h4>
                        <p className="text-[12px] text-[#8a8f9c] mt-0.5 font-mono">{srv.address}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-3 font-mono text-[12px]">
                      {loading ? (
                        <span className="text-[#8a8f9c]">{t('Pinging…')}</span>
                      ) : online ? (
                        <>
                          <div className="text-right">
                            <span className="text-[#46d28b] font-bold block">ONLINE</span>
                            <span className="text-[11px] text-[#8a8f9c]/70">{info?.players ?? 0} / {info?.max ?? 0}</span>
                          </div>
                          <div className="flex items-center gap-1 bg-[#0e0f14] px-2 py-1 rounded border border-[#262a36]">
                            <Wifi size={10} className="text-[#46d28b]" />
                            <span className="text-lime-400 font-bold">{info?.ping ?? 0}ms</span>
                          </div>
                        </>
                      ) : (
                        <>
                          <span className="text-red-400 font-bold">OFFLINE</span>
                          <div className="flex items-center gap-1 bg-[#0e0f14] px-2 py-1 rounded border border-[#262a36] opacity-40">
                            <Wifi size={10} className="text-red-400" />
                            <span className="text-red-400">---</span>
                          </div>
                        </>
                      )}
                    </div>
                  </div>

                  <div className="flex items-center justify-between pt-2 border-t border-[#262a36]/30 mt-1">
                    <p className="text-[12px] text-[#8a8f9c]/80 truncate max-w-[70%] italic">
                      {info?.motd ? info.motd.replace(/§[0-9a-fk-or]/gi, '') : '—'}
                    </p>
                    <div className="flex items-center gap-1.5">
                      <button
                        onClick={(e) => { e.stopPropagation(); handleLaunchDirect(srv.address, srv.name); }}
                        disabled={isLaunching}
                        className="text-[11px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-md transition-all active:scale-[0.95] flex items-center gap-1 cursor-pointer bg-lime-500/10 hover:bg-lime-500/20 border border-lime-500/20 text-lime-400 hover:border-lime-500/40 disabled:opacity-40 disabled:pointer-events-none"
                        title="Launch game & connect"
                      >
                        <Zap size={10} />
                        {t("Launch")}
                      </button>
                      <button
                        onClick={(e) => { e.stopPropagation(); onRemoveServer(srv.id); }}
                        className="p-1 rounded bg-transparent hover:bg-red-500/15 text-[#8a8f9c] hover:text-red-400 transition-all cursor-pointer border border-transparent hover:border-red-500/10"
                        title="Delete server"
                      >
                        <Trash2 size={11} />
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
