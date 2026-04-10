export interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
}

export interface Conversation {
  id: number;
  sessionId: string;
  title: string;
  messages: Message[];
  createdAt: string;
}

export interface Question {
  header: string;
  question: string;
  options: Option[];
  multiSelect: boolean;
}

export interface Option {
  label: string;
  description?: string;
  preview?: string;
}

export interface PendingQuestion {
  hasQuestion: boolean;
  questionId: string | null;
  questions: Question[];
  metadata: Record<string, unknown>;
}

export interface ChatRequest {
  question: string;
  sessionId: string;
  workspace?: string;
}
