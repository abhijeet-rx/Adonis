import React, { useState, useEffect } from 'react';
import { 
  Activity, 
  Workflow as WorkflowIcon, 
  Layers, 
  Cpu, 
  Sparkles, 
  Terminal, 
  CheckCircle2, 
  XCircle, 
  RefreshCw, 
  GitBranch, 
  ShieldCheck, 
  Lock, 
  UserCheck, 
  LogOut, 
  KeyRound, 
  Mail, 
  User as UserIcon,
  AlertCircle,
  Plus,
  Trash2,
  FolderGit2
} from 'lucide-react';
import { workflowApi, type Workflow, type WorkflowStatus } from './services/workflowService';

interface HealthData {
  status: string;
  service: string;
  version: string;
  timestamp: string;
}

interface UserProfile {
  id: string;
  name: string;
  email: string;
  createdAt: string;
  updatedAt: string;
}

interface AuthSuccessResponse {
  token: string;
  user: UserProfile;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";
const TOKEN_STORAGE_KEY = 'adonis_access_token';

export const App: React.FC = () => {
  // Health Diagnostic State
  const [health, setHealth] = useState<HealthData | null>(null);
  const [healthLoading, setHealthLoading] = useState(false);
  const [healthError, setHealthError] = useState<string | null>(null);

  // Authentication State
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(TOKEN_STORAGE_KEY));
  const [currentUser, setCurrentUser] = useState<UserProfile | null>(null);
  const [authTab, setAuthTab] = useState<'login' | 'register'>('login');
  const [formName, setFormName] = useState('');
  const [formEmail, setFormEmail] = useState('');
  const [formPassword, setFormPassword] = useState('');
  const [authLoading, setAuthLoading] = useState(false);
  const [authError, setAuthError] = useState<string | null>(null);
  const [authSuccess, setAuthSuccess] = useState<string | null>(null);

  // Protected Endpoint Test State
  const [protectedLoading, setProtectedLoading] = useState(false);
  const [protectedMessage, setProtectedMessage] = useState<string | null>(null);
  const [protectedError, setProtectedError] = useState<string | null>(null);

  // Workflow State (Phase 2 CRUD)
  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [workflowsLoading, setWorkflowsLoading] = useState(false);
  const [workflowError, setWorkflowError] = useState<string | null>(null);
  const [wfName, setWfName] = useState('');
  const [wfDesc, setWfDesc] = useState('');
  const [wfStatus, setWfStatus] = useState<WorkflowStatus>('DRAFT');
  const [creatingWf, setCreatingWf] = useState(false);

