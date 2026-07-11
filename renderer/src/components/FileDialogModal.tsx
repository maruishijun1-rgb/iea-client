import React, { useState } from 'react';
import { Folder, File, ChevronRight, X, Check } from 'lucide-react';

interface FileDialogModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (path: string) => void;
  type: 'java' | 'folder';
}

interface FileItem {
  name: string;
  isFolder: boolean;
  path: string;
}

export default function FileDialogModal({
  isOpen,
  onClose,
  onSelect,
  type
}: FileDialogModalProps) {
  const [currentPath, setCurrentPath] = useState(
    type === 'java' 
      ? 'C:\\Program Files\\Java\\jre1.8.0_361\\bin' 
      : 'C:\\Users\\Admin\\AppData\\Roaming'
  );

  const [selectedItem, setSelectedItem] = useState<FileItem | null>(null);

  if (!isOpen) return null;

  // モック用のファイルツリー
  const javaFiles: FileItem[] = [
    { name: '.. (Parent Directory)', isFolder: true, path: 'C:\\Program Files\\Java\\jre1.8.0_361' },
    { name: 'java.exe', isFolder: false, path: 'C:\\Program Files\\Java\\jre1.8.0_361\\bin\\java.exe' },
    { name: 'javaw.exe', isFolder: false, path: 'C:\\Program Files\\Java\\jre1.8.0_361\\bin\\javaw.exe' },
    { name: 'javacpl.exe', isFolder: false, path: 'C:\\Program Files\\Java\\jre1.8.0_361\\bin\\javacpl.exe' },
    { name: 'policytool.exe', isFolder: false, path: 'C:\\Program Files\\Java\\jre1.8.0_361\\bin\\policytool.exe' },
    { name: 'keytool.exe', isFolder: false, path: 'C:\\Program Files\\Java\\jre1.8.0_361\\bin\\keytool.exe' },
  ];

  const folderFiles: FileItem[] = [
    { name: '.. (Parent Directory)', isFolder: true, path: 'C:\\Users\\Admin\\AppData' },
    { name: '.minecraft', isFolder: true, path: 'C:\\Users\\Admin\\AppData\\Roaming\\.minecraft' },
    { name: '.ieaclient', isFolder: true, path: 'C:\\Users\\Admin\\AppData\\Roaming\\.ieaclient' },
    { name: 'Discord', isFolder: true, path: 'C:\\Users\\Admin\\AppData\\Roaming\\Discord' },
    { name: 'Spotify', isFolder: true, path: 'C:\\Users\\Admin\\AppData\\Roaming\\Spotify' },
  ];

  const files = type === 'java' ? javaFiles : folderFiles;

  const handleItemClick = (item: FileItem) => {
    if (item.name.startsWith('..')) {
      // 親ディレクトリ移動のダミー
      const parts = currentPath.split('\\');
      parts.pop();
      setCurrentPath(parts.join('\\'));
      setSelectedItem(null);
    } else if (item.isFolder) {
      setCurrentPath(item.path);
      setSelectedItem(null);
    } else {
      setSelectedItem(item);
    }
  };

  const handleConfirm = () => {
    if (type === 'folder') {
      // フォルダ選択の場合は現在のパス、または選択したフォルダパス
      const finalPath = selectedItem && selectedItem.isFolder ? selectedItem.path : currentPath + '\\.minecraft';
      onSelect(finalPath);
    } else {
      // ファイル選択の場合は選択したファイル
      if (selectedItem) {
        onSelect(selectedItem.path);
      } else {
        onSelect(currentPath + '\\javaw.exe');
      }
    }
    onClose();
  };

  return (
    <div className="fixed inset-0 bg-[#0e0f14]/80 backdrop-blur-md flex items-center justify-center z-50 p-4 animate-fadeIn">
      <div className="bg-[#16181f] border border-[#262a36] rounded-2xl w-full max-w-md shadow-2xl overflow-hidden flex flex-col h-[400px]">
        {/* ヘッダー */}
        <div className="flex justify-between items-center bg-[#1c1f29] border-b border-[#262a36] px-4 py-3 select-none">
          <div className="flex items-center gap-2">
            <Folder size={14} className="text-lime-400" />
            <span className="text-sm font-bold text-[#e7e9ee] uppercase tracking-wider">
              {type === 'java' ? 'Select Java Executable' : 'Select Game Directory'}
            </span>
          </div>
          <button 
            onClick={onClose}
            className="text-[#8a8f9c] hover:text-[#e7e9ee] cursor-pointer transition-colors"
          >
            <X size={16} />
          </button>
        </div>

        {/* パス表示バー */}
        <div className="bg-[#0e0f14]/50 border-b border-[#262a36] px-4 py-2 flex items-center gap-1 text-[12px] font-mono text-[#8a8f9c] select-text overflow-x-auto whitespace-nowrap scrollbar-none">
          <span>Local Disk (C:)</span>
          <ChevronRight size={10} />
          <span>{currentPath}</span>
        </div>

        {/* ファイルリスト領域 */}
        <div className="flex-1 overflow-y-auto p-2 space-y-1 scrollbar-thin">
          {files.map((file, idx) => {
            const isSelected = selectedItem?.path === file.path;
            return (
              <div
                key={idx}
                onClick={() => handleItemClick(file)}
                className={`flex items-center justify-between p-2 rounded-lg cursor-pointer transition-all select-none
                  ${isSelected
                    ? 'bg-lime-500/10 border border-lime-500/20 text-lime-400'
                    : 'hover:bg-[#1c1f29] border border-transparent text-[#8a8f9c] hover:text-[#e7e9ee]'
                  }`}
              >
                <div className="flex items-center gap-2 text-sm font-mono">
                  {file.isFolder ? (
                    <Folder size={14} className="text-lime-400" />
                  ) : (
                    <File size={14} className="text-[#8a8f9c]" />
                  )}
                  <span>{file.name}</span>
                </div>
                {file.isFolder && !file.name.startsWith('..') && (
                  <ChevronRight size={12} className="opacity-50" />
                )}
                {isSelected && (
                  <Check size={12} className="text-lime-400" />
                )}
              </div>
            );
          })}
        </div>

        {/* フッター */}
        <div className="bg-[#1c1f29] border-t border-[#262a36] px-4 py-3 flex justify-between items-center">
          <div className="text-[12px] text-[#8a8f9c] font-mono truncate max-w-[55%]">
            Selected: {selectedItem ? selectedItem.name : (type === 'folder' ? '.minecraft (folder)' : 'javaw.exe')}
          </div>
          <div className="flex gap-2">
            <button
              onClick={onClose}
              className="px-3.5 py-1.5 rounded-lg bg-transparent border border-[#262a36] text-sm font-semibold text-[#8a8f9c] hover:text-[#e7e9ee] cursor-pointer"
            >
              Cancel
            </button>
            <button
              onClick={handleConfirm}
              className="px-4 py-1.5 rounded-lg bg-lime-500 text-[#0e0f14] text-sm font-bold hover:bg-lime-400 cursor-pointer flex items-center gap-1 shadow-lg shadow-lime-500/10"
            >
              <Check size={12} />
              Confirm
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
