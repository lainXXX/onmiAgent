import { useEffect, useRef, useState, useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import clsx from 'clsx';

interface MarkdownRendererProps {
  content: string;
  className?: string;
}

function CopyButton({ code }: { code: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (e) {
      console.error('Failed to copy:', e);
    }
  }, [code]);

  return (
    <button
      onClick={handleCopy}
      className="absolute top-2 right-2 px-2 py-1 text-xs bg-gray-700 hover:bg-gray-600 text-gray-300 rounded transition-colors opacity-0 group-hover:opacity-100"
      title="Copy code"
    >
      {copied ? '✓ Copied' : 'Copy'}
    </button>
  );
}

export function MarkdownRenderer({ content, className }: MarkdownRendererProps) {
  return (
    <ReactMarkdown
      className={clsx(
        'prose prose-slate dark:prose-invert max-w-none',
        'prose-headings:font-semibold prose-headings:text-gray-900 dark:prose-headings:text-gray-100',
        'prose-p:text-gray-700 dark:prose-p:text-gray-300',
        'prose-a:text-blue-600 dark:prose-a:text-blue-400',
        'prose-code:text-sm prose-code:bg-gray-100 dark:prose-code:bg-gray-800',
        'prose-pre:bg-gray-900 dark:prose-pre:bg-gray-800',
        'prose-ul:list-disc prose-ol:list-decimal',
        className
      )}
      remarkPlugins={[remarkGfm, remarkBreaks]}
      components={{
        code({ node, className, children, ...props }) {
          const match = /language-(\w+)/.exec(className || '');
          const isInline = !match && !String(children).includes('\n');

          if (isInline) {
            return (
              <code
                className="px-1.5 py-0.5 rounded bg-gray-100 dark:bg-gray-800 text-blue-600 dark:text-blue-400 font-mono text-sm"
                {...props}
              >
                {children}
              </code>
            );
          }

          return (
            <div className="relative group !mt-0 !mb-4 rounded-lg !bg-gray-900 dark:!bg-gray-800">
              <CopyButton code={String(children).replace(/\n$/, '')} />
              <SyntaxHighlighter
                style={oneDark}
                language={match?.[1] || 'text'}
                PreTag="div"
              >
                {String(children).replace(/\n$/, '')}
              </SyntaxHighlighter>
            </div>
          );
        },
        pre({ children }) {
          return <pre className="not-prose">{children}</pre>;
        },
        table({ children }) {
          return (
            <div className="overflow-x-auto my-4">
              <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
                {children}
              </table>
            </div>
          );
        },
        th({ children }) {
          return (
            <th className="px-4 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wider bg-gray-50 dark:bg-gray-800">
              {children}
            </th>
          );
        },
        td({ children }) {
          return (
            <td className="px-4 py-2 text-sm text-gray-700 dark:text-gray-300">
              {children}
            </td>
          );
        },
        blockquote({ children }) {
          return (
            <blockquote className="border-l-4 border-blue-500 pl-4 italic text-gray-600 dark:text-gray-400 my-4">
              {children}
            </blockquote>
          );
        },
        a({ href, children }) {
          return (
            <a
              href={href}
              target="_blank"
              rel="noopener noreferrer"
              className="text-blue-600 dark:text-blue-400 hover:underline"
            >
              {children}
            </a>
          );
        },
      }}
    >
      {content}
    </ReactMarkdown>
  );
}

interface StreamingMarkdownProps extends MarkdownRendererProps {
  onStreamEnd?: () => void;
}

export function StreamingMarkdown({ content, onStreamEnd, className }: StreamingMarkdownProps) {
  const contentRef = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    contentRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [content]);

  useEffect(() => {
    if (!content) return;

    const codeBlockCount = (content.match(/```/g) || []).length;
    const isComplete = codeBlockCount % 2 === 0;

    if (!content.endsWith('\n') && isComplete && onStreamEnd) {
      onStreamEnd();
    }
  }, [content, onStreamEnd]);

  return (
    <div className="relative">
      <MarkdownRenderer content={content} className={className} />
      {content.length > 0 && (
        <span
          ref={contentRef}
          className="inline-block w-0.5 h-5 bg-blue-500 ml-1 animate-pulse align-text-bottom"
        />
      )}
    </div>
  );
}
