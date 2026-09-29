export type WorkflowStatus = 'DRAFT' | 'ACTIVE';

export interface WorkflowNodePosition {
  x: number;
  y: number;
}

export interface WorkflowNode {
  id: string;
  type: string;
  position?: WorkflowNodePosition;
  data?: Record<string, unknown>;
}

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string;
  targetHandle?: string;
}

export interface Workflow {
  id: string;
  userId: string;
  name: string;
  description?: string;
  status: WorkflowStatus;
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  createdAt: string;
  updatedAt: string;
}

export interface CreateWorkflowRequest {
  name: string;
  description?: string;
  status?: WorkflowStatus;
  nodes?: WorkflowNode[];
  edges?: WorkflowEdge[];
}

export interface UpdateWorkflowRequest {
  name: string;
  description?: string;
  status?: WorkflowStatus;
  nodes?: WorkflowNode[];
  edges?: WorkflowEdge[];
}

export type ExecutionStatus = 'RUNNING' | 'SUCCESS' | 'FAILED';
export type NodeExecutionStatus = 'SUCCESS' | 'FAILED' | 'SKIPPED';

export interface RetryConfig {
  enabled: boolean;
  maxRetries: number;
  initialBackoffMs: number;
  backoffMultiplier: number;
  maxBackoffMs?: number;
}

export interface NodeExecutionAttempt {
  attemptNumber: number;
  status: ExecutionStatus | NodeExecutionStatus | string;
  startedAt?: string | null;
  completedAt?: string | null;
  durationMs: number;
  input?: Record<string, unknown> | null;
  output?: Record<string, unknown> | null;
  error?: string | null;
}

export interface NodeExecutionResult {
  nodeId: string;
  nodeType: string;
  status: ExecutionStatus | NodeExecutionStatus | string;
  startedAt?: string | null;
  completedAt?: string | null;
  durationMs: number;
  input?: Record<string, unknown> | null;
  output?: Record<string, unknown> | null;
  error?: string | null;
  retryCount?: number;
  attempts?: NodeExecutionAttempt[];
}

export type NodeExecution = NodeExecutionResult;

export interface WorkflowExecutionResult {
  id?: string;
  executionId: string;
  workflowId: string;
  status: ExecutionStatus;
  triggerType?: string;
  startedAt: string;
  completedAt?: string | null;
  durationMs: number;
  nodes: NodeExecutionResult[];
  nodeExecutions?: NodeExecutionResult[];
  error?: string | null;
}

export interface ExecutionResponse {
  id: string;
  executionId?: string;
  workflowId: string;
  status: ExecutionStatus;
  triggerType?: string;
  startedAt: string;
  completedAt?: string | null;
  durationMs?: number | null;
  nodeExecutions: NodeExecutionResult[];
  nodes?: NodeExecutionResult[];
  error?: string | null;
}

export interface ExecutionSummaryResponse {
  id: string;
  executionId?: string;
  workflowId: string;
  status: ExecutionStatus;
  triggerType?: string;
  startedAt: string;
  completedAt?: string | null;
  durationMs?: number | null;
  error?: string | null;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export const workflowApi = {
  async listWorkflows(token: string): Promise<Workflow[]> {
    const res = await fetch(`${API_BASE_URL}/api/workflows`, {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Accept': 'application/json'
      }
    });
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to fetch workflows (HTTP ${res.status})`);
    }
    return res.json();
  },

  async getWorkflow(id: string, token: string): Promise<Workflow> {
    const res = await fetch(`${API_BASE_URL}/api/workflows/${encodeURIComponent(id)}`, {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Accept': 'application/json'
      }
    });
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to fetch workflow (HTTP ${res.status})`);
    }
    return res.json();
  },

  async createWorkflow(request: CreateWorkflowRequest, token: string): Promise<Workflow> {
    const res = await fetch(`${API_BASE_URL}/api/workflows`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
        'Accept': 'application/json'
      },
      body: JSON.stringify(request)
    });
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to create workflow (HTTP ${res.status})`);
    }
    return res.json();
  },

  async updateWorkflow(id: string, request: UpdateWorkflowRequest, token: string): Promise<Workflow> {
    const res = await fetch(`${API_BASE_URL}/api/workflows/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
        'Accept': 'application/json'
      },
      body: JSON.stringify(request)
    });
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to update workflow (HTTP ${res.status})`);
    }
    return res.json();
  },

  async deleteWorkflow(id: string, token: string): Promise<void> {
    const res = await fetch(`${API_BASE_URL}/api/workflows/${encodeURIComponent(id)}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    });
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to delete workflow (HTTP ${res.status})`);
    }
  },

  async executeWorkflow(id: string, token: string): Promise<WorkflowExecutionResult> {
    const res = await fetch(`${API_BASE_URL}/api/workflows/${encodeURIComponent(id)}/execute`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Accept': 'application/json'
      }
    });
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to execute workflow (HTTP ${res.status})`);
    }
    return res.json();
  },

  async getExecution(executionId: string, token: string): Promise<ExecutionResponse> {
    const res = await fetch(`${API_BASE_URL}/api/executions/${encodeURIComponent(executionId)}`, {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Accept': 'application/json'
      }
    });
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to fetch execution (HTTP ${res.status})`);
    }
    return res.json();
  },

  async getWorkflowExecutions(
    workflowId: string,
    token: string,
    page = 0,
    size = 20
  ): Promise<PageResponse<ExecutionSummaryResponse>> {
    const res = await fetch(
      `${API_BASE_URL}/api/workflows/${encodeURIComponent(workflowId)}/executions?page=${page}&size=${size}`,
      {
        headers: {
          'Authorization': `Bearer ${token}`,
          'Accept': 'application/json'
        }
      }
    );
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to fetch workflow executions (HTTP ${res.status})`);
    }
    return res.json();
  },

  async listExecutions(
    token: string,
    page = 0,
    size = 20,
    status?: string
  ): Promise<PageResponse<ExecutionSummaryResponse>> {
    const query = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) {
      query.set('status', status);
    }
    const res = await fetch(`${API_BASE_URL}/api/executions?${query.toString()}`, {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Accept': 'application/json'
      }
    });
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to fetch executions (HTTP ${res.status})`);
    }
    return res.json();
  }
};
