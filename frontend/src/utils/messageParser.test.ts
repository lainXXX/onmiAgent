import { describe, it, expect } from 'vitest';
import { parseMessageContent, completeCodeBlock } from './messageParser';

describe('parseMessageContent', () => {
  describe('think tag parsing', () => {
    it('extracts think content when both tags are present', () => {
      const raw = '<think> This is my thought process about the solution.</think> The answer is 42.';
      const result = parseMessageContent(raw);

      expect(result.thinkContent).toEqual(['This is my thought process about the solution.']);
      expect(result.mainContent).toBe('The answer is 42.');
      expect(result.isStreaming).toBe(false);
    });

    it('handles content with only think start tag (streaming)', () => {
      const raw = '<think> Still thinking about this problem...';
      const result = parseMessageContent(raw);

      expect(result.thinkContent).toEqual(['Still thinking about this problem...']);
      expect(result.mainContent).toBe('');
      expect(result.isStreaming).toBe(true);
    });

    it('returns empty array when no think tags', () => {
      const raw = 'Just a normal message without any thinking.';
      const result = parseMessageContent(raw);

      expect(result.thinkContent).toEqual([]);
      expect(result.mainContent).toBe('Just a normal message without any thinking.');
      expect(result.isStreaming).toBe(false);
    });

    it('handles think content with special characters', () => {
      const raw = '<think> Consider: 1 + 1 = 2, and `"code"` too.</think> Answer with `code` included.';
      const result = parseMessageContent(raw);

      expect(result.thinkContent).toEqual(['Consider: 1 + 1 = 2, and `"code"` too.']);
      expect(result.mainContent).toBe('Answer with `code` included.');
    });

    it('trims whitespace from extracted content', () => {
      const raw = '<think>   Lots of whitespace here  </think>   Main content   ';
      const result = parseMessageContent(raw);

      expect(result.thinkContent).toEqual(['Lots of whitespace here']);
      expect(result.mainContent).toBe('Main content');
    });

    it('handles multiple think sections (multi-turn)', () => {
      const raw = '<think> First thought</think> Main here<think> Second thought</think>';
      const result = parseMessageContent(raw);

      expect(result.thinkContent).toEqual(['First thought', 'Second thought']);
      expect(result.mainContent).toBe('Main here');
    });

    it('handles streaming with multi-turn thinks', () => {
      const raw = '<think> First done</think> Some text<think> Still thinking...';
      const result = parseMessageContent(raw);

      expect(result.thinkContent).toEqual(['First done', 'Still thinking...']);
      expect(result.mainContent).toBe('Some text');
      expect(result.isStreaming).toBe(true);
    });
  });

  describe('escaped newline fix', () => {
    it('fixes escaped newlines from JSON serialization', () => {
      const raw = '<think> Think with\\nnewlines</think> Answer with\\nnewlines too.';
      const result = parseMessageContent(raw);

      expect(result.thinkContent).toEqual(['Think with\nnewlines']);
      expect(result.mainContent).toBe('Answer with\nnewlines too.');
    });

    it('handles content with escaped newlines only', () => {
      const raw = 'Line1\\nLine2\\nLine3';
      const result = parseMessageContent(raw);

      expect(result.thinkContent).toEqual([]);
      expect(result.mainContent).toBe('Line1\nLine2\nLine3');
    });
  });

  describe('code block detection', () => {
    it('detects incomplete code block (odd number of ```)', () => {
      const raw = '<think> Thinking...</think> Here is code:\n```javascript\nconst x = 1';
      const result = parseMessageContent(raw);

      expect(result.isCodeBlockIncomplete).toBe(true);
    });

    it('detects complete code block (even number of ```)', () => {
      const raw = '<think> Thinking...</think> Here is code:\n```javascript\nconst x = 1;\n```';
      const result = parseMessageContent(raw);

      expect(result.isCodeBlockIncomplete).toBe(false);
    });

    it('handles no code blocks', () => {
      const raw = 'No code here.';
      const result = parseMessageContent(raw);

      expect(result.isCodeBlockIncomplete).toBe(false);
    });

    it('handles multiple complete code blocks', () => {
      const raw = '```js\ncode1\n```\nSome text\n```ts\ncode2\n```';
      const result = parseMessageContent(raw);

      expect(result.isCodeBlockIncomplete).toBe(false);
    });

    it('handles multiple incomplete code blocks', () => {
      const raw = '```js\ncode1\n```\nSome text\n```ts\ncode2';
      const result = parseMessageContent(raw);

      expect(result.isCodeBlockIncomplete).toBe(true);
    });
  });
});

describe('completeCodeBlock', () => {
  it('appends closing ``` when code block is incomplete', () => {
    const content = '```javascript\nconst x = 1';
    const result = completeCodeBlock(content);

    expect(result).toBe('```javascript\nconst x = 1\n```');
  });

  it('does not modify complete code blocks', () => {
    const content = '```javascript\nconst x = 1;\n```';
    const result = completeCodeBlock(content);

    expect(result).toBe(content);
  });

  it('does not modify content without code blocks', () => {
    const content = 'Just plain text without code.';
    const result = completeCodeBlock(content);

    expect(result).toBe(content);
  });

  describe('incomplete headings', () => {
    it('appends space to incomplete heading (## without text)', () => {
      const content = 'Some text\n##';
      const result = completeCodeBlock(content);

      expect(result).toBe('Some text\n## ');
    });

    it('appends space to incomplete heading with space (##  without text)', () => {
      const content = 'Some text\n## ';
      const result = completeCodeBlock(content);

      expect(result).toBe('Some text\n##  ');
    });

    it('does not modify complete heading', () => {
      const content = '## Complete Heading';
      const result = completeCodeBlock(content);

      expect(result).toBe(content);
    });

    it('does not modify heading with newline after', () => {
      const content = '## Heading\nSome text';
      const result = completeCodeBlock(content);

      expect(result).toBe(content);
    });

    it('handles multiple heading levels', () => {
      const content = '###';
      const result = completeCodeBlock(content);

      expect(result).toBe('### ');
    });
  });

  describe('incomplete list items', () => {
    it('appends space to incomplete unordered list', () => {
      const content = '-';
      const result = completeCodeBlock(content);

      expect(result).toBe('- ');
    });

    it('appends space to incomplete unordered list with marker', () => {
      const content = '- ';
      const result = completeCodeBlock(content);

      expect(result).toBe('-  ');
    });

    it('appends space to incomplete ordered list', () => {
      const content = '1.';
      const result = completeCodeBlock(content);

      expect(result).toBe('1. ');
    });

    it('appends space to incomplete ordered list with marker', () => {
      const content = '1. ';
      const result = completeCodeBlock(content);

      expect(result).toBe('1.  ');
    });

    it('does not modify complete list item', () => {
      const content = '- Complete item';
      const result = completeCodeBlock(content);

      expect(result).toBe(content);
    });

    it('does not modify complete ordered list item', () => {
      const content = '1. Complete item';
      const result = completeCodeBlock(content);

      expect(result).toBe(content);
    });
  });
});
