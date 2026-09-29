import React, { useState, useCallback, useMemo, useRef, useEffect } from 'react';
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  Controls,
  MiniMap,
  addEdge,
  useNodesState,
  useEdgesState,
  useReactFlow,
  type Connection,
  type Edge,
  type Node,
  type NodeTypes,
  type OnConnect,
  type OnNodesChange,
  type OnEdgesChange,
  type XYPosition
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

import {
  ArrowLeft,
  Save,
  CheckCircle2,
  AlertCircle,
  RefreshCw,
  Sparkles,
  Play,
  History
} from 'lucide-react';

import {
  workflowToReactFlow,
  reactFlowToWorkflow,
  NODE_TYPES,
  type CustomNodeData
} from './workflowAdapter';
import { TriggerNode } from './TriggerNode';
import { HttpRequestNode } from './HttpRequestNode';
import { GenericNode } from './GenericNode';
import { NodePalette } from './NodePalette';
import { NodeConfigPanel } from './NodeConfigPanel';
import { ExecutionResultModal } from './ExecutionResultModal';
import { ExecutionHistoryPanel } from './ExecutionHistoryPanel';
import {
  workflowApi,
  type Workflow,
  type WorkflowStatus,
  type WorkflowExecutionResult,
  type ExecutionResponse
} from '../../services/workflowService';

interface WorkflowBuilderProps {
  workflowId: string;
  token: string;
  onBack: () => void;
  onWorkflowSaved?: (updatedWorkflow: Workflow) => void;
}

