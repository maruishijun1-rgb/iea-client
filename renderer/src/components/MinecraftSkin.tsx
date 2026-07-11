import React from 'react';

interface MinecraftSkinProps {
  username?: string;
  isLaunching?: boolean;
  customColors?: Record<string, string>;
}

export default function MinecraftSkin({ username = 'Player_IEA', isLaunching = false, customColors }: MinecraftSkinProps) {
  // 16x32 の Minecraft スタイル ピクセルアート
  // IEA Client 特製 PvP パーカー & ライムマントを着用したスキン
  
  // デフォルトカラー定義
  const defaultColors: Record<string, string> = {
    'S': '#d39975', // 肌 (Skin)
    's': '#b87e5b', // 肌 影
    'H': '#2e1c0c', // 髪 (Hair)
    'E': '#a3e635', // 目 - ライムグリーン (Eyes)
    'W': '#ffffff', // 白目
    'C': '#1e222b', // パーカー メイン (Coat - Dark Slate)
    'c': '#14161d', // パーカー 影
    'L': '#a3e635', // ライムアクセント (Lime)
    'l': '#65a30d', // ライム 影
    'B': '#111318', // インナー / ズボン (Black)
    'G': '#4c5366', // グレー (Gray)
    'g': '#353a47', // グレー 影
    'O': '#65a30d', // マント裏 (Cape Outer)
    'o': '#4d7c0f', // マント 影
  };

  const colors = customColors || defaultColors;


  // 頭部: 8x8 (x: 4~11, y: 0~7)
  const head = [
    ['H', 'H', 'H', 'H', 'H', 'H', 'H', 'H'],
    ['H', 'H', 'H', 'H', 'H', 'H', 'H', 'H'],
    ['H', 'S', 'S', 'S', 'S', 'S', 'S', 'H'],
    ['S', 'W', 'E', 'S', 'S', 'E', 'W', 'S'],
    ['S', 'S', 'S', 'S', 'S', 'S', 'S', 'S'],
    ['S', 'S', 'S', 's', 's', 'S', 'S', 'S'],
    ['C', 'S', 'S', 'S', 'S', 'S', 'S', 'C'], // フードの襟元
    ['C', 'C', 'S', 'S', 'S', 'S', 'C', 'C'],
  ];

  // 胴体: 8x12 (x: 4~11, y: 8~19)
  // IEAの「I」ロゴと、ライムの紐、ジッパー
  const body = [
    ['C', 'L', 'B', 'B', 'B', 'B', 'L', 'C'],
    ['C', 'C', 'L', 'B', 'B', 'L', 'C', 'c'],
    ['c', 'C', 'C', 'B', 'B', 'C', 'c', 'c'],
    ['C', 'C', 'G', 'L', 'L', 'G', 'C', 'c'], // IEAの「I」風
    ['C', 'C', 'c', 'L', 'L', 'c', 'C', 'c'],
    ['C', 'C', 'c', 'L', 'L', 'c', 'C', 'c'],
    ['C', 'C', 'G', 'L', 'L', 'G', 'C', 'c'],
    ['c', 'C', 'c', 'c', 'c', 'c', 'c', 'c'],
    ['c', 'C', 'C', 'C', 'C', 'C', 'C', 'c'],
    ['C', 'C', 'C', 'C', 'C', 'C', 'C', 'c'],
    ['C', 'L', 'L', 'C', 'C', 'L', 'L', 'c'], // ポケットの縁
    ['C', 'c', 'c', 'c', 'c', 'c', 'c', 'c'],
  ];

  // 左腕: 4x12 (x: 0~3, y: 8~19) - ライムグリーンのショルダーライン
  const leftArm = [
    ['C', 'L', 'C', 'C'],
    ['C', 'L', 'C', 'C'],
    ['c', 'L', 'C', 'C'],
    ['c', 'L', 'c', 'c'],
    ['C', 'G', 'C', 'C'],
    ['C', 'C', 'C', 'c'],
    ['c', 'c', 'c', 'c'],
    ['C', 'C', 'C', 'C'],
    ['C', 'S', 'S', 'C'], // 袖口から手首
    ['S', 'S', 'S', 'S'],
    ['S', 's', 's', 's'],
    ['s', 's', 's', 's'],
  ];

  // 右腕: 4x12 (x: 12~15, y: 8~19) - 左腕と対称
  const rightArm = [
    ['C', 'C', 'L', 'C'],
    ['C', 'C', 'L', 'c'],
    ['C', 'C', 'L', 'c'],
    ['c', 'c', 'L', 'c'],
    ['C', 'C', 'G', 'C'],
    ['c', 'C', 'C', 'C'],
    ['c', 'c', 'c', 'c'],
    ['C', 'C', 'C', 'C'],
    ['C', 'S', 'S', 'C'],
    ['S', 'S', 'S', 'S'],
    ['s', 's', 's', 's'],
    ['s', 's', 's', 's'],
  ];

  // 左脚: 4x12 (x: 4~7, y: 20~31) - ダークパンツ & ライムライン & スニーカー
  const leftLeg = [
    ['B', 'B', 'B', 'B'],
    ['B', 'L', 'B', 'B'],
    ['B', 'L', 'B', 'B'],
    ['b', 'L', 'b', 'b'],
    ['B', 'B', 'B', 'B'],
    ['b', 'b', 'b', 'b'],
    ['B', 'B', 'B', 'B'],
    ['B', 'B', 'B', 'B'],
    ['L', 'L', 'L', 'L'], // スニーカー上部
    ['G', 'G', 'G', 'G'], // スニーカー
    ['W', 'W', 'W', 'W'], // 白ソール
    ['B', 'B', 'B', 'B'],
  ];

  // 右脚: 4x12 (x: 8~11, y: 20~31) - 対称
  const rightLeg = [
    ['B', 'B', 'B', 'B'],
    ['B', 'B', 'L', 'B'],
    ['B', 'B', 'L', 'B'],
    ['b', 'b', 'L', 'b'],
    ['B', 'B', 'B', 'B'],
    ['b', 'b', 'b', 'b'],
    ['B', 'B', 'B', 'B'],
    ['B', 'B', 'B', 'B'],
    ['L', 'L', 'L', 'L'],
    ['G', 'G', 'G', 'G'],
    ['W', 'W', 'W', 'W'],
    ['B', 'B', 'B', 'B'],
  ];

  return (
    <div className="flex flex-col items-center justify-center p-6 bg-[#16181f]/80 border border-[#262a36] rounded-2xl relative overflow-hidden group select-none backdrop-blur-sm h-full">
      {/* 背景のグリッドグラフィック */}
      <div className="absolute inset-0 bg-[linear-gradient(rgba(163,230,53,0.02)_1px,transparent_1px),linear-gradient(90deg,rgba(163,230,53,0.02)_1px,transparent_1px)] bg-[size:16px_16px] pointer-events-none" />
      
      {/* 起動中のエフェクト（脈動） */}
      {isLaunching && (
        <div className="absolute inset-0 bg-lime-500/5 animate-pulse border border-lime-500/20 rounded-2xl pointer-events-none" />
      )}

      {/* マント (後ろに流れるように左右に少し見せる) */}
      <div className="absolute top-[28%] left-[24%] right-[24%] bottom-[20%] bg-gradient-to-b from-lime-600 to-lime-900 rounded-lg opacity-30 group-hover:opacity-50 transition-opacity blur-xs pointer-events-none" />

      {/* 3Dっぽく浮かび上がらせるコンテナ */}
      <div className={`relative transition-all duration-700 ease-out transform ${isLaunching ? 'scale-105 rotate-1 animate-bounce' : 'group-hover:scale-[1.03] group-hover:-translate-y-1'}`}>
        <svg 
          width="160" 
          height="320" 
          viewBox="0 0 16 32" 
          className="drop-shadow-[0_12px_24px_rgba(0,0,0,0.6)]"
          shapeRendering="crispEdges"
        >
          {/* マントのはみ出し部分 (左) */}
          <rect x="2.5" y="8" width="1.5" height="13" fill={colors['O']} opacity="0.85" />
          <rect x="2.5" y="21" width="1.5" height="1" fill={colors['o']} opacity="0.85" />
          
          {/* マントのはみ出し部分 (右) */}
          <rect x="12" y="8" width="1.5" height="13" fill={colors['O']} opacity="0.85" />
          <rect x="12" y="21" width="1.5" height="1" fill={colors['o']} opacity="0.85" />

          {/* 頭部レンダリング */}
          {head.map((row, y) => 
            row.map((colorKey, x) => (
              <rect 
                key={`h-${y}-${x}`} 
                x={x + 4} 
                y={y} 
                width="1" 
                height="1" 
                fill={colors[colorKey] || 'transparent'} 
              />
            ))
          )}

          {/* 左腕レンダリング */}
          {leftArm.map((row, y) => 
            row.map((colorKey, x) => (
              <rect 
                key={`la-${y}-${x}`} 
                x={x} 
                y={y + 8} 
                width="1" 
                height="1" 
                fill={colors[colorKey] || 'transparent'} 
              />
            ))
          )}

          {/* 胴体レンダリング */}
          {body.map((row, y) => 
            row.map((colorKey, x) => (
              <rect 
                key={`b-${y}-${x}`} 
                x={x + 4} 
                y={y + 8} 
                width="1" 
                height="1" 
                fill={colors[colorKey] || 'transparent'} 
              />
            ))
          )}

          {/* 右腕レンダリング */}
          {rightArm.map((row, y) => 
            row.map((colorKey, x) => (
              <rect 
                key={`ra-${y}-${x}`} 
                x={x + 12} 
                y={y + 8} 
                width="1" 
                height="1" 
                fill={colors[colorKey] || 'transparent'} 
              />
            ))
          )}

          {/* 左脚レンダリング */}
          {leftLeg.map((row, y) => 
            row.map((colorKey, x) => (
              <rect 
                key={`ll-${y}-${x}`} 
                x={x + 4} 
                y={y + 20} 
                width="1" 
                height="1" 
                fill={colors[colorKey] || 'transparent'} 
              />
            ))
          )}

          {/* 右脚レンダリング */}
          {rightLeg.map((row, y) => 
            row.map((colorKey, x) => (
              <rect 
                key={`rl-${y}-${x}`} 
                x={x + 8} 
                y={y + 20} 
                width="1" 
                height="1" 
                fill={colors[colorKey] || 'transparent'} 
              />
            ))
          )}
        </svg>
      </div>

      {/* スキンプロファイルラベル */}
      <div className="mt-5 text-center relative z-10 w-full">
        <p className="text-base font-semibold tracking-wide text-[#e7e9ee] group-hover:text-lime-400 transition-colors">
          {username}
        </p>
        <p className="text-[12px] font-mono text-[#8a8f9c] tracking-widest mt-0.5 uppercase">
          IEA Cape Equipped
        </p>
      </div>

      {/* クライアント特製バッジ */}
      <div className="absolute top-3 right-3 bg-lime-500/10 border border-lime-500/20 text-lime-400 text-[11px] font-mono px-2 py-0.5 rounded-full select-none">
        PvP SKIN
      </div>
    </div>
  );
}
