export interface ParsedContent {
  thinkContent: string[];
  mainContent: string;
  isStreaming: boolean;
  isCodeBlockIncomplete: boolean;
}

/**
 * Fixes escaped newlines from JSON serialization
 */
function fixEscapedNewlines(content: string): string {
  return content.replace(/\\n/g, '\n').replace(/\\r/g, '');
}

/**
 * Parses raw message content to extract think content and main content.
 * Handles streaming state where think tags may not be closed yet.
 * Supports multi-turn think (multiple <think>...</think> sections).
 */
export function parseMessageContent(raw: string): ParsedContent {
  const thinkStart = '<think>';
  const thinkEnd = '</think>';

  // First fix escaped newlines from JSON
  const fixedRaw = fixEscapedNewlines(raw);
  const thinkContent: string[] = [];
  let mainContent = fixedRaw;
  let isStreaming = false;

  // Track positions of all think sections
  interface ThinkSection {
    start: number;
    end: number;
    content: string;
  }
  const sections: ThinkSection[] = [];

  // Find all complete think sections
  let searchStart = 0;
  let thinkStartIdx = fixedRaw.indexOf(thinkStart, searchStart);

  while (thinkStartIdx !== -1) {
    const thinkEndIdx = fixedRaw.indexOf(thinkEnd, thinkStartIdx);

    if (thinkEndIdx !== -1) {
      const content = fixedRaw.slice(
        thinkStartIdx + thinkStart.length,
        thinkEndIdx
      ).trim();
      sections.push({ start: thinkStartIdx, end: thinkEndIdx + thinkEnd.length, content });
      thinkContent.push(content);
      searchStart = thinkEndIdx + thinkEnd.length;
    } else {
      // Streaming case
      isStreaming = true;
      const streamingContent = fixedRaw.slice(thinkStartIdx + thinkStart.length).trim();
      if (streamingContent) {
        thinkContent.push(streamingContent);
      }
      break;
    }

    thinkStartIdx = fixedRaw.indexOf(thinkStart, searchStart);
  }

  // Remove all think sections from main content by position
  if (sections.length > 0 || isStreaming) {
    // Build cleaned content by removing all think sections
    let cleaned = '';
    let lastEnd = 0;

    // Sort sections by start position
    const sortedSections = [...sections].sort((a, b) => a.start - b.start);

    for (const section of sortedSections) {
      // Add content before this think section
      cleaned += fixedRaw.slice(lastEnd, section.start);
      lastEnd = section.end;
    }

    // For streaming case, find where the streaming think starts
    if (isStreaming) {
      const streamingIdx = fixedRaw.indexOf(thinkStart, lastEnd);
      if (streamingIdx !== -1) {
        cleaned += fixedRaw.slice(lastEnd, streamingIdx);
      }
    } else {
      // Add content after last think section
      cleaned += fixedRaw.slice(lastEnd);
    }

    mainContent = cleaned.trim();
  }

  // Check if code block is incomplete (odd number of ```)
  const codeBlockCount = (mainContent.match(/```/g) || []).length;
  const isCodeBlockIncomplete = codeBlockCount % 2 !== 0;

  return {
    thinkContent,
    mainContent,
    isStreaming,
    isCodeBlockIncomplete,
  };
}

/**
 * Completes incomplete Markdown structures for streaming display.
 * - Code blocks: appends closing ``` if odd number
 * - Headings: appends trailing space if heading marker is incomplete
 * - List items: appends trailing space if list marker is incomplete
 */
export function completeCodeBlock(content: string): string {
  let result = content;

  // Complete incomplete code blocks
  const codeBlockCount = (result.match(/```/g) || []).length;
  if (codeBlockCount % 2 !== 0) {
    result = result + '\n```';
  }

  const lines = result.split('\n');
  const lastLine = lines[lines.length - 1];

  // Complete incomplete ATX headings
  // A valid heading needs: "## text" or "##\n", but NOT just "##" at end of line
  // Match: starts with 1-6 #, followed by only optional whitespace at end of content
  if (/^#{1,6}(\s*)$/.test(lastLine) && !lastLine.match(/^#{1,6}\s+\S/)) {
    lines[lines.length - 1] = lastLine + ' ';
    result = lines.join('\n');
  }

  // Complete incomplete list items
  // Match: "- " or "1. " or "* " etc. without any text after
  if (/^(\s*)([-*+]|\d+\.)\s*$/.test(lastLine) && !lastLine.match(/^(\s*)([-*+]|\d+\.)\s+\S/)) {
    lines[lines.length - 1] = lastLine + ' ';
    result = lines.join('\n');
  }

  return result;
}
