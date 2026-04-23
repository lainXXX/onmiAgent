import { useState, useRef } from 'react';
import { uploadRagFile, type EtlProcessReport } from '../api/rag';

interface RagUploadToolProps {
  onClose?: () => void;
}

export function RagUploadTool({ onClose }: RagUploadToolProps) {
  const [file, setFile] = useState<File | null>(null);
  const [kbId, setKbId] = useState('');
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<EtlProcessReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = e.target.files?.[0];
    if (selected) {
      setFile(selected);
      setResult(null);
      setError(null);
    }
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    const dropped = e.dataTransfer.files?.[0];
    if (dropped) {
      setFile(dropped);
      setResult(null);
      setError(null);
    }
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
  };

  const handleUpload = async () => {
    if (!file) return;

    setUploading(true);
    setError(null);
    setResult(null);

    try {
      const report = await uploadRagFile(file, kbId || undefined);
      setResult(report);
    } catch (err) {
      setError(err instanceof Error ? err.message : '上传失败');
    } finally {
      setUploading(false);
    }
  };

  const handleReset = () => {
    setFile(null);
    setResult(null);
    setError(null);
    setKbId('');
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  return (
    <div className="p-4">
      <div className="flex items-center justify-between mb-4">
        <h3 className="font-semibold text-zinc-200">RAG 文件上传</h3>
        {onClose && (
          <button
            onClick={onClose}
            className="p-1 text-zinc-500 hover:text-zinc-300 transition-colors"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        )}
      </div>

      {/* Drop Zone */}
      <div
        onDrop={handleDrop}
        onDragOver={handleDragOver}
        onClick={() => fileInputRef.current?.click()}
        className={`border-2 border-dashed rounded-lg p-6 text-center cursor-pointer transition-colors mb-4 ${
          file
            ? 'border-blue-500 bg-blue-500/10'
            : 'border-zinc-700 hover:border-blue-500 hover:bg-zinc-900'
        }`}
      >
        <input
          ref={fileInputRef}
          type="file"
          onChange={handleFileChange}
          className="hidden"
          accept=".pdf,.doc,.docx,.txt,.md,.ppt,.pptx"
        />
        {file ? (
          <div className="flex items-center justify-center gap-2">
            <svg className="w-5 h-5 text-blue-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
            <span className="text-sm text-zinc-300 truncate max-w-40">{file.name}</span>
            <span className="text-xs text-zinc-500">({(file.size / 1024 / 1024).toFixed(2)} MB)</span>
          </div>
        ) : (
          <>
            <svg className="w-8 h-8 mx-auto text-zinc-500 mb-2" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
            </svg>
            <p className="text-sm text-zinc-400">
              拖拽文件或点击上传
            </p>
            <p className="text-xs text-zinc-500 mt-1">
              支持 PDF, DOC, DOCX, TXT, MD, PPT, PPTX
            </p>
          </>
        )}
      </div>

      {/* KB ID Input */}
      <div className="mb-4">
        <label className="block text-xs font-medium text-zinc-500 mb-1">
          知识库 ID（可选）
        </label>
        <input
          type="text"
          value={kbId}
          onChange={(e) => setKbId(e.target.value)}
          placeholder="留空使用默认知识库"
          className="w-full px-3 py-2 text-sm border border-zinc-700 rounded-lg bg-zinc-900 text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500"
        />
      </div>

      {/* Action Buttons */}
      <div className="flex gap-2 mb-4">
        <button
          onClick={handleUpload}
          disabled={!file || uploading}
          className="flex-1 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-500 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {uploading ? '处理中...' : '上传并处理'}
        </button>
        <button
          onClick={handleReset}
          disabled={!file && !result && !error}
          className="px-4 py-2 text-sm text-zinc-400 border border-zinc-700 rounded-lg hover:bg-zinc-900 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          重置
        </button>
      </div>

      {/* Error */}
      {error && (
        <div className="p-3 mb-4 bg-red-500/10 border border-red-500/30 rounded-lg">
          <p className="text-sm text-red-400">{error}</p>
        </div>
      )}

      {/* Result */}
      {result && (
        <div className="p-3 bg-green-500/10 border border-green-500/30 rounded-lg">
          <p className="text-sm font-medium text-green-400 mb-2">处理完成</p>
          <div className="grid grid-cols-2 gap-2 text-xs text-green-500">
            <div>母块: {result.parentChunkCount}</div>
            <div>子块: {result.childChunkCount}</div>
            <div>母块Token: {result.avgTokenPerParent.toFixed(1)}</div>
            <div>子块Token: {result.avgTokenPerChild.toFixed(1)}</div>
            <div>总Token: {result.totalTokenConsumed}</div>
            <div>耗时: {result.processTimeMs}ms</div>
          </div>
        </div>
      )}
    </div>
  );
}
