import { useState, useEffect, useRef, useCallback } from 'react';
import { Sidebar } from './components/Sidebar';
import { ToolsSidebar } from './components/ToolsSidebar';
import { ChatMessage } from './components/ChatMessage';
import { ChatInput } from './components/ChatInput';
import { QuestionInline } from './components/QuestionInline';
import { CommandApprovalInline } from './components/CommandApprovalInline';
import { streamChat, checkPendingQuestions, checkPendingApprovals } from './api/chat';
import type { Conversation, Message, Question } from './types';

export function App() {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<number | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamingContent, setStreamingContent] = useState('');
  const [pendingQuestion, setPendingQuestion] = useState<{
    questionId: string;
    questions: Question[];
  } | null>(null);
  const [pendingApproval, setPendingApproval] = useState<{
    ticketId: string;
    command: string;
    message: string;
  } | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const activeConversation = conversations.find((c) => c.id === activeId);

  // Auto-scroll to bottom
  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, streamingContent, scrollToBottom]);

  // Poll for pending questions and dangerous command approvals
  const startPolling = useCallback(() => {
    if (pollingRef.current) return;
    pollingRef.current = setInterval(async () => {
      try {
        // Check for pending approvals (dangerous commands)
        const approvals = await checkPendingApprovals();
        if (approvals.length > 0) {
          const approval = approvals[0];
          setPendingApproval({
            ticketId: approval.ticketId,
            command: approval.command,
            message: '危险命令待审批',
          });
          setIsStreaming(false);
          stopPolling();
          return;
        }
        // Check for pending questions
        const pending = await checkPendingQuestions();
        if (pending.hasQuestion && pending.questionId && pending.questions.length > 0) {
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

  // Load conversations from localStorage
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

  // Save conversations to localStorage
  useEffect(() => {
    if (conversations.length > 0) {
      localStorage.setItem('omni-conversations', JSON.stringify(conversations));
    }
  }, [conversations]);

  const handleNewChat = () => {
    const newConv: Conversation = {
      id: Date.now(),
      sessionId: crypto.randomUUID(),
      title: 'New Chat',
      messages: [],
      createdAt: new Date().toISOString(),
    };
    setConversations((prev) => [newConv, ...prev]);
    setActiveId(newConv.id);
    setMessages([]);
    setPendingQuestion(null);
  };

  const handleSelectChat = (id: number) => {
    const conv = conversations.find((c) => c.id === id);
    if (conv) {
      setActiveId(id);
      setMessages(conv.messages);
      setPendingQuestion(null);
    }
  };

  const handleSend = async (text: string) => {
    if (!activeConversation || isStreaming) return;

    const userMessage: Message = {
      id: crypto.randomUUID(),
      role: 'user',
      content: text,
      timestamp: Date.now(),
    };

    // Update conversation title from first message
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
    setPendingQuestion(null);
    setPendingApproval(null);
    startPolling();

    try {
      let fullContent = '';
      for await (const event of streamChat(text, activeConversation.sessionId)) {
        if (event.type === 'ask-user-question') {
          setIsStreaming(false);
          setPendingQuestion({
            questionId: event.questionId!,
            questions: event.questions! as Question[],
          });
          stopPolling();
          return;
        }

        if (event.type === 'text' && event.data) {
          fullContent += event.data;
          setStreamingContent(fullContent);
        }
      }

      const assistantMessage: Message = {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: fullContent,
        timestamp: Date.now(),
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
    }
  };

  const handleQuestionAnswered = (answerText: string) => {
    setPendingQuestion(null);
    // 将用户回答作为新消息发送，自动触发新的 stream
    handleSend(answerText);
  };

  const handleApprovalHandled = () => {
    setPendingApproval(null);
    // Clear the streaming content and restart the conversation
    setStreamingContent('');
    // The user can continue chatting - the approval result will come through in a new message
  };

  return (
    <div className="flex h-full bg-white dark:bg-gray-900">
      <Sidebar
        conversations={conversations}
        activeId={activeId}
        onSelect={handleSelectChat}
        onNew={handleNewChat}
      />

      <main className="flex-1 flex flex-col min-w-0">
        {/* Header */}
        <header className="px-6 py-4 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900">
          <h1 className="text-lg font-semibold text-gray-800 dark:text-gray-200">
            {activeConversation?.title || 'OmniAgent'}
          </h1>
        </header>

        {/* Messages */}
        <div className="flex-1 overflow-y-auto">
          {messages.length === 0 && !isStreaming && !streamingContent && !pendingQuestion && (
            <div className="flex items-center justify-center h-full">
              <div className="text-center text-gray-500 dark:text-gray-400">
                <svg
                  className="w-16 h-16 mx-auto mb-4 text-purple-400"
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
                <p className="text-lg">Start a conversation with OmniAgent</p>
                <p className="text-sm mt-1">Ask questions, search knowledge base, or request actions</p>
              </div>
            </div>
          )}

          {messages.map((msg) => (
            <ChatMessage key={msg.id} message={msg} />
          ))}

          {isStreaming && streamingContent && (
            <ChatMessage
              message={{
                id: 'streaming',
                role: 'assistant',
                content: streamingContent,
                timestamp: Date.now(),
              }}
            />
          )}

          {isStreaming && !streamingContent && (
            <div className="flex gap-3 p-4">
              <div className="w-9 h-9 rounded-lg bg-gray-200 dark:bg-gray-700 flex items-center justify-center">
                <span className="text-gray-500 dark:text-gray-400 text-sm font-semibold">AI</span>
              </div>
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wide">
                    OmniAgent
                  </span>
                  <span className="flex gap-1">
                    <span className="w-2 h-2 bg-purple-500 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                    <span className="w-2 h-2 bg-purple-500 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                    <span className="w-2 h-2 bg-purple-500 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                  </span>
                </div>
              </div>
            </div>
          )}

          {pendingQuestion && (
            <div className="px-4">
              <div className="flex gap-3 p-4 bg-purple-50 dark:bg-purple-950/20 rounded-lg">
                <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-purple-500 to-pink-500 flex items-center justify-center text-white font-semibold text-sm flex-shrink-0">
                  AI
                </div>
                <div className="flex-1 min-w-0">
                  <div className="text-xs font-medium text-purple-600 dark:text-purple-400 mb-2 uppercase tracking-wide">
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
            <div className="px-4">
              <div className="flex gap-3 p-4 bg-purple-50 dark:bg-purple-950/20 rounded-lg">
                <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-purple-500 to-pink-500 flex items-center justify-center text-white font-semibold text-sm flex-shrink-0">
                  AI
                </div>
                <div className="flex-1 min-w-0">
                  <div className="text-xs font-medium text-purple-600 dark:text-purple-400 mb-2 uppercase tracking-wide">
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

        {/* Input */}
        <ChatInput onSend={handleSend} disabled={isStreaming || !!pendingApproval} />
      </main>

      <ToolsSidebar />
    </div>
  );
}
