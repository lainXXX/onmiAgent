import { useState, useRef, useEffect, FormEvent, KeyboardEvent } from 'react';
import { TOOLS_TOGGLE_EVENT } from './ToolsSidebar';

interface ChatInputProps {
  onSend: (message: string) => void;
  disabled?: boolean;
  workspace?: string;
  onWorkspaceChange?: (ws: string) => void;
}

export function ChatInput({ onSend, disabled, workspace = '', onWorkspaceChange }: ChatInputProps) {
  const [input, setInput] = useState('');
  const [showWorkspace, setShowWorkspace] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 150)}px`;
    }
  }, [input]);

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (input.trim() && !disabled) {
      onSend(input.trim());
      setInput('');
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="p-2 sm:p-4 bg-gradient-to-t from-zinc-950 via-zinc-950 to-transparent">
      <div className="max-w-3xl mx-auto">
        <div className="bg-zinc-900 border border-zinc-800 rounded-xl px-2 sm:px-3 py-2 shadow-2xl focus-within:border-zinc-600 transition-colors">
          <textarea
            ref={textareaRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Message OmniAgent..."
            disabled={disabled}
            rows={1}
            className="w-full bg-transparent border-none outline-none resize-none text-zinc-200 text-sm placeholder-zinc-700 max-h-24 py-1 px-1 font-sans leading-relaxed"
          />
          <div className="flex justify-between items-center mt-2 pt-2 border-t border-zinc-800/50">
            <div className="flex items-center gap-2">
              {/* Workspace Selector */}
              <div className="relative">
                <button
                  type="button"
                  onClick={() => setShowWorkspace(!showWorkspace)}
                  className={`flex items-center gap-1.5 px-2 py-1 text-xs rounded transition-colors max-w-[200px] ${
                    workspace
                      ? 'bg-blue-500/20 text-blue-400 border border-blue-500/40 hover:bg-blue-500/30'
                      : 'text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800'
                  }`}
                  title="Workspace 设置"
                >
                  {workspace ? (
                    <>
                      <svg className="w-3.5 h-3.5 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
                      </svg>
                      <span className="truncate hidden sm:inline">{workspace}</span>
                      <span className="truncate sm:hidden">WS</span>
                    </>
                  ) : (
                    <>
                      <svg className="w-3.5 h-3.5 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
                      </svg>
                      <span className="hidden sm:inline">默认</span>
                    </>
                  )}
                </button>

                {/* Workspace Dropdown */}
                {showWorkspace && (
                  <div className="absolute bottom-full left-0 mb-2 w-64 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl z-50">
                    <div className="p-3 border-b border-zinc-800">
                      <input
                        type="text"
                        value={workspace}
                        onChange={(e) => onWorkspaceChange?.(e.target.value)}
                        placeholder="输入 Workspace 路径..."
                        className="w-full px-2.5 py-1.5 text-xs bg-zinc-800 border border-zinc-700 rounded text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-blue-500"
                        autoFocus
                        onBlur={() => setTimeout(() => setShowWorkspace(false), 200)}
                      />
                    </div>
                    <div className="p-2">
                      <p className="text-[10px] text-zinc-500 mb-1.5">当前 Workspace:</p>
                      <p className="text-xs text-zinc-300 truncate font-mono bg-zinc-800 px-2 py-1 rounded">
                        {workspace || '使用项目默认目录'}
                      </p>
                    </div>
                    {workspace && (
                      <div className="p-2 border-t border-zinc-800">
                        <button
                          onClick={() => { onWorkspaceChange?.(''); setShowWorkspace(false); }}
                          className="w-full text-xs text-zinc-500 hover:text-blue-400 transition-colors text-center"
                        >
                          重置为默认
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </div>

              {/* Tools Button */}
              <button
                type="button"
                onClick={() => window.dispatchEvent(new Event(TOOLS_TOGGLE_EVENT))}
                className="p-1.5 text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded transition-colors"
                title="工具面板"
              >
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                  <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                </svg>
              </button>
            </div>
            <button
              type="submit"
              disabled={!input.trim() || disabled}
              className={`px-3 py-1 rounded text-xs font-bold transition-all disabled:cursor-not-allowed ${
                disabled
                  ? 'bg-blue-500/20 text-blue-400 border border-blue-500/40'
                  : 'bg-zinc-100 hover:bg-white text-zinc-950 disabled:opacity-40'
              }`}
            >
              {disabled ? (
                <span className="flex items-center gap-1">
                  <span className="flex gap-0.5">
                    <span className="w-1 h-1 bg-blue-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                    <span className="w-1 h-1 bg-blue-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                    <span className="w-1 h-1 bg-blue-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                  </span>
                </span>
              ) : 'Send'}
            </button>
          </div>
        </div>
      </div>
    </form>
  );
}
