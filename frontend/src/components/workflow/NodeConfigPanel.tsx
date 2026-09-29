import React, { useState } from 'react';
import type { Node } from '@xyflow/react';
import { X, Trash2, Check, Sliders, Globe, Zap, Box, RotateCcw } from 'lucide-react';
import { NODE_TYPES, type CustomNodeData } from './workflowAdapter';

interface NodeConfigPanelProps {
  selectedNode: Node<CustomNodeData>;
  onUpdateNodeData: (nodeId: string, newData: CustomNodeData) => void;
  onDeleteNode: (nodeId: string) => void;
  onClose: () => void;
}

export const NodeConfigPanel: React.FC<NodeConfigPanelProps> = ({
  selectedNode,
  onUpdateNodeData,
  onDeleteNode,
  onClose
}) => {
  const nodeData = selectedNode.data || {};
  const [label, setLabel] = useState<string>(
    typeof nodeData.label === 'string' ? nodeData.label : ''
  );
  const [description, setDescription] = useState<string>(
    typeof nodeData.description === 'string' ? nodeData.description : ''
  );
  const [method, setMethod] = useState<'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'>(
    (nodeData.method as 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH') || 'GET'
  );
  const [url, setUrl] = useState<string>(
    typeof nodeData.url === 'string' ? nodeData.url : 'https://api.example.com'
  );
  const [triggerType, setTriggerType] = useState<string>(
    typeof nodeData.triggerType === 'string' ? nodeData.triggerType : 'Manual'
  );

  const rawRetry = (nodeData.retry as Record<string, unknown>) || {};
  const [retryEnabled, setRetryEnabled] = useState<boolean>(
    Boolean(rawRetry.enabled ?? nodeData.retryEnabled ?? false)
  );
  const [maxRetries, setMaxRetries] = useState<number>(
    Number(rawRetry.maxRetries ?? nodeData.maxRetries ?? 3)
  );
  const [initialBackoffMs, setInitialBackoffMs] = useState<number>(
    Number(rawRetry.initialBackoffMs ?? nodeData.retryBackoff ?? 1000)
  );
  const [backoffMultiplier, setBackoffMultiplier] = useState<number>(
    Number(rawRetry.backoffMultiplier ?? nodeData.retryBackoffMultiplier ?? 2.0)
  );

  const [isSavedRecently, setIsSavedRecently] = useState(false);

  const handleApply = (e: React.FormEvent) => {
    e.preventDefault();
    const updatedData: CustomNodeData = {
      ...selectedNode.data,
      label: label.trim() || 'Untitled Node',
      description: description.trim(),
      method,
      url: url.trim(),
      triggerType,
      retry: {
        enabled: retryEnabled,
        maxRetries: Math.max(0, Math.min(10, Number(maxRetries) || 0)),
        initialBackoffMs: Math.max(0, Math.min(60000, Number(initialBackoffMs) || 1000)),
        backoffMultiplier: Math.max(0.1, Number(backoffMultiplier) || 2.0),
        maxBackoffMs: 30000
      }
    };
    onUpdateNodeData(selectedNode.id, updatedData);
    setIsSavedRecently(true);
    setTimeout(() => setIsSavedRecently(false), 2000);
  };

  const getNodeIcon = () => {
    switch (selectedNode.type) {
      case NODE_TYPES.TRIGGER:
        return <Zap className="w-4 h-4 text-amber-400" />;
      case NODE_TYPES.HTTP_REQUEST:
        return <Globe className="w-4 h-4 text-emerald-400" />;
      default:
        return <Box className="w-4 h-4 text-indigo-400" />;
    }
  };

  const getNodeTypeName = () => {
    switch (selectedNode.type) {
      case NODE_TYPES.TRIGGER:
        return 'Trigger Configuration';
      case NODE_TYPES.HTTP_REQUEST:
        return 'HTTP Request Configuration';
      default:
        return 'Custom Node Configuration';
    }
  };

  return (
    <aside className="w-80 bg-[#0c1220] border-l border-slate-800 flex flex-col h-full shrink-0 shadow-2xl z-20">
      {/* Panel Header */}
      <div className="p-4 border-b border-slate-800/80 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-lg bg-slate-800 flex items-center justify-center border border-slate-700/60">
            {getNodeIcon()}
          </div>
          <div>
            <h3 className="text-xs font-bold text-white tracking-tight">{getNodeTypeName()}</h3>
            <span className="text-[10px] font-mono text-slate-500">ID: {selectedNode.id}</span>
          </div>
        </div>
        <button
          onClick={onClose}
          className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition"
          title="Close configuration"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      {/* Form Content */}
      <form onSubmit={handleApply} className="p-4 space-y-4 flex-1 overflow-y-auto">
        {/* Node Label */}
        <div className="space-y-1.5">
          <label className="text-xs font-medium text-slate-300 block">Label</label>
          <input
            type="text"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="Node display name"
            className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-xs text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 transition"
            maxLength={100}
          />
        </div>

        {/* HTTP Request specific fields */}
        {selectedNode.type === NODE_TYPES.HTTP_REQUEST && (
          <>
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-slate-300 block">HTTP Method</label>
              <select
                value={method}
                onChange={(e) => setMethod(e.target.value as 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH')}
                className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500 transition"
              >
                <option value="GET">GET</option>
                <option value="POST">POST</option>
                <option value="PUT">PUT</option>
                <option value="DELETE">DELETE</option>
                <option value="PATCH">PATCH</option>
              </select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-slate-300 block">Endpoint URL</label>
              <input
                type="text"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder="https://api.example.com/v1/resource"
                className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-xs text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 font-mono transition"
                maxLength={500}
              />
            </div>
          </>
        )}

        {/* Trigger specific fields */}
        {selectedNode.type === NODE_TYPES.TRIGGER && (
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-slate-300 block">Trigger Mode</label>
            <select
              value={triggerType}
              onChange={(e) => setTriggerType(e.target.value)}
              className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500 transition"
            >
              <option value="Manual">Manual Trigger</option>
              <option value="Scheduled">Scheduled (Config only)</option>
              <option value="Webhook">Webhook (Config only)</option>
            </select>
          </div>
        )}

        {/* Retry Configuration (for executable nodes) */}
        {selectedNode.type !== NODE_TYPES.TRIGGER && (
          <div className="p-3 rounded-lg bg-slate-900/80 border border-slate-800 space-y-3">
            <div className="flex items-center justify-between">
              <label className="text-xs font-semibold text-slate-200 flex items-center gap-1.5 cursor-pointer">
                <RotateCcw className="w-3.5 h-3.5 text-sky-400" />
                Retry Policy
              </label>
              <label className="relative inline-flex items-center cursor-pointer">
                <input
                  type="checkbox"
                  checked={retryEnabled}
                  onChange={(e) => setRetryEnabled(e.target.checked)}
                  className="sr-only peer"
                />
                <div className="w-8 h-4 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-3 after:w-3 after:transition-all peer-checked:bg-emerald-500"></div>
                <span className="ml-2 text-[11px] text-slate-400 font-medium">
                  {retryEnabled ? 'Enabled' : 'Disabled'}
                </span>
              </label>
            </div>

            {retryEnabled && (
              <div className="space-y-2 pt-1 border-t border-slate-800/80 text-xs">
                <div className="space-y-1">
                  <div className="flex justify-between items-center text-slate-400 text-[11px]">
                    <span>Max Retries</span>
                    <span className="font-mono text-slate-300">{maxRetries} after initial</span>
                  </div>
                  <input
                    type="number"
                    min={0}
                    max={10}
                    value={maxRetries}
                    onChange={(e) => setMaxRetries(Math.max(0, Math.min(10, parseInt(e.target.value) || 0)))}
                    className="w-full px-2.5 py-1.5 bg-slate-950 border border-slate-700 rounded text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
                  />
                </div>

                <div className="space-y-1">
                  <div className="flex justify-between items-center text-slate-400 text-[11px]">
                    <span>Initial Backoff</span>
                    <span className="font-mono text-slate-300">{initialBackoffMs} ms</span>
                  </div>
                  <input
                    type="number"
                    min={0}
                    step={100}
                    max={60000}
                    value={initialBackoffMs}
                    onChange={(e) => setInitialBackoffMs(Math.max(0, parseInt(e.target.value) || 0))}
                    className="w-full px-2.5 py-1.5 bg-slate-950 border border-slate-700 rounded text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
                  />
                </div>

                <div className="space-y-1">
                  <div className="flex justify-between items-center text-slate-400 text-[11px]">
                    <span>Backoff Multiplier</span>
                    <span className="font-mono text-slate-300">{backoffMultiplier}x</span>
                  </div>
                  <input
                    type="number"
                    min={1}
                    step={0.5}
                    max={10}
                    value={backoffMultiplier}
                    onChange={(e) => setBackoffMultiplier(Math.max(1, parseFloat(e.target.value) || 2.0))}
                    className="w-full px-2.5 py-1.5 bg-slate-950 border border-slate-700 rounded text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
                  />
                </div>
              </div>
            )}
          </div>
        )}

        {/* Description / Notes */}
        <div className="space-y-1.5">
          <label className="text-xs font-medium text-slate-300 block">Description / Notes</label>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            placeholder="Describe the purpose or configuration of this node..."
            className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-xs text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 transition resize-none"
            maxLength={300}
          />
        </div>

        {/* Position coordinates (read-only for clarity) */}
        <div className="p-3 rounded-lg bg-slate-950/60 border border-slate-800 text-[11px] font-mono text-slate-400 space-y-1">
          <div className="text-slate-500 font-sans text-[10px] uppercase tracking-wider">Canvas Coordinates</div>
          <div className="flex justify-between">
            <span>X: {Math.round(selectedNode.position.x)}px</span>
            <span>Y: {Math.round(selectedNode.position.y)}px</span>
          </div>
        </div>

        {/* Action Buttons */}
        <div className="pt-2 space-y-2">
          <button
            type="submit"
            className="w-full py-2 px-3 rounded-lg bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-semibold text-xs flex items-center justify-center gap-1.5 transition shadow-sm"
          >
            {isSavedRecently ? (
              <>
                <Check className="w-3.5 h-3.5" />
                Updated!
              </>
            ) : (
              <>
                <Sliders className="w-3.5 h-3.5" />
                Apply Changes
              </>
            )}
          </button>

          <button
            type="button"
            onClick={() => onDeleteNode(selectedNode.id)}
            className="w-full py-2 px-3 rounded-lg bg-slate-800 hover:bg-rose-950/40 text-slate-400 hover:text-rose-300 border border-slate-700 hover:border-rose-800/60 text-xs flex items-center justify-center gap-1.5 transition"
          >
            <Trash2 className="w-3.5 h-3.5" />
            Delete Node
          </button>
        </div>
      </form>
    </aside>
  );
};
