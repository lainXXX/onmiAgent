const API_BASE = '/api/etl';

export interface EtlProcessReport {
  parentChunkCount: number;
  childChunkCount: number;
  avgTokenPerParent: number;
  avgTokenPerChild: number;
  totalTokenConsumed: number;
  processTimeMs: number;
}

export async function uploadRagFile(
  file: File,
  kbId?: string
): Promise<EtlProcessReport> {
  const formData = new FormData();
  formData.append('file', file);
  if (kbId) {
    formData.append('kbId', kbId);
  }

  const response = await fetch(`${API_BASE}/process/file`, {
    method: 'POST',
    body: formData,
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(error || `HTTP ${response.status}`);
  }

  const text = await response.text();
  // Parse the simple text response
  const report: EtlProcessReport = {
    parentChunkCount: 0,
    childChunkCount: 0,
    avgTokenPerParent: 0,
    avgTokenPerChild: 0,
    totalTokenConsumed: 0,
    processTimeMs: 0,
  };

  const lines = text.split('\n');
  for (const line of lines) {
    const match = line.match(/生成母块数:\s*(\d+)/);
    if (match) report.parentChunkCount = parseInt(match[1], 10);
    const childMatch = line.match(/生成子块数:\s*(\d+)/);
    if (childMatch) report.childChunkCount = parseInt(childMatch[1], 10);
    const parentTokenMatch = line.match(/平均母块Token数:\s*([\d.]+)/);
    if (parentTokenMatch) report.avgTokenPerParent = parseFloat(parentTokenMatch[1]);
    const childTokenMatch = line.match(/平均子块Token数:\s*([\d.]+)/);
    if (childTokenMatch) report.avgTokenPerChild = parseFloat(childTokenMatch[1]);
    const totalTokenMatch = line.match(/总Token消耗:\s*(\d+)/);
    if (totalTokenMatch) report.totalTokenConsumed = parseInt(totalTokenMatch[1], 10);
    const timeMatch = line.match(/处理耗时:\s*(\d+)ms/);
    if (timeMatch) report.processTimeMs = parseInt(timeMatch[1], 10);
  }

  return report;
}
