import React, { useState } from 'react';
import type { Node } from '@xyflow/react';
import {
  X,
  Trash2,
  Check,
  Sliders,
  Globe,
  Zap,
  Box,
  RotateCcw,
  Copy,
  Calendar,
  Clock,
  RefreshCw,
  ExternalLink,
  Shield
} from 'lucide-react';
import { NODE_TYPES, type CustomNodeData } from './workflowAdapter';
import {
  API_BASE_URL,
  workflowApi,
  type Workflow,
  type WorkflowTriggerType,
  type WorkflowTriggerConfigRequest
} from '../../services/workflowService';

interface NodeConfigPanelProps {
  selectedNode: Node<CustomNodeData>;
  workflow?: Workflow | null;
  token?: string;
  onUpdateNodeData: (nodeId: string, newData: CustomNodeData) => void;
  onDeleteNode: (nodeId: string) => void;
  onClose: () => void;
  onUpdateWorkflowTrigger?: (
    triggerType: WorkflowTriggerType,
    triggerConfig: WorkflowTriggerConfigRequest
  ) => void;
  onWorkflowUpdated?: (updatedWorkflow: Workflow) => void;
}

const COMMON_TIMEZONES = [
  'UTC',
  'Asia/Kolkata',
  'America/New_York',
  'America/Los_Angeles',
  'America/Chicago',
  'Europe/London',
  'Europe/Paris',
  'Europe/Berlin',
  'Asia/Tokyo',
  'Asia/Singapore',
  'Asia/Dubai',
  'Australia/Sydney'
];

const CRON_PRESETS = [
  { label: 'Every 5m', cron: '0 */5 * * * *' },
  { label: 'Every 15m', cron: '0 */15 * * * *' },
  { label: 'Hourly', cron: '0 0 * * * *' },
  { label: 'Daily (Midnight)', cron: '0 0 0 * * *' },
  { label: 'Weekdays 9 AM', cron: '0 0 9 * * MON-FRI' }
];

export const NodeConfigPanel: React.FC<NodeConfigPanelProps> = ({
  selectedNode,
  workflow,
  token,
  onUpdateNodeData,
  onDeleteNode,
  onClose,
  onUpdateWorkflowTrigger,
  onWorkflowUpdated
}) => {
  const nodeData = selectedNode.data || {};
  const [label, setLabel] = useState<string>(
    typeof nodeData.label === 'string' ? nodeData.label : ''
  );
  const [description, setDescription] = useState<string>(
    typeof nodeData.description === 'string' ? nodeData.description : ''
  );
  const [method, setMethod] = useState<'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'>(
    (nodeData.method as 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH') || 'GET'
  );
  const [url, setUrl] = useState<string>(
    typeof nodeData.url === 'string' ? nodeData.url : 'https://api.example.com'
  );

  // Trigger Configuration State
  const initialTriggerType: WorkflowTriggerType =
    workflow?.triggerType ||
    (typeof nodeData.triggerType === 'string'
      ? (nodeData.triggerType.toUpperCase() as WorkflowTriggerType)
      : 'MANUAL');

  const [triggerType, setTriggerType] = useState<WorkflowTriggerType>(
    initialTriggerType === 'SCHEDULE' || initialTriggerType === 'WEBHOOK'
      ? initialTriggerType
      : 'MANUAL'
  );

  const [cronExpression, setCronExpression] = useState<string>(
    workflow?.triggerConfig?.cronExpression ||
      (typeof nodeData.cronExpression === 'string' ? nodeData.cronExpression : '0 */5 * * * *')
  );

  const [timezone, setTimezone] = useState<string>(
    workflow?.triggerConfig?.timezone ||
      (typeof nodeData.timezone === 'string' ? nodeData.timezone : 'UTC')
  );

  const [webhookPath, setWebhookPath] = useState<string>(
    workflow?.triggerConfig?.webhookPath ||
      (typeof nodeData.webhookPath === 'string' ? nodeData.webhookPath : '')
  );

  const [secretMasked, setSecretMasked] = useState<string>(
    workflow?.triggerConfig?.secretMasked || '********'
  );

  const [rawSecret, setRawSecret] = useState<string | null>(null);
  const [isRegenerating, setIsRegenerating] = useState(false);
  const [regenerateError, setRegenerateError] = useState<string | null>(null);
  const [regenerateSuccess, setRegenerateSuccess] = useState<string | null>(null);
  const [copiedUrl, setCopiedUrl] = useState(false);
  const [copiedSecret, setCopiedSecret] = useState(false);

  // Retry state
  const rawRetry = (nodeData.retry || nodeData.retryConfig || {}) as Partial<Record<string, unknown>>;
  const [retryEnabled, setRetryEnabled] = useState<boolean>(
    Boolean(rawRetry.enabled ?? nodeData.retryEnabled ?? false)
  );
  const [maxRetries, setMaxRetries] = useState<number>(
    Number(rawRetry.maxRetries ?? nodeData.maxRetries ?? 0)
  );
  const [initialBackoffMs, setInitialBackoffMs] = useState<number>(
    Number(rawRetry.initialBackoffMs ?? nodeData.retryBackoff ?? 1000)
  );
  const [backoffMultiplier, setBackoffMultiplier] = useState<number>(
    Number(rawRetry.backoffMultiplier ?? nodeData.retryBackoffMultiplier ?? 2.0)
  );
  const [maxBackoffMs, setMaxBackoffMs] = useState<number>(
    Number(rawRetry.maxBackoffMs ?? nodeData.maxBackoffMs ?? 30000)
  );

  const [isSavedRecently, setIsSavedRecently] = useState(false);

  const effectiveWebhookPath = webhookPath || workflow?.triggerConfig?.webhookPath || '';
  const fullWebhookUrl = effectiveWebhookPath
    ? `${API_BASE_URL}/api/webhooks/${effectiveWebhookPath}`
    : '';

  const handleCopyWebhookUrl = () => {
    if (!fullWebhookUrl) return;
    void navigator.clipboard.writeText(fullWebhookUrl);
    setCopiedUrl(true);
    setTimeout(() => setCopiedUrl(false), 2000);
  };

  const handleCopySecret = () => {
    if (!rawSecret) return;
    void navigator.clipboard.writeText(rawSecret);
    setCopiedSecret(true);
    setTimeout(() => setCopiedSecret(false), 2000);
  };

  const handleRegenerateSecret = async () => {
    if (!workflow || !token) return;
    setIsRegenerating(true);
    setRegenerateError(null);
    setRegenerateSuccess(null);
    try {
      const res = await workflowApi.regenerateWebhook(workflow.id, token);
      setWebhookPath(res.webhookPath);
      setRawSecret(res.rawSecret);
      setSecretMasked(res.maskedSecret);
      setRegenerateSuccess('New secret generated! Copy it now—it will not be shown again.');
      if (onWorkflowUpdated) {
        onWorkflowUpdated({
          ...workflow,
          triggerType: 'WEBHOOK',
          triggerConfig: {
            ...workflow.triggerConfig,
            webhookPath: res.webhookPath,
            secretMasked: res.maskedSecret,
            secretConfigured: true
          }
        });
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to regenerate secret';
      setRegenerateError(msg);
    } finally {
      setIsRegenerating(false);
    }
  };

  const handleApply = (e: React.FormEvent) => {
    e.preventDefault();
    const cleanRetry = {
      enabled: retryEnabled,
      maxRetries: Math.max(0, Math.min(10, Number(maxRetries) || 0)),
      initialBackoffMs: Math.max(0, Math.min(60000, Number(initialBackoffMs) || 1000)),
      backoffMultiplier: Math.max(0.1, Number(backoffMultiplier) || 2.0),
      maxBackoffMs: Math.max(0, Math.min(60000, Number(maxBackoffMs) || 30000))
    };

    const isTrigger = selectedNode.type === NODE_TYPES.TRIGGER;

    const updatedData: CustomNodeData = {
      ...selectedNode.data,
      label: label.trim() || (isTrigger ? `${triggerType} Trigger` : 'Untitled Node'),
      description: description.trim(),
      method,
      url: url.trim(),
      triggerType: isTrigger ? triggerType : undefined,
      cronExpression: isTrigger && triggerType === 'SCHEDULE' ? cronExpression : undefined,
      timezone: isTrigger && triggerType === 'SCHEDULE' ? timezone : undefined,
      retry: cleanRetry,
      retryConfig: cleanRetry
    };

    onUpdateNodeData(selectedNode.id, updatedData);

    if (isTrigger && onUpdateWorkflowTrigger) {
      onUpdateWorkflowTrigger(triggerType, {
        cronExpression: triggerType === 'SCHEDULE' ? cronExpression : undefined,
        timezone: triggerType === 'SCHEDULE' ? timezone : undefined
      });
    }

    setIsSavedRecently(true);
    setTimeout(() => setIsSavedRecently(false), 2000);
  };

  const getNodeIcon = () => {
    switch (selectedNode.type) {
      case NODE_TYPES.TRIGGER:
        return <Zap className="w-4 h-4 text-amber-400" />;
      case NODE_TYPES.HTTP_REQUEST:
        return <Globe className="w-4 h-4 text-emerald-400" />;
      default:
        return <Box className="w-4 h-4 text-indigo-400" />;
    }
  };

  const getNodeTypeName = () => {
    switch (selectedNode.type) {
      case NODE_TYPES.TRIGGER:
        return 'Trigger Configuration';
      case NODE_TYPES.HTTP_REQUEST:
        return 'HTTP Request Configuration';
      default:
        return 'Custom Node Configuration';
    }
  };

  return (
    <aside className="w-84 lg:w-96 bg-[#0c1220] border-l border-slate-800 flex flex-col h-full shrink-0 shadow-2xl z-20 overflow-hidden">
      {/* Panel Header */}
      <div className="p-4 border-b border-slate-800/80 flex items-center justify-between shrink-0">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-lg bg-slate-800 flex items-center justify-center border border-slate-700/60">
            {getNodeIcon()}
          </div>
          <div>
            <h3 className="text-xs font-bold text-white tracking-tight">{getNodeTypeName()}</h3>
            <span className="text-[10px] font-mono text-slate-500">ID: {selectedNode.id}</span>
          </div>
        </div>
        <button
          onClick={onClose}
          className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition"
          title="Close configuration"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      {/* Form Content */}
      <form onSubmit={handleApply} className="p-4 space-y-4 flex-1 overflow-y-auto">
        {/* Node Label */}
        <div className="space-y-1.5">
          <label className="text-xs font-medium text-slate-300 block">Label</label>
          <input
            type="text"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="Node display name"
            className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-xs text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 transition"
            maxLength={100}
          />
        </div>

        {/* Trigger specific fields (Phase 8) */}
        {selectedNode.type === NODE_TYPES.TRIGGER && (
          <div className="space-y-4 pt-1">
            <div className="space-y-1.5">
              <label className="text-xs font-semibold text-slate-200 block">Workflow Trigger Type</label>
              <div className="grid grid-cols-3 gap-1.5 p-1 bg-slate-950/80 border border-slate-800 rounded-lg text-xs">
                <button
                  type="button"
                  onClick={() => setTriggerType('MANUAL')}
                  className={`py-1.5 px-2 rounded-md font-medium text-center transition flex flex-col items-center gap-1 ${
                    triggerType === 'MANUAL'
                      ? 'bg-sky-500 text-slate-950 shadow font-semibold'
                      : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
                  }`}
                >
                  <Zap className="w-3.5 h-3.5" />
                  <span>Manual</span>
                </button>
                <button
                  type="button"
                  onClick={() => setTriggerType('SCHEDULE')}
                  className={`py-1.5 px-2 rounded-md font-medium text-center transition flex flex-col items-center gap-1 ${
                    triggerType === 'SCHEDULE'
                      ? 'bg-purple-500 text-slate-950 shadow font-semibold'
                      : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
                  }`}
                >
                  <Calendar className="w-3.5 h-3.5" />
                  <span>Schedule</span>
                </button>
                <button
                  type="button"
                  onClick={() => setTriggerType('WEBHOOK')}
                  className={`py-1.5 px-2 rounded-md font-medium text-center transition flex flex-col items-center gap-1 ${
                    triggerType === 'WEBHOOK'
                      ? 'bg-amber-500 text-slate-950 shadow font-semibold'
                      : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
                  }`}
                >
                  <ExternalLink className="w-3.5 h-3.5" />
                  <span>Webhook</span>
                </button>
              </div>
            </div>

            {/* MANUAL Info */}
            {triggerType === 'MANUAL' && (
              <div className="p-3 bg-slate-900/60 border border-slate-800 rounded-lg text-xs text-slate-400 space-y-1 leading-relaxed">
                <div className="flex items-center gap-1.5 text-sky-400 font-semibold">
                  <Zap className="w-3.5 h-3.5" />
                  <span>On-Demand Trigger</span>
                </div>
                <p>
                  Workflow is initiated manually by authenticated users via the UI &quot;Run Workflow&quot;
                  button or the <code className="text-slate-300 font-mono">POST /api/workflows/:id/execute</code> endpoint.
                </p>
              </div>
            )}

            {/* SCHEDULE Configuration */}
            {triggerType === 'SCHEDULE' && (
              <div className="p-3 bg-purple-950/20 border border-purple-900/40 rounded-lg space-y-3 text-xs">
                <div className="flex items-center gap-1.5 text-purple-300 font-semibold">
                  <Clock className="w-3.5 h-3.5" />
                  <span>Cron Schedule Settings</span>
                </div>

                <div className="space-y-1">
                  <label className="text-[11px] font-medium text-slate-300 flex justify-between items-center">
                    <span>Cron Expression</span>
                    <span className="text-[10px] text-slate-500">Spring 5/6-field</span>
                  </label>
                  <input
                    type="text"
                    value={cronExpression}
                    onChange={(e) => setCronExpression(e.target.value)}
                    placeholder="0 */5 * * * *"
                    className="w-full px-2.5 py-1.5 bg-slate-950 border border-slate-700 rounded text-xs text-white focus:outline-none focus:border-purple-500 font-mono"
                  />
                  <div className="flex flex-wrap gap-1 pt-1">
                    {CRON_PRESETS.map((preset) => (
                      <button
                        key={preset.label}
                        type="button"
                        onClick={() => setCronExpression(preset.cron)}
                        className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700 font-mono transition"
                      >
                        {preset.label}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="space-y-1">
                  <label className="text-[11px] font-medium text-slate-300 block">Timezone</label>
                  <select
                    value={timezone}
                    onChange={(e) => setTimezone(e.target.value)}
                    className="w-full px-2.5 py-1.5 bg-slate-950 border border-slate-700 rounded text-xs text-white focus:outline-none focus:border-purple-500 font-mono"
                  >
                    {COMMON_TIMEZONES.map((tz) => (
                      <option key={tz} value={tz}>
                        {tz}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="pt-1 text-[11px] text-slate-400 space-y-1 border-t border-purple-900/30">
                  <div className="flex justify-between">
                    <span className="text-slate-500">Misfire Policy:</span>
                    <span className="font-mono text-purple-300">DO_NOT_CATCH_UP</span>
                  </div>
                  {workflow?.triggerConfig?.nextFireTime && (
                    <div className="flex justify-between">
                      <span className="text-slate-500">Next fire:</span>
                      <span className="font-mono text-slate-300 truncate max-w-[170px]">
                        {workflow.triggerConfig.nextFireTime}
                      </span>
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* WEBHOOK Configuration */}
            {triggerType === 'WEBHOOK' && (
              <div className="p-3 bg-amber-950/20 border border-amber-900/40 rounded-lg space-y-3 text-xs">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5 text-amber-300 font-semibold">
                    <ExternalLink className="w-3.5 h-3.5" />
                    <span>Webhook Capability URL</span>
                  </div>
                  <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/20">
                    Async 202
                  </span>
                </div>

                {/* Webhook URL with copy */}
                <div className="space-y-1">
                  <label className="text-[11px] font-medium text-slate-300 block">Trigger Endpoint</label>
                  <div className="flex items-center gap-1">
                    <input
                      type="text"
                      readOnly
                      value={fullWebhookUrl || 'Save workflow to generate webhook URL'}
                      className="w-full px-2 py-1.5 bg-slate-950 border border-slate-700 rounded text-[11px] text-slate-300 font-mono truncate focus:outline-none"
                    />
                    <button
                      type="button"
                      onClick={handleCopyWebhookUrl}
                      disabled={!fullWebhookUrl}
                      className="p-1.5 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 disabled:opacity-50 transition shrink-0"
                      title="Copy webhook URL"
                    >
                      {copiedUrl ? (
                        <Check className="w-3.5 h-3.5 text-emerald-400" />
                      ) : (
                        <Copy className="w-3.5 h-3.5" />
                      )}
                    </button>
                  </div>
                </div>

                {/* Secret Status & Display */}
                <div className="space-y-1.5 pt-1 border-t border-amber-900/30">
                  <div className="flex items-center justify-between text-[11px]">
                    <span className="font-medium text-slate-300 flex items-center gap-1">
                      <Shield className="w-3 h-3 text-amber-400" />
                      Secret Protection
                    </span>
                    <span className="font-mono text-slate-400">{secretMasked}</span>
                  </div>

                  {rawSecret && (
                    <div className="p-2 rounded bg-amber-900/40 border border-amber-700/60 text-[11px] space-y-1">
                      <div className="flex items-center justify-between text-amber-200 font-medium">
                        <span>New Secret (Copy Now!):</span>
                        <button
                          type="button"
                          onClick={handleCopySecret}
                          className="flex items-center gap-1 px-1.5 py-0.5 rounded bg-amber-800 text-white text-[10px] hover:bg-amber-700"
                        >
                          {copiedSecret ? (
                            <>
                              <Check className="w-3 h-3 text-emerald-300" />
                              Copied
                            </>
                          ) : (
                            <>
                              <Copy className="w-3 h-3" />
                              Copy
                            </>
                          )}
                        </button>
                      </div>
                      <div className="font-mono text-[11px] text-amber-100 break-all select-all bg-slate-950/80 p-1 rounded">
                        {rawSecret}
                      </div>
                    </div>
                  )}

                  {regenerateSuccess && !rawSecret && (
                    <p className="text-[10px] text-emerald-400">{regenerateSuccess}</p>
                  )}
                  {regenerateError && (
                    <p className="text-[10px] text-rose-400">{regenerateError}</p>
                  )}

                  <button
                    type="button"
                    onClick={() => void handleRegenerateSecret()}
                    disabled={isRegenerating || !workflow?.id}
                    className="w-full py-1.5 px-2 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs flex items-center justify-center gap-1.5 transition border border-slate-700 disabled:opacity-50"
                  >
                    <RefreshCw className={`w-3 h-3 ${isRegenerating ? 'animate-spin' : ''}`} />
                    <span>{isRegenerating ? 'Regenerating...' : 'Regenerate Secret'}</span>
                  </button>
                </div>

                <div className="text-[10px] text-slate-400 space-y-1 border-t border-amber-900/30 pt-1 leading-relaxed">
                  <p>
                    <span className="text-slate-300 font-medium">Secret Header:</span> Send{' '}
                    <code className="text-amber-300 font-mono">X-Webhook-Secret: &lt;secret&gt;</code>
                  </p>
                  <p>
                    <span className="text-slate-300 font-medium">Deduplication:</span> Optional{' '}
                    <code className="text-amber-300 font-mono">Idempotency-Key: &lt;unique-id&gt;</code>
                  </p>
                  <p className="text-slate-500 italic">
                    Webhook payload is verified, redacted for sensitive tokens, and queued via Redis Streams.
                  </p>
                </div>
              </div>
            )}
          </div>
        )}

        {/* HTTP Request specific fields */}
        {selectedNode.type === NODE_TYPES.HTTP_REQUEST && (
          <>
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-slate-300 block">HTTP Method</label>
              <select
                value={method}
                onChange={(e) => setMethod(e.target.value as 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH')}
                className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-xs text-white focus:outline-none focus:border-emerald-500 transition"
              >
                <option value="GET">GET</option>
                <option value="POST">POST</option>
                <option value="PUT">PUT</option>
                <option value="DELETE">DELETE</option>
                <option value="PATCH">PATCH</option>
              </select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-slate-300 block">Endpoint URL</label>
              <input
                type="text"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder="https://api.example.com/v1/resource"
                className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-xs text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 font-mono transition"
                maxLength={500}
              />
            </div>
          </>
        )}

        {/* Retry Configuration (for executable nodes) */}
        {selectedNode.type !== NODE_TYPES.TRIGGER && (
          <div className="p-3 rounded-lg bg-slate-900/80 border border-slate-800 space-y-3">
            <div className="flex items-center justify-between">
              <label className="text-xs font-semibold text-slate-200 flex items-center gap-1.5 cursor-pointer">
                <RotateCcw className="w-3.5 h-3.5 text-sky-400" />
                Retry Policy
              </label>
              <label className="relative inline-flex items-center cursor-pointer">
                <input
                  type="checkbox"
                  checked={retryEnabled}
                  onChange={(e) => setRetryEnabled(e.target.checked)}
                  className="sr-only peer"
                />
                <div className="w-8 h-4 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-3 after:w-3 after:transition-all peer-checked:bg-emerald-500"></div>
                <span className="ml-2 text-[11px] text-slate-400 font-medium">
                  {retryEnabled ? 'Enabled' : 'Disabled'}
                </span>
              </label>
            </div>

            {retryEnabled && (
              <div className="space-y-2 pt-1 border-t border-slate-800/80 text-xs">
                <div className="space-y-1">
                  <div className="flex justify-between items-center text-slate-400 text-[11px]">
                    <span>Max Retries</span>
                    <span className="font-mono text-slate-300">{maxRetries} after initial</span>
                  </div>
                  <input
                    type="number"
                    min={0}
                    max={10}
                    value={maxRetries}
                    onChange={(e) => setMaxRetries(Math.max(0, Math.min(10, parseInt(e.target.value) || 0)))}
                    className="w-full px-2.5 py-1.5 bg-slate-950 border border-slate-700 rounded text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
                  />
                </div>

                <div className="space-y-1">
                  <div className="flex justify-between items-center text-slate-400 text-[11px]">
                    <span>Initial Backoff</span>
                    <span className="font-mono text-slate-300">{initialBackoffMs} ms</span>
                  </div>
                  <input
                    type="number"
                    min={0}
                    step={100}
                    max={60000}
                    value={initialBackoffMs}
                    onChange={(e) => setInitialBackoffMs(Math.max(0, parseInt(e.target.value) || 0))}
                    className="w-full px-2.5 py-1.5 bg-slate-950 border border-slate-700 rounded text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
                  />
                </div>

                <div className="space-y-1">
                  <div className="flex justify-between items-center text-slate-400 text-[11px]">
                    <span>Backoff Multiplier</span>
                    <span className="font-mono text-slate-300">{backoffMultiplier}x</span>
                  </div>
                  <input
                    type="number"
                    min={1}
                    step={0.5}
                    max={10}
                    value={backoffMultiplier}
                    onChange={(e) => setBackoffMultiplier(Math.max(1, parseFloat(e.target.value) || 2.0))}
                    className="w-full px-2.5 py-1.5 bg-slate-950 border border-slate-700 rounded text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
                  />
                </div>

                <div className="space-y-1">
                  <div className="flex justify-between items-center text-slate-400 text-[11px]">
                    <span>Maximum Backoff</span>
                    <span className="font-mono text-slate-300">{maxBackoffMs} ms</span>
                  </div>
                  <input
                    type="number"
                    min={0}
                    step={1000}
                    max={60000}
                    value={maxBackoffMs}
                    onChange={(e) => setMaxBackoffMs(Math.max(0, Math.min(60000, parseInt(e.target.value) || 0)))}
                    className="w-full px-2.5 py-1.5 bg-slate-950 border border-slate-700 rounded text-xs text-white focus:outline-none focus:border-emerald-500 font-mono"
                  />
                </div>
              </div>
            )}
          </div>
        )}

        {/* Description / Notes */}
        <div className="space-y-1.5">
          <label className="text-xs font-medium text-slate-300 block">Description / Notes</label>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            placeholder="Describe the purpose or configuration of this node..."
            className="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-xs text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500 transition resize-none"
            maxLength={300}
          />
        </div>

        {/* Position coordinates (read-only for clarity) */}
        <div className="p-3 rounded-lg bg-slate-950/60 border border-slate-800 text-[11px] font-mono text-slate-400 space-y-1">
          <div className="text-slate-500 font-sans text-[10px] uppercase tracking-wider">Canvas Coordinates</div>
          <div className="flex justify-between">
            <span>X: {Math.round(selectedNode.position.x)}px</span>
            <span>Y: {Math.round(selectedNode.position.y)}px</span>
          </div>
        </div>

        {/* Action Buttons */}
        <div className="pt-2 space-y-2">
          <button
            type="submit"
            className="w-full py-2 px-3 rounded-lg bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-semibold text-xs flex items-center justify-center gap-1.5 transition shadow-sm"
          >
            {isSavedRecently ? (
              <>
                <Check className="w-3.5 h-3.5" />
                Updated!
              </>
            ) : (
              <>
                <Sliders className="w-3.5 h-3.5" />
                Apply Changes
              </>
            )}
          </button>

          <button
            type="button"
            onClick={() => onDeleteNode(selectedNode.id)}
            className="w-full py-2 px-3 rounded-lg bg-slate-800 hover:bg-rose-950/40 text-slate-400 hover:text-rose-300 border border-slate-700 hover:border-rose-800/60 text-xs flex items-center justify-center gap-1.5 transition"
          >
            <Trash2 className="w-3.5 h-3.5" />
            Delete Node
          </button>
        </div>
      </form>
    </aside>
  );
};