  const fetchHealth = async () => {
    setHealthLoading(true);
    setHealthError(null);
    try {
      const res = await fetch(`${API_BASE_URL}/api/health`);
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${res.statusText}`);
      }
      const data: HealthData = await res.json();
      setHealth(data);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to connect to backend';
      setHealthError(message);
      setHealth(null);
    } finally {
      setHealthLoading(false);
    }
  };

  const handleLogout = () => {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    setToken(null);
    setCurrentUser(null);
    setWorkflows([]);
    setWorkflowError(null);
    setProtectedMessage(null);
    setProtectedError(null);
    setAuthSuccess('You have been logged out.');
  };

  const loadUserProfile = async (authToken: string) => {
    try {
      const res = await fetch(`${API_BASE_URL}/api/users/me`, {
        headers: {
          'Authorization': `Bearer ${authToken}`,
          'Accept': 'application/json'
        }
      });
      if (res.ok) {
        const profile: UserProfile = await res.json();
        setCurrentUser(profile);
      } else if (res.status === 401) {
        // Expired or invalid token
        handleLogout();
      }
    } catch {
      // Offline or network error
    }
  };

  const loadWorkflows = async (authToken: string) => {
    setWorkflowsLoading(true);
    setWorkflowError(null);
    try {
      const data = await workflowApi.listWorkflows(authToken);
      setWorkflows(data);
    } catch (err: unknown) {
      setWorkflowError(err instanceof Error ? err.message : 'Failed to load workflows');
    } finally {
      setWorkflowsLoading(false);
    }
  };

  const handleCreateWorkflow = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!token || !wfName.trim()) return;
    setCreatingWf(true);
    setWorkflowError(null);
    try {
      await workflowApi.createWorkflow({
        name: wfName.trim(),
        description: wfDesc.trim() || undefined,
        status: wfStatus,
        nodes: [],
        edges: []
      }, token);
      setWfName('');
      setWfDesc('');
      await loadWorkflows(token);
    } catch (err: unknown) {
      setWorkflowError(err instanceof Error ? err.message : 'Failed to create workflow');
    } finally {
      setCreatingWf(false);
    }
  };

  const handleDeleteWorkflow = async (id: string) => {
    if (!token) return;
    try {
      await workflowApi.deleteWorkflow(id, token);
      await loadWorkflows(token);
    } catch (err: unknown) {
      setWorkflowError(err instanceof Error ? err.message : 'Failed to delete workflow');
    }
  };

  useEffect(() => {
    void fetchHealth();
    const storedToken = localStorage.getItem(TOKEN_STORAGE_KEY);
    if (storedToken) {
      void loadUserProfile(storedToken);
      void loadWorkflows(storedToken);
    }
  }, []);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthLoading(true);
    setAuthError(null);
    setAuthSuccess(null);

    try {
      const res = await fetch(`${API_BASE_URL}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: formEmail, password: formPassword })
      });

      const data = await res.json();

      if (!res.ok) {
        throw new Error(data.message || `Login failed (HTTP ${res.status})`);
      }

      const authData = data as AuthSuccessResponse;
      localStorage.setItem(TOKEN_STORAGE_KEY, authData.token);
      setToken(authData.token);
      setCurrentUser(authData.user);
      void loadWorkflows(authData.token);
      setFormPassword('');
      setAuthSuccess(`Welcome back, ${authData.user.name}!`);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Login failed';
      setAuthError(message);
    } finally {
      setAuthLoading(false);
    }
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthLoading(true);
    setAuthError(null);
    setAuthSuccess(null);

    try {
      const res = await fetch(`${API_BASE_URL}/api/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: formName, email: formEmail, password: formPassword })
      });

      const data = await res.json();

      if (!res.ok) {
        throw new Error(data.message || `Registration failed (HTTP ${res.status})`);
      }

      const authData = data as AuthSuccessResponse;
      localStorage.setItem(TOKEN_STORAGE_KEY, authData.token);
      setToken(authData.token);
      setCurrentUser(authData.user);
      void loadWorkflows(authData.token);
      setFormPassword('');
      setFormName('');
      setAuthSuccess(`Account created successfully! Welcome, ${authData.user.name}.`);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Registration failed';
      setAuthError(message);
    } finally {
      setAuthLoading(false);
    }
  };

  const testProtectedEndpoint = async () => {
    if (!token) return;
    setProtectedLoading(true);
    setProtectedMessage(null);
    setProtectedError(null);

    try {
      const res = await fetch(`${API_BASE_URL}/api/users/me`, {
        headers: {
          'Authorization': `Bearer ${token}`,
          'Accept': 'application/json'
        }
      });

      if (!res.ok) {
        if (res.status === 401) {
          handleLogout();
          throw new Error('HTTP 401: Token expired or invalid. You have been logged out.');
        }
        throw new Error(`HTTP ${res.status}: Access Denied / Invalid Token`);
      }

      const data: UserProfile = await res.json();
      setProtectedMessage(`Authenticated as ${data.name} (${data.email}) — User ID: ${data.id}`);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to query protected endpoint';
      setProtectedError(message);
    } finally {
      setProtectedLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#090d16] text-slate-100 flex flex-col font-sans selection:bg-emerald-500 selection:text-white">
      {/* Top Navigation */}
      <header className="border-b border-slate-800 bg-[#0d1322]/80 backdrop-blur-md sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-6 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-emerald-500 to-teal-400 flex items-center justify-center shadow-lg shadow-emerald-500/20">
              <WorkflowIcon className="w-5 h-5 text-slate-950 font-bold" />
            </div>
            <div>
              <span className="text-xl font-bold tracking-tight bg-gradient-to-r from-white via-slate-200 to-slate-400 bg-clip-text text-transparent">
                Adonis
              </span>
              <span className="ml-2 text-xs font-mono font-medium px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                Phase 2: Workflow CRUD
              </span>
            </div>
          </div>

          <div className="flex items-center gap-4 text-sm">
            {currentUser ? (
              <div className="flex items-center gap-3">
                <span className="text-xs text-slate-300 hidden sm:inline">
                  Signed in as <strong className="text-white">{currentUser.name}</strong>
                </span>
                <button
                  onClick={handleLogout}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-rose-950/40 text-slate-300 hover:text-rose-300 border border-slate-700 hover:border-rose-800/50 text-xs transition"
                >
                  <LogOut className="w-3.5 h-3.5" />
                  Log Out
                </button>
              </div>
            ) : (
              <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-900 border border-slate-800">
                <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
                <span className="text-xs text-slate-400">Environment: Local Dev</span>
              </div>
            )}
            <a 
              href="https://github.com/abhijeet-rx/Adonis" 
              target="_blank" 
              rel="noreferrer"
              className="text-xs text-slate-400 hover:text-white flex items-center gap-1.5 transition-colors"
            >
              <GitBranch className="w-3.5 h-3.5" />
              Repository
            </a>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 max-w-7xl mx-auto px-6 py-12 w-full space-y-10">
        {/* Hero Section */}
        <section className="text-center space-y-4 max-w-3xl mx-auto">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-slate-800/80 border border-slate-700 text-xs text-slate-300">
            <Terminal className="w-3.5 h-3.5 text-emerald-400" />
            <span>Developer-First Workflow Automation</span>
          </div>
          <h1 className="text-4xl sm:text-5xl font-extrabold tracking-tight text-white leading-tight">
            Build, Orchestrate, and Automate with <span className="bg-gradient-to-r from-emerald-400 to-teal-300 bg-clip-text text-transparent">Adonis</span>
          </h1>
          <p className="text-slate-400 text-base sm:text-lg leading-relaxed">
            Lightweight, high-performance workflow automation engine designed for developers.
            Phase 1 incorporates persistent user identity via MongoDB, BCrypt password hashing, and stateless JWT tokens.
          </p>
        </section>

        {/* Phase 1: Authentication & User Profile Section */}
        <section className="bg-[#0f172a]/70 border border-slate-800 rounded-2xl p-6 shadow-xl backdrop-blur">
          <div className="flex items-center justify-between mb-6 border-b border-slate-800/80 pb-4">
            <div className="flex items-center gap-2.5">
              <KeyRound className="w-5 h-5 text-emerald-400" />
              <h2 className="text-lg font-semibold text-white">Authentication &amp; Identity</h2>
              <span className="text-xs text-slate-500 font-mono">POST /api/auth/* &bull; GET /api/users/me</span>
            </div>
            {token && (
              <span className="flex items-center gap-1 text-xs px-2.5 py-1 rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-medium">
                <ShieldCheck className="w-3.5 h-3.5" />
                Active Session
              </span>
            )}
          </div>

          {authError && (
            <div className="mb-6 p-4 rounded-xl bg-rose-950/30 border border-rose-800/50 text-xs text-rose-300 flex items-center gap-2">
              <AlertCircle className="w-4 h-4 shrink-0 text-rose-400" />
              <span>{authError}</span>
            </div>
          )}

          {authSuccess && (
            <div className="mb-6 p-4 rounded-xl bg-emerald-950/30 border border-emerald-800/50 text-xs text-emerald-300 flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 shrink-0 text-emerald-400" />
              <span>{authSuccess}</span>
            </div>
          )}

          {!token ? (
            <div className="max-w-md mx-auto">
              <div className="flex rounded-lg bg-slate-900/90 p-1 border border-slate-800 mb-6">
                <button
                  type="button"
                  onClick={() => { setAuthTab('login'); setAuthError(null); }}
                  className={`flex-1 py-2 text-xs font-semibold rounded-md transition ${authTab === 'login' ? 'bg-emerald-500 text-slate-950 shadow' : 'text-slate-400 hover:text-white'}`}
                >
                  Sign In
                </button>
                <button
                  type="button"
                  onClick={() => { setAuthTab('register'); setAuthError(null); }}
                  className={`flex-1 py-2 text-xs font-semibold rounded-md transition ${authTab === 'register' ? 'bg-emerald-500 text-slate-950 shadow' : 'text-slate-400 hover:text-white'}`}
                >
                  Create Account
                </button>
              </div>

              {authTab === 'login' ? (
                <form onSubmit={handleLogin} className="space-y-4">
                  <div>
                    <label className="block text-xs font-medium text-slate-300 mb-1.5 flex items-center gap-1.5">
                      <Mail className="w-3.5 h-3.5 text-slate-400" /> Email Address
                    </label>
                    <input
                      type="email"
                      required
                      value={formEmail}
                      onChange={(e) => setFormEmail(e.target.value)}
                      placeholder="user@example.com"
                      className="w-full px-3.5 py-2.5 rounded-xl bg-slate-900/90 border border-slate-700/80 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 transition"
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-slate-300 mb-1.5 flex items-center gap-1.5">
                      <Lock className="w-3.5 h-3.5 text-slate-400" /> Password
                    </label>
                    <input
                      type="password"
                      required
                      value={formPassword}
                      onChange={(e) => setFormPassword(e.target.value)}
                      placeholder="••••••••"
                      className="w-full px-3.5 py-2.5 rounded-xl bg-slate-900/90 border border-slate-700/80 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 transition"
                    />
                  </div>
                  <button
                    type="submit"
                    disabled={authLoading}
                    className="w-full py-2.5 px-4 rounded-xl bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-semibold text-xs transition disabled:opacity-50 flex items-center justify-center gap-2 shadow-lg shadow-emerald-500/20"
                  >
                    {authLoading ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : <Lock className="w-3.5 h-3.5" />}
                    Sign In
                  </button>
                </form>
              ) : (
                <form onSubmit={handleRegister} className="space-y-4">
                  <div>
                    <label className="block text-xs font-medium text-slate-300 mb-1.5 flex items-center gap-1.5">
                      <UserIcon className="w-3.5 h-3.5 text-slate-400" /> Full Name
                    </label>
                    <input
                      type="text"
                      required
                      value={formName}
                      onChange={(e) => setFormName(e.target.value)}
                      placeholder="Abhijeet Singh"
                      className="w-full px-3.5 py-2.5 rounded-xl bg-slate-900/90 border border-slate-700/80 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 transition"
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-slate-300 mb-1.5 flex items-center gap-1.5">
                      <Mail className="w-3.5 h-3.5 text-slate-400" /> Email Address
                    </label>
                    <input
                      type="email"
                      required
                      value={formEmail}
                      onChange={(e) => setFormEmail(e.target.value)}
                      placeholder="user@example.com"
                      className="w-full px-3.5 py-2.5 rounded-xl bg-slate-900/90 border border-slate-700/80 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 transition"
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-slate-300 mb-1.5 flex items-center gap-1.5">
                      <Lock className="w-3.5 h-3.5 text-slate-400" /> Password (Min 6 chars)
                    </label>
                    <input
                      type="password"
                      required
                      minLength={6}
                      value={formPassword}
                      onChange={(e) => setFormPassword(e.target.value)}
                      placeholder="••••••••"
                      className="w-full px-3.5 py-2.5 rounded-xl bg-slate-900/90 border border-slate-700/80 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 transition"
                    />
                  </div>
                  <button
                    type="submit"
                    disabled={authLoading}
                    className="w-full py-2.5 px-4 rounded-xl bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-semibold text-xs transition disabled:opacity-50 flex items-center justify-center gap-2 shadow-lg shadow-emerald-500/20"
                  >
                    {authLoading ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : <UserCheck className="w-3.5 h-3.5" />}
                    Create Account
                  </button>
                </form>
              )}
            </div>
          ) : (
            <div className="space-y-6">
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <div className="p-4 rounded-xl bg-slate-900/80 border border-slate-800">
                  <span className="text-xs text-slate-400 block mb-1">User Identity</span>
                  <div className="text-sm font-semibold text-white">{currentUser?.name || 'Loading...'}</div>
                  <div className="text-xs text-slate-400 font-mono">{currentUser?.email}</div>
                  {currentUser?.updatedAt && (
                    <div className="text-[10px] text-slate-500 mt-1 font-mono">Updated: {new Date(currentUser.updatedAt).toLocaleString()}</div>
                  )}
                </div>

                <div className="p-4 rounded-xl bg-slate-900/80 border border-slate-800">
                  <span className="text-xs text-slate-400 block mb-1">MongoDB Document ID</span>
                  <div className="text-xs font-mono text-emerald-400 break-all">{currentUser?.id || '—'}</div>
                </div>

                <div className="p-4 rounded-xl bg-slate-900/80 border border-slate-800">
                  <span className="text-xs text-slate-400 block mb-1">Authentication Mechanism</span>
                  <div className="text-xs font-mono text-slate-300">Stateless JWT (HMAC-SHA256)</div>
                  <div className="text-[10px] text-slate-500 mt-1">BCrypt-hashed credentials</div>
                </div>
              </div>

              {/* JWT Bearer Token View & Protected Endpoint Test */}
              <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800/80 space-y-3">
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                  <div className="flex items-center gap-2">
                    <ShieldCheck className="w-4 h-4 text-emerald-400" />
                    <span className="text-xs font-semibold text-white">Active Authentication Session</span>
                  </div>
                  <button
                    onClick={testProtectedEndpoint}
                    disabled={protectedLoading}
                    className="flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 text-xs font-medium transition disabled:opacity-50"
                  >
                    <RefreshCw className={`w-3.5 h-3.5 ${protectedLoading ? 'animate-spin' : ''}`} />
                    Test Protected Endpoint (GET /api/users/me)
                  </button>
                </div>

                <div className="flex items-center justify-between p-2.5 rounded-lg bg-slate-950 border border-slate-800 text-xs">
                  <div className="flex items-center gap-2">
                    <span className="inline-block w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
                    <span className="font-mono text-[11px] text-slate-300">JWT Token Active</span>
                  </div>
                  <span className="text-[11px] text-slate-500 font-mono">Authorization: Bearer ••••••••••••</span>
                </div>

                {protectedMessage && (
                  <div className="p-3 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-xs text-emerald-300 flex items-center gap-2">
                    <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                    <span>{protectedMessage}</span>
                  </div>
                )}

                {protectedError && (
                  <div className="p-3 rounded-lg bg-rose-500/10 border border-rose-500/20 text-xs text-rose-300 flex items-center gap-2">
                    <XCircle className="w-4 h-4 text-rose-400 shrink-0" />
                    <span>{protectedError}</span>
                  </div>
                )}
              </div>

              {/* Phase 2: Workflow CRUD Management Card */}
              <div className="p-5 rounded-xl bg-slate-900/60 border border-slate-800/80 space-y-4">
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-slate-800/60 pb-3">
                  <div className="flex items-center gap-2">
                    <FolderGit2 className="w-4 h-4 text-emerald-400" />
                    <span className="text-sm font-semibold text-white">Workflows ({workflows.length})</span>
                    <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">Phase 2 CRUD</span>
                  </div>
                  <button
                    onClick={() => token && void loadWorkflows(token)}
                    disabled={workflowsLoading}
                    className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs transition disabled:opacity-50"
                  >
                    <RefreshCw className={`w-3 h-3 ${workflowsLoading ? 'animate-spin' : ''}`} />
                    Refresh
                  </button>
                </div>

                {workflowError && (
                  <div className="p-3 rounded-lg bg-rose-500/10 border border-rose-500/20 text-xs text-rose-300 flex items-center gap-2">
                    <AlertCircle className="w-4 h-4 text-rose-400 shrink-0" />
                    <span>{workflowError}</span>
                  </div>
                )}

                {/* Create Workflow Form */}
                <form onSubmit={handleCreateWorkflow} className="grid grid-cols-1 sm:grid-cols-12 gap-3 p-3.5 rounded-lg bg-slate-950/70 border border-slate-800/60">
                  <div className="sm:col-span-4">
                    <input
                      type="text"
                      placeholder="Workflow name"
                      value={wfName}
                      onChange={(e) => setWfName(e.target.value)}
                      maxLength={100}
                      required
                      className="w-full px-3 py-1.5 bg-slate-900 border border-slate-700/80 rounded-lg text-xs text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500"
                    />
                  </div>
                  <div className="sm:col-span-5">
                    <input
                      type="text"
                      placeholder="Description (optional)"
                      value={wfDesc}
                      onChange={(e) => setWfDesc(e.target.value)}
                      maxLength={500}
                      className="w-full px-3 py-1.5 bg-slate-900 border border-slate-700/80 rounded-lg text-xs text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <select
                      value={wfStatus}
                      onChange={(e) => setWfStatus(e.target.value as WorkflowStatus)}
                      className="w-full px-3 py-1.5 bg-slate-900 border border-slate-700/80 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500"
                    >
                      <option value="DRAFT">DRAFT</option>
                      <option value="ACTIVE">ACTIVE</option>
                    </select>
                  </div>
                  <div className="sm:col-span-1">
                    <button
                      type="submit"
                      disabled={creatingWf || !wfName.trim()}
                      className="w-full h-full flex items-center justify-center gap-1 px-3 py-1.5 rounded-lg bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-semibold text-xs transition disabled:opacity-50"
                      title="Create Workflow"
                    >
                      <Plus className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </form>

                {/* Workflow List */}
                <div className="space-y-2">
                  {workflowsLoading && workflows.length === 0 ? (
                    <div className="text-center py-4 text-xs text-slate-500">Loading workflows...</div>
                  ) : workflows.length === 0 ? (
                    <div className="text-center py-4 text-xs text-slate-500">No workflows created yet. Create your first workflow above.</div>
                  ) : (
                    workflows.map((wf) => (
                      <div key={wf.id} className="flex items-center justify-between p-3 rounded-lg bg-slate-950/60 border border-slate-800/60 hover:border-slate-700/80 transition">
                        <div className="space-y-0.5">
                          <div className="flex items-center gap-2">
                            <span className="text-xs font-semibold text-white">{wf.name}</span>
                            <span className={`text-[10px] font-mono px-1.5 py-0.5 rounded ${wf.status === 'ACTIVE' ? 'bg-emerald-500/20 text-emerald-400' : 'bg-slate-800 text-slate-400'}`}>
                              {wf.status}
                            </span>
                          </div>
                          {wf.description && <p className="text-[11px] text-slate-400">{wf.description}</p>}
                          <div className="text-[10px] text-slate-500 font-mono">
                            ID: {wf.id} &bull; Created: {new Date(wf.createdAt).toLocaleDateString()}
                          </div>
                        </div>
                        <button
                          onClick={() => handleDeleteWorkflow(wf.id)}
                          className="p-1.5 rounded-lg text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 transition"
                          title="Delete workflow"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    ))
                  )}
                </div>
              </div>
            </div>
          )}
        </section>

        {/* Phase 0: System Health / API Verification Card */}
        <section className="bg-[#0f172a]/70 border border-slate-800 rounded-2xl p-6 shadow-xl backdrop-blur">
          <div className="flex items-center justify-between mb-4 border-b border-slate-800/80 pb-4">
            <div className="flex items-center gap-2.5">
              <Activity className="w-5 h-5 text-emerald-400" />
              <h2 className="text-lg font-semibold text-white">Backend Health Diagnostic</h2>
              <span className="text-xs text-slate-500 font-mono">GET /api/health</span>
            </div>
            <button
              onClick={fetchHealth}
              disabled={healthLoading}
              className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 transition disabled:opacity-50"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${healthLoading ? 'animate-spin' : ''}`} />
              Test Endpoint
            </button>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="p-4 rounded-xl bg-slate-900/80 border border-slate-800/80">
              <span className="text-xs font-medium text-slate-400 block mb-1">Service Status</span>
              {healthLoading ? (
                <div className="text-sm text-slate-400">Pinging backend...</div>
              ) : health ? (
                <div className="flex items-center gap-2 text-emerald-400 font-semibold text-sm">
                  <CheckCircle2 className="w-4 h-4" />
                  <span>{health.status} (Healthy)</span>
                </div>
              ) : (
                <div className="flex items-center gap-2 text-amber-400 font-semibold text-sm">
                  <XCircle className="w-4 h-4" />
                  <span>Offline / Waiting</span>
                </div>
              )}
            </div>

            <div className="p-4 rounded-xl bg-slate-900/80 border border-slate-800/80">
              <span className="text-xs font-medium text-slate-400 block mb-1">Registered Service</span>
              <div className="text-sm font-mono text-slate-200">
                {health ? health.service : 'com.adonis:adonis-backend'}
              </div>
            </div>

            <div className="p-4 rounded-xl bg-slate-900/80 border border-slate-800/80">
              <span className="text-xs font-medium text-slate-400 block mb-1">Backend Version</span>
              <div className="text-sm font-mono text-slate-200">
                {health ? health.version : '0.0.1-SNAPSHOT (Java 21)'}
              </div>
            </div>
          </div>

          {healthError && (
            <div className="mt-4 p-3 rounded-lg bg-amber-500/10 border border-amber-500/20 text-xs text-amber-300">
              Notice: Backend not yet running on port 8080 ({healthError}). Start the Spring Boot backend using <code className="px-1 py-0.5 bg-slate-800 rounded text-slate-200">./mvnw spring-boot:run</code> to verify live response.
            </div>
          )}
        </section>

        {/* Architecture & Phased Roadmap Preview */}
        <section className="space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-xl font-bold text-white flex items-center gap-2">
              <Layers className="w-5 h-5 text-emerald-400" />
              Architecture &amp; Incremental Roadmap
            </h2>
            <span className="text-xs text-slate-400">Phase 2 complete</span>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
            <div className="p-5 rounded-xl bg-slate-900/50 border border-slate-800/80">
              <div className="flex items-center justify-between mb-3">
                <ShieldCheck className="w-6 h-6 text-emerald-400" />
                <span className="text-[10px] font-bold text-emerald-400 uppercase tracking-wider px-2 py-0.5 rounded bg-emerald-500/20">Done</span>
              </div>
              <h3 className="font-semibold text-white text-sm mb-1">Phase 0: Monorepo Setup</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Java 21 Spring Boot baseline, React Vite TS scaffold, Docker definitions, and GitHub Actions CI.
              </p>
            </div>

            <div className="p-5 rounded-xl bg-slate-900/50 border border-slate-800/80">
              <div className="flex items-center justify-between mb-3">
                <KeyRound className="w-6 h-6 text-emerald-400" />
                <span className="text-[10px] font-bold text-emerald-400 uppercase tracking-wider px-2 py-0.5 rounded bg-emerald-500/20">Done</span>
              </div>
              <h3 className="font-semibold text-white text-sm mb-1">Phase 1: Auth &amp; MongoDB</h3>
              <p className="text-xs text-slate-300 leading-relaxed">
                User management, Spring Security, BCrypt password hashing, stateless JWT tokens, and MongoDB persistence.
              </p>
            </div>

            <div className="p-5 rounded-xl bg-slate-900/50 border border-slate-800/80">
              <div className="flex items-center justify-between mb-3">
                <Cpu className="w-6 h-6 text-emerald-400" />
                <span className="text-[10px] font-bold text-emerald-400 uppercase tracking-wider px-2 py-0.5 rounded bg-emerald-500/20">Done</span>
              </div>
              <h3 className="font-semibold text-white text-sm mb-1">Phase 2: Workflow CRUD</h3>
              <p className="text-xs text-slate-300 leading-relaxed">
                Workflow MongoDB document, user ownership isolation, REST CRUD endpoints, validation, and security tests.
              </p>
            </div>

            <div className="p-5 rounded-xl bg-slate-900/40 border border-slate-800/80 opacity-80">
              <div className="flex items-center justify-between mb-3">
                <Sparkles className="w-6 h-6 text-slate-400" />
                <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider px-2 py-0.5 rounded bg-slate-800">Planned</span>
              </div>
              <h3 className="font-semibold text-slate-300 text-sm mb-1">Phase 3: React Flow Canvas</h3>
              <p className="text-xs text-slate-500 leading-relaxed">
                Visual node graph editor, draggable connectors, node configuration modals, and edge validation.
              </p>
            </div>
          </div>
        </section>
      </main>

      {/* Footer */}
      <footer className="border-t border-slate-800/80 bg-[#0d1322] py-6 text-xs text-slate-500 text-center">
        <div className="max-w-7xl mx-auto px-6 flex flex-col sm:flex-row items-center justify-between gap-4">
          <p>Adonis Workflow Automation Platform &copy; 2026. All rights reserved.</p>
          <div className="flex items-center gap-4">
            <span>Java 21</span>
            <span>&bull;</span>
            <span>Spring Boot 3.3</span>
            <span>&bull;</span>
            <span>MongoDB 7.0</span>
            <span>&bull;</span>
            <span>JWT + BCrypt</span>
          </div>
        </div>
      </footer>
    </div>
  );
};

export default App;
