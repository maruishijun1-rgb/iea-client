import React, { useState, useEffect } from 'react';
import { ResourcePack } from '../types';
import { FolderUp, ArrowLeftRight, ArrowRight, ArrowLeft, Trash2, HelpCircle, Package } from 'lucide-react';
import { useT } from '../lang';

const iea = (window as any).iea;

interface TabPacksProps {
  packs: ResourcePack[];
  onTogglePack: (id: string) => void;
  onAddPack: (name: string, description: string, size: string, version: string) => void;
  onRemovePack: (id: string) => void;
}

export default function TabPacks({
  packs,
  onTogglePack,
  onAddPack,
  onRemovePack,
}: TabPacksProps) {
  const t = useT();
  const [dragOver, setDragOver] = useState(false);
  const [icons, setIcons] = useState<Record<string, string>>({});

  // Fetch each pack's real pack.png icon (via main).
  useEffect(() => {
    let cancel = false;
    packs.forEach((p) => {
      if (icons[p.id]) return;
      iea.packIcon(p.id).then((u: string) => { if (!cancel && u) setIcons((x) => ({ ...x, [p.id]: u })); }).catch(() => {});
    });
    return () => { cancel = true; };
  }, [packs.map((p) => p.id).join(',')]);

  const packIcon = (pack: ResourcePack) => {
    const url = icons[pack.id];
    if (url) return <img src={url} alt="" draggable={false} className="w-10 h-10 rounded-lg bg-black/30 border border-[#262a36] flex-shrink-0 object-cover" style={{ imageRendering: 'pixelated' }} />;
    return <div className="w-10 h-10 rounded-lg bg-black/30 border border-[#262a36] flex items-center justify-center text-[#8a8f9c] flex-shrink-0"><Package size={18} /></div>;
  };

  const availablePacks = packs.filter(p => !p.isActive);
  const selectedPacks = packs.filter(p => p.isActive);

  // ドラッグ＆ドロップのダミーハンドラー
  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(true);
  };

  const handleDragLeave = () => {
    setDragOver(false);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    
    // ランダムなパックを追加
    const names = [
      '§dCherryPvP [32x] Short Swords',
      '§bAqua Pack [16x] - FPS Boost',
      '§eGolden Edit [64x] by IEA',
      '§7Bedwars Pro v4 [16x]',
      '§cBloodRed PvP [128x] - Animated'
    ];
    const descs = [
      'Short swords, low fire, custom sky, and beautiful dynamic pink particles.',
      'Highly optimized pixel layout, crystal clear armor, and competitive HUD support.',
      'Specially designed custom UI, golden textures, and high contrast bow charging tiers.',
      'Ultra smooth blocks, clean wool colors, and optimized FPS performance.',
      'Realistic high resolution, epic crosshair, animated ender pearls, and blood red theme.'
    ];

    const idx = Math.floor(Math.random() * names.length);
    onAddPack(names[idx], descs[idx], '8.4 MB', '1.8.9');
  };

  const triggerBrowseFile = () => {
    // クリックで追加
    const names = [
      '§dCosmic Blue [32x] PvP',
      '§aEmerald Speed [16x]',
      '§5Amethyst PvP [64x]',
      '§6Furious Fire [32x]',
      '§fClassic Revamp [16x]'
    ];
    const descs = [
      'Galaxy skybox, smooth ores, custom swords, and neon blue outlines.',
      'Optimized green theme, short fire, clean particles, and custom sound effects.',
      'Royal purple design, custom animated items, custom bow trail, and FPS boost.',
      'Fierce flame animations, custom particle pack, low shield, and HD armor.',
      'Original look with short swords, clean breaking animation, and transparent GUI.'
    ];
    const idx = Math.floor(Math.random() * names.length);
    onAddPack(names[idx], descs[idx], '12.1 MB', '1.8.9');
  };

  // カラータグを除去してプレーンテキストにする
  const cleanName = (name: string) => {
    return name.replace(/§[0-9a-z]/g, '');
  };

  return (
    <div className="flex flex-col gap-4 h-full animate-fadeIn min-h-0">
      {/* 上部: ドラッグ＆ドロップ アップロード領域 */}
      <div
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={triggerBrowseFile}
        className={`border-2 border-dashed rounded-lg p-5 text-center cursor-pointer transition-all duration-300 relative overflow-hidden flex flex-col items-center justify-center gap-1.5 select-none h-[110px] flex-shrink-0
          ${dragOver
            ? 'border-lime-400 bg-lime-500/5'
            : 'border-[#262a36] hover:border-lime-500/50 bg-[#16181f] hover:bg-[#1c1f29]/30'
          }`}
      >
        <FolderUp size={20} className={`${dragOver ? 'text-lime-400' : 'text-[#8a8f9c] group-hover:text-lime-400'}`} />
        
        <div>
          <p className="text-sm font-semibold text-[#e7e9ee]">
            {dragOver ? t('Drop file to Import Resource Pack') : t('Drag & Drop .zip to Import Pack')}
          </p>
          <p className="text-[12px] text-[#8a8f9c] mt-1">
            {t('or click to browse')}
          </p>
        </div>
      </div>

      {/* 下部: 2カラムドラッグ＆移動風 リソースパック選択 */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 flex-1 min-h-0">
        {/* 左: 利用可能 (Available) */}
        <div className="bg-[#16181f] border border-[#262a36] rounded-xl p-4 shadow-lg flex flex-col h-full min-h-0">
          <div className="flex justify-between items-center mb-3 border-b border-[#262a36]/50 pb-2.5">
            <div>
              <h3 className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider font-display">{t("Available Packs")}</h3>
              <p className="text-[12px] text-[#8a8f9c] mt-0.5">{t("Not loaded into game")}</p>
            </div>
            <span className="text-[12px] font-mono bg-[#0e0f14] border border-[#262a36] text-[#8a8f9c] px-2.5 py-0.5 rounded font-bold">
              {availablePacks.length} {t("packs")}
            </span>
          </div>

          <div className="flex-1 overflow-y-auto space-y-2 pr-1 scrollbar-thin">
            {availablePacks.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-center p-4 border border-dashed border-[#262a36] rounded-lg bg-[#0e0f14]/20">
                <HelpCircle size={24} className="text-[#8a8f9c]/20 mb-1" />
                <p className="text-[12px] text-[#8a8f9c]">{t("No packs available.")}</p>
                <p className="text-[11px] text-[#8a8f9c]/50">{t("Import pack zip file above.")}</p>
              </div>
            ) : (
              availablePacks.map((pack) => (
                <div
                  key={pack.id}
                  onClick={() => onTogglePack(pack.id)}
                  className="group/pack bg-[#1c1f29] border border-[#262a36] hover:border-lime-500/30 p-2 rounded-lg flex items-center justify-between transition-all duration-200 cursor-pointer select-none"
                >
                  <div className="flex items-center gap-3">
                    {packIcon(pack)}
                    <div className="max-w-[160px] sm:max-w-[180px]">
                      <h4 className="text-sm font-bold text-[#e7e9ee] group-hover/pack:text-lime-400 transition-colors truncate">
                        {cleanName(pack.name)}
                      </h4>
                      <p className="text-[12px] text-[#8a8f9c] mt-0.5 line-clamp-1">
                        {pack.description}
                      </p>
                      <div className="flex gap-1.5 mt-1">
                        <span className="text-[10px] font-mono text-[#8a8f9c] bg-[#0e0f14] border border-[#262a36] px-1.5 py-0.2 rounded-sm font-semibold">
                          {pack.size}
                        </span>
                        <span className="text-[10px] font-mono text-[#8a8f9c] bg-[#0e0f14] border border-[#262a36] px-1.5 py-0.2 rounded-sm font-semibold">
                          {pack.version}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div className="flex gap-1">
                    {/* 右移動矢印 */}
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        onTogglePack(pack.id);
                      }}
                      className="p-1.5 rounded bg-[#0e0f14] border border-[#262a36] hover:border-lime-500/20 hover:text-lime-400 text-[#8a8f9c] transition-colors cursor-pointer active:scale-[0.9]"
                      title="Activate Pack"
                    >
                      <ArrowRight size={13} />
                    </button>
                    {/* 削除 */}
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        onRemovePack(pack.id);
                      }}
                      className="p-1.5 rounded bg-[#0e0f14] border border-[#262a36] hover:border-red-500/20 hover:text-red-400 text-[#8a8f9c] transition-colors cursor-pointer active:scale-[0.9]"
                      title="Delete Pack"
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* 右: 適用中 (Selected) */}
        <div className="bg-[#16181f] border border-[#262a36] rounded-xl p-4 shadow-lg flex flex-col h-full min-h-0">
          <div className="flex justify-between items-center mb-3 border-b border-[#262a36]/50 pb-2.5">
            <div>
              <h3 className="text-sm font-bold text-lime-400 uppercase tracking-wider font-display">{t("Active Packs")}</h3>
              <p className="text-[12px] text-[#8a8f9c] mt-0.5">{t("Loaded into Minecraft")}</p>
            </div>
            <span className="text-[12px] font-mono bg-lime-500/10 border border-lime-500/20 text-lime-400 px-2.5 py-0.5 rounded font-bold">
              {selectedPacks.length} {t("active")}
            </span>
          </div>

          <div className="flex-1 overflow-y-auto space-y-2 pr-1 scrollbar-thin">
            {selectedPacks.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-center p-4 border border-dashed border-[#262a36] rounded-lg bg-[#0e0f14]/20">
                <ArrowLeftRight size={24} className="text-[#8a8f9c]/15 mb-1" />
                <p className="text-[12px] text-[#8a8f9c]">{t("No active packs.")}</p>
                <p className="text-[11px] text-[#8a8f9c]/50">{t("Click right arrow on available packs.")}</p>
              </div>
            ) : (
              selectedPacks.map((pack) => (
                <div
                  key={pack.id}
                  onClick={() => onTogglePack(pack.id)}
                  className="group/pack bg-[#1c1f29] border border-lime-500/20 hover:border-lime-500/40 p-2 rounded-lg flex items-center justify-between transition-all duration-200 cursor-pointer select-none"
                >
                  <div className="flex items-center gap-3">
                    {packIcon(pack)}
                    <div className="max-w-[160px] sm:max-w-[180px]">
                      <h4 className="text-sm font-bold text-lime-400 truncate">
                        {cleanName(pack.name)}
                      </h4>
                      <p className="text-[12px] text-[#8a8f9c] mt-0.5 line-clamp-1">
                        {pack.description}
                      </p>
                      <div className="flex gap-1.5 mt-1">
                        <span className="text-[10px] font-mono text-lime-400 bg-lime-500/5 border border-lime-500/20 px-1.5 py-0.2 rounded-sm font-bold">
                          {pack.size}
                        </span>
                        <span className="text-[10px] font-mono text-[#8a8f9c] bg-[#0e0f14] border border-[#262a36] px-1.5 py-0.2 rounded-sm font-semibold">
                          {pack.version}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div className="flex gap-1">
                    {/* 左移動矢印 */}
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        onTogglePack(pack.id);
                      }}
                      className="p-1.5 rounded bg-[#0e0f14] border border-[#262a36] hover:border-lime-500/20 hover:text-lime-400 text-[#8a8f9c] transition-colors cursor-pointer active:scale-[0.9]"
                      title="Deactivate Pack"
                    >
                      <ArrowLeft size={13} />
                    </button>
                    {/* 削除 */}
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        onRemovePack(pack.id);
                      }}
                      className="p-1.5 rounded bg-[#0e0f14] border border-[#262a36] hover:border-red-500/20 hover:text-red-400 text-[#8a8f9c] transition-colors cursor-pointer active:scale-[0.9]"
                      title="Delete Pack"
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
