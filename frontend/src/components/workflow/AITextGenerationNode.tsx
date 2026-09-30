import React, { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { Sparkles } from 'lucide-react';
import type { CustomNodeData } from './workflowAdapter';

export const AITextGenerationNode: React.FC<NodeProps> = memo(({ data, selected }) => {
  const nodeData = data as unknown as CustomNodeData;

  const provider = (typeof nodeData.provider === 'string' ? nodeData.provider : 'openai').toLowerCase();
  const model = typeof nodeData.model === 'string' ? nodeData.model : 'gpt-4o-mini';
  const prompt = typeof nodeData.userPrompt === 'string' ? nodeData.userPrompt : (typeof nodeData.prompt === 'string' ? nodeData.prompt : '');

  const providerBadge = provider === 'gemini'
    ? 'bg-blue-500/20 text-blue-300 border-blue-500/30'
    : 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30';

  return (
    <div
      className={`min-w-[240px] max-w-[300px] rounded-xl bg-slate-900/95 border transition-all duration-150 shadow-lg ${
        selected
          ? 'border-purple-400 ring-2 ring-purple-400/30 shadow-purple-500/20'
          : 'border-slate-700/80 hover:border-slate-600'
      }`}
    >
      {/* Target Connection Handle (Input) */}
      <Handle
        type="target"
        position={Position.Left}
        id="input"
        className="!w-3 !h-3 !bg-purple-400 !border-2 !border-slate-900 hover:!scale-125 transition-transform"
      />

      {/* Node Header */}
      <div className="flex items-center justify-between px-3.5 py-2.5 bg-slate-800/80 rounded-t-xl border-b border-slate-700/60">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded-lg bg-purple-500/20 text-purple-400 flex items-center justify-center border border-purple-500/30">
            <Sparkles className="w-3.5 h-3.5" />
          </div>
          <span className="text-xs font-semibold text-white tracking-tight">AI Text Gen</span>
        </div>
        <div className="flex items-center gap-1.5">
          <span className={`text-[10px] font-mono font-bold px-1.5 py-0.5 rounded border uppercase ${providerBadge}`}>
            {provider}
          </span>
        </div>
      </div>

      {/* Node Body */}
      <div className="p-3.5 space-y-2">
        <div className="flex items-center justify-between">
          <div className="text-xs font-medium text-slate-100 truncate">
            {nodeData.label || 'AI Text Generation'}
          </div>
          <span className="text-[10px] font-mono text-purple-300 bg-purple-950/60 px-1.5 py-0.5 rounded border border-purple-800/40">
            {model}
          </span>
        </div>

        {prompt ? (
          <div className="p-2 rounded-lg bg-slate-950/80 border border-slate-800 text-[11px] text-slate-300 line-clamp-2 leading-relaxed">
            {prompt}
          </div>
        ) : (
          <div className="p-2 rounded-lg bg-slate-950/80 border border-slate-800 text-[11px] text-slate-500 italic">
            Configure prompt template...
          </div>
        )}

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
        className="!w-3 !h-3 !bg-purple-400 !border-2 !border-slate-900 hover:!scale-125 transition-transform"
      />
    </div>
  );
});

AITextGenerationNode.displayName = 'AITextGenerationNode';
