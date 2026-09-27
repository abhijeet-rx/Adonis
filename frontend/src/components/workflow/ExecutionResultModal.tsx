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
  Check
} from 'lucide-react';
import type { WorkflowExecutionResult, NodeExecutionResult } from '../../services/workflowService';

interface ExecutionResultModalProps {
  result: WorkflowExecutionResult;
  onClose: () => void;
}

export const ExecutionResultModal: React.FC<ExecutionResultModalProps> = ({ result, onClose }) => {
  const [expandedNodes, setExpandedNodes] = useState<Record<string, boolean>>({});
  const [copiedId, setCopiedId] = useState(false);

  const toggleExpand = (nodeId: string) => {
    setExpandedNodes((prev) => ({
      ...prev,
      [nodeId]: !prev[nodeId]
    }));
  };

  const handleCopyId = () => {
    if (result.executionId) {
      void navigator.clipboard.writeText(result.executionId);
      setCopiedId(true);
      setTimeout(() => setCopiedId(false), 2000);
    }
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

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-fadeIn">
      <div className="relative w-full max-w-2xl max-h-[85vh] bg-[#0d1322] border border-slate-800 rounded-xl shadow-2xl flex flex-col overflow-hidden text-slate-100">
        
        {/* Header */}
        <div className="px-6 py-4 border-b border-slate-800 flex items-center justify-between bg-[#11182d]">
          <div className="flex items-center gap-3">
            {isSuccess ? (
              <div className="p-2 rounded-lg bg-emerald-500/10 border border-emerald-500/30 text-emerald-400">
                <CheckCircle2 className="w-5 h-5" />
              </div>
            ) : (
              <div className="p-2 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-400">
                <XCircle className="w-5 h-5" />
              </div>
            )}
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-base font-semibold text-white">
                  Execution {result.status}
                </h3>
                <span
                  className={`text-xs px-2 py-0.5 rounded font-mono font-medium ${
                    isSuccess
                      ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                      : 'bg-rose-500/20 text-rose-400 border border-rose-500/30'
                  }`}
                >
                  {result.status}
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-0.5">
                Completed in <span className="font-semibold text-slate-200">{result.durationMs} ms</span>
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
              {result.executionId}
            </code>
            <button
              onClick={handleCopyId}
              className="text-slate-400 hover:text-slate-200 p-0.5 transition"
              title="Copy execution ID"
            >
              {copiedId ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            </button>
          </div>

          <div className="flex items-center gap-4">
            <span className="flex items-center gap-1">
              <Clock className="w-3.5 h-3.5 text-slate-500" />
              {result.nodes.length} {result.nodes.length === 1 ? 'node' : 'nodes'} executed
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
        <div className="p-6 overflow-y-auto space-y-3">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
            Execution Steps
          </h4>

          {result.nodes.length === 0 ? (
            <p className="text-xs text-slate-500 italic">No nodes were executed.</p>
          ) : (
            result.nodes.map((node: NodeExecutionResult, index: number) => {
              const nodeSuccess = node.status === 'SUCCESS';
              const isExpanded = expandedNodes[node.nodeId] ?? (index === result.nodes.length - 1);
              const statusCode = node.output && typeof node.output === 'object' && 'statusCode' in node.output
                ? (node.output as { statusCode: number }).statusCode
                : null;

              return (
                <div
                  key={node.nodeId}
                  className={`rounded-lg border transition ${
                    nodeSuccess
                      ? 'bg-slate-900/60 border-slate-800 hover:border-slate-700'
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
                              : node.nodeType === 'httpRequest'
                              ? 'HTTP Request'
                              : 'Generic Node'}
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
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      {nodeSuccess ? (
                        <span className="flex items-center gap-1 text-xs text-emerald-400 font-medium">
                          <CheckCircle2 className="w-3.5 h-3.5" />
                          Success
                        </span>
                      ) : (
                        <span className="flex items-center gap-1 text-xs text-rose-400 font-medium">
                          <XCircle className="w-3.5 h-3.5" />
                          Failed
                        </span>
                      )}

                      <span className="text-[11px] text-slate-500 font-mono">
                        {node.durationMs} ms
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
                    <div className="px-4 pb-4 pt-1 border-t border-slate-800/80 text-xs space-y-2">
                      {node.error && (
                        <div className="p-2.5 rounded bg-rose-950/50 border border-rose-800/40 text-rose-300 font-mono text-[11px]">
                          <span className="font-semibold block mb-0.5">Error:</span>
                          {node.error}
                        </div>
                      )}

                      {node.output && (
                        <div>
                          <div className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 mb-1">
                            Node Output:
                          </div>
                          <pre className="p-2.5 rounded bg-slate-950/80 border border-slate-800/80 text-slate-300 font-mono text-[11px] overflow-x-auto max-h-48">
                            {JSON.stringify(node.output, null, 2)}
                          </pre>
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
        <div className="px-6 py-3 border-t border-slate-800 flex items-center justify-end bg-[#090d16]">
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
