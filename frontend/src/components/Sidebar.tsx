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

  const [expandedGroups, setExpandedGroups] = useState({
    today: true,
    yesterday: true,
    older: true,
  });

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

  const toggleGroup = (group: 'today' | 'yesterday' | 'older') => {
    setExpandedGroups((prev) => ({ ...prev, [group]: !prev[group] }));
  };

  const GroupSection = ({ title, convs, groupKey }: { title: string; convs: Conversation[]; groupKey: 'today' | 'yesterday' | 'older' }) => {
    if (convs.length === 0) return null;
    const isExpanded = expandedGroups[groupKey];

    return (
      <div className="mb-4">
        <button
          onClick={() => toggleGroup(groupKey)}
          className="w-full flex items-center gap-2 px-3 py-2 text-[10px] font-bold text-zinc-500 uppercase tracking-widest hover:text-zinc-400 transition-colors"
        >
          <svg
            className={`w-3 h-3 transition-transform ${isExpanded ? 'rotate-90' : ''}`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
          </svg>
          {title} ({convs.length})
        </button>
        {isExpanded && convs.map((conv) => (
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
    );
  };

  return (
    <aside className="w-64 md:w-72 lg:w-64 bg-zinc-950 border-r border-zinc-800 flex flex-col h-full max-w-[85vw] md:max-w-[320px]">
      {/* Header */}
      <div className="p-4 border-b border-zinc-800">
        <button
          onClick={onNew}
          className="w-full flex items-center justify-center gap-2 px-4 py-2 bg-zinc-800 hover:bg-zinc-700 text-zinc-100 text-sm rounded border border-zinc-700 transition-colors"
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
          </svg>
          New Chat
        </button>
      </div>

      {/* Conversation List */}
      <div className="flex-1 overflow-y-auto p-2">
        <GroupSection title="Today" convs={grouped.today} groupKey="today" />
        <GroupSection title="Yesterday" convs={grouped.yesterday} groupKey="yesterday" />
        <GroupSection title="Older" convs={grouped.older} groupKey="older" />
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
  const [isExpanded, setIsExpanded] = useState(false);
  const [editValue, setEditValue] = useState(conv.title);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const handleToggleExpand = (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsExpanded(!isExpanded);
  };

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

  const formatDate = (timestamp: string) => {
    const date = new Date(timestamp);
    return date.toLocaleString('zh-CN', {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  return (
    <div
      className={`group w-full flex flex-col px-3 py-2 rounded text-left text-sm transition-colors cursor-pointer ${
        isActive
          ? 'bg-zinc-900 text-zinc-100 border border-zinc-800'
          : 'text-zinc-500 hover:bg-zinc-900 hover:text-zinc-300'
      }`}
    >
      <div className="flex items-center gap-2">
        <button
          onClick={handleToggleExpand}
          className="p-0.5 hover:text-zinc-300 flex-shrink-0"
          title={isExpanded ? '收起' : '展开'}
        >
          <svg
            className={`w-3 h-3 transition-transform ${isExpanded ? 'rotate-90' : ''}`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
          </svg>
        </button>
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
              className="flex-1 bg-transparent border-b border-zinc-500 outline-none text-zinc-100 truncate"
            />
          ) : (
            <span
              className="truncate cursor-text"
              onDoubleClick={handleDoubleClick}
              title="Double-click to edit"
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
          className="opacity-0 group-hover:opacity-100 p-1 hover:text-red-400 transition-opacity flex-shrink-0"
          title="Delete"
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
          </svg>
        </button>
      </div>
      {isExpanded && (
        <div className="mt-2 pl-5 text-xs text-zinc-500 space-y-1">
          <div>创建时间: {formatDate(conv.createdAt)}</div>
          <div>消息数: {conv.messages?.length || 0}</div>
          <div className="text-zinc-600">会话 ID: {conv.sessionId?.slice(0, 8)}...</div>
        </div>
      )}
    </div>
  );
}