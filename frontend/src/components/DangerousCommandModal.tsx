import { useState, useEffect, useRef } from 'react';
import { ShieldAlert, Terminal, X, Check, Loader2, AlertTriangle } from 'lucide-react';
import { clsx } from 'clsx';

interface PendingApproval {
  ticketId: string;
  command: string;
  message: string;
}

interface DangerousCommandModalProps {
  pendingApproval: PendingApproval | null;
  onClose: () => void;
  onApproved: () => void;
}

export function DangerousCommandModal({
  pendingApproval,
  onClose,
  onApproved,
}: DangerousCommandModalProps) {
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitResult, setSubmitResult] = useState<'approved' | 'rejected' | null>(null);
  const modalRef = useRef<HTMLDivElement>(null);

  // Lock body scroll when modal is open
  useEffect(() => {
    if (pendingApproval) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => {
      document.body.style.overflow = '';
    };
  }, [pendingApproval]);

  // Close on Escape
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && pendingApproval && !isSubmitting) {
        handleReject();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [pendingApproval, isSubmitting]);

  // Focus trap
  useEffect(() => {
    if (pendingApproval && modalRef.current) {
      modalRef.current.focus();
    }
  }, [pendingApproval]);

  const handleApprove = async () => {
    if (!pendingApproval || isSubmitting) return;
    setIsSubmitting(true);
    try {
      const response = await fetch('/approval', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ticketId: pendingApproval.ticketId,
          approved: true,
          command: pendingApproval.command,
        }),
      });
      const data = await response.json();
      if (data.success) {
        setSubmitResult('approved');
        setTimeout(() => {
          onApproved();
          onClose();
          setSubmitResult(null);
        }, 1200);
      } else {
        alert(`审批失败: ${data.message}`);
        setIsSubmitting(false);
      }
    } catch (e) {
      console.error('Approval error:', e);
      alert('审批请求失败，请重试');
      setIsSubmitting(false);
    }
  };

  const handleReject = async () => {
    if (!pendingApproval || isSubmitting) return;
    setIsSubmitting(true);
    try {
      const response = await fetch('/approval', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ticketId: pendingApproval.ticketId,
          approved: false,
          command: pendingApproval.command,
        }),
      });
      const data = await response.json();
      if (data.success) {
        setSubmitResult('rejected');
        setTimeout(() => {
          onApproved();
          onClose();
          setSubmitResult(null);
        }, 800);
      } else {
        alert(`拒绝失败: ${data.message}`);
        setIsSubmitting(false);
      }
    } catch (e) {
      console.error('Reject error:', e);
      alert('拒绝请求失败，请重试');
      setIsSubmitting(false);
    }
  };

  if (!pendingApproval) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/70 backdrop-blur-sm animate-in fade-in duration-200"
        onClick={!isSubmitting ? onClose : undefined}
      />

      {/* Modal */}
      <div
        ref={modalRef}
        tabIndex={-1}
        className={clsx(
          'relative w-full max-w-lg',
          'bg-[#0d0d0d] border border-amber-500/30',
          'shadow-[0_0_60px_rgba(251,191,36,0.15)]',
          'animate-in zoom-in-95 fade-in duration-300',
          'focus:outline-none'
        )}
      >
        {/* Diagonal warning stripes */}
        <div
          className="absolute -top-px left-0 right-0 h-1.5"
          style={{
            background: 'repeating-linear-gradient(45deg, #f59e0b 0, #f59e0b 8px, transparent 0, transparent 16px)',
          }}
        />
        <div
          className="absolute -bottom-px left-0 right-0 h-1.5"
          style={{
            background: 'repeating-linear-gradient(45deg, #f59e0b 0, #f59e0b 8px, transparent 0, transparent 16px)',
          }}
        />

        {/* Content */}
        <div className="p-6 pt-8">
          {/* Header */}
          <div className="flex items-start justify-between mb-6">
            <div className="flex items-center gap-3">
              <div className="relative">
                <ShieldAlert className="w-8 h-8 text-amber-500" />
                <span className="absolute -top-1 -right-1 flex h-3 w-3">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-500 opacity-75" />
                  <span className="relative inline-flex rounded-full h-3 w-3 bg-amber-500" />
                </span>
              </div>
              <div>
                <h2 className="text-lg font-bold text-amber-50 tracking-tight">
                  危险操作待审批
                </h2>
                <p className="text-xs text-amber-500/70 mt-0.5">
                  DANGEROUS COMMAND PENDING APPROVAL
                </p>
              </div>
            </div>

            {!isSubmitting && (
              <button
                onClick={onClose}
                className="p-1.5 text-neutral-500 hover:text-amber-50 hover:bg-neutral-800 rounded transition-colors"
                aria-label="关闭"
              >
                <X className="w-5 h-5" />
              </button>
            )}
          </div>

          {/* Warning box */}
          <div className="mb-6 p-4 bg-amber-500/5 border border-amber-500/20 rounded">
            <div className="flex items-start gap-3">
              <AlertTriangle className="w-5 h-5 text-amber-500 flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-sm font-medium text-amber-100">
                  此命令具有破坏性，正在等待你的确认
                </p>
                <p className="text-xs text-amber-500/60 mt-1">
                  This command may cause data loss or system damage
                </p>
              </div>
            </div>
          </div>

          {/* Command display */}
          <div className="mb-6">
            <label className="flex items-center gap-2 text-xs font-semibold text-neutral-400 uppercase tracking-wider mb-2">
              <Terminal className="w-3.5 h-3.5" />
              待执行命令
            </label>
            <div className="relative">
              <div className="absolute left-0 top-0 bottom-0 w-1 bg-amber-500/50 rounded-l" />
              <pre
                className={clsx(
                  'pl-5 pr-4 py-3',
                  'bg-neutral-900 border border-neutral-800 rounded-r',
                  'font-mono text-sm text-amber-50',
                  'overflow-x-auto whitespace-pre-wrap break-all',
                  'max-h-40 overflow-y-auto'
                )}
              >
                {pendingApproval.command}
              </pre>
            </div>
          </div>

          {/* Ticket ID */}
          <div className="mb-6 flex items-center justify-between">
            <span className="text-xs text-neutral-500">票根 ID</span>
            <code className="text-xs font-mono text-neutral-400 bg-neutral-800 px-2 py-1 rounded">
              {pendingApproval.ticketId}
            </code>
          </div>

          {/* Actions */}
          <div className="flex items-center gap-3">
            <button
              onClick={handleReject}
              disabled={isSubmitting}
              className={clsx(
                'flex-1 flex items-center justify-center gap-2',
                'px-4 py-3 rounded font-semibold text-sm',
                'border border-neutral-700 text-neutral-300',
                'hover:bg-neutral-800 hover:border-neutral-600',
                'disabled:opacity-50 disabled:cursor-not-allowed',
                'transition-all duration-150'
              )}
            >
              {submitResult === 'rejected' ? (
                <>
                  <Check className="w-4 h-4 text-green-500" />
                  <span className="text-green-500">已拒绝</span>
                </>
              ) : isSubmitting ? (
                <Loader2 className="w-4 h-4 animate-spin" />
              ) : (
                <>
                  <X className="w-4 h-4" />
                  <span>拒绝执行</span>
                </>
              )}
            </button>

            <button
              onClick={handleApprove}
              disabled={isSubmitting}
              className={clsx(
                'flex-1 flex items-center justify-center gap-2',
                'px-4 py-3 rounded font-semibold text-sm',
                'bg-amber-500 text-neutral-900',
                'hover:bg-amber-400',
                'disabled:opacity-50 disabled:cursor-not-allowed',
                'transition-all duration-150',
                'shadow-[0_0_20px_rgba(245,158,11,0.3)]',
                'hover:shadow-[0_0_30px_rgba(245,158,11,0.5)]'
              )}
            >
              {submitResult === 'approved' ? (
                <>
                  <Check className="w-4 h-4" />
                  <span>已批准</span>
                </>
              ) : isSubmitting ? (
                <Loader2 className="w-4 h-4 animate-spin" />
              ) : (
                <>
                  <Check className="w-4 h-4" />
                  <span>确认执行</span>
                </>
              )}
            </button>
          </div>

          {/* Footer hint */}
          <p className="mt-4 text-center text-xs text-neutral-600">
            按 <kbd className="px-1.5 py-0.5 bg-neutral-800 rounded text-neutral-400 font-mono">Esc</kbd> 拒绝 ·{' '}
            <kbd className="px-1.5 py-0.5 bg-neutral-800 rounded text-neutral-400 font-mono">Enter</kbd> 批准
          </p>
        </div>
      </div>
    </div>
  );
}

