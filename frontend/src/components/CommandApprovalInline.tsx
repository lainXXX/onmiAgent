import { useState } from 'react';
import { ShieldAlert, Terminal, Check, X, Loader2, AlertTriangle } from 'lucide-react';
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
  message = '此命令具有破坏性，需要你的确认才能执行',
  onApproved,
}: CommandApprovalInlineProps) {
  const [status, setStatus] = useState<'idle' | 'approving' | 'approved' | 'rejected'>('idle');

  const handleApprove = async () => {
    setStatus('approving');
    try {
      await submitApproval(ticketId, command, true);
      setStatus('approved');
      setTimeout(onApproved, 800);
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
      setTimeout(onApproved, 800);
    } catch (e) {
      console.error('Reject error:', e);
      setStatus('idle');
    }
  };

  return (
    <div
      className={clsx(
        'mx-4 mb-4 rounded-xl border transition-all duration-300',
        'bg-gradient-to-br from-purple-50 to-pink-50 dark:from-purple-950/30 dark:to-pink-950/20',
        'border-purple-200/50 dark:border-purple-800/30',
        'shadow-sm'
      )}
    >
      {/* Header */}
      <div className="flex items-center gap-3 px-4 py-3 border-b border-purple-100 dark:border-purple-900/50">
        <div className="relative flex-shrink-0">
          <ShieldAlert className="w-5 h-5 text-purple-500" />
          {status === 'idle' && (
            <span className="absolute -top-0.5 -right-0.5 flex h-2.5 w-2.5">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-purple-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-purple-500" />
            </span>
          )}
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-semibold text-purple-700 dark:text-purple-300">
            危险操作待确认
          </p>
          <p className="text-xs text-purple-500/70 dark:text-purple-400/50 mt-0.5">
            DANGEROUS COMMAND PENDING APPROVAL
          </p>
        </div>
        <div className="flex-shrink-0">
          {status === 'approved' && (
            <span className="flex items-center gap-1 text-xs font-medium text-green-600 dark:text-green-400">
              <Check className="w-3.5 h-3.5" />
              已批准
            </span>
          )}
          {status === 'rejected' && (
            <span className="flex items-center gap-1 text-xs font-medium text-red-500">
              <X className="w-3.5 h-3.5" />
              已拒绝
            </span>
          )}
        </div>
      </div>

      {/* Warning */}
      <div className="flex items-start gap-2.5 px-4 py-3 bg-amber-50/60 dark:bg-amber-900/10">
        <AlertTriangle className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
        <p className="text-xs text-amber-700 dark:text-amber-400 leading-relaxed">
          {message}
        </p>
      </div>

      {/* Command display */}
      <div className="px-4 py-3">
        <div className="flex items-center gap-1.5 mb-2">
          <Terminal className="w-3.5 h-3.5 text-purple-400" />
          <span className="text-xs font-medium text-purple-500/70 dark:text-purple-400/60 uppercase tracking-wider">
            待执行命令
          </span>
        </div>
        <pre
          className={clsx(
            'w-full px-3 py-2.5 rounded-lg',
            'bg-neutral-900 border border-neutral-800',
            'font-mono text-sm text-emerald-400',
            'whitespace-pre-wrap break-all',
            'max-h-32 overflow-y-auto'
          )}
        >
          {command}
        </pre>
      </div>

      {/* Ticket ID */}
      <div className="px-4 pb-2 flex items-center justify-between">
        <span className="text-xs text-neutral-400">票根 ID</span>
        <code className="text-xs font-mono text-neutral-500 bg-neutral-800 px-1.5 py-0.5 rounded">
          {ticketId.slice(0, 8)}...
        </code>
      </div>

      {/* Actions */}
      <div className="flex gap-2 px-4 pb-4">
        <button
          onClick={handleReject}
          disabled={status !== 'idle'}
          className={clsx(
            'flex-1 flex items-center justify-center gap-1.5',
            'px-4 py-2.5 rounded-lg font-medium text-sm',
            'border transition-all duration-150',
            status === 'rejected'
              ? 'border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-950/30 text-red-500'
              : 'border-neutral-200 dark:border-neutral-700 text-neutral-600 dark:text-neutral-400 hover:bg-neutral-50 dark:hover:bg-neutral-800 hover:border-neutral-300 dark:hover:border-neutral-600',
            'disabled:opacity-50 disabled:cursor-not-allowed'
          )}
        >
          {status === 'approving' ? (
            <Loader2 className="w-3.5 h-3.5 animate-spin" />
          ) : (
            <X className="w-3.5 h-3.5" />
          )}
          <span>拒绝</span>
        </button>

        <button
          onClick={handleApprove}
          disabled={status !== 'idle'}
          className={clsx(
            'flex-1 flex items-center justify-center gap-1.5',
            'px-4 py-2.5 rounded-lg font-semibold text-sm',
            'transition-all duration-150',
            status === 'approved'
              ? 'bg-green-500 text-white'
              : 'bg-gradient-to-r from-purple-500 to-pink-500 text-white hover:from-purple-600 hover:to-pink-600 shadow-sm hover:shadow',
            'disabled:opacity-50 disabled:cursor-not-allowed'
          )}
        >
          {status === 'approving' ? (
            <Loader2 className="w-3.5 h-3.5 animate-spin" />
          ) : (
            <Check className="w-3.5 h-3.5" />
          )}
          <span>{status === 'approved' ? '已批准' : '确认执行'}</span>
        </button>
      </div>
    </div>
  );
}
