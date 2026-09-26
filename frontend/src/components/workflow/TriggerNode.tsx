import React, { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { Zap } from 'lucide-react';
import type { CustomNodeData } from './workflowAdapter';

export const TriggerNode: React.FC<NodeProps> = memo(({ data, selected }) => {
  const nodeData = data as unknown as CustomNodeData;

  return (
    <div
      className={`min-w-[220px] max-w-[280px] rounded-xl bg-slate-900/95 border transition-all duration-150 shadow-lg ${
        selected
          ? 'border-amber-400 ring-2 ring-amber-400/30 shadow-amber-500/20'
          : 'border-slate-700/80 hover:border-slate-600'
      }`}
    >
      {/* Node Header */}
      <div className="flex items-center justify-between px-3.5 py-2.5 bg-slate-800/80 rounded-t-xl border-b border-slate-700/60">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded-lg bg-amber-500/20 text-amber-400 flex items-center justify-center border border-amber-500/30">
            <Zap className="w-3.5 h-3.5 fill-amber-400/30" />
          </div>
          <span className="text-xs font-semibold text-white tracking-tight">Trigger</span>
        </div>
        <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-amber-500/10 text-amber-300 border border-amber-500/20">
          {nodeData.triggerType || 'Manual'}
        </span>
      </div>

      {/* Node Body */}
      <div className="p-3.5 space-y-1.5">
        <div className="text-xs font-medium text-slate-100 truncate">
          {nodeData.label || 'Manual Trigger'}
        </div>
        {nodeData.description ? (
          <p className="text-[11px] text-slate-400 line-clamp-2 leading-relaxed">
            {nodeData.description}
          </p>
        ) : (
          <p className="text-[11px] text-slate-500 italic">Initiates workflow execution</p>
        )}
      </div>

      {/* Source Connection Handle (Output only) */}
      <Handle
        type="source"
        position={Position.Right}
        id="output"
        className="!w-3 !h-3 !bg-amber-400 !border-2 !border-slate-900 hover:!scale-125 transition-transform"
      />
    </div>
  );
});

TriggerNode.displayName = 'TriggerNode';
