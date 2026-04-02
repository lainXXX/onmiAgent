import { memo, useMemo } from 'react';
import { Message } from '../types';
import { MarkdownRenderer } from './MarkdownRenderer';
import { Lightbulb, ChevronDown } from 'lucide-react';
import { parseMessageContent, completeCodeBlock } from '../utils/messageParser';

interface ChatMessageProps {
  message: Message;
}

export const ChatMessage = memo(function ChatMessage({
  message,
}: ChatMessageProps) {
  const isUser = message.role === 'user';

  const { thinkContent, mainContent, isStreaming } = useMemo(() => {
    const parsed = parseMessageContent(message.content);
    // Complete code blocks before rendering
    const fixedMain = completeCodeBlock(parsed.mainContent);
    return {
      thinkContent: parsed.thinkContent,
      mainContent: fixedMain,
      isStreaming: parsed.isStreaming,
    };
  }, [message.content]);

  return (
    <div
      className={`flex gap-3 p-4 animate-in slide-in-from-bottom-2 duration-300 ${
        isUser ? 'bg-purple-50 dark:bg-purple-950/20' : ''
      }`}
    >
      {/* Avatar */}
      <div
        className={`w-9 h-9 rounded-lg flex items-center justify-center font-semibold text-sm flex-shrink-0 ${
          isUser
            ? 'bg-gradient-to-br from-purple-500 to-pink-500 text-white'
            : 'bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300'
        }`}
      >
        {isUser ? 'U' : 'AI'}
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0">
        <div className="text-xs font-medium text-gray-500 dark:text-gray-400 mb-1 uppercase tracking-wide">
          {isUser ? 'You' : 'OmniAgent'}
        </div>

        <div className="text-gray-800 dark:text-gray-200 leading-relaxed">
          {isUser ? (
            <p className="whitespace-pre-wrap">{message.content}</p>
          ) : (
            <div className="flex flex-col gap-4">
              {/* Multi-turn thinking sections */}
              {thinkContent.map((think, idx) => (
                <details
                  key={idx}
                  className="group border border-slate-200 dark:border-slate-700 rounded-lg bg-slate-50 dark:bg-slate-900 overflow-hidden"
                  open={isStreaming && idx === thinkContent.length - 1}
                >
                  <summary className="flex items-center justify-between p-3 cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors list-none">
                    <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400 text-sm font-medium">
                      <Lightbulb
                        className={`w-4 h-4 ${
                          isStreaming ? 'animate-pulse text-amber-500' : ''
                        }`}
                      />
                      <span>
                        {isStreaming && idx === thinkContent.length - 1
                          ? `正在思考... (${idx + 1}/${thinkContent.length})`
                          : `思考 #${idx + 1}`}
                      </span>
                    </div>
                    <ChevronDown className="w-4 h-4 text-slate-400 group-open:rotate-180 transition-transform" />
                  </summary>

                  <div className="px-4 pb-4 pt-0 text-slate-600 dark:text-slate-400 text-sm leading-relaxed italic whitespace-pre-wrap">
                    {think}
                    {isStreaming && idx === thinkContent.length - 1 && (
                      <span className="inline-block w-0.5 h-4 bg-slate-400 ml-1 animate-pulse" />
                    )}
                  </div>
                </details>
              ))}

              {/* Main content */}
              {mainContent && <MarkdownRenderer content={mainContent} />}

              {/* Loading dots when think closed but main not started */}
              {!isStreaming && thinkContent.length > 0 && !mainContent && (
                <div className="flex gap-1 items-center text-slate-400 text-sm italic">
                  <div className="w-1.5 h-1.5 bg-slate-400 rounded-full animate-bounce" />
                  <div className="w-1.5 h-1.5 bg-slate-400 rounded-full animate-bounce [animation-delay:0.2s]" />
                  <div className="w-1.5 h-1.5 bg-slate-400 rounded-full animate-bounce [animation-delay:0.4s]" />
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
});