// Parse pending approval from message content
export function parsePendingApproval(content: string): PendingApproval | null {
  // Try standard format first: ⏸️ {message}\n\n票根ID: {ticketId}\n\n...
  const ticketMatch = content.match(/票根ID:\s*([a-f0-9-]{36})/i);
  if (ticketMatch) {
    const ticketId = ticketMatch[1];
    const messageMatch = content.match(/⏸️\s*([^\n]+(?:\n(?!\n)[^\n]*)*)/);
    const rawMessage = messageMatch?.[1]?.trim() || '危险命令待审批';
    const colonIdx = rawMessage.indexOf(':');
    const command = colonIdx !== -1 ? rawMessage.slice(colonIdx + 1).trim() : rawMessage;
    return { ticketId, command, message: rawMessage };
  }

  // Fallback: match LLM-generated approval descriptions like:
  // "命令已经被提交审批，等待用户批准" or "命令已提交审批，等待确认"
  const approvalPhrases = [
    /命令已经.*提交.*审批/,
    /命令已提交.*审批/,
    /等待.*批准/,
    /已提交.*危险命令.*审批/,
    /⏸️.*危险.*命令.*待.*审批/,
  ];
  for (const phrase of approvalPhrases) {
    if (phrase.test(content)) {
      // Extract command from the full content - look for "rm", "chmod", "dd" etc
      const commandMatch = content.match(/\b(rm\s+-rf[^\s]*|chmod\s+777[^\s]*|dd\s+if[^\s]*|mkfs[^\s]*|:\s*\(.*:.*\|.*:.*&.*\}.\s*:)/);
      const command = commandMatch?.[1] || content.slice(0, 80);
      return {
        ticketId: '', // Will be fetched from polling
        command,
        message: '危险命令待审批',
      };
    }
  }

  return null;
}