const WorkflowBuilderCanvas: React.FC<WorkflowBuilderProps> = ({
  workflowId,
  token,
  onBack,
  onWorkflowSaved
}) => {
  const reactFlowInstance = useReactFlow();
  const reactFlowWrapper = useRef<HTMLDivElement>(null);

  // Workflow Core State
  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [workflowName, setWorkflowName] = useState('');
  const [workflowDescription, setWorkflowDescription] = useState('');
  const [workflowStatus, setWorkflowStatus] = useState<WorkflowStatus>('DRAFT');

  // Loading & Error States
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Dirty state tracking
  const [isDirty, setIsDirty] = useState(false);

  // Execution state (Phase 4 & 5)
  const [isExecuting, setIsExecuting] = useState(false);
  const [executionResult, setExecutionResult] = useState<WorkflowExecutionResult | null>(null);
  const [activeExecutionDetail, setActiveExecutionDetail] = useState<
    WorkflowExecutionResult | ExecutionResponse | null
  >(null);
  const [showExecutionModal, setShowExecutionModal] = useState(false);
  const [showHistoryPanel, setShowHistoryPanel] = useState(false);
  const [historyRefreshTrigger, setHistoryRefreshTrigger] = useState(0);

  // React Flow state
  const [nodes, setNodes, onNodesChangeOriginal] = useNodesState<Node<CustomNodeData>>([]);
  const [edges, setEdges, onEdgesChangeOriginal] = useEdgesState<Edge>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);

  // Custom node types definition
  const nodeTypes: NodeTypes = useMemo(
    () => ({
      [NODE_TYPES.TRIGGER]: TriggerNode,
      [NODE_TYPES.HTTP_REQUEST]: HttpRequestNode,
      [NODE_TYPES.GENERIC]: GenericNode
    }),
    []
  );

  // Initial load
  const loadWorkflow = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const data = await workflowApi.getWorkflow(workflowId, token);
      setWorkflow(data);
      setWorkflowName(data.name);
      setWorkflowDescription(data.description || '');
      setWorkflowStatus(data.status);

      const { nodes: flowNodes, edges: flowEdges } = workflowToReactFlow(data);
      setNodes(flowNodes);
      setEdges(flowEdges);
      setIsDirty(false);
      setSelectedNodeId(null);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load workflow';
      setErrorMessage(msg);
    } finally {
      setIsLoading(false);
    }
  }, [workflowId, token, setNodes, setEdges]);

  useEffect(() => {
    void loadWorkflow();
  }, [loadWorkflow]);

  // Track node changes
  const onNodesChange: OnNodesChange<Node<CustomNodeData>> = useCallback(
    (changes) => {
      onNodesChangeOriginal(changes);
      // Mark dirty on position or dimension change or remove
      const hasMeaningfulChange = changes.some(
        (c) => c.type === 'position' || c.type === 'remove'
      );
      if (hasMeaningfulChange) {
        setIsDirty(true);
      }
    },
    [onNodesChangeOriginal]
  );

  // Track edge changes
  const onEdgesChange: OnEdgesChange<Edge> = useCallback(
    (changes) => {
      onEdgesChangeOriginal(changes);
      const hasMeaningfulChange = changes.some((c) => c.type === 'remove');
      if (hasMeaningfulChange) {
        setIsDirty(true);
      }
    },
    [onEdgesChangeOriginal]
  );

  // Connecting nodes
  const onConnect: OnConnect = useCallback(
    (params: Connection) => {
      const newEdge: Edge = {
        id: `edge-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`,
        source: params.source,
        target: params.target,
        sourceHandle: params.sourceHandle,
        targetHandle: params.targetHandle,
        animated: true,
        style: { stroke: '#10b981', strokeWidth: 2 }
      };
      setEdges((eds) => addEdge(newEdge, eds));
      setIsDirty(true);
    },
    [setEdges]
  );

  // Add node from palette (click or drop)
  const addNodeAtPosition = useCallback(
    (type: string, position?: XYPosition) => {
      const uniqueId = `node-${Date.now()}-${Math.random().toString(36).substring(2, 6)}`;
      let defaultData: CustomNodeData;

      if (type === NODE_TYPES.TRIGGER) {
        defaultData = {
          label: 'Manual Trigger',
          triggerType: 'Manual',
          description: 'Starts workflow graph execution'
        };
      } else if (type === NODE_TYPES.HTTP_REQUEST) {
        defaultData = {
          label: 'HTTP Request',
          method: 'GET',
          url: 'https://api.example.com',
          description: 'External API call'
        };
      } else {
        defaultData = {
          label: 'Custom Step',
          description: 'Generic workflow step'
        };
      }

      // Default coordinates: if position is provided use it, otherwise staggered center
      const finalPosition = position || {
        x: 180 + (nodes.length % 4) * 60,
        y: 140 + (nodes.length % 4) * 60
      };

      const newNode: Node<CustomNodeData> = {
        id: uniqueId,
        type,
        position: finalPosition,
        data: defaultData
      };

      setNodes((nds) => nds.concat(newNode));
      setSelectedNodeId(uniqueId);
      setIsDirty(true);
    },
    [nodes.length, setNodes]
  );

  // Drag & drop support from Palette onto Canvas
  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();

      const type = event.dataTransfer.getData('application/reactflow-type');
      if (!type) return;

      const clientPos = { x: event.clientX, y: event.clientY };
      const position = reactFlowInstance.screenToFlowPosition
        ? reactFlowInstance.screenToFlowPosition(clientPos)
        : { x: event.clientX - 250, y: event.clientY - 100 };

      addNodeAtPosition(type, position);
    },
    [reactFlowInstance, addNodeAtPosition]
  );

  // Selected node
  const selectedNode = useMemo(
    () => nodes.find((n) => n.id === selectedNodeId) || null,
    [nodes, selectedNodeId]
  );

  // Update selected node data
  const handleUpdateNodeData = useCallback(
    (nodeId: string, newData: CustomNodeData) => {
      setNodes((nds) =>
        nds.map((node) => {
          if (node.id === nodeId) {
            return {
              ...node,
              data: newData
            };
          }
          return node;
        })
      );
      setIsDirty(true);
    },
    [setNodes]
  );

  // Delete node
  const handleDeleteNode = useCallback(
    (nodeId: string) => {
      setNodes((nds) => nds.filter((n) => n.id !== nodeId));
      setEdges((eds) => eds.filter((e) => e.source !== nodeId && e.target !== nodeId));
      if (selectedNodeId === nodeId) {
        setSelectedNodeId(null);
      }
      setIsDirty(true);
    },
    [selectedNodeId, setNodes, setEdges]
  );

  // Save workflow
  const handleSave = async () => {
    if (!workflow) return;
    setIsSaving(true);
    setErrorMessage(null);
    setSuccessMessage(null);

    const { nodes: convertedNodes, edges: convertedEdges } = reactFlowToWorkflow(nodes, edges);

    try {
      const updated = await workflowApi.updateWorkflow(
        workflow.id,
        {
          name: workflowName.trim() || workflow.name,
          description: workflowDescription.trim() || undefined,
          status: workflowStatus,
          nodes: convertedNodes,
          edges: convertedEdges
        },
        token
      );

      setWorkflow(updated);
      setIsDirty(false);
      setSuccessMessage('Workflow saved successfully!');
      if (onWorkflowSaved) {
        onWorkflowSaved(updated);
      }
      setTimeout(() => setSuccessMessage(null), 3000);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to save workflow';
      setErrorMessage(msg);
    } finally {
      setIsSaving(false);
    }
  };

  // Execute workflow (Phase 4)
  const handleExecute = async () => {
    if (!workflow || isExecuting) return;

    setIsExecuting(true);
    setErrorMessage(null);
    setSuccessMessage(null);

    try {
      // If there are unsaved changes, save first so latest graph is executed
      if (isDirty) {
        const { nodes: convertedNodes, edges: convertedEdges } = reactFlowToWorkflow(nodes, edges);
        const updated = await workflowApi.updateWorkflow(
          workflow.id,
          {
            name: workflowName.trim() || workflow.name,
            description: workflowDescription.trim() || undefined,
            status: workflowStatus,
            nodes: convertedNodes,
            edges: convertedEdges
          },
          token
        );
        setWorkflow(updated);
        setIsDirty(false);
        if (onWorkflowSaved) {
          onWorkflowSaved(updated);
        }
      }

      const result = await workflowApi.executeWorkflow(workflow.id, token);
      setExecutionResult(result);
      setActiveExecutionDetail(result);
      setShowExecutionModal(true);
      setHistoryRefreshTrigger((prev) => prev + 1);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Execution failed';
      setErrorMessage(msg);
    } finally {
      setIsExecuting(false);
    }
  };

  // Back confirmation
  const handleBack = () => {
    if (isDirty) {
      const confirmed = window.confirm(
        'You have unsaved changes in this workflow. Are you sure you want to exit without saving?'
      );
      if (!confirmed) return;
    }
    onBack();
  };

  // Reload confirmation
  const handleReload = async () => {
    if (isDirty) {
      const confirmed = window.confirm(
        'You have unsaved changes in this workflow. Reloading will discard them. Continue?'
      );
      if (!confirmed) {
        return;
      }
    }
    await loadWorkflow();
  };

  return (
    <div className="flex flex-col h-[calc(100vh-64px)] w-full bg-[#090d16] text-slate-100 overflow-hidden select-none">
      {/* Top Builder Toolbar */}
      <header className="h-14 bg-[#0d1322] border-b border-slate-800 px-4 flex items-center justify-between z-30 shrink-0">
        <div className="flex items-center gap-3">
          <button
            onClick={handleBack}
            className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-medium transition"
            title="Return to workflow list"
          >
            <ArrowLeft className="w-4 h-4" />
            <span className="hidden sm:inline">Back</span>
          </button>

          <div className="h-5 w-px bg-slate-800" />

          {/* Workflow Name input */}
          <div className="flex items-center gap-2">
            <input
              type="text"
              value={workflowName}
              onChange={(e) => {
                setWorkflowName(e.target.value);
                setIsDirty(true);
              }}
              placeholder="Workflow Name"
              className="bg-transparent hover:bg-slate-900 focus:bg-slate-900 border border-transparent hover:border-slate-800 focus:border-slate-700 rounded px-2 py-1 text-sm font-semibold text-white focus:outline-none transition max-w-[200px] sm:max-w-xs truncate"
            />

            {/* Workflow Status badge / toggle */}
            <select
              value={workflowStatus}
              onChange={(e) => {
                setWorkflowStatus(e.target.value as WorkflowStatus);
                setIsDirty(true);
              }}
              className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded border focus:outline-none cursor-pointer ${
                workflowStatus === 'ACTIVE'
                  ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30'
                  : 'bg-slate-800 text-slate-400 border-slate-700'
              }`}
            >
              <option value="DRAFT">DRAFT</option>
              <option value="ACTIVE">ACTIVE</option>
            </select>
          </div>
        </div>

        {/* Toolbar Center: Status Indicator */}
        <div className="hidden md:flex items-center gap-2">
          {isSaving ? (
            <span className="flex items-center gap-1.5 text-xs text-amber-400 font-medium">
              <RefreshCw className="w-3.5 h-3.5 animate-spin" />
              Saving...
            </span>
          ) : isDirty ? (
            <span className="flex items-center gap-1.5 text-xs text-amber-400 font-medium px-2 py-0.5 rounded bg-amber-500/10 border border-amber-500/20">
              <span className="w-2 h-2 rounded-full bg-amber-400 animate-pulse" />
              Unsaved changes
            </span>
          ) : (
            <span className="flex items-center gap-1.5 text-xs text-emerald-400 font-medium px-2 py-0.5 rounded bg-emerald-500/10 border border-emerald-500/20">
              <CheckCircle2 className="w-3.5 h-3.5" />
              Saved
            </span>
          )}
        </div>

        {/* Toolbar Right: Actions */}
        <div className="flex items-center gap-2">
          <button
            onClick={() => void handleReload()}
            disabled={isLoading || isSaving}
            className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs transition disabled:opacity-50"
            title="Reload canvas from server"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} />
            <span className="hidden lg:inline">Reload</span>
          </button>

          <button
            onClick={() => void handleSave()}
            disabled={isSaving || isExecuting || !workflowName.trim()}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-semibold text-xs transition disabled:opacity-50 shadow-md shadow-emerald-500/20"
          >
            <Save className="w-3.5 h-3.5" />
            <span>Save</span>
          </button>

          <button
            onClick={() => void handleExecute()}
            disabled={isExecuting || isSaving || !workflow}
            className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg bg-sky-500 hover:bg-sky-400 text-slate-950 font-semibold text-xs transition disabled:opacity-50 shadow-md shadow-sky-500/20"
            title="Execute workflow synchronously"
          >
            {isExecuting ? (
              <RefreshCw className="w-3.5 h-3.5 animate-spin" />
            ) : (
              <Play className="w-3.5 h-3.5 fill-current" />
            )}
            <span>{isExecuting ? 'Running...' : 'Run Workflow'}</span>
          </button>

          <button
            onClick={() => setShowHistoryPanel((prev) => !prev)}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition border ${
              showHistoryPanel
                ? 'bg-sky-500/20 text-sky-400 border-sky-500/40 shadow-sm'
                : 'bg-slate-800 hover:bg-slate-700 text-slate-300 border-slate-700'
            }`}
            title="Toggle execution history logs"
          >
            <History className="w-3.5 h-3.5" />
            <span>History</span>
          </button>

          {executionResult && !isExecuting && (
            <button
              onClick={() => {
                setActiveExecutionDetail(executionResult);
                setShowExecutionModal(true);
              }}
              className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs transition border border-slate-700"
              title="View last execution result"
            >
              <span
                className={`w-2 h-2 rounded-full ${
                  executionResult.status === 'SUCCESS' ? 'bg-emerald-400' : 'bg-rose-400'
                }`}
              />
              <span className="hidden xl:inline">Result</span>
            </button>
          )}
        </div>
      </header>

      {/* Error or Success notification banners */}
      {errorMessage && (
        <div className="bg-rose-950/80 border-b border-rose-800/80 px-4 py-2 text-xs text-rose-300 flex items-center justify-between shrink-0">
          <div className="flex items-center gap-2">
            <AlertCircle className="w-4 h-4 text-rose-400 shrink-0" />
            <span>{errorMessage}</span>
          </div>
          <button
            onClick={() => setErrorMessage(null)}
            className="text-rose-400 hover:text-white"
          >
            &times;
          </button>
        </div>
      )}

      {successMessage && (
        <div className="bg-emerald-950/80 border-b border-emerald-800/80 px-4 py-2 text-xs text-emerald-300 flex items-center justify-between shrink-0">
          <div className="flex items-center gap-2">
            <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
            <span>{successMessage}</span>
          </div>
          <button
            onClick={() => setSuccessMessage(null)}
            className="text-emerald-400 hover:text-white"
          >
            &times;
          </button>
        </div>
      )}

      {/* Main Builder Body: Sidebar + Canvas + Config Panel */}
      <div className="flex-1 flex overflow-hidden relative">
        {/* Left: Node Palette */}
        <NodePalette onAddNode={(type) => addNodeAtPosition(type)} />

        {/* Center: React Flow Canvas */}
        <div
          ref={reactFlowWrapper}
          className="flex-1 h-full w-full relative bg-[#090d16]"
          onDragOver={onDragOver}
          onDrop={onDrop}
        >
          {isLoading ? (
            <div className="absolute inset-0 flex items-center justify-center bg-slate-950/80 z-20">
              <div className="flex flex-col items-center gap-2">
                <RefreshCw className="w-6 h-6 text-emerald-400 animate-spin" />
                <span className="text-xs text-slate-400">Loading workflow graph...</span>
              </div>
            </div>
          ) : (
            <ReactFlow
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onConnect={onConnect}
              onNodeClick={(_, node) => setSelectedNodeId(node.id)}
              onPaneClick={() => setSelectedNodeId(null)}
              nodeTypes={nodeTypes}
              fitView
              minZoom={0.2}
              maxZoom={2}
              className="bg-[#090d16]"
            >
              <Background color="#1e293b" gap={20} size={1} />
              <Controls className="!bg-slate-900/90 !border-slate-800 !rounded-xl !shadow-xl !fill-slate-300" />
              <MiniMap
                nodeColor={(n) => {
                  if (n.type === NODE_TYPES.TRIGGER) return '#f59e0b';
                  if (n.type === NODE_TYPES.HTTP_REQUEST) return '#10b981';
                  return '#6366f1';
                }}
                maskColor="rgba(15, 23, 42, 0.75)"
                className="!bg-slate-900/90 !border-slate-800 !rounded-xl"
              />
            </ReactFlow>
          )}

          {/* Empty Canvas Callout */}
          {!isLoading && nodes.length === 0 && (
            <div className="absolute inset-0 flex items-center justify-center pointer-events-none z-10">
              <div className="p-6 rounded-2xl bg-slate-900/80 border border-slate-800/80 text-center max-w-sm backdrop-blur pointer-events-auto shadow-2xl">
                <div className="w-10 h-10 rounded-xl bg-emerald-500/10 text-emerald-400 flex items-center justify-center mx-auto mb-3 border border-emerald-500/20">
                  <Sparkles className="w-5 h-5" />
                </div>
                <h4 className="text-sm font-semibold text-white mb-1">Canvas is empty</h4>
                <p className="text-xs text-slate-400 mb-4 leading-relaxed">
                  Add nodes from the palette on the left or click below to add an initial trigger.
                </p>
                <button
                  type="button"
                  onClick={() => addNodeAtPosition(NODE_TYPES.TRIGGER)}
                  className="px-3.5 py-1.5 rounded-lg bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-semibold text-xs transition inline-flex items-center gap-1.5 shadow"
                >
                  <Sparkles className="w-3.5 h-3.5" />
                  Add Initial Trigger
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Right: Node Configuration Panel */}
        {selectedNode && (
          <NodeConfigPanel
            key={selectedNode.id}
            selectedNode={selectedNode}
            onUpdateNodeData={handleUpdateNodeData}
            onDeleteNode={handleDeleteNode}
            onClose={() => setSelectedNodeId(null)}
          />
        )}

        {/* Right: Execution History Panel (Phase 5) */}
        {workflow && (
          <ExecutionHistoryPanel
            workflowId={workflow.id}
            token={token}
            isOpen={showHistoryPanel}
            onClose={() => setShowHistoryPanel(false)}
            refreshTrigger={historyRefreshTrigger}
            onSelectExecution={(exec) => {
              setActiveExecutionDetail(exec);
              setShowExecutionModal(true);
            }}
          />
        )}
      </div>

      {/* Execution Result Modal (Phase 4 & 5) */}
      {showExecutionModal && (activeExecutionDetail || executionResult) && (
        <ExecutionResultModal
          result={(activeExecutionDetail || executionResult)!}
          onClose={() => {
            setShowExecutionModal(false);
            setActiveExecutionDetail(null);
          }}
        />
      )}
    </div>
  );
};

export const WorkflowBuilder: React.FC<WorkflowBuilderProps> = (props) => {
  return (
    <ReactFlowProvider>
      <WorkflowBuilderCanvas {...props} />
    </ReactFlowProvider>
  );
};
