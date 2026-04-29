import type { ChatRequest, Question } from '../types';

export interface PendingApproval {
  ticketId: string;
  command: string;
}

const API_BASE = '/chat';

/**
 * 过滤工具调用 JSON 文本，避免内部实现细节暴露给用户
 * 只过滤独立的 tool_calls JSON，不过滤嵌入在思考内容中的
 */
function filterToolCallText(text: string): string | null {
  // 如果文本看起来是完整的思考块（以 < 开头），保留
  if (text.trim().startsWith('<') && text.includes('}')) {
    return text;
  }
  // 如果文本是纯 JSON 格式的工具调用，过滤掉
  const trimmed = text.trim();
  if ((trimmed.startsWith('{') && trimmed.endsWith('}')) ||
      (trimmed.startsWith('{"') && trimmed.includes('"tool_calls"'))) {
    return null;
  }
  // 过滤嵌入在文本中的 tool_calls JSON (如: ...","tool_calls":[{"id":"...}])
  text = text.replace(/,"tool_calls":\[[\s\S]*?\]/g, '');
  text = text.replace(/"tool_calls":\[[\s\S]*?\]/g, '');
  // 过滤末尾的 },--- 模式
  text = text.replace(/\},?\s*---$/g, '');
  return text;
}

/**
 * 过滤掉思考标签，因为思考内容已经通过 event:thought 单独发送
 */
function filterThinkingTags(text: string): string {
  return text
    .replace(/<thinking>[\s\S]*?<\/thinking>/gi, '')
    .replace(/\[begin_thought\][\s\S]*?\[end_thought\]/gi, '')
    .replace(/<think>[\s\S]*?<\/think>/gi, '')
    .replace(/<think>[\s\S]*?<\/think>/gi, '')
    .trim();
}

export interface StreamEvent {
  type: 'text' | 'thought' | 'dangerous-command' | 'ask-user-question' | 'tool-call';
  id?: string;
  data?: string;
  ticketId?: string;
  command?: string;
  questionId?: string;
  conversationId?: string;
  questions?: unknown[];
  toolName?: string;
  toolInput?: string;
  thinkToolName?: string;
}

export async function* streamChat(
  question: string,
  sessionId: string,
  workspace?: string,
  bypassApproval?: boolean
): AsyncGenerator<StreamEvent, void, unknown> {
  const response = await fetch(`${API_BASE}/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, sessionId, workspace, bypassApproval } satisfies ChatRequest),
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  const reader = response.body?.getReader();
  if (!reader) throw new Error('No response body');

  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // 处理 buffer 直到没有完整的 SSE 事件
      while (buffer.includes('\n\n')) {
        const eventEnd = buffer.indexOf('\n\n');
        const event = buffer.slice(0, eventEnd);
        buffer = buffer.slice(eventEnd + 2);

        // 解析 SSE 事件
        let eventType = '';
        const lines = event.split('\n');

        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventType = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim();
            if (!data || data === '[DONE]') continue;

            if (eventType === 'dangerous-command') {
              yield { type: 'dangerous-command', ...JSON.parse(data) } as StreamEvent;
            } else if (eventType === 'ask-user-question') {
              yield { type: 'ask-user-question', ...JSON.parse(data) } as StreamEvent;
            } else if (eventType === 'thought') {
              // 思考事件 - 直接传递原始内容，让渲染层解析
              const chunk = JSON.parse(data);
              yield { type: 'thought', id: chunk.id, data: chunk.content || '' } as StreamEvent;
            } else if (eventType === 'tool') {
              // 工具调用事件
              const chunk = JSON.parse(data);
              if (chunk.toolName) {
                yield { type: 'tool-call', id: chunk.id, toolName: chunk.toolName } as StreamEvent;
              }
            } else if (eventType === 'message' || !eventType) {
              // 普通消息事件 - 需要过滤掉 <think> 标签内容
              try {
                const chunk = JSON.parse(data);
                if (chunk.content) {
                  // 过滤掉 <think> 和 </think> 之间的内容
                  const filtered = filterThinkingTags(chunk.content);
                  if (filtered) {
                    yield { type: 'text', id: chunk.id, data: filtered };
                  }
                }
              } catch {
                // 如果不是 JSON，当作纯文本处理（向后兼容）
                let filtered = filterToolCallText(data);
                if (filtered) {
                  filtered = filterThinkingTags(filtered);
                  if (filtered) {
                    yield { type: 'text', data: filtered };
                  }
                }
              }
            }
          }
        }
      }
    }

    // 处理剩余 buffer
    if (buffer.trim()) {
      const lines = buffer.split('\n');
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim();
          if (!data || data === '[DONE]') continue;
          const filtered = filterToolCallText(data);
          if (filtered) {
            yield { type: 'text', data: filtered };
          }
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

export async function checkPendingQuestions(): Promise<{
  hasQuestion: boolean;
  questionId: string | null;
  questions: Question[];
} | null> {
  try {
    const response = await fetch('/api/questions/pending');
    const data = await response.json();
    return data;
  } catch {
    return null;
  }
}

export async function checkPendingApprovals(): Promise<
  {
    ticketId: string;
    command: string;
    message: string;
  }[]
> {
  try {
    const response = await fetch('/approval/pending');
    return response.json();
  } catch {
    return [];
  }
}

export async function submitQuestionAnswer(
  questionId: string,
  answers: Record<string, string>,
  annotations?: Record<string, unknown>
): Promise<void> {
  await fetch(`/api/questions/${questionId}/answer`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ answers, annotations: annotations || {} }),
  });
}

export async function skipQuestion(questionId: string): Promise<void> {
  await fetch(`/api/questions/${questionId}/answer`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ skip: true, skipReason: 'User skipped' }),
  });
}

export async function submitApproval(
  ticketId: string,
  command: string,
  approved: boolean
): Promise<{ success: boolean; message: string }> {
  const response = await fetch('/approval', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ticketId, command, approved }),
  });
  return response.json();
}

/**
 * 连接审批事件 SSE 通道（实时推送）
 * 返回 abort controller 用于断开连接
 */
export function connectApprovalEvents(
  onDangerousCommand: (ticketId: string, command: string, message: string) => void
): { eventSource: EventSource; controller: AbortController } {
  const controller = new AbortController();
  const eventSource = new EventSource('/approval-events');

  eventSource.addEventListener('dangerous-command', (event) => {
    try {
      const data = JSON.parse(event.data);
      onDangerousCommand(data.ticketId, data.command, data.message);
    } catch (e) {
      console.error('Failed to parse dangerous-command event:', e);
    }
  });

  eventSource.onerror = (error) => {
    console.error('Approval events SSE error:', error);
    // SSE 会自动重连
  };

  return { eventSource, controller };
}
