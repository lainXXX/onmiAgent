import { useState } from 'react';
import { Check, X, Loader2 } from 'lucide-react';
import { clsx } from 'clsx';
import { submitApproval } from '../api/chat';

interface CommandApprovalInlineProps {
  ticketId: string;
  command: string;
  message?: string;
  onApproved: () => void;
}

export function CommandApprovalInline({
  ticketId,
  command,
  message = '该命令尝试访问 WORKSPACE 外的路径，需要确认',
  onApproved,
}: CommandApprovalInlineProps) {
  const [status, setStatus] = useState<'idle' | 'approving' | 'approved' | 'rejected'>('idle');

  const handleApprove = async () => {
    setStatus('approving');
    try {
      await submitApproval(ticketId, command, true);
      setStatus('approved');
      setTimeout(onApproved, 500);
    } catch (e) {
      console.error('Approve error:', e);
      setStatus('idle');
    }
  };

  const handleReject = async () => {
    setStatus('approving');
    try {
      await submitApproval(ticketId, command, false);
      setStatus('rejected');
      setTimeout(onApproved, 500);
    } catch (e) {
      console.error('Reject error:', e);
      setStatus('idle');
    }
  };

  return (
    <div
      className={clsx(
        'mx-4 mb-3 px-4 py-3 rounded-lg border',
        'bg-zinc-900/80 border-zinc-800',
        'transition-all duration-200'
      )}
    >
      <div className="flex items-center gap-4">
        {/* Label */}
        <span className="text-xs text-zinc-400 font-medium shrink-0">
          待审批命令
        </span>

        {/* Command */}
        <code className={clsx(
          'flex-1 text-xs font-mono text-zinc-300',
          'truncate'
        )}>
          {command}
        </code>

        {/* Actions */}
        <div className="flex items-center gap-2 shrink-0">
          {status === 'idle' && (
            <>
              <button
                onClick={handleReject}
                className={clsx(
                  'px-3 py-1.5 rounded text-xs font-medium',
                  'border border-zinc-700 text-zinc-500',
                  'hover:border-zinc-600 hover:text-zinc-400',
                  'transition-colors'
                )}
              >
                拒绝
              </button>
              <button
                onClick={handleApprove}
                className={clsx(
                  'px-3 py-1.5 rounded text-xs font-medium',
                  'bg-blue-600 text-white',
                  'hover:bg-blue-500',
                  'transition-colors'
                )}
              >
                允许
              </button>
            </>
          )}

          {status === 'approving' && (
            <span className="flex items-center gap-1.5 text-xs text-zinc-500">
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
              处理中...
            </span>
          )}

          {status === 'approved' && (
            <span className="flex items-center gap-1 text-xs text-green-500">
              <Check className="w-3.5 h-3.5" />
              已允许
            </span>
          )}

          {status === 'rejected' && (
            <span className="flex items-center gap-1 text-xs text-red-500">
              <X className="w-3.5 h-3.5" />
              已拒绝
            </span>
          )}
        </div>
      </div>
    </div>
  );
}
