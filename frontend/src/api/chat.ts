import type { ChatRequest, PendingQuestion } from '../types';

export interface PendingApproval {
  ticketId: string;
  command: string;
}

const API_BASE = '/chat';

export interface StreamEvent {
  type: 'text' | 'dangerous-command' | 'ask-user-question';
  data?: string;
  ticketId?: string;
  command?: string;
  questionId?: string;
  conversationId?: string;
  questions?: unknown[];
}

export async function* streamChat(
  question: string,
  sessionId: string,
  workspace?: string
): AsyncGenerator<StreamEvent, void, unknown> {
  const response = await fetch(`${API_BASE}/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, sessionId, workspace } satisfies ChatRequest),
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  const reader = response.body?.getReader();
  if (!reader) throw new Error('No response body');

  const decoder = new TextDecoder();
  let buffer = '';
  let currentEventType = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // SSE format: event: xxx\ndata: yyy\n\n
      let eventEnd: number;
      while ((eventEnd = buffer.indexOf('\n\n')) !== -1) {
        const event = buffer.slice(0, eventEnd);
        buffer = buffer.slice(eventEnd + 2);

        const lines = event.split('\n');
        currentEventType = '';
        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEventType = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim();
            if (data && data !== '[DONE]') {
              if (currentEventType === 'dangerous-command') {
                yield { type: 'dangerous-command', ...JSON.parse(data) } as StreamEvent;
              } else if (currentEventType === 'ask-user-question') {
                yield { type: 'ask-user-question', ...JSON.parse(data) } as StreamEvent;
              } else {
                yield { type: 'text', data };
              }
            }
          }
        }
      }
    }

    // Process remaining buffer
    if (buffer) {
      const lines = buffer.split('\n');
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim();
          if (data && data !== '[DONE]') {
            yield { type: 'text', data };
          }
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

export async function checkPendingQuestions(): Promise<PendingQuestion> {
  const response = await fetch('/api/questions/pending');
  return response.json();
}

export async function submitAnswer(
  questionId: string,
  answers: Record<string, string>
): Promise<void> {
  await fetch(`/api/questions/${questionId}/answer`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ answers, annotations: {} }),
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

export async function checkPendingApprovals(): Promise<
  { ticketId: string; command: string }[]
> {
  const response = await fetch('/approval/pending');
  if (!response.ok) return [];
  return response.json();
}
