import React, { useState } from 'react';
import {
  CheckCircle2,
  XCircle,
  Clock,
  ChevronDown,
  ChevronRight,
  X,
  Globe,
  Zap,
  Box,
  Copy,
  Check,
  MinusCircle,
  RefreshCw,
  RotateCcw
} from 'lucide-react';
import type {
  WorkflowExecutionResult,
  ExecutionResponse,
  NodeExecutionResult
} from '../../services/workflowService';

interface ExecutionResultModalProps {
  result: WorkflowExecutionResult | ExecutionResponse;
  onClose: () => void;
}

export const ExecutionResultModal: React.FC<ExecutionResultModalProps> = ({ result, onClose }) => {
  const [expandedNodes, setExpandedNodes] = useState<Record<string, boolean>>({});
  const [activeTab, setActiveTab] = useState<Record<string, 'output' | 'input'>>({});
  const [copiedId, setCopiedId] = useState(false);
  const [copiedPayload, setCopiedPayload] = useState<string | null>(null);

  const toggleExpand = (nodeId: string) => {
    setExpandedNodes((prev) => ({
      ...prev,
      [nodeId]: !prev[nodeId]
    }));
  };

  const setNodeTab = (nodeId: string, tab: 'output' | 'input') => {
    setActiveTab((prev) => ({
      ...prev,
      [nodeId]: tab
    }));
  };

  const executionId =
    ('executionId' in result && result.executionId ? result.executionId : result.id) || '';

  const nodesList: NodeExecutionResult[] =
    ('nodeExecutions' in result && result.nodeExecutions ? result.nodeExecutions : result.nodes) || [];

  const handleCopyId = () => {
    if (executionId) {
      void navigator.clipboard.writeText(executionId);
      setCopiedId(true);
      setTimeout(() => setCopiedId(false), 2000);
    }
  };

  const handleCopyJson = (key: string, data: unknown) => {
    void navigator.clipboard.writeText(JSON.stringify(data, null, 2));
    setCopiedPayload(key);
    setTimeout(() => setCopiedPayload(null), 2000);
  };

  const getNodeIcon = (nodeType: string) => {
    switch (nodeType.toLowerCase()) {
      case 'trigger':
        return <Zap className="w-4 h-4 text-amber-400" />;
      case 'httprequest':
      case 'http-request':
        return <Globe className="w-4 h-4 text-sky-400" />;
      default:
        return <Box className="w-4 h-4 text-purple-400" />;
    }
  };

  const isSuccess = result.status === 'SUCCESS';
  const isRunning = result.status === 'RUNNING';

  const formatDuration = (ms: number | null | undefined): string => {
    if (ms == null) return '-';
    if (ms < 1000) return `${ms} ms`;
    return `${(ms / 1000).toFixed(2)} s`;
  };

  const formatTimestamp = (iso: string | null | undefined): string => {
    if (!iso) return '-';
    try {
      const d = new Date(iso);
      return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    } catch {
      return iso;
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-fadeIn">
      <div className="relative w-full max-w-2xl max-h-[88vh] bg-[#0d1322] border border-slate-800 rounded-xl shadow-2xl flex flex-col overflow-hidden text-slate-100">
        {/* Header */}
        <div className="px-6 py-4 border-b border-slate-800 flex items-center justify-between bg-[#11182d]">
          <div className="flex items-center gap-3">
            {isSuccess ? (
              <div className="p-2 rounded-lg bg-emerald-500/10 border border-emerald-500/30 text-emerald-400">
                <CheckCircle2 className="w-5 h-5" />
              </div>
            ) : isRunning ? (
              <div className="p-2 rounded-lg bg-amber-500/10 border border-amber-500/30 text-amber-400">
                <RefreshCw className="w-5 h-5 animate-spin" />
              </div>
            ) : (
              <div className="p-2 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-400">
                <XCircle className="w-5 h-5" />
              </div>
            )}
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-base font-semibold text-white">Execution {result.status}</h3>
                <span
                  className={`text-xs px-2 py-0.5 rounded font-mono font-medium ${
                    isSuccess
                      ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                      : isRunning
                      ? 'bg-amber-500/20 text-amber-400 border border-amber-500/30'
                      : 'bg-rose-500/20 text-rose-400 border border-rose-500/30'
                  }`}
                >
                  {result.status}
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-0.5">
                Duration: <span className="font-semibold text-slate-200">{formatDuration(result.durationMs)}</span>
                {result.startedAt && (
                  <span className="ml-2 text-slate-500">
                    (Started {formatTimestamp(result.startedAt)})
                  </span>
                )}
              </p>
            </div>
          </div>

          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition"
            title="Close execution view"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Sub-Header Metadata */}
        <div className="px-6 py-2.5 bg-[#090d16] border-b border-slate-800 flex flex-wrap items-center justify-between gap-3 text-xs text-slate-400">
          <div className="flex items-center gap-2">
            <span>Execution ID:</span>
            <code className="bg-slate-900 px-2 py-0.5 rounded text-slate-300 font-mono text-[11px] border border-slate-800">
              {executionId}
            </code>
            <button
              onClick={handleCopyId}
              className="text-slate-400 hover:text-slate-200 p-0.5 transition"
              title="Copy execution ID"
            >
              {copiedId ? (
                <Check className="w-3.5 h-3.5 text-emerald-400" />
              ) : (
                <Copy className="w-3.5 h-3.5" />
              )}
            </button>
          </div>

          <div className="flex items-center gap-4">
            <span className="flex items-center gap-1">
              <Clock className="w-3.5 h-3.5 text-slate-500" />
              {nodesList.length} {nodesList.length === 1 ? 'node' : 'nodes'} in trace
            </span>
          </div>
        </div>

        {/* Error message banner if overall failed */}
        {result.error && (
          <div className="mx-6 mt-4 p-3 rounded-lg bg-rose-950/40 border border-rose-800/60 text-xs text-rose-300">
            <span className="font-semibold text-rose-200 block mb-0.5">Execution Failure:</span>
            {result.error}
          </div>
        )}

        {/* Execution Nodes List */}
        <div className="p-6 overflow-y-auto space-y-3 flex-1">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
            Node-by-Node Execution Trace
          </h4>

          {nodesList.length === 0 ? (
            <p className="text-xs text-slate-500 italic">No node records available.</p>
          ) : (
            nodesList.map((node: NodeExecutionResult, index: number) => {
              const nodeSuccess = node.status === 'SUCCESS';
              const nodeSkipped = node.status === 'SKIPPED';
              const isExpanded = expandedNodes[node.nodeId] ?? (index === nodesList.length - 1);
              const currentTab = activeTab[node.nodeId] || 'output';

              const statusCode =
                node.output && typeof node.output === 'object' && 'statusCode' in node.output
                  ? (node.output as { statusCode: number }).statusCode
                  : null;

              return (
                <div
                  key={node.nodeId}
                  className={`rounded-lg border transition ${
                    nodeSuccess
                      ? 'bg-slate-900/60 border-slate-800 hover:border-slate-700'
                      : nodeSkipped
                      ? 'bg-slate-900/30 border-slate-800/60 opacity-75'
                      : 'bg-rose-950/20 border-rose-900/40 hover:border-rose-800/60'
                  }`}
                >
                  {/* Step Row Header */}
                  <div
                    onClick={() => toggleExpand(node.nodeId)}
                    className="p-3.5 flex items-center justify-between cursor-pointer select-none"
                  >
                    <div className="flex items-center gap-3">
                      <span className="w-5 h-5 rounded-full bg-slate-800 text-[10px] font-mono font-bold text-slate-400 flex items-center justify-center shrink-0">
                        {index + 1}
                      </span>
                      {getNodeIcon(node.nodeType)}
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-semibold text-white">
                            {node.nodeType === 'trigger'
                              ? 'Trigger'
                              : node.nodeType === 'httpRequest' || node.nodeType === 'http-request'
                              ? 'HTTP Request'
                              : 'Generic Step'}
                          </span>
                          <span className="text-[11px] font-mono text-slate-400">
                            ({node.nodeId})
                          </span>
                          {statusCode !== null && (
                            <span
                              className={`text-[10px] font-mono font-bold px-1.5 py-0.2 rounded border ${
                                statusCode >= 200 && statusCode < 400
                                  ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30'
                                  : 'bg-rose-500/10 text-rose-400 border-rose-500/30'
                              }`}
                            >
                              HTTP {statusCode}
                            </span>
                          )}
                          {((node.attempts && node.attempts.length > 1) || (node.retryCount != null && node.retryCount > 0)) && (
                            <span className="text-[10px] font-mono px-1.5 py-0.2 rounded bg-amber-500/10 text-amber-400 border border-amber-500/30 flex items-center gap-1">
                              <RotateCcw className="w-2.5 h-2.5" />
                              {node.attempts && node.attempts.length > 1
                                ? `${node.attempts.length} attempts`
                                : `${node.retryCount} retries`}
                            </span>
                          )}
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      {nodeSuccess ? (
                        <span className="flex items-center gap-1 text-xs text-emerald-400 font-medium">
                          <CheckCircle2 className="w-3.5 h-3.5" />
                          Success
                        </span>
                      ) : nodeSkipped ? (
                        <span className="flex items-center gap-1 text-xs text-slate-400 font-medium">
                          <MinusCircle className="w-3.5 h-3.5 text-slate-500" />
                          Skipped
                        </span>
                      ) : (
                        <span className="flex items-center gap-1 text-xs text-rose-400 font-medium">
                          <XCircle className="w-3.5 h-3.5" />
                          Failed
                        </span>
                      )}

                      <span className="text-[11px] text-slate-500 font-mono">
                        {nodeSkipped ? '-' : `${node.durationMs} ms`}
                      </span>

                      {isExpanded ? (
                        <ChevronDown className="w-4 h-4 text-slate-400" />
                      ) : (
                        <ChevronRight className="w-4 h-4 text-slate-400" />
                      )}
                    </div>
                  </div>

                  {/* Expanded Node Details */}
                  {isExpanded && (
                    <div className="px-4 pb-4 pt-1 border-t border-slate-800/80 text-xs space-y-2.5">
                      {node.error && (
                        <div className="p-2.5 rounded bg-rose-950/50 border border-rose-800/40 text-rose-300 font-mono text-[11px]">
                          <span className="font-semibold block mb-0.5">Error:</span>
                          {node.error}
                        </div>
                      )}

                      {/* Attempts Breakdown */}
                      {node.attempts && node.attempts.length > 1 && (
                        <div className="rounded-lg bg-slate-950/80 border border-slate-800 p-2.5 space-y-2">
                          <div className="flex items-center justify-between text-[11px] font-semibold text-slate-300">
                            <span className="flex items-center gap-1.5">
                              <RotateCcw className="w-3 h-3 text-amber-400" />
                              Attempts Breakdown ({node.attempts.length} total)
                            </span>
                          </div>
                          <div className="space-y-1.5">
                            {node.attempts.map((att) => {
                              const attSuccess = att.status === 'SUCCESS';
                              return (
                                <div
                                  key={att.attemptNumber}
                                  className="flex items-center justify-between px-2.5 py-1.5 rounded bg-slate-900/90 border border-slate-800 text-[11px] font-mono"
                                >
                                  <div className="flex items-center gap-2">
                                    <span className="text-slate-400 font-semibold">
                                      Attempt {att.attemptNumber}
                                    </span>
                                    <span
                                      className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${
                                        attSuccess
                                          ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                                          : 'bg-rose-500/20 text-rose-400 border border-rose-500/30'
                                      }`}
                                    >
                                      {att.status}
                                    </span>
                                    {att.error && (
                                      <span className="text-slate-400 truncate max-w-xs text-[10px]" title={att.error}>
                                        {att.error}
                                      </span>
                                    )}
                                  </div>
                                  <span className="text-slate-500 text-[10px]">
                                    {att.durationMs != null ? `${att.durationMs} ms` : '-'}
                                  </span>
                                </div>
                              );
                            })}
                          </div>
                        </div>
                      )}

                      {nodeSkipped && !node.error && (
                        <p className="text-slate-400 italic text-[11px] py-1">
                          Downstream node execution was skipped due to fail-fast policy after upstream node failure.
                        </p>
                      )}

                      {!nodeSkipped && (
                        <div>
                          {/* Tabs for Input / Output */}
                          <div className="flex items-center justify-between border-b border-slate-800 pb-1.5 mb-2">
                            <div className="flex items-center gap-2">
                              <button
                                type="button"
                                onClick={() => setNodeTab(node.nodeId, 'output')}
                                className={`text-[11px] font-semibold px-2 py-0.5 rounded transition ${
                                  currentTab === 'output'
                                    ? 'bg-slate-800 text-sky-400 border border-sky-500/30'
                                    : 'text-slate-400 hover:text-slate-200'
                                }`}
                              >
                                Output
                              </button>
                              <button
                                type="button"
                                onClick={() => setNodeTab(node.nodeId, 'input')}
                                className={`text-[11px] font-semibold px-2 py-0.5 rounded transition ${
                                  currentTab === 'input'
                                    ? 'bg-slate-800 text-sky-400 border border-sky-500/30'
                                    : 'text-slate-400 hover:text-slate-200'
                                }`}
                              >
                                Input
                              </button>
                            </div>

                            <button
                              type="button"
                              onClick={() => {
                                const payload = currentTab === 'output' ? node.output : node.input;
                                handleCopyJson(`${node.nodeId}-${currentTab}`, payload);
                              }}
                              className="flex items-center gap-1 text-[11px] text-slate-400 hover:text-slate-200 transition"
                              title="Copy JSON payload"
                            >
                              {copiedPayload === `${node.nodeId}-${currentTab}` ? (
                                <>
                                  <Check className="w-3 h-3 text-emerald-400" />
                                  <span className="text-emerald-400">Copied</span>
                                </>
                              ) : (
                                <>
                                  <Copy className="w-3 h-3" />
                                  <span>Copy {currentTab}</span>
                                </>
                              )}
                            </button>
                          </div>

                          {/* Tab Content */}
                          {currentTab === 'output' && (
                            <pre className="p-2.5 rounded bg-slate-950/90 border border-slate-800/80 text-slate-300 font-mono text-[11px] overflow-x-auto max-h-48 whitespace-pre-wrap break-all">
                              {node.output && Object.keys(node.output).length > 0
                                ? JSON.stringify(node.output, null, 2)
                                : '{}'}
                            </pre>
                          )}

                          {currentTab === 'input' && (
                            <pre className="p-2.5 rounded bg-slate-950/90 border border-slate-800/80 text-slate-300 font-mono text-[11px] overflow-x-auto max-h-48 whitespace-pre-wrap break-all">
                              {node.input && Object.keys(node.input).length > 0
                                ? JSON.stringify(node.input, null, 2)
                                : '{}'}
                            </pre>
                          )}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-3 border-t border-slate-800 flex items-center justify-between bg-[#090d16]">
          <div className="text-[11px] text-slate-500 font-mono">
            {result.completedAt && (
              <span>Completed at {new Date(result.completedAt).toLocaleString()}</span>
            )}
          </div>
          <button
            onClick={onClose}
            className="px-4 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium transition"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
