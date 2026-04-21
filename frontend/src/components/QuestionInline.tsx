import { useState } from 'react';
import type { Question } from '../types';
import { skipQuestion as skipQuestionApi, submitQuestionAnswer as submitAnswerApi } from '../api/chat';

interface QuestionInlineProps {
  questionId: string;
  questions: Question[];
  onAnswered: (answerText: string) => void;
}

export function QuestionInline({ questionId, questions, onAnswered }: QuestionInlineProps) {
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [otherTexts, setOtherTexts] = useState<Record<number, string>>({});

  const handleOptionClick = (questionIdx: number, value: string, question: Question) => {
    if (question.multiSelect) {
      setAnswers((prev) => ({
        ...prev,
        [questionIdx]: prev[questionIdx] === value ? '' : value,
      }));
    } else {
      setAnswers((prev) => ({ ...prev, [questionIdx]: value }));
    }
  };

  const handleOtherClick = (questionIdx: number, question: Question) => {
    if (question.multiSelect) {
      setAnswers((prev) => ({
        ...prev,
        [questionIdx]: prev[questionIdx] === '__other__' ? '' : '__other__',
      }));
    } else {
      setAnswers((prev) => ({ ...prev, [questionIdx]: '__other__' }));
    }
  };

  const handleSubmit = async () => {
    // 构建答案 map：问题文本 -> 选项 label
    const answersMap: Record<string, string> = {};
    questions.forEach((q, idx) => {
      const answer = answers[idx];
      answersMap[q.question] = answer === '__other__'
        ? otherTexts[idx] || '(未填写)'
        : answer || '(未选择)';
    });

    // 调用 API 提交答案
    try {
      await submitAnswerApi(questionId, answersMap);
    } catch (e) {
      console.error('Failed to submit answer:', e);
    }

    // 格式化答案为文本，用于发送给 AI
    const lines: string[] = ['[用户回答]'];
    questions.forEach((q, idx) => {
      const answer = answers[idx];
      const answerText = answer === '__other__'
        ? otherTexts[idx] || '(未填写)'
        : answer || '(未选择)';
      lines.push(`${q.question}=${answerText}`);
    });

    const answerText = lines.join('\n');
    onAnswered(answerText);
  };

  const handleSkip = async () => {
    try {
      await skipQuestionApi(questionId);
      onAnswered('[用户跳过了问题]');
    } catch (e) {
      console.error('Failed to skip:', e);
      onAnswered('[用户跳过了问题]');
    }
  };

  const canSubmit = questions.every((_, idx) => {
    const answer = answers[idx];
    if (!answer) return false;
    if (answer === '__other__' && !otherTexts[idx]?.trim()) return false;
    return true;
  });

  return (
    <div className="p-4 border-t border-zinc-800 bg-zinc-900/50">
      <div className="max-w-2xl mx-auto">
        {questions.map((question, qIdx) => (
          <div key={qIdx} className="mb-6 last:mb-0">
            <div className="flex items-center gap-2 mb-2">
              <span className="px-2 py-0.5 bg-zinc-800 text-blue-400 text-xs font-semibold rounded">
                {question.header}
              </span>
            </div>
            <p className="text-zinc-200 font-medium mb-3">
              {question.question}
            </p>
            <div className="flex flex-wrap gap-2">
              {question.options.map((option, oIdx) => {
                const value = option.label;
                const isSelected = answers[qIdx] === value;
                return (
                  <button
                    key={oIdx}
                    onClick={() => handleOptionClick(qIdx, value, question)}
                    className={`px-4 py-2 rounded-full text-sm font-medium transition-colors ${
                      isSelected
                        ? 'bg-blue-600 text-white'
                        : 'bg-zinc-800 text-zinc-300 hover:bg-zinc-700'
                    }`}
                    title={option.description}
                  >
                    {value}
                  </button>
                );
              })}
              <button
                onClick={() => handleOtherClick(qIdx, question)}
                className={`px-4 py-2 rounded-full text-sm font-medium border-2 border-dashed transition-colors ${
                  answers[qIdx] === '__other__'
                    ? 'bg-blue-600 text-white border-blue-600'
                    : 'bg-zinc-800 text-zinc-300 border-zinc-600 hover:border-blue-500'
                }`}
              >
                其他
              </button>
            </div>

            {/* Other text input */}
            {answers[qIdx] === '__other__' && (
              <div className="mt-3">
                <input
                  type="text"
                  placeholder="请输入你的回答..."
                  value={otherTexts[qIdx] || ''}
                  onChange={(e) =>
                    setOtherTexts((prev) => ({ ...prev, [qIdx]: e.target.value }))
                  }
                  className="w-full max-w-md px-4 py-2 bg-zinc-900 border border-zinc-700 rounded-lg text-zinc-200 placeholder-zinc-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  autoFocus
                />
              </div>
            )}
          </div>
        ))}

        {/* Actions */}
        <div className="flex items-center gap-3 pt-4 border-t border-zinc-800">
          <button
            onClick={handleSkip}
            className="px-4 py-2 text-sm text-zinc-500 hover:text-zinc-300 transition-colors"
          >
            跳过
          </button>
          <button
            onClick={handleSubmit}
            disabled={!canSubmit}
            className="px-6 py-2 bg-blue-600 text-white rounded-lg font-medium text-sm disabled:opacity-40 disabled:cursor-not-allowed hover:bg-blue-500 transition-colors"
          >
            提交回答
          </button>
        </div>
      </div>
    </div>
  );
}
