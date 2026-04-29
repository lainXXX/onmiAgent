export interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
  blocks?: ChatStep[];
}

export interface Conversation {
  id: number;
  sessionId: string;
  title: string;
  messages: Message[];
  createdAt: string;
  workspace?: string;
  bypassApproval?: boolean;
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
  bypassApproval?: boolean;
}

export interface ChatStep {
  id: string;
  type: 'thought' | 'tool-call' | 'text';
  content: string;
  toolName?: string;
}
