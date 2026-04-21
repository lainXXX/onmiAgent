/**
 * Fixes escaped newlines in JSON strings that come from AI responses.
 */
function fixEscapedNewlines(raw: string): string {
  return raw.replace(/\\n/g, '\n');
}

/**
 * Extracts tool name from think content if a tool call is present.
 */
function extractToolNameFromThink(thinkContent: string): string | null {
  const toolCallsMatch = thinkContent.match(/"tool_calls"\s*:\s*\[\s*\{[\s\S]*?"name"\s*:\s*"([^"]+)"/);
  if (toolCallsMatch) {
    return toolCallsMatch[1];
  }
  const nameMatch = thinkContent.match(/"name"\s*:\s*"([^"]+)"/);
  if (nameMatch) {
    return nameMatch[1];
  }
  return null;
}

export interface ParsedContent {
  thinkContent: string[];
  thinkToolNames: (string | null)[];
  mainContent: string;
  isStreaming: boolean;
  isCodeBlockIncomplete: boolean;
}

export function parseMessageContent(raw: string): ParsedContent {
  const thinkStart = '<think>';
  const thinkEnd = '</think>';

  const fixedRaw = fixEscapedNewlines(raw);
  const thinkContent: string[] = [];
  const thinkToolNames: (string | null)[] = [];
  let mainContent = fixedRaw;
  let isStreaming = false;

  interface ThinkSection {
    start: number;
    end: number;
    content: string;
  }
  const sections: ThinkSection[] = [];

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
      thinkToolNames.push(extractToolNameFromThink(content));
      searchStart = thinkEndIdx + thinkEnd.length;
    } else {
      isStreaming = true;
      const streamingContent = fixedRaw.slice(thinkStartIdx + thinkStart.length).trim();
      if (streamingContent) {
        thinkContent.push(streamingContent);
        thinkToolNames.push(extractToolNameFromThink(streamingContent));
      }
      break;
    }

    thinkStartIdx = fixedRaw.indexOf(thinkStart, searchStart);
  }

  if (sections.length > 0 || isStreaming) {
    let cleaned = '';
    let lastEnd = 0;

    const sortedSections = [...sections].sort((a, b) => a.start - b.start);

    for (const section of sortedSections) {
      cleaned += fixedRaw.slice(lastEnd, section.start);
      lastEnd = section.end;
    }

    if (isStreaming) {
      const streamingIdx = fixedRaw.indexOf(thinkStart, lastEnd);
      if (streamingIdx !== -1) {
        cleaned += fixedRaw.slice(lastEnd, streamingIdx);
      }
    } else {
      cleaned += fixedRaw.slice(lastEnd);
    }

    mainContent = cleaned.trim();
  }

  const codeBlockCount = (mainContent.match(/```/g) || []).length;
  const isCodeBlockIncomplete = codeBlockCount % 2 !== 0;

  return {
    thinkContent,
    thinkToolNames,
    mainContent,
    isStreaming,
    isCodeBlockIncomplete,
  };
}

export function completeCodeBlock(content: string): string {
  let result = content;

  const codeBlockCount = (result.match(/```/g) || []).length;
  if (codeBlockCount % 2 !== 0) {
    result = result + '\n```';
  }

  const lines = result.split('\n');
  const lastLine = lines[lines.length - 1];

  if (/^#{1,6}(\s*)$/.test(lastLine) && !lastLine.match(/^#{1,6}\s+\S/)) {
    lines[lines.length - 1] = lastLine + ' ';
    result = lines.join('\n');
  }

  if (/^(\s*)([-*+]|\d+\.)\s*$/.test(lastLine) && !lastLine.match(/^(\s*)([-*+]|\d+\.)\s+\S/)) {
    lines[lines.length - 1] = lastLine + ' ';
    result = lines.join('\n');
  }

  return result;
}
