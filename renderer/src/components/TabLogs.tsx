import React, { useEffect, useRef } from 'react';
import { ClientLogs } from '../types';
import { Trash2, Download, Play, Pause, ToggleLeft, ToggleRight, Scroll } from 'lucide-react';
import { useT } from '../lang';

interface TabLogsProps {
  logs: ClientLogs[];
  onClearLogs: () => void;
  autoScroll: boolean;
  onToggleAutoScroll: () => void;
  isPaused: boolean;
  onTogglePaused: () => void;
}

export default function TabLogs({
  logs,
  onClearLogs,
  autoScroll,
  onToggleAutoScroll,
  isPaused,
  onTogglePaused,
}: TabLogsProps) {
  const t = useT();
  const containerRef = useRef<HTMLDivElement>(null);

  // 自動スクロール処理
  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [logs, autoScroll]);

  const handleExportLogs = () => {
    // ログをテキストファイルとして書き出す偽アクション
    const text = logs.map(l => `[${l.timestamp}] [${l.thread}/${l.level}]: ${l.message}`).join('\n');
    const blob = new Blob([text], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'iea-client-latest.log';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const getLogLevelColor = (level: 'INFO' | 'WARN' | 'ERROR') => {
    switch (level) {
      case 'WARN': return 'text-amber-500 font-bold';
      case 'ERROR': return 'text-red-400 font-bold';
      default: return 'text-slate-400';
    }
  };

  const getMessageColor = (level: 'INFO' | 'WARN' | 'ERROR') => {
    switch (level) {
      case 'WARN': return 'text-amber-300';
      case 'ERROR': return 'text-red-300';
      default: return 'text-slate-200';
    }
  };

  return (
    <div className="flex flex-col gap-4 h-full animate-fadeIn overflow-hidden">
      {/* 操作バー */}
      <div className="bg-[#16181f] border border-[#262a36] rounded-xl p-3 shadow-lg flex items-center justify-between flex-wrap gap-2 flex-shrink-0">
        <div className="flex items-center gap-2">
          <Scroll size={15} className="text-lime-400" />
          <h3 className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider font-display">{t("Client Console")}</h3>
          {isPaused && (
            <span className="text-[10px] bg-amber-500/10 border border-amber-500/20 text-amber-500 px-1.5 py-0.5 rounded font-bold">
              PAUSED
            </span>
          )}
        </div>

        <div className="flex items-center gap-2">
          {/* 自動スクロール */}
          <button
            onClick={onToggleAutoScroll}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#0e0f14] border border-[#262a36] text-[12px] text-[#8a8f9c] hover:text-[#e7e9ee] transition-colors cursor-pointer select-none font-mono"
            title="Toggle Auto Scroll"
          >
            <span>{t("Auto Scroll")}</span>
            {autoScroll ? (
              <ToggleRight size={20} className="text-lime-400" />
            ) : (
              <ToggleLeft size={20} className="text-[#8a8f9c]" />
            )}
          </button>

          {/* 一時停止 / 再開 */}
          <button
            onClick={onTogglePaused}
            className={`p-2 rounded-lg border transition-all cursor-pointer flex items-center justify-center
              ${isPaused
                ? 'bg-lime-500/10 border-lime-500/30 text-lime-400'
                : 'bg-[#0e0f14] border-[#262a36] text-[#8a8f9c] hover:text-[#e7e9ee]'
              }`}
            title={isPaused ? 'Resume Logging' : 'Pause Logging'}
          >
            {isPaused ? <Play size={12} fill="currentColor" /> : <Pause size={12} fill="currentColor" />}
          </button>

          {/* ログ書き出し */}
          <button
            onClick={handleExportLogs}
            disabled={logs.length === 0}
            className="p-2 rounded-lg bg-[#0e0f14] border border-[#262a36] text-[#8a8f9c] hover:text-[#e7e9ee] transition-all cursor-pointer disabled:opacity-30 disabled:cursor-not-allowed"
            title="Export Logs"
          >
            <Download size={12} />
          </button>

          {/* クリア */}
          <button
            onClick={onClearLogs}
            className="p-2 rounded-lg bg-[#0e0f14] border border-[#262a36] text-red-400/80 hover:text-red-400 hover:bg-red-500/5 transition-all cursor-pointer"
            title="Clear Logs"
          >
            <Trash2 size={12} />
          </button>
        </div>
      </div>

      {/* ログコンソールボックス */}
      <div 
        id="log-console"
        ref={containerRef}
        className="flex-1 min-h-0 bg-[#0a0b0d] border border-[#262a36] rounded-xl p-4 font-mono text-[12px] leading-relaxed overflow-y-auto overflow-x-hidden scrollbar-thin select-text"
      >
        {logs.length === 0 ? (
          <div className="h-full flex flex-col items-center justify-center text-center text-[#8a8f9c]/40 font-sans p-8">
            <Scroll size={32} className="opacity-15 mb-2" />
            <p>{t("Console is empty.")}</p>
            <p className="text-[12px] opacity-70 mt-1">{t("Start game to stream initialization logs here.")}</p>
          </div>
        ) : (
          <div className="space-y-1">
            {logs.map((log, idx) => (
              <div key={idx} className="flex items-start gap-1.5 hover:bg-slate-500/5 py-0.5 px-1 rounded transition-colors whitespace-pre-wrap break-all">
                {/* タイムスタンプ */}
                <span className="text-[#8a8f9c]/60 select-none">[{log.timestamp}]</span>
                
                {/* スレッドとレベル */}
                <span className={`select-none font-semibold ${getLogLevelColor(log.level)}`}>
                  [{log.thread}/{log.level}]:
                </span>
                
                {/* メッセージ */}
                <span className={getMessageColor(log.level)}>
                  {log.message}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
