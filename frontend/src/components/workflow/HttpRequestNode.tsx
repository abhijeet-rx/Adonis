import React, { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { Globe } from 'lucide-react';
import type { CustomNodeData } from './workflowAdapter';

export const HttpRequestNode: React.FC<NodeProps> = memo(({ data, selected }) => {
  const nodeData = data as unknown as CustomNodeData;

  const methodColors: Record<string, string> = {
    GET: 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30',
    POST: 'bg-sky-500/20 text-sky-300 border-sky-500/30',
    PUT: 'bg-indigo-500/20 text-indigo-300 border-indigo-500/30',
    DELETE: 'bg-rose-500/20 text-rose-300 border-rose-500/30',
    PATCH: 'bg-purple-500/20 text-purple-300 border-purple-500/30'
  };

  const method = nodeData.method || 'GET';
  const badgeStyle = methodColors[method] || methodColors.GET;

  return (
    <div
      className={`min-w-[240px] max-w-[300px] rounded-xl bg-slate-900/95 border transition-all duration-150 shadow-lg ${
        selected
          ? 'border-emerald-400 ring-2 ring-emerald-400/30 shadow-emerald-500/20'
          : 'border-slate-700/80 hover:border-slate-600'
      }`}
    >
      {/* Target Connection Handle (Input) */}
      <Handle
        type="target"
        position={Position.Left}
        id="input"
        className="!w-3 !h-3 !bg-emerald-400 !border-2 !border-slate-900 hover:!scale-125 transition-transform"
      />

      {/* Node Header */}
      <div className="flex items-center justify-between px-3.5 py-2.5 bg-slate-800/80 rounded-t-xl border-b border-slate-700/60">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded-lg bg-emerald-500/20 text-emerald-400 flex items-center justify-center border border-emerald-500/30">
            <Globe className="w-3.5 h-3.5" />
          </div>
          <span className="text-xs font-semibold text-white tracking-tight">HTTP Request</span>
        </div>
        <span className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded border ${badgeStyle}`}>
          {method}
        </span>
      </div>

      {/* Node Body */}
      <div className="p-3.5 space-y-2">
        <div className="text-xs font-medium text-slate-100 truncate">
          {nodeData.label || 'HTTP Request'}
        </div>
        <div className="p-1.5 rounded-lg bg-slate-950/80 border border-slate-800 font-mono text-[11px] text-slate-300 truncate" title={nodeData.url || 'https://api.example.com'}>
          {nodeData.url || 'https://api.example.com'}
        </div>
        {nodeData.description && (
          <p className="text-[11px] text-slate-400 line-clamp-1 leading-relaxed">
            {nodeData.description}
          </p>
        )}
      </div>

      {/* Source Connection Handle (Output) */}
      <Handle
        type="source"
        position={Position.Right}
        id="output"
        className="!w-3 !h-3 !bg-emerald-400 !border-2 !border-slate-900 hover:!scale-125 transition-transform"
      />
    </div>
  );
});

HttpRequestNode.displayName = 'HttpRequestNode';
