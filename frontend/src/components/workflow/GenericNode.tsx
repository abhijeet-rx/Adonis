import React, { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { Box } from 'lucide-react';
import type { CustomNodeData } from './workflowAdapter';

export const GenericNode: React.FC<NodeProps> = memo(({ data, selected }) => {
  const nodeData = data as unknown as CustomNodeData;

  return (
    <div
      className={`min-w-[220px] max-w-[280px] rounded-xl bg-slate-900/95 border transition-all duration-150 shadow-lg ${
        selected
          ? 'border-indigo-400 ring-2 ring-indigo-400/30 shadow-indigo-500/20'
          : 'border-slate-700/80 hover:border-slate-600'
      }`}
    >
      {/* Target Connection Handle (Input) */}
      <Handle
        type="target"
        position={Position.Left}
        id="input"
        className="!w-3 !h-3 !bg-indigo-400 !border-2 !border-slate-900 hover:!scale-125 transition-transform"
      />

      {/* Node Header */}
      <div className="flex items-center justify-between px-3.5 py-2.5 bg-slate-800/80 rounded-t-xl border-b border-slate-700/60">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded-lg bg-indigo-500/20 text-indigo-400 flex items-center justify-center border border-indigo-500/30">
            <Box className="w-3.5 h-3.5" />
          </div>
          <span className="text-xs font-semibold text-white tracking-tight">Custom Node</span>
        </div>
        <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-slate-800 text-slate-300 border border-slate-700">
          Generic
        </span>
      </div>

      {/* Node Body */}
      <div className="p-3.5 space-y-1.5">
        <div className="text-xs font-medium text-slate-100 truncate">
          {nodeData.label || 'Generic Step'}
        </div>
        {nodeData.description ? (
          <p className="text-[11px] text-slate-400 line-clamp-2 leading-relaxed">
            {nodeData.description}
          </p>
        ) : (
          <p className="text-[11px] text-slate-500 italic">Custom workflow logic step</p>
        )}
      </div>

      {/* Source Connection Handle (Output) */}
      <Handle
        type="source"
        position={Position.Right}
        id="output"
        className="!w-3 !h-3 !bg-indigo-400 !border-2 !border-slate-900 hover:!scale-125 transition-transform"
      />
    </div>
  );
});

GenericNode.displayName = 'GenericNode';
