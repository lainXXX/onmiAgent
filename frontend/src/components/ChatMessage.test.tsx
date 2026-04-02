import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ChatMessage } from './ChatMessage';
import type { Message } from '../types';

describe('ChatMessage', () => {
  const baseMessage: Message = {
    id: '1',
    role: 'assistant',
    content: '',
    timestamp: Date.now(),
  };

  describe('user messages', () => {
    it('renders user message content directly', () => {
      const userMessage: Message = {
        ...baseMessage,
        id: '2',
        role: 'user',
        content: 'Hello, how are you?',
      };

      render(<ChatMessage message={userMessage} />);

      expect(screen.getByText('Hello, how are you?')).toBeTruthy();
      expect(screen.getByText('You')).toBeTruthy();
    });
  });

  describe('assistant messages with think tags', () => {
    it('renders think content in collapsible details', () => {
      const assistantMessage: Message = {
        ...baseMessage,
        content: '<think> This is my thinking process.</think> The answer is 42.',
      };

      render(<ChatMessage message={assistantMessage} />);

      expect(screen.getByText('This is my thinking process.')).toBeTruthy();
      expect(screen.getByText(/思考 #1/)).toBeTruthy();
    });

    it('shows "正在思考..." when think tag is open (streaming)', () => {
      const streamingMessage: Message = {
        ...baseMessage,
        content: '<think> Still processing...',
      };

      render(<ChatMessage message={streamingMessage} />);

      expect(screen.getByText(/正在思考... \(1\/1\)/)).toBeTruthy();
    });

    it('renders main content after think tags', () => {
      const completeMessage: Message = {
        ...baseMessage,
        content: '<think> Done thinking.</think> Here is the solution.',
      };

      render(<ChatMessage message={completeMessage} />);

      expect(screen.getByText('Here is the solution.')).toBeTruthy();
    });

    it('shows thinking icon with pulse animation when streaming', () => {
      const streamingMessage: Message = {
        ...baseMessage,
        content: '<think> Thinking...',
      };

      render(<ChatMessage message={streamingMessage} />);

      // Details element should be open during streaming
      const details = document.querySelector('details');
      expect(details?.hasAttribute('open')).toBeTruthy();
    });
  });

  describe('markdown rendering', () => {
    it('renders bold text from markdown', () => {
      const markdownMessage: Message = {
        ...baseMessage,
        content: '<think> Thinking...</think> This is **bold** text.',
      };

      render(<ChatMessage message={markdownMessage} />);

      const boldElement = screen.getByText('bold');
      expect(boldElement.tagName).toBe('STRONG');
    });

    it('renders inline code', () => {
      const codeMessage: Message = {
        ...baseMessage,
        content: '<think> Thinking...</think> Use `console.log()` for debugging.',
      };

      render(<ChatMessage message={codeMessage} />);

      expect(screen.getByText('console.log()')).toBeTruthy();
    });
  });

  describe('avatar and labels', () => {
    it('shows "U" avatar for user messages', () => {
      const userMessage: Message = {
        ...baseMessage,
        role: 'user',
        content: 'User message',
      };

      render(<ChatMessage message={userMessage} />);

      // Find the avatar element containing 'U' with gradient class
      const avatar = document.querySelector('.from-purple-500');
      expect(avatar?.textContent).toBe('U');
    });

    it('shows "AI" avatar for assistant messages', () => {
      const assistantMessage: Message = {
        ...baseMessage,
        content: '<think> Think...</think> Answer.',
      };

      render(<ChatMessage message={assistantMessage} />);

      const avatar = screen.getByText('AI');
      expect(avatar).toBeTruthy();
    });

    it('shows "You" label for user messages', () => {
      const userMessage: Message = {
        ...baseMessage,
        role: 'user',
        content: 'User content',
      };

      render(<ChatMessage message={userMessage} />);

      expect(screen.getByText('You')).toBeTruthy();
    });

    it('shows "OmniAgent" label for assistant messages', () => {
      const assistantMessage: Message = {
        ...baseMessage,
        content: '<think> Think...</think> Answer.',
      };

      render(<ChatMessage message={assistantMessage} />);

      expect(screen.getByText('OmniAgent')).toBeTruthy();
    });
  });
});
