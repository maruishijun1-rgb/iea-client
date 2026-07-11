import React, { useState } from 'react';
import { Sliders, Eye, EyeOff, Folder, ToggleLeft, ToggleRight, Sparkles } from 'lucide-react';
import { useT } from '../lang';

interface TabSettingsProps {
  language: 'jp' | 'en';
  onChangeLanguage: (lang: 'jp' | 'en') => void;
  ram: number;
  onChangeRam: (ram: number) => void;
  javaPath: string;
  onChangeJavaPath: (path: string) => void;
  gamePath: string;
  onChangeGamePath: (path: string) => void;
  ieaEnabled: boolean;
  onToggleIea: () => void;
  discordEnabled: boolean;
  onToggleDiscord: () => void;
  limitLogs: boolean;
  onToggleLimitLogs: () => void;
  hypixelApiKey: string;
  onChangeHypixelApiKey: (key: string) => void;
  onBrowseJavaPath: () => void;
  onBrowseGamePath: () => void;
}

export default function TabSettings({
  language,
  onChangeLanguage,
  ram,
  onChangeRam,
  javaPath,
  onChangeGamePath,
  onChangeJavaPath,
  gamePath,
  ieaEnabled,
  onToggleIea,
  discordEnabled,
  onToggleDiscord,
  limitLogs,
  onToggleLimitLogs,
  hypixelApiKey,
  onChangeHypixelApiKey,
  onBrowseJavaPath,
  onBrowseGamePath,
}: TabSettingsProps) {
  const t = useT();
  const [showApiKey, setShowApiKey] = useState(false);

  // RAMの推奨判定
  const getRamStatus = (val: number) => {
    if (val < 2) return { text: 'Low RAM (May lag)', color: 'text-red-400' };
    if (val >= 2 && val <= 6) return { text: 'Recommended for 1.8.9', color: 'text-lime-400' };
    return { text: 'High RAM (May cause GC lag)', color: 'text-orange-400' };
  };

  const ramStatus = getRamStatus(ram);

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 h-full animate-fadeIn overflow-y-auto pr-1 pb-4 scrollbar-thin">
      {/* 左カラム: コア設定 (7 cols) */}
      <div className="lg:col-span-7 flex flex-col gap-4">
        {/* Minecraft・システム関連設定 */}
        <div className="bg-[#16181f] border border-[#262a36] rounded-xl p-5 shadow-lg space-y-5">
          <div className="flex items-center gap-2 border-b border-[#262a36]/50 pb-3">
            <Sliders size={15} className="text-lime-400" />
            <h3 className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider font-display">{t('Game Preferences')}</h3>
          </div>

          {/* 言語設定 */}
          <div className="space-y-1.5">
            <label className="text-[12px] font-mono text-[#8a8f9c] uppercase tracking-wider block font-bold">{t('Language / 言語')}</label>
            <div className="flex gap-2">
              <button
                id="select-lang-jp"
                onClick={() => onChangeLanguage('jp')}
                className={`flex-1 py-2 px-3 rounded-lg border text-sm font-semibold transition-all cursor-pointer text-center
                  ${language === 'jp'
                    ? 'bg-lime-500/10 border-lime-500/30 text-lime-400 shadow-[0_4px_12px_rgba(163,230,53,0.03)]'
                    : 'bg-[#0e0f14] border-[#262a36] text-[#8a8f9c] hover:bg-[#1c1f29]'
                  }`}
              >
                日本語 (Japanese)
              </button>
              <button
                id="select-lang-en"
                onClick={() => onChangeLanguage('en')}
                className={`flex-1 py-2 px-3 rounded-lg border text-sm font-semibold transition-all cursor-pointer text-center
                  ${language === 'en'
                    ? 'bg-lime-500/10 border-lime-500/30 text-lime-400 shadow-[0_4px_12px_rgba(163,230,53,0.03)]'
                    : 'bg-[#0e0f14] border-[#262a36] text-[#8a8f9c] hover:bg-[#1c1f29]'
                  }`}
              >
                English (US)
              </button>
            </div>
          </div>

          {/* RAM割り当て */}
          <div className="space-y-2">
            <div className="flex justify-between items-center">
              <label htmlFor="input-ram" className="text-[12px] font-mono text-[#8a8f9c] uppercase tracking-wider block font-bold">{t('Memory Allocation (RAM)')}</label>
              <span className="text-[13px] font-bold text-lime-400 font-mono bg-lime-500/5 border border-lime-500/20 px-2.5 py-0.5 rounded">
                {ram.toFixed(1)} GB
              </span>
            </div>
            
            <input
              id="input-ram"
              type="range"
              min="1.0"
              max="16.0"
              step="0.5"
              value={ram}
              onChange={(e) => onChangeRam(parseFloat(e.target.value))}
              className="w-full h-1.5 bg-[#0e0f14] rounded-lg appearance-none cursor-pointer accent-lime-500 outline-none border border-[#262a36]"
            />
            
            <div className="flex justify-between text-[11px] font-mono text-[#8a8f9c]/60">
              <span>1.0 GB</span>
              <span className={ramStatus.color}>{t(ramStatus.text)}</span>
              <span>16.0 GB</span>
            </div>
          </div>

          {/* Java 実行ファイルのパス */}
          <div className="space-y-1.5">
            <div className="flex justify-between items-center">
              <label htmlFor="java-path" className="text-[12px] font-mono text-[#8a8f9c] uppercase tracking-wider block font-bold">{t('Java Executable Path')}</label>
              <button
                onClick={onBrowseJavaPath}
                className="text-[11px] font-mono text-lime-400 hover:underline flex items-center gap-1 cursor-pointer font-bold"
              >
                <Folder size={10} /> {t('Auto Detect')}
              </button>
            </div>
            <div className="flex gap-2">
              <input
                id="java-path"
                type="text"
                value={javaPath}
                onChange={(e) => onChangeJavaPath(e.target.value)}
                className="flex-1 bg-[#0e0f14] border border-[#262a36] rounded-lg px-3 py-2 text-sm text-[#e7e9ee] outline-none font-mono focus:border-lime-500/50"
              />
              <button
                onClick={onBrowseJavaPath}
                className="bg-[#1c1f29] border border-[#262a36] hover:bg-[#262a36] text-[#e7e9ee] px-3.5 py-2 rounded-lg text-sm font-bold cursor-pointer active:scale-[0.98] transition-colors flex items-center gap-1.5"
              >
                <Folder size={12} className="text-lime-400" />
                {t('Browse')}
              </button>
            </div>
          </div>

          {/* ゲームディレクトリ */}
          <div className="space-y-1.5">
            <label htmlFor="game-path" className="text-[12px] font-mono text-[#8a8f9c] uppercase tracking-wider block font-bold">{t('Game Directory (.minecraft)')}</label>
            <div className="flex gap-2">
              <input
                id="game-path"
                type="text"
                value={gamePath}
                onChange={(e) => onChangeGamePath(e.target.value)}
                className="flex-1 bg-[#0e0f14] border border-[#262a36] rounded-lg px-3 py-2 text-sm text-[#e7e9ee] outline-none font-mono focus:border-lime-500/50"
              />
              <button
                onClick={onBrowseGamePath}
                className="bg-[#1c1f29] border border-[#262a36] hover:bg-[#262a36] text-[#e7e9ee] px-3.5 py-2 rounded-lg text-sm font-bold cursor-pointer active:scale-[0.98] transition-colors flex items-center gap-1.5"
              >
                <Folder size={12} className="text-lime-400" />
                {t('Browse')}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* 右カラム: クライアントトグル & API設定 (5 cols) */}
      <div className="lg:col-span-5 flex flex-col gap-4">
        {/* トグル設定群 */}
        <div className="bg-[#16181f] border border-[#262a36] rounded-xl p-5 shadow-lg space-y-4">
          <div className="flex items-center gap-2 border-b border-[#262a36]/50 pb-3">
            <Sparkles size={15} className="text-lime-400" />
            <h3 className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider font-display">{t('Client Features')}</h3>
          </div>

          {/* IEAクライアント有効化 */}
          <div 
            onClick={onToggleIea}
            className="flex items-center justify-between cursor-pointer select-none group/toggle py-1"
          >
            <div className="max-w-[75%]">
              <h4 className="text-sm font-bold text-[#e7e9ee] group-hover/toggle:text-lime-400 transition-colors">
                {t('Enable IEA Client patches')}
              </h4>
              <p className="text-[12px] text-[#8a8f9c] mt-0.5">{t('FPS optimization, PvP HUD & custom crosshairs.')}</p>
            </div>
            <button id="toggle-iea" className="text-lime-400 cursor-pointer">
              {ieaEnabled ? (
                <ToggleRight size={32} className="text-lime-400" />
              ) : (
                <ToggleLeft size={32} className="text-[#8a8f9c]" />
              )}
            </button>
          </div>

          {/* Discord表示 */}
          <div 
            onClick={onToggleDiscord}
            className="flex items-center justify-between cursor-pointer select-none group/toggle py-1 border-t border-[#262a36]/30 pt-3"
          >
            <div className="max-w-[75%]">
              <h4 className="text-sm font-bold text-[#e7e9ee] group-hover/toggle:text-lime-400 transition-colors">
                {t('Discord Rich Presence')}
              </h4>
              <p className="text-[12px] text-[#8a8f9c] mt-0.5">{t('Show active status, current server & stats on Discord.')}</p>
            </div>
            <button id="toggle-discord" className="text-lime-400 cursor-pointer">
              {discordEnabled ? (
                <ToggleRight size={32} className="text-lime-400" />
              ) : (
                <ToggleLeft size={32} className="text-[#8a8f9c]" />
              )}
            </button>
          </div>

          {/* ログ制限 */}
          <div 
            onClick={onToggleLimitLogs}
            className="flex items-center justify-between cursor-pointer select-none group/toggle py-1 border-t border-[#262a36]/30 pt-3"
          >
            <div className="max-w-[75%]">
              <h4 className="text-sm font-bold text-[#e7e9ee] group-hover/toggle:text-lime-400 transition-colors">
                {t('Limit Log File Size')}
              </h4>
              <p className="text-[12px] text-[#8a8f9c] mt-0.5">{t('Auto-clear startup logs older than 7 days to save disk.')}</p>
            </div>
            <button id="toggle-logs" className="text-lime-400 cursor-pointer">
              {limitLogs ? (
                <ToggleRight size={32} className="text-lime-400" />
              ) : (
                <ToggleLeft size={32} className="text-[#8a8f9c]" />
              )}
            </button>
          </div>
        </div>

        {/* Hypixel API 設定 */}
        <div className="bg-[#16181f] border border-[#262a36] rounded-xl p-5 shadow-lg space-y-4">
          <div className="flex items-center gap-2 border-b border-[#262a36]/50 pb-3">
            <Eye size={15} className="text-lime-400" />
            <h3 className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider font-display">{t('Hypixel Integration')}</h3>
          </div>

          <div className="space-y-1.5">
            <div className="flex justify-between items-center">
              <label htmlFor="hypixel-key" className="text-[12px] font-mono text-[#8a8f9c] uppercase tracking-wider block font-bold">{t('Hypixel API Key')}</label>
            </div>
            <div className="flex gap-1.5 relative">
              <input
                id="hypixel-key"
                type={showApiKey ? 'text' : 'password'}
                placeholder="00000000-0000-0000-0000-000000000000"
                value={hypixelApiKey}
                onChange={(e) => onChangeHypixelApiKey(e.target.value)}
                className="flex-1 bg-[#0e0f14] border border-[#262a36] rounded-lg pl-3 pr-9 py-2 text-sm text-[#e7e9ee] outline-none font-mono focus:border-lime-500/50"
              />
              <button
                onClick={() => setShowApiKey(!showApiKey)}
                className="absolute right-2.5 top-2 text-[#8a8f9c] hover:text-[#e7e9ee] transition-colors cursor-pointer"
                title={showApiKey ? 'Hide Key' : 'Show Key'}
              >
                {showApiKey ? <EyeOff size={14} /> : <Eye size={14} />}
              </button>
            </div>
            <p className="text-[11px] text-[#8a8f9c]/80 leading-relaxed">
              Provides real-time stats overlay, bedwars win streak counter, and instant level detection inside Hypixel lobbies. Use <code className="bg-[#0e0f14] px-1.5 py-0.5 rounded text-lime-400 font-mono text-[10px] border border-[#262a36]">/api new</code> on Hypixel to obtain yours.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
