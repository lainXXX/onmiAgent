import { useState, useRef } from 'react';
import { Conversation } from '../types';

interface SidebarProps {
  conversations: Conversation[];
  activeId: number | null;
  onSelect: (id: number) => void;
  onNew: () => void;
  onDelete: (id: number) => void;
  onRename: (id: number, title: string) => void;
}

export function Sidebar({ conversations, activeId, onSelect, onNew, onDelete, onRename }: SidebarProps) {
  const today = new Date().toDateString();
  const yesterday = new Date(Date.now() - 86400000).toDateString();

  const grouped = conversations.reduce(
    (acc, conv) => {
      const date = new Date(conv.createdAt).toDateString();
      if (date === today) acc.today.push(conv);
      else if (date === yesterday) acc.yesterday.push(conv);
      else acc.older.push(conv);
      return acc;
    },
    { today: [] as Conversation[], yesterday: [] as Conversation[], older: [] as Conversation[] }
  );

  return (
    <aside className="w-64 bg-gray-50 dark:bg-gray-900 border-r border-gray-200 dark:border-gray-700 flex flex-col h-full">
      {/* Header */}
      <div className="p-4 border-b border-gray-200 dark:border-gray-700">
        <button
          onClick={onNew}
          className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-gradient-to-r from-purple-500 to-pink-500 text-white rounded-lg font-medium text-sm hover:opacity-90 transition-opacity"
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
          </svg>
          New Chat
        </button>
      </div>

      {/* Conversation List */}
      <div className="flex-1 overflow-y-auto p-2">
        {grouped.today.length > 0 && (
          <div className="mb-4">
            <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">Today</div>
            {grouped.today.map((conv) => (
              <ConversationItem
                key={conv.id}
                conv={conv}
                isActive={conv.id === activeId}
                onClick={() => onSelect(conv.id)}
                onDelete={() => onDelete(conv.id)}
                onRename={(title) => onRename(conv.id, title)}
              />
            ))}
          </div>
        )}

        {grouped.yesterday.length > 0 && (
          <div className="mb-4">
            <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">Yesterday</div>
            {grouped.yesterday.map((conv) => (
              <ConversationItem
                key={conv.id}
                conv={conv}
                isActive={conv.id === activeId}
                onClick={() => onSelect(conv.id)}
                onDelete={() => onDelete(conv.id)}
                onRename={(title) => onRename(conv.id, title)}
              />
            ))}
          </div>
        )}

        {grouped.older.length > 0 && (
          <div className="mb-4">
            <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">Older</div>
            {grouped.older.map((conv) => (
              <ConversationItem
                key={conv.id}
                conv={conv}
                isActive={conv.id === activeId}
                onClick={() => onSelect(conv.id)}
                onDelete={() => onDelete(conv.id)}
                onRename={(title) => onRename(conv.id, title)}
              />
            ))}
          </div>
        )}
      </div>
    </aside>
  );
}

interface ConversationItemProps {
  conv: Conversation;
  isActive: boolean;
  onClick: () => void;
  onDelete: () => void;
  onRename: (title: string) => void;
}

function ConversationItem({ conv, isActive, onClick, onDelete, onRename }: ConversationItemProps) {
  const [isEditing, setIsEditing] = useState(false);
  const [editValue, setEditValue] = useState(conv.title);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const handleDoubleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsEditing(true);
    setEditValue(conv.title);
  };

  const handleBlur = () => {
    setIsEditing(false);
    if (editValue.trim() && editValue !== conv.title) {
      onRename(editValue.trim());
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleBlur();
    } else if (e.key === 'Escape') {
      setIsEditing(false);
      setEditValue(conv.title);
    }
  };

  return (
    <div
      className={`group w-full flex items-center gap-2 px-3 py-2 rounded-lg text-left text-sm transition-colors ${
        isActive
          ? 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300'
          : 'text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-800'
      }`}
    >
      <button onClick={onClick} className="flex-1 flex items-center gap-2 min-w-0">
        <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
        </svg>
        {isEditing ? (
          <input
            ref={(el) => { inputRef.current = el; }}
            type="text"
            value={editValue}
            onChange={(e) => setEditValue(e.target.value)}
            onBlur={handleBlur}
            onKeyDown={handleKeyDown}
            onClick={(e) => e.stopPropagation()}
            autoFocus
            className="flex-1 bg-transparent border-b border-purple-500 outline-none text-gray-800 dark:text-gray-200 truncate"
          />
        ) : (
          <span
            className="truncate cursor-text"
            onDoubleClick={handleDoubleClick}
            title="双击编辑标题"
          >
            {conv.title}
          </span>
        )}
      </button>
      <button
        onClick={(e) => {
          e.stopPropagation();
          onDelete();
        }}
        className="opacity-0 group-hover:opacity-100 p-1 hover:text-red-500 transition-opacity flex-shrink-0"
        title="删除会话"
      >
        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
        </svg>
      </button>
    </div>
  );
}
