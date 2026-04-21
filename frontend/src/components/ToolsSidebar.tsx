import { useState, useEffect } from 'react';
import { KnowledgeBasePanel } from './KnowledgeBasePanel';

// Toggle event name for cross-component communication
export const TOOLS_TOGGLE_EVENT = 'tools-sidebar-toggle';

export function ToolsSidebar() {
  const [isExpanded, setIsExpanded] = useState(false);

  // Listen for toggle events from ChatInput
  useEffect(() => {
    const handler = () => setIsExpanded(prev => !prev);
    window.addEventListener(TOOLS_TOGGLE_EVENT, handler);
    return () => window.removeEventListener(TOOLS_TOGGLE_EVENT, handler);
  }, []);

  const toggleExpanded = () => {
    setIsExpanded(!isExpanded);
  };

  return (
    <>
      {/* Full-screen Knowledge Base Panel Overlay */}
      {isExpanded && (
        <div className="fixed inset-0 z-50 bg-zinc-950">
          {/* Close Button */}
          <button
            onClick={toggleExpanded}
            className="absolute top-4 right-4 z-50 p-2 text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 rounded-lg transition-colors bg-zinc-900/80 backdrop-blur-sm border border-zinc-800"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>

          {/* Content - Knowledge Base Panel */}
          <KnowledgeBasePanel onClose={toggleExpanded} />
        </div>
      )}

      {/* Floating Icon Button */}
      {!isExpanded && (
        <button
          onClick={toggleExpanded}
          className="fixed right-4 bottom-20 z-40 p-3 bg-zinc-800 hover:bg-zinc-700 border border-zinc-700 rounded-full shadow-lg transition-all group"
          title="知识库管理"
        >
          <svg className="w-5 h-5 text-zinc-400 group-hover:text-zinc-200" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
          </svg>
          {/* Tooltip */}
          <span className="absolute right-full mr-3 top-1/2 -translate-y-1/2 px-3 py-1.5 bg-zinc-800 text-zinc-200 text-sm rounded opacity-0 group-hover:opacity-100 transition-opacity whitespace-nowrap pointer-events-none border border-zinc-700">
            知识库
          </span>
        </button>
      )}
    </>
  );
}
