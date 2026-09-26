export type WorkflowStatus = 'DRAFT' | 'ACTIVE';

export interface WorkflowNode {
  id: string;
  type: string;
  data?: Record<string, unknown>;
}

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
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
  }
};
