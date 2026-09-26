import React from 'react';
import { Zap, Globe, Box, Plus } from 'lucide-react';
import { NODE_TYPES } from './workflowAdapter';

interface NodePaletteProps {
  onAddNode: (type: string) => void;
}

interface PaletteItem {
  type: string;
  label: string;
  category: string;
  description: string;
  icon: React.ReactNode;
  borderHover: string;
  badgeStyle: string;
}

export const NodePalette: React.FC<NodePaletteProps> = ({ onAddNode }) => {
  const paletteItems: PaletteItem[] = [
    {
      type: NODE_TYPES.TRIGGER,
      label: 'Manual Trigger',
      category: 'Trigger',
      description: 'Initiates workflow graph execution',
      icon: <Zap className="w-4 h-4 text-amber-400" />,
      borderHover: 'hover:border-amber-500/50',
      badgeStyle: 'bg-amber-500/10 text-amber-400 border-amber-500/20'
    },
    {
      type: NODE_TYPES.HTTP_REQUEST,
      label: 'HTTP Request',
      category: 'Action',
      description: 'Configures external REST endpoint calls',
      icon: <Globe className="w-4 h-4 text-emerald-400" />,
      borderHover: 'hover:border-emerald-500/50',
      badgeStyle: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20'
    },
    {
      type: NODE_TYPES.GENERIC,
      label: 'Custom Node',
      category: 'Generic',
      description: 'General purpose workflow configuration block',
      icon: <Box className="w-4 h-4 text-indigo-400" />,
      borderHover: 'hover:border-indigo-500/50',
      badgeStyle: 'bg-indigo-500/10 text-indigo-400 border-indigo-500/20'
    }
  ];

  const onDragStart = (event: React.DragEvent, nodeType: string) => {
    event.dataTransfer.setData('application/reactflow-type', nodeType);
    event.dataTransfer.effectAllowed = 'move';
  };

  return (
    <aside className="w-72 bg-[#0c1220] border-r border-slate-800 flex flex-col h-full shrink-0 select-none">
      {/* Sidebar Header */}
      <div className="p-4 border-b border-slate-800/80">
        <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400">Node Palette</h3>
        <p className="text-[11px] text-slate-500 mt-0.5">Click or drag nodes onto the canvas</p>
      </div>

      {/* Nodes List */}
      <div className="p-3 space-y-2.5 overflow-y-auto flex-1">
        {paletteItems.map((item) => (
          <div
            key={item.type}
            draggable
            onDragStart={(e) => onDragStart(e, item.type)}
            className={`group p-3 rounded-xl bg-slate-900/90 border border-slate-800 cursor-grab active:cursor-grabbing transition-all duration-150 ${item.borderHover} hover:bg-slate-800/60 shadow-sm`}
          >
            <div className="flex items-start justify-between gap-2">
              <div className="flex items-center gap-2">
                <div className="w-7 h-7 rounded-lg bg-slate-800/90 flex items-center justify-center border border-slate-700/60">
                  {item.icon}
                </div>
                <div>
                  <div className="text-xs font-semibold text-white group-hover:text-emerald-300 transition-colors">
                    {item.label}
                  </div>
                  <span className={`inline-block text-[9px] font-mono px-1.5 py-0.2 rounded border ${item.badgeStyle}`}>
                    {item.category}
                  </span>
                </div>
              </div>
              <button
                type="button"
                onClick={() => onAddNode(item.type)}
                className="opacity-80 group-hover:opacity-100 p-1.5 rounded-lg bg-slate-800 hover:bg-emerald-500 hover:text-slate-950 text-slate-400 transition"
                title={`Add ${item.label}`}
              >
                <Plus className="w-3.5 h-3.5" />
              </button>
            </div>
            <p className="text-[11px] text-slate-400 mt-2 leading-relaxed">
              {item.description}
            </p>
          </div>
        ))}
      </div>

      {/* Info Tip */}
      <div className="p-3.5 border-t border-slate-800/80 bg-slate-950/40 text-[11px] text-slate-500 space-y-1">
        <div className="text-slate-400 font-medium">Tips:</div>
        <div>&bull; Connect nodes by dragging handles</div>
        <div>&bull; Click a node to edit its configuration</div>
        <div>&bull; Select and press Delete to remove</div>
      </div>
    </aside>
  );
};
