import React, { useState, useEffect, useCallback } from 'react';
import {
  History,
  RefreshCw,
  CheckCircle2,
  XCircle,
  Clock,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  Copy,
  Check,
  AlertCircle
} from 'lucide-react';
import {
  workflowApi,
  type ExecutionSummaryResponse,
  type ExecutionResponse
} from '../../services/workflowService';

interface ExecutionHistoryPanelProps {
  workflowId: string;
  token: string;
  onSelectExecution: (execution: ExecutionResponse) => void;
  isOpen: boolean;
  onClose: () => void;
  refreshTrigger?: number;
}

export const ExecutionHistoryPanel: React.FC<ExecutionHistoryPanelProps> = ({
  workflowId,
  token,
  onSelectExecution,
  isOpen,
  onClose,
  refreshTrigger = 0
}) => {
  const [history, setHistory] = useState<ExecutionSummaryResponse[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [isLoading, setIsLoading] = useState(false);
  const [loadingExecutionId, setLoadingExecutionId] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const pageSize = 10;

  const loadHistory = useCallback(
    async (targetPage = 0) => {
      setIsLoading(true);
      setErrorMessage(null);
      try {
        const data = await workflowApi.getWorkflowExecutions(workflowId, token, targetPage, pageSize);
        setHistory(data.content);
        setPage(data.page);
        setTotalPages(data.totalPages > 0 ? data.totalPages : 1);
        setTotalElements(data.totalElements);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Failed to load execution history';
        setErrorMessage(msg);
      } finally {
        setIsLoading(false);
      }
    },
    [workflowId, token]
  );

  useEffect(() => {
    if (isOpen) {
      void loadHistory(0);
    }
  }, [isOpen, refreshTrigger, loadHistory]);

  const handleOpenExecution = async (execId: string) => {
    setLoadingExecutionId(execId);
    try {
      const fullDetail = await workflowApi.getExecution(execId, token);
      onSelectExecution(fullDetail);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load execution details';
      setErrorMessage(msg);
    } finally {
      setLoadingExecutionId(null);
    }
  };

  const handleCopyId = (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    void navigator.clipboard.writeText(id);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 1500);
  };

  const formatDuration = (ms: number | null | undefined): string => {
    if (ms == null) return '-';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(2)}s`;
  };

  const formatTimestamp = (iso: string | null | undefined): string => {
    if (!iso) return '-';
    try {
      const d = new Date(iso);
      return d.toLocaleString(undefined, {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      });
    } catch {
      return iso;
    }
  };

  if (!isOpen) {
    return null;
  }

  return (
    <div className="w-80 sm:w-96 bg-[#0c1220] border-l border-slate-800 flex flex-col h-full z-20 shrink-0 shadow-2xl animate-fadeIn text-slate-200">
      {/* Panel Header */}
      <div className="px-4 py-3 border-b border-slate-800 flex items-center justify-between bg-[#101728]">
        <div className="flex items-center gap-2">
          <History className="w-4 h-4 text-sky-400" />
          <h3 className="text-sm font-semibold text-white">Execution History</h3>
          <span className="text-[11px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-400 font-mono">
            {totalElements}
          </span>
        </div>
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => void loadHistory(page)}
            disabled={isLoading}
            className="p-1 rounded text-slate-400 hover:text-white hover:bg-slate-800 transition disabled:opacity-50"
            title="Refresh history"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} />
          </button>
          <button
            onClick={onClose}
            className="p-1 rounded text-slate-400 hover:text-white hover:bg-slate-800 transition"
            title="Close history panel"
          >
            &times;
          </button>
        </div>
      </div>

      {/* Error Alert */}
      {errorMessage && (
        <div className="p-3 bg-rose-950/60 border-b border-rose-900/60 text-xs text-rose-300 flex items-center gap-2">
          <AlertCircle className="w-4 h-4 text-rose-400 shrink-0" />
          <span>{errorMessage}</span>
        </div>
      )}

      {/* History Items List */}
      <div className="flex-1 overflow-y-auto p-3 space-y-2.5">
        {isLoading && history.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 gap-2 text-slate-500">
            <RefreshCw className="w-5 h-5 animate-spin text-sky-400" />
            <span className="text-xs">Loading execution history...</span>
          </div>
        ) : history.length === 0 ? (
          <div className="py-12 px-4 text-center">
            <div className="w-10 h-10 rounded-full bg-slate-800/80 text-slate-500 flex items-center justify-center mx-auto mb-2">
              <Clock className="w-5 h-5" />
            </div>
            <p className="text-xs font-medium text-slate-400">No executions recorded</p>
            <p className="text-[11px] text-slate-500 mt-1">
              Click &quot;Run Workflow&quot; to execute and create persistent execution logs.
            </p>
          </div>
        ) : (
          history.map((item) => {
            const isSuccess = item.status === 'SUCCESS';
            const isRunning = item.status === 'RUNNING';
            const execId = item.id || item.executionId || '';
            const isOpening = loadingExecutionId === execId;

            return (
              <div
                key={execId}
                onClick={() => void handleOpenExecution(execId)}
                className={`group p-3 rounded-lg border cursor-pointer transition select-none ${
                  isSuccess
                    ? 'bg-slate-900/50 hover:bg-slate-900/90 border-slate-800 hover:border-emerald-500/40'
                    : isRunning
                    ? 'bg-amber-950/20 hover:bg-amber-950/40 border-amber-900/40'
                    : 'bg-rose-950/20 hover:bg-rose-950/40 border-rose-900/40 hover:border-rose-700/60'
                }`}
              >
                <div className="flex items-center justify-between mb-1.5">
                  <div className="flex items-center gap-1.5">
                    {isSuccess ? (
                      <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-emerald-400 bg-emerald-500/10 px-1.5 py-0.5 rounded border border-emerald-500/20">
                        <CheckCircle2 className="w-3 h-3" />
                        SUCCESS
                      </span>
                    ) : isRunning ? (
                      <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-amber-400 bg-amber-500/10 px-1.5 py-0.5 rounded border border-amber-500/20">
                        <RefreshCw className="w-3 h-3 animate-spin" />
                        RUNNING
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-rose-400 bg-rose-500/10 px-1.5 py-0.5 rounded border border-rose-500/20">
                        <XCircle className="w-3 h-3" />
                        FAILED
                      </span>
                    )}

                    <span className="text-[11px] font-mono text-slate-400">
                      {formatDuration(item.durationMs)}
                    </span>
                  </div>

                  <div className="flex items-center gap-1 opacity-80 group-hover:opacity-100 transition">
                    <button
                      onClick={(e) => handleCopyId(e, execId)}
                      className="p-1 rounded text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition"
                      title="Copy execution ID"
                    >
                      {copiedId === execId ? (
                        <Check className="w-3 h-3 text-emerald-400" />
                      ) : (
                        <Copy className="w-3 h-3" />
                      )}
                    </button>
                    {isOpening ? (
                      <RefreshCw className="w-3 h-3 animate-spin text-sky-400" />
                    ) : (
                      <ExternalLink className="w-3 h-3 text-slate-500 group-hover:text-sky-400 transition" />
                    )}
                  </div>
                </div>

                <div className="flex items-center justify-between text-[11px] text-slate-400 font-mono">
                  <span className="truncate max-w-[180px] text-slate-500">
                    #{execId.slice(0, 10)}...
                  </span>
                  <span>{formatTimestamp(item.startedAt)}</span>
                </div>

                {item.error && (
                  <p className="mt-1.5 text-[11px] text-rose-400 truncate bg-rose-950/40 px-2 py-0.5 rounded border border-rose-900/40">
                    {item.error}
                  </p>
                )}
              </div>
            );
          })
        )}
      </div>

      {/* Pagination Footer */}
      {totalPages > 1 && (
        <div className="px-4 py-2.5 border-t border-slate-800 flex items-center justify-between bg-[#101728] text-xs text-slate-400">
          <span>
            Page <span className="text-white font-medium">{page + 1}</span> of{' '}
            <span className="text-white font-medium">{totalPages}</span>
          </span>
          <div className="flex items-center gap-1">
            <button
              onClick={() => void loadHistory(page - 1)}
              disabled={page <= 0 || isLoading}
              className="p-1 rounded hover:bg-slate-800 disabled:opacity-30 disabled:hover:bg-transparent text-slate-300"
              title="Previous page"
            >
              <ChevronLeft className="w-4 h-4" />
            </button>
            <button
              onClick={() => void loadHistory(page + 1)}
              disabled={page >= totalPages - 1 || isLoading}
              className="p-1 rounded hover:bg-slate-800 disabled:opacity-30 disabled:hover:bg-transparent text-slate-300"
              title="Next page"
            >
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
