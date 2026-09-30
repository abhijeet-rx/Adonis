import type { Node, Edge } from '@xyflow/react';
import type { Workflow, WorkflowNode, WorkflowEdge, RetryConfig } from '../../services/workflowService';

export interface CustomNodeData extends Record<string, unknown> {
  label: string;
  description?: string;
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
  url?: string;
  triggerType?: string;
  retry?: RetryConfig;
  retryConfig?: RetryConfig;
  // Phase 9: AI Node Configuration
  provider?: 'openai' | 'gemini';
  model?: string;
  systemPrompt?: string;
  userPrompt?: string;
  prompt?: string;
  temperature?: number;
  maxTokens?: number;
  jsonSchema?: string;
  [key: string]: unknown;
}

export const NODE_TYPES = {
  TRIGGER: 'trigger',
  HTTP_REQUEST: 'httpRequest',
  GENERIC: 'generic',
  AI_TEXT_GENERATION: 'ai_text_generation',
  AI_STRUCTURED_OUTPUT: 'ai_structured_output'
} as const;

/**
 * Converts a backend Workflow into React Flow nodes and edges.
 */
export function workflowToReactFlow(workflow: Workflow): {
  nodes: Node<CustomNodeData>[];
  edges: Edge[];
} {
  const nodes: Node<CustomNodeData>[] = (workflow.nodes || []).map((node, index) => {
    const rawData = (node.data || {}) as Record<string, unknown>;
    
    // Default label based on type
    const defaultLabel =
      node.type === NODE_TYPES.TRIGGER
        ? 'Manual Trigger'
        : node.type === NODE_TYPES.HTTP_REQUEST
        ? 'HTTP Request'
        : node.type === NODE_TYPES.AI_TEXT_GENERATION
        ? 'AI Text Generation'
        : node.type === NODE_TYPES.AI_STRUCTURED_OUTPUT
        ? 'AI Structured Output'
        : 'Generic Step';

    const nodeData: CustomNodeData = {
      label: typeof rawData.label === 'string' ? rawData.label : defaultLabel,
      description: typeof rawData.description === 'string' ? rawData.description : '',
      method: (rawData.method as CustomNodeData['method']) || 'GET',
      url: typeof rawData.url === 'string' ? rawData.url : 'https://api.example.com',
      triggerType: typeof rawData.triggerType === 'string' ? rawData.triggerType : 'Manual',
      ...rawData
    };

    // Use saved position or generate a clean staggered default
    const position = node.position && typeof node.position.x === 'number' && typeof node.position.y === 'number'
      ? { x: node.position.x, y: node.position.y }
      : { x: 100 + (index % 3) * 280, y: 120 + Math.floor(index / 3) * 160 };

    return {
      id: node.id,
      type: node.type || NODE_TYPES.GENERIC,
      position,
      data: nodeData
    };
  });

  const edges: Edge[] = (workflow.edges || []).map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    sourceHandle: edge.sourceHandle || null,
    targetHandle: edge.targetHandle || null,
    animated: true,
    style: { stroke: '#10b981', strokeWidth: 2 }
  }));

  return { nodes, edges };
}

/**
 * Converts React Flow nodes and edges into the backend Workflow format.
 */
export function reactFlowToWorkflow(
  nodes: Node<CustomNodeData>[],
  edges: Edge[]
): {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
} {
  const workflowNodes: WorkflowNode[] = nodes.map((node) => ({
    id: node.id,
    type: node.type || NODE_TYPES.GENERIC,
    position: {
      x: node.position.x,
      y: node.position.y
    },
    data: {
      ...node.data
    }
  }));

  const workflowEdges: WorkflowEdge[] = edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    sourceHandle: edge.sourceHandle || undefined,
    targetHandle: edge.targetHandle || undefined
  }));

  return { nodes: workflowNodes, edges: workflowEdges };
}
