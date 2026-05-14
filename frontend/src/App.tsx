import { useState, useEffect, useRef, useCallback } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Sidebar } from './components/Sidebar';
import { ToolsSidebar } from './components/ToolsSidebar';
import { ChatMessage } from './components/ChatMessage';
import { ChatInput } from './components/ChatInput';
import { QuestionInline } from './components/QuestionInline';
import { CommandApprovalInline } from './components/CommandApprovalInline';
import { streamChat, checkPendingQuestions, connectApprovalEvents } from './api/chat';
import { AuthProvider, useAuth } from './context/AuthContext';
import { PrivateRoute } from './components/PrivateRoute';
import { AuthRoute } from './components/AuthRoute';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import type { Conversation, Message, Question, ChatStep } from './types';

function ChatPage() {
  const { user, logout } = useAuth();
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<number | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamingContent, setStreamingContent] = useState('');
  const [leftSidebarOpen, setLeftSidebarOpen] = useState(() => {
    if (typeof window !== 'undefined') {
      return window.innerWidth >= 1024;
    }
    return true;
  });
  const [workspace, setWorkspace] = useState<string>(() => {
    return localStorage.getItem('omni-workspace') || '';
  });
  const [bypassApproval, setBypassApproval] = useState<boolean>(false);
  const [pendingQuestion, setPendingQuestion] = useState<{
    questionId: string;
    questions: Question[];
  } | null>(null);
  const [pendingApproval, setPendingApproval] = useState<{
    ticketId: string;
    command: string;
    message: string;
  } | null>(null);
  const [streamingBlocks, setStreamingBlocks] = useState<ChatStep[]>([]);
  const [streamingBlockId, setStreamingBlockId] = useState<string | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const approvalSseRef = useRef<{ eventSource: EventSource; controller: AbortController } | null>(null);

  const activeConversation = conversations.find((c) => c.id === activeId);

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, streamingContent, scrollToBottom]);

  const startApprovalSse = useCallback(() => {
    if (approvalSseRef.current) return;
    const { eventSource, controller } = connectApprovalEvents((ticketId, command, message) => {
      setPendingApproval({ ticketId, command, message });
      setIsStreaming(false);
    });
    approvalSseRef.current = { eventSource, controller };
  }, []);

  const stopApprovalSse = useCallback(() => {
    if (approvalSseRef.current) {
      approvalSseRef.current.eventSource.close();
      approvalSseRef.current.controller.abort();
      approvalSseRef.current = null;
    }
  }, []);

  const startPolling = useCallback(() => {
    if (pollingRef.current) return;
    pollingRef.current = setInterval(async () => {
      try {
        const pending = await checkPendingQuestions();
        if (pending && pending.hasQuestion && pending.questionId && pending.questions.length > 0) {
          setPendingQuestion({
            questionId: pending.questionId,
            questions: pending.questions,
          });
          setIsStreaming(false);
          stopPolling();
        }
      } catch (e) {
        console.error('Polling error:', e);
      }
    }, 2000);
  }, []);

  const stopPolling = useCallback(() => {
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  }, []);

  useEffect(() => {
    const saved = localStorage.getItem('omni-conversations');
    if (saved) {
      try {
        const parsed = JSON.parse(saved) as Conversation[];
        setConversations(parsed);
        if (parsed.length > 0) {
          setActiveId(parsed[0].id);
          setMessages(parsed[0].messages);
        }
      } catch (e) {
        console.error('Failed to load conversations:', e);
      }
    }
  }, []);

  useEffect(() => {
    if (conversations.length > 0) {
      localStorage.setItem('omni-conversations', JSON.stringify(conversations));
    }
  }, [conversations]);

  useEffect(() => {
    if (workspace) {
      localStorage.setItem('omni-workspace', workspace);
    } else {
      localStorage.removeItem('omni-workspace');
    }
  }, [workspace]);

  useEffect(() => {
    if (activeId !== null) {
      setConversations((convs) =>
        convs.map((c) => (c.id === activeId ? { ...c, bypassApproval } : c))
      );
    }
  }, [bypassApproval, activeId]);

  const handleNewChat = () => {
    const newConv: Conversation = {
      id: Date.now(),
      sessionId: crypto.randomUUID(),
      title: 'New Chat',
      messages: [],
      createdAt: new Date().toISOString(),
      bypassApproval: false,
    };
    setConversations((prev) => [newConv, ...prev]);
    setActiveId(newConv.id);
    setMessages([]);
    setBypassApproval(false);
    setPendingQuestion(null);
  };

  const handleSelectChat = (id: number) => {
    const conv = conversations.find((c) => c.id === id);
    if (conv) {
      setActiveId(id);
      setMessages(conv.messages);
      setBypassApproval(conv.bypassApproval ?? false);
      setWorkspace(conv.workspace ?? '');
      setPendingQuestion(null);
    }
  };

  const handleDeleteChat = (id: number) => {
    if (!confirm('确定要删除这个会话吗？')) return;
    const remaining = conversations.filter((c) => c.id !== id);
    setConversations(remaining);
    if (activeId === id) {
      if (remaining.length > 0) {
        setActiveId(remaining[0].id);
        setMessages(remaining[0].messages);
      } else {
        setActiveId(null);
        setMessages([]);
      }
    }
    if (remaining.length === 0) {
      localStorage.removeItem('omni-conversations');
    }
  };

  const handleRenameChat = (id: number, title: string) => {
    setConversations((convs) =>
      convs.map((c) => (c.id === id ? { ...c, title } : c))
    );
  };

  const handleSend = async (text: string) => {
    if (!activeConversation || isStreaming) return;

    const userMessage: Message = {
      id: crypto.randomUUID(),
      role: 'user',
      content: text,
      timestamp: Date.now(),
    };

    const title = messages.length === 0 ? text.slice(0, 30) + (text.length > 30 ? '...' : '') : activeConversation.title;

    setMessages((prev) => {
      const updated = [...prev, userMessage];
      setConversations((convs) =>
        convs.map((c) => (c.id === activeId ? { ...c, title, messages: updated } : c))
      );
      return updated;
    });

    setIsStreaming(true);
    setStreamingContent('');
    setStreamingBlocks([]);
    setPendingQuestion(null);
    setPendingApproval(null);
    startApprovalSse();
    startPolling();

    try {
      let fullContent = '';
      const thoughtBufferById: Record<string, string> = {};
      const textBufferById: Record<string, string> = {};
      const blocksLocal: ChatStep[] = [];
      setStreamingBlocks(blocksLocal);

      for await (const event of streamChat(text, activeConversation.sessionId, workspace, bypassApproval)) {
        if (event.type === 'ask-user-question') {
          setIsStreaming(false);
          setPendingQuestion({
            questionId: event.questionId!,
            questions: event.questions! as Question[],
          });
          stopPolling();
          return;
        }

        if (event.type === 'dangerous-command') {
          setIsStreaming(false);
          setPendingApproval({
            ticketId: event.ticketId!,
            command: event.command!,
            message: event.data || '危险命令待审批',
          });
          stopPolling();
          return;
        }

        if (event.type === 'thought' && event.data) {
          fullContent += event.data;
          const id = event.id || 'default';
          if (!thoughtBufferById[id]) {
            thoughtBufferById[id] = '';
          }
          thoughtBufferById[id] += event.data.replace(/^\n/, '');

          const existingThoughtIdx = blocksLocal.findIndex(b => b.type === 'thought' && b.id === id);
          if (existingThoughtIdx >= 0) {
            blocksLocal[existingThoughtIdx].content = thoughtBufferById[id];
          } else {
            blocksLocal.push({
              id: id,
              type: 'thought',
              content: thoughtBufferById[id],
              toolName: undefined,
            });
          }
          setStreamingBlockId(id);
          setStreamingBlocks([...blocksLocal]);
          setStreamingContent(fullContent);
        } else if (event.type === 'tool-call' && event.toolName) {
          fullContent += `\n[Tool: ${event.toolName}]\n`;
          const lastThought = blocksLocal.filter(b => b.type === 'thought').pop();
          if (lastThought) {
            lastThought.toolName = event.toolName;
          }
          setStreamingBlocks([...blocksLocal]);
          setStreamingContent(fullContent);
        } else if (event.type === 'text' && event.id && event.data) {
          if (!textBufferById[event.id]) {
            textBufferById[event.id] = '';
          }
          textBufferById[event.id] += event.data.replace(/^\n/, '');
          fullContent += event.data;

          const lastBlock = blocksLocal[blocksLocal.length - 1];
          if (lastBlock && lastBlock.type === 'text') {
            lastBlock.content = textBufferById[event.id];
          } else {
            blocksLocal.push({
              id: event.id,
              type: 'text',
              content: textBufferById[event.id],
            });
          }
          setStreamingBlockId(event.id);
          setStreamingBlocks([...blocksLocal]);
          setStreamingContent(fullContent);
        }
      }

      const assistantMessage: Message = {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: fullContent,
        timestamp: Date.now(),
        blocks: blocksLocal,
      };

      setMessages((prev) => {
        const updated = [...prev, assistantMessage];
        setConversations((convs) =>
          convs.map((c) => (c.id === activeId ? { ...c, messages: updated } : c))
        );
        return updated;
      });
    } catch (e) {
      console.error('Stream error:', e);
      setStreamingContent('Error: Failed to get response');
    } finally {
      setIsStreaming(false);
      stopPolling();
      stopApprovalSse();
    }
  };

  const handleQuestionAnswered = (answerText: string) => {
    setPendingQuestion(null);
    handleSend(answerText);
  };

  const handleApprovalHandled = () => {
    setPendingApproval(null);
    setStreamingContent('');
  };

  return (
    <div className="flex h-full bg-zinc-950">
      {leftSidebarOpen && (
        <div
          className="fixed inset-0 bg-black/50 z-30 lg:hidden"
          onClick={() => setLeftSidebarOpen(false)}
        />
      )}

      <div
        className={`fixed lg:relative z-40 h-full transition-transform duration-200 ${
          leftSidebarOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
        }`}
      >
        <Sidebar
          conversations={conversations}
          activeId={activeId}
          onSelect={(id) => {
            handleSelectChat(id);
            setLeftSidebarOpen(false);
          }}
          onNew={handleNewChat}
          onDelete={handleDeleteChat}
          onRename={handleRenameChat}
        />
      </div>

      <main className="flex-1 flex flex-col min-w-0">
        <header className="px-4 md:px-6 py-3 md:py-4 border-b border-zinc-800 bg-zinc-950 flex items-center gap-3">
          <button
            onClick={() => setLeftSidebarOpen(!leftSidebarOpen)}
            className="p-1.5 text-zinc-500 hover:text-zinc-300 hover:bg-zinc-900 rounded-lg transition-colors"
            title={leftSidebarOpen ? '隐藏侧边栏' : '显示侧边栏'}
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d={leftSidebarOpen ? 'M11 19l-7-7 7-7' : 'M13 5l7 7-7 7'} />
            </svg>
          </button>
          <h1 className="text-base md:text-lg font-semibold text-zinc-200 truncate">
            {activeConversation?.title || 'OmniAgent'}
          </h1>
          <div className="flex-1" />
          {user && (
            <div className="flex items-center gap-3">
              <div className="hidden sm:flex items-center gap-2">
                <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-blue-500 to-purple-600 flex items-center justify-center">
                  <span className="text-white text-sm font-semibold">
                    {user.username.charAt(0).toUpperCase()}
                  </span>
                </div>
                <span className="text-sm text-zinc-300">{user.username}</span>
              </div>
              <button
                onClick={logout}
                className="p-1.5 text-zinc-500 hover:text-zinc-300 hover:bg-zinc-900 rounded-lg transition-colors"
                title="退出登录"
              >
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 9V5.25A2.25 2.25 0 0013.5 3h-6a2.25 2.25 0 00-2.25 2.25v13.5A2.25 2.25 0 007.5 21h6a2.25 2.25 0 002.25-2.25V15M12 9l-3 3m0 0l3 3m-3-3h12.75" />
                </svg>
              </button>
            </div>
          )}
        </header>

        <div className="flex-1 overflow-y-auto">
          {messages.length === 0 && !isStreaming && !streamingContent && !pendingQuestion && (
            <div className="flex items-center justify-center h-full">
              <div className="text-center text-zinc-500">
                <svg
                  className="w-16 h-16 mx-auto mb-4 text-zinc-600"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={1.5}
                    d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"
                  />
                </svg>
                <p className="text-lg text-zinc-300">Start a conversation with OmniAgent</p>
                <p className="text-sm mt-1">Ask questions, search knowledge base, or request actions</p>
              </div>
            </div>
          )}

          {messages.map((msg) => (
            <ChatMessage key={msg.id} message={msg} />
          ))}

          {isStreaming && streamingBlocks.length > 0 && (
            <ChatMessage
              message={{
                id: 'streaming',
                role: 'assistant',
                content: streamingContent,
                timestamp: Date.now(),
                blocks: streamingBlocks,
              }}
              streamingBlockId={streamingBlockId}
              isStreaming={isStreaming}
            />
          )}

          {isStreaming && !streamingContent && (
            <div className="flex gap-2 sm:gap-3 p-3 sm:p-4">
              <div className="w-8 h-8 sm:w-9 sm:h-9 rounded-lg bg-zinc-800 flex items-center justify-center">
                <span className="text-zinc-400 text-xs sm:text-sm font-semibold">AI</span>
              </div>
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-medium text-zinc-500 uppercase tracking-wide">
                    OmniAgent
                  </span>
                  <span className="relative flex gap-1">
                    <span className="w-2 h-2 bg-blue-500 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                    <span className="w-2 h-2 bg-blue-500 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                    <span className="w-2 h-2 bg-blue-500 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                    <span className="absolute inset-0 bg-blue-500/30 rounded-full animate-ping opacity-75" />
                  </span>
                </div>
                <div className="mt-1 text-xs text-zinc-600 animate-pulse">正在思考...</div>
              </div>
            </div>
          )}

          {pendingQuestion && (
            <div className="px-2 sm:px-4">
              <div className="flex gap-2 sm:gap-3 p-3 sm:p-4 bg-zinc-900/50 rounded-lg border border-zinc-800">
                <div className="w-8 h-8 sm:w-9 sm:h-9 rounded-lg bg-zinc-800 flex items-center justify-center text-zinc-300 font-semibold text-xs sm:text-sm flex-shrink-0">
                  AI
                </div>
                <div className="flex-1 min-w-0">
                  <div className="text-xs font-medium text-blue-400 mb-2 uppercase tracking-wide">
                    OmniAgent has a question
                  </div>
                  <QuestionInline
                    questionId={pendingQuestion.questionId!}
                    questions={pendingQuestion.questions}
                    onAnswered={handleQuestionAnswered}
                  />
                </div>
              </div>
            </div>
          )}

          {pendingApproval && (
            <div className="px-2 sm:px-4">
              <div className="flex gap-2 sm:gap-3 p-3 sm:p-4 bg-zinc-900/50 rounded-lg border border-zinc-800">
                <div className="w-8 h-8 sm:w-9 sm:h-9 rounded-lg bg-zinc-800 flex items-center justify-center text-zinc-300 font-semibold text-xs sm:text-sm flex-shrink-0">
                  AI
                </div>
                <div className="flex-1 min-w-0">
                  <div className="text-xs font-medium text-amber-400 mb-2 uppercase tracking-wide">
                    危险命令待审批
                  </div>
                  <CommandApprovalInline
                    ticketId={pendingApproval.ticketId}
                    command={pendingApproval.command}
                    message="此命令具有破坏性，需要你的确认才能执行"
                    onApproved={handleApprovalHandled}
                  />
                </div>
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        <ChatInput
          onSend={handleSend}
          disabled={isStreaming || !!pendingApproval}
          workspace={workspace}
          onWorkspaceChange={setWorkspace}
          bypassApproval={bypassApproval}
          onBypassApprovalChange={setBypassApproval}
        />
      </main>

      <ToolsSidebar />
    </div>
  );
}

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<AuthRoute><LoginPage /></AuthRoute>} />
          <Route path="/register" element={<AuthRoute><RegisterPage /></AuthRoute>} />
          <Route path="/" element={<PrivateRoute><ChatPage /></PrivateRoute>} />
          <Route path="*" element={<Navigate to="/" />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}