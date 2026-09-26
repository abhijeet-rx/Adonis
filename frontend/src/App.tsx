import React, { useState, useEffect } from 'react';
import { 
  Activity, 
  Workflow, 
  Layers, 
  Cpu, 
  Sparkles, 
  Terminal, 
  CheckCircle2, 
  XCircle, 
  RefreshCw,
  GitBranch,
  ShieldCheck
} from 'lucide-react';

interface HealthData {
  status: string;
  service: string;
  version: string;
  timestamp: string;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

export const App: React.FC = () => {
  const [health, setHealth] = useState<HealthData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchHealth = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`${API_BASE_URL}/api/health`);
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${res.statusText}`);
      }
      const data: HealthData = await res.json();
      setHealth(data);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to connect to backend';
      setError(message);
      setHealth(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchHealth();
  }, []);

  return (
    <div className="min-h-screen bg-[#090d16] text-slate-100 flex flex-col font-sans selection:bg-emerald-500 selection:text-white">
      {/* Top Navigation */}
      <header className="border-b border-slate-800 bg-[#0d1322]/80 backdrop-blur-md sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-6 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-emerald-500 to-teal-400 flex items-center justify-center shadow-lg shadow-emerald-500/20">
              <Workflow className="w-5 h-5 text-slate-950 font-bold" />
            </div>
            <div>
              <span className="text-xl font-bold tracking-tight bg-gradient-to-r from-white via-slate-200 to-slate-400 bg-clip-text text-transparent">
                Adonis
              </span>
              <span className="ml-2 text-xs font-mono font-medium px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                Phase 0: Initialization
              </span>
            </div>
          </div>

          <div className="flex items-center gap-4 text-sm">
            <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-900 border border-slate-800">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
              <span className="text-xs text-slate-400">Environment: Local Dev</span>
            </div>
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
      <main className="flex-1 max-w-7xl mx-auto px-6 py-12 w-full space-y-12">
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
            Combining Spring Boot 3 &amp; Java 21 reliability with a modern React visual execution workspace.
          </p>
        </section>

        {/* System Health / API Verification Card */}
        <section className="bg-[#0f172a]/70 border border-slate-800 rounded-2xl p-6 shadow-xl backdrop-blur">
          <div className="flex items-center justify-between mb-4 border-b border-slate-800/80 pb-4">
            <div className="flex items-center gap-2.5">
              <Activity className="w-5 h-5 text-emerald-400" />
              <h2 className="text-lg font-semibold text-white">Backend Health Diagnostic</h2>
              <span className="text-xs text-slate-500 font-mono">GET /api/health</span>
            </div>
            <button
              onClick={fetchHealth}
              disabled={loading}
              className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 transition disabled:opacity-50"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
              Test Endpoint
            </button>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="p-4 rounded-xl bg-slate-900/80 border border-slate-800/80">
              <span className="text-xs font-medium text-slate-400 block mb-1">Service Status</span>
              {loading ? (
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

          {error && (
            <div className="mt-4 p-3 rounded-lg bg-amber-500/10 border border-amber-500/20 text-xs text-amber-300">
              Notice: Backend not yet running on port 8080 ({error}). Start the Spring Boot backend using <code className="px-1 py-0.5 bg-slate-800 rounded text-slate-200">./mvnw spring-boot:run</code> to verify live response.
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
            <span className="text-xs text-slate-400">Current: Phase 0 Completed</span>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
            <div className="p-5 rounded-xl bg-slate-900/50 border border-emerald-500/30 relative overflow-hidden">
              <div className="absolute top-2 right-2 px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-500/20 text-emerald-400 uppercase tracking-wider">
                Active
              </div>
              <ShieldCheck className="w-6 h-6 text-emerald-400 mb-3" />
              <h3 className="font-semibold text-white text-sm mb-1">Phase 0: Monorepo Setup</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Java 21 Spring Boot baseline, React Vite TS scaffold, Docker definitions, and GitHub Actions CI.
              </p>
            </div>

            <div className="p-5 rounded-xl bg-slate-900/40 border border-slate-800/80 opacity-80">
              <Workflow className="w-6 h-6 text-slate-400 mb-3" />
              <h3 className="font-semibold text-slate-300 text-sm mb-1">Phase 1: Auth &amp; MongoDB</h3>
              <p className="text-xs text-slate-500 leading-relaxed">
                User management, Spring Security, JWT token lifecycle, and persistence in MongoDB.
              </p>
            </div>

            <div className="p-5 rounded-xl bg-slate-900/40 border border-slate-800/80 opacity-80">
              <Cpu className="w-6 h-6 text-slate-400 mb-3" />
              <h3 className="font-semibold text-slate-300 text-sm mb-1">Phase 2: Workflow CRUD</h3>
              <p className="text-xs text-slate-500 leading-relaxed">
                Workflow definition schemas, REST endpoints for creation, editing, and querying.
              </p>
            </div>

            <div className="p-5 rounded-xl bg-slate-900/40 border border-slate-800/80 opacity-80">
              <Sparkles className="w-6 h-6 text-slate-400 mb-3" />
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
            <span>React + Vite</span>
            <span>&bull;</span>
            <span>Tailwind CSS</span>
          </div>
        </div>
      </footer>
    </div>
  );
};

export default App;
