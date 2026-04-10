import { useState } from 'react';
import { RagUploadTool } from './RagUploadTool';

interface Tool {
  id: string;
  name: string;
  icon: React.ReactNode;
  description: string;
}

interface ToolsSidebarProps {
  tools?: Tool[];
  workspace?: string;
  onWorkspaceChange?: (ws: string) => void;
}

const defaultTools: Tool[] = [
  {
    id: 'rag-upload',
    name: 'RAG文件上传',
    description: '上传文档到知识库',
    icon: (
      <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
      </svg>
    ),
  },
];

export function ToolsSidebar({ tools = defaultTools, workspace = '', onWorkspaceChange }: ToolsSidebarProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [activeTool, setActiveTool] = useState<string | null>(null);

  const handleToolClick = (toolId: string) => {
    setActiveTool(activeTool === toolId ? null : toolId);
  };

  return (
    <>
      {/* Toggle Button */}
      <button
        onClick={() => setIsOpen(!isOpen)}
        className={`fixed right-0 top-1/2 -translate-y-1/2 z-50 p-2 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-l-lg shadow-lg transition-all duration-200 ${
          isOpen ? 'translate-x-0' : ''
        }`}
        style={isOpen ? { right: '320px' } : {}}
        title="工具面板"
      >
        <svg
          className={`w-5 h-5 text-gray-600 dark:text-gray-400 transition-transform ${isOpen ? 'rotate-180' : ''}`}
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={2}
        >
          <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
        </svg>
      </button>

      {/* Sidebar */}
      <aside
        className={`fixed right-0 top-0 h-full w-80 bg-white dark:bg-gray-900 border-l border-gray-200 dark:border-gray-700 shadow-xl transition-transform duration-200 z-40 ${
          isOpen ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        <div className="flex items-center justify-between p-4 border-b border-gray-200 dark:border-gray-700">
          <h2 className="font-semibold text-gray-800 dark:text-gray-200">工具</h2>
          <button
            onClick={() => setIsOpen(false)}
            className="p-1 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 transition-colors"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Workspace Setting */}
        <div className="p-4 border-b border-gray-200 dark:border-gray-700">
          <label className="block text-xs font-medium text-gray-500 dark:text-gray-400 mb-1.5">
            Workspace 目录
          </label>
          <input
            type="text"
            value={workspace}
            onChange={(e) => onWorkspaceChange?.(e.target.value)}
            placeholder="默认项目目录"
            className="w-full px-2.5 py-1.5 text-xs bg-gray-100 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg text-gray-700 dark:text-gray-300 placeholder-gray-400 focus:outline-none focus:border-purple-500 transition-colors"
          />
          {workspace && (
            <button
              onClick={() => onWorkspaceChange?.('')}
              className="mt-1.5 w-full text-xs text-gray-400 hover:text-purple-500 transition-colors"
            >
              重置为默认
            </button>
          )}
          <p className="mt-1.5 text-xs text-gray-400">
            {workspace ? '已授权访问此目录' : '未设置，使用项目默认目录'}
          </p>
        </div>

        {/* Tool List */}
        <div className="flex-1 overflow-y-auto">
          {tools.map((tool) => (
            <div key={tool.id}>
              <button
                onClick={() => handleToolClick(tool.id)}
                className={`w-full flex items-center gap-3 px-4 py-3 text-left transition-colors ${
                  activeTool === tool.id
                    ? 'bg-purple-50 dark:bg-purple-950/20 border-l-2 border-purple-500'
                    : 'hover:bg-gray-50 dark:hover:bg-gray-800 border-l-2 border-transparent'
                }`}
              >
                <span className="text-gray-500 dark:text-gray-400">{tool.icon}</span>
                <div className="flex-1 min-w-0">
                  <div className="font-medium text-sm text-gray-700 dark:text-gray-300">{tool.name}</div>
                  <div className="text-xs text-gray-400 truncate">{tool.description}</div>
                </div>
                <svg
                  className={`w-4 h-4 text-gray-400 transition-transform ${activeTool === tool.id ? 'rotate-180' : ''}`}
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
                </svg>
              </button>

              {/* Expanded Content */}
              {activeTool === tool.id && (
                <div className="border-t border-gray-100 dark:border-gray-800">
                  {tool.id === 'rag-upload' && <RagUploadTool onClose={() => setActiveTool(null)} />}
                </div>
              )}
            </div>
          ))}
        </div>
      </aside>

      {/* Backdrop */}
      {isOpen && (
        <div
          className="fixed inset-0 bg-black/20 z-30"
          onClick={() => setIsOpen(false)}
        />
      )}
    </>
  );
}
