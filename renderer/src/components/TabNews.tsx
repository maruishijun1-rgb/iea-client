import React, { useState, useEffect } from 'react';
import { Newspaper, Tag, Calendar } from 'lucide-react';
import { useT } from '../lang';

const iea = (window as any).iea;

interface Release {
  tag: string;
  name: string;
  body: string;
  date: string;
  url: string;
  prerelease: boolean;
}

// Trim GitHub release notes for display (drop the Claude/co-author footer).
function cleanBody(s: string) {
  if (!s) return '';
  return String(s).replace(/\r/g, '')
    .replace(/^\s*🤖.*$/gm, '')
    .replace(/^\s*Co-Authored-By:.*$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function fmtDate(iso: string) {
  if (!iso) return '';
  try { return new Date(iso).toLocaleDateString(); } catch (_) { return ''; }
}

export default function TabNews() {
  const t = useT();
  const [releases, setReleases] = useState<Release[] | null>(null);
  const [version, setVersion] = useState('');

  useEffect(() => {
    let cancel = false;
    (async () => {
      try { if (!cancel) setVersion(await iea.appVersion()); } catch (_) {}
      try {
        const r = await iea.getNews();
        if (!cancel) setReleases(Array.isArray(r) ? r : []);
      } catch (_) { if (!cancel) setReleases([]); }
    })();
    return () => { cancel = true; };
  }, []);

  return (
    <div className="flex flex-col gap-4 h-full animate-fadeIn overflow-hidden">
      {/* ヘッダー */}
      <div className="flex items-center gap-3 border-b border-[#262a36]/50 pb-3 flex-shrink-0">
        <div className="p-2 rounded-lg bg-lime-500/10 border border-lime-500/20 text-lime-400">
          <Newspaper size={16} />
        </div>
        <div>
          <h2 className="text-base font-bold text-[#e7e9ee] font-display">{t("What's New")}</h2>
          <p className="text-[13px] text-[#8a8f9c]">{t('Updates & release notes')}</p>
        </div>
      </div>

      {/* リリース一覧 */}
      <div className="flex-1 overflow-y-auto pr-1 space-y-3 scrollbar-thin">
        {releases === null && (
          <div className="h-full flex items-center justify-center text-[#8a8f9c] text-sm">{t('Loading…')}</div>
        )}
        {releases !== null && releases.length === 0 && (
          <div className="h-full flex flex-col items-center justify-center text-center text-[#8a8f9c] gap-2 py-10">
            <Newspaper size={26} className="opacity-40" />
            <p className="text-sm">{t('Could not load release notes.')}</p>
          </div>
        )}
        {releases !== null && releases.map((r, i) => {
          const isCurrent = version && (r.tag === 'v' + version || r.tag === version);
          const body = cleanBody(r.body);
          return (
            <div key={r.tag + i} className="bg-[#16181f] border border-[#262a36] rounded-xl p-4 shadow-lg">
              <div className="flex items-center justify-between flex-wrap gap-2">
                <div className="flex items-center gap-2">
                  <span className="flex items-center gap-1 text-sm font-bold text-lime-400 font-mono bg-lime-500/5 border border-lime-500/20 px-2 py-0.5 rounded">
                    <Tag size={11} /> {r.tag}
                  </span>
                  {i === 0 && (
                    <span className="text-[11px] font-bold uppercase tracking-wider text-[#0e0f14] bg-lime-400 px-2 py-0.5 rounded">{t('Latest')}</span>
                  )}
                  {isCurrent && (
                    <span className="text-[11px] font-bold uppercase tracking-wider text-lime-400 border border-lime-500/30 px-2 py-0.5 rounded">{t('Current')}</span>
                  )}
                  {r.prerelease && (
                    <span className="text-[11px] font-bold uppercase tracking-wider text-orange-400 border border-orange-500/30 px-2 py-0.5 rounded">beta</span>
                  )}
                </div>
                <span className="flex items-center gap-1 text-[12px] font-mono text-[#8a8f9c]">
                  <Calendar size={10} /> {fmtDate(r.date)}
                </span>
              </div>
              {r.name && r.name !== r.tag && (
                <h3 className="text-base font-bold text-[#e7e9ee] mt-2">{r.name}</h3>
              )}
              {body && (
                <p className="text-[13px] text-[#c5cad6] mt-2 leading-relaxed whitespace-pre-wrap break-words">{body}</p>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
