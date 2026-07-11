import React, { useState, useEffect } from 'react';
import { Account } from '../types';
import { User, ShieldAlert, Plus, Trash2, CheckCircle } from 'lucide-react';
import { useT } from '../lang';

const iea = (window as any).iea;

interface TabAccountsProps {
  accounts: Account[];
  currentAccount: Account;
  onSelectAccount: (account: Account) => void;
  onAddAccount: (username: string, type: 'microsoft' | 'offline') => void;
  onRemoveAccount: (id: string) => void;
}

export default function TabAccounts({
  accounts,
  currentAccount,
  onSelectAccount,
  onAddAccount,
  onRemoveAccount,
}: TabAccountsProps) {
  const t = useT();
  const [offlineName, setOfflineName] = useState('');
  const [errorMsg, setErrorMsg] = useState('');
  const [faces, setFaces] = useState<Record<string, string>>({});

  // Fetch each saved account's real face (via main; CSP-safe data URL).
  useEffect(() => {
    let cancel = false;
    accounts.forEach((a) => {
      const uuid = (a as any).uuid;
      if (!uuid || faces[a.id]) return;
      iea.skinFace(uuid).then((u: string) => { if (!cancel && u) setFaces((p) => ({ ...p, [a.id]: u })); }).catch(() => {});
    });
    return () => { cancel = true; };
  }, [accounts.map((a) => a.id).join(',')]);

  const handleAddOffline = (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg('');

    const trimmed = offlineName.trim();
    if (!trimmed) {
      setErrorMsg('Username cannot be empty.');
      return;
    }

    if (!/^[a-zA-Z0-9_]{3,16}$/.test(trimmed)) {
      setErrorMsg('Username must be 3-16 characters and Alphanumeric or Underline.');
      return;
    }

    // 重複チェック
    if (accounts.some(acc => acc.username.toLowerCase() === trimmed.toLowerCase())) {
      setErrorMsg('An account with this username already exists.');
      return;
    }

    onAddAccount(trimmed, 'offline');
    setOfflineName('');
  };

  const handleMicrosoftLogin = () => {
    // Microsoftログインダイアログのモック
    const randId = Math.floor(Math.random() * 1000);
    const mockMicrosoftNames = ['SniperPvP', 'GodStrafe', 'W_Tap_Legend', 'ComboMaster', 'HypixelGod'];
    const randomName = mockMicrosoftNames[Math.floor(Math.random() * mockMicrosoftNames.length)] + `_${randId}`;
    onAddAccount(randomName, 'microsoft');
  };

  // Real Minecraft face for an account (fetched into `faces`); letter fallback.
  const faceAvatar = (account: Account) => {
    const url = faces[account.id];
    if (url) {
      return <img src={url} alt="" draggable={false}
        className="w-8 h-8 rounded bg-black/10 flex-shrink-0" style={{ imageRendering: 'pixelated' }} />;
    }
    return (
      <div className="w-8 h-8 rounded bg-[#0e0f14] border border-[#262a36] flex items-center justify-center text-lime-400 text-sm font-bold font-mono flex-shrink-0">
        {account.username.charAt(0).toUpperCase()}
      </div>
    );
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 h-full animate-fadeIn overflow-y-auto lg:overflow-y-visible pr-1">
      {/* 左カラム: アカウント追加・ログイン (5 cols) */}
      <div className="lg:col-span-5 flex flex-col gap-4">
        {/* Microsoft ログイン */}
        <div className="bg-[#16181f] border border-[#262a36] rounded-xl p-5 shadow-lg flex flex-col gap-4">
          <div>
            <h3 className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider font-display">{t("Premium Auth")}</h3>
            <p className="text-[13px] text-[#8a8f9c] mt-0.5">{t("Securely log in via Mojang / Microsoft OAuth")}</p>
          </div>

          <button
            onClick={handleMicrosoftLogin}
            className="w-full py-3 rounded-lg font-bold text-[13px] tracking-wider transition-colors duration-150 bg-blue-600 hover:bg-blue-500 text-white active:scale-[0.98] cursor-pointer flex items-center justify-center gap-2.5 border border-blue-500/10"
          >
            {/* Microsoft ロゴ */}
            <div className="grid grid-cols-2 gap-[2px] w-3 h-3 flex-shrink-0">
              <div className="bg-[#f25022] w-1.5 h-1.5" />
              <div className="bg-[#7fba00] w-1.5 h-1.5" />
              <div className="bg-[#00a4ef] w-1.5 h-1.5" />
              <div className="bg-[#ffb900] w-1.5 h-1.5" />
            </div>
            {t("SIGN IN WITH MICROSOFT")}
          </button>
        </div>

        {/* オフラインログイン */}
        <div className="bg-[#16181f] border border-[#262a36] rounded-xl p-5 shadow-lg flex flex-col gap-4">
          <div>
            <h3 className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider font-display">{t("Offline Profile")}</h3>
            <p className="text-[13px] text-[#8a8f9c] mt-0.5">{t("Play on local or cracked Minecraft servers")}</p>
          </div>

          <form onSubmit={handleAddOffline} className="space-y-3.5">
            <div className="space-y-1">
              <label htmlFor="offline-username" className="text-[12px] font-mono text-[#8a8f9c] uppercase tracking-wider block font-bold">{t("Username")}</label>
              <input
                id="offline-username"
                type="text"
                placeholder={t("Enter PvP Name")}
                value={offlineName}
                onChange={(e) => setOfflineName(e.target.value)}
                maxLength={16}
                className="w-full bg-[#0e0f14] border border-[#262a36] rounded-lg px-3 py-2 text-sm text-[#e7e9ee] focus:border-lime-500 outline-none transition-colors placeholder:text-[#8a8f9c]/30 font-mono"
              />
            </div>

            {errorMsg && (
              <p className="text-[12px] font-mono text-red-400 flex items-center gap-1">
                <ShieldAlert size={12} />
                {t(errorMsg)}
              </p>
            )}

            <button
              type="submit"
              className="w-full py-3 rounded-lg font-bold text-[13px] tracking-wider transition-all duration-200 bg-transparent border border-lime-500/30 hover:border-lime-400 hover:bg-lime-500/5 text-lime-400 active:scale-[0.98] cursor-pointer flex items-center justify-center gap-1.5"
            >
              <Plus size={14} />
              {t("ADD OFFLINE PROFILE")}
            </button>
          </form>
        </div>
      </div>

      {/* 右カラム: アカウント一覧 (7 cols) */}
      <div className="lg:col-span-7 flex flex-col bg-[#16181f] border border-[#262a36] rounded-xl p-5 shadow-lg min-h-[350px] overflow-hidden">
        <div className="mb-4 border-b border-[#262a36]/50 pb-3">
          <h3 className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider font-display">{t("Stored Profiles")}</h3>
          <p className="text-[13px] text-[#8a8f9c] mt-0.5">{t('Quickly swap active profiles for Minecraft session')}</p>
        </div>

        {/* アカウントリスト */}
        <div className="flex-1 overflow-y-auto pr-1 space-y-2.5 scrollbar-thin">
          {accounts.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center text-center p-8 border border-dashed border-[#262a36] rounded-lg bg-[#0e0f14]/30">
              <User size={32} className="text-[#8a8f9c]/20 mb-2" />
              <p className="text-sm text-[#8a8f9c]">{t("No accounts saved.")}</p>
              <p className="text-[12px] text-[#8a8f9c]/60 mt-1">{t('Add an offline name or sign in with Microsoft above.')}</p>
            </div>
          ) : (
            accounts.map((account) => {
              const isCurrent = account.id === currentAccount.id;
              return (
                <div
                  key={account.id}
                  onClick={() => onSelectAccount(account)}
                  className={`group/item border rounded-lg p-3 flex items-center justify-between transition-all duration-200 cursor-pointer relative overflow-hidden select-none
                    ${isCurrent
                      ? 'bg-lime-500/5 border-lime-500/30 shadow-[0_4px_12px_rgba(163,230,53,0.03)]'
                      : 'bg-[#1c1f29] border-[#262a36] hover:bg-[#262a36]/50 hover:border-lime-500/20'
                    }`}
                >
                  <div className="flex items-center gap-3 relative z-10">
                    {/* アバター */}
                    {faceAvatar(account)}
                    
                    <div>
                      <h4 className="text-sm font-bold text-[#e7e9ee] group-hover/item:text-lime-400 transition-colors flex items-center gap-1.5 font-mono">
                        {account.username}
                        {isCurrent && (
                          <CheckCircle size={12} className="text-lime-400 fill-lime-400/10" />
                        )}
                      </h4>
                      <p className="text-[12px] text-[#8a8f9c] mt-0.5 flex items-center gap-2 font-mono">
                        {account.type === 'microsoft' ? (
                          <span className="text-blue-400 bg-blue-500/10 border border-blue-400/20 text-[10px] font-bold px-1.5 py-0.5 rounded-sm">
                            MICROSOFT
                          </span>
                        ) : (
                          <span className="text-[#8a8f9c] bg-[#0e0f14] border border-[#262a36] text-[10px] font-bold px-1.5 py-0.5 rounded-sm">
                            OFFLINE
                          </span>
                        )}
                        {account.createdAt && <span className="text-[10px] opacity-60">{t('Added')}: {account.createdAt}</span>}
                      </p>
                    </div>
                  </div>

                  <div className="flex items-center gap-2 relative z-10">
                    {isCurrent && (
                      <span className="text-[11px] font-bold text-lime-400 uppercase tracking-widest font-mono bg-lime-500/10 px-2 py-0.5 rounded border border-lime-500/20 mr-2">
                        ACTIVE
                      </span>
                    )}
                    
                    {/* 削除ボタン */}
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        onRemoveAccount(account.id);
                      }}
                      className="p-1.5 rounded bg-transparent hover:bg-red-500/15 text-[#8a8f9c] hover:text-red-400 transition-all cursor-pointer border border-transparent hover:border-red-500/10"
                      title="Delete Profile"
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
