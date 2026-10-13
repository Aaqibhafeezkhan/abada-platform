import React, { useCallback, useEffect, useState } from 'react';
import { History, Loader2, Play, Square } from 'lucide-react';
import { InsightAPI, ReplayCase, ReplayRun, ReplayStep } from '@/api/insight';

const POLL_MS = 3000;

const STATE_LABEL: Record<ReplayCase['state'], string> = {
  PENDING: 'Waiting', RUNNING: 'Running', DONE: 'Replayed', NEEDS_HUMAN: 'Needs a reviewer',
  NEEDS_EXTERNAL: 'Needs a worker or event', SKIPPED: 'Skipped', FAILED: 'Failed',
};

const describe = (step?: ReplayStep): string =>
  step ? `${step.activityId} ${step.kind.toLowerCase()} ${step.outcome}` : 'nothing more';

const money = (value?: number, unpriced = false): string =>
  value === undefined ? '—' : `$${value.toFixed(4)}${unpriced ? ' + unpriced calls' : ''}`;

interface ReplayPanelProps {
  projectId: string;
  proposalId: number;
  /** A proposal that is no longer under review cannot be replayed. */
  open: boolean;
  /** Project reviewer, maintainer or owner; the engine still enforces it. */
  canRun: boolean;
  api?: Pick<typeof InsightAPI, 'listReplays' | 'planReplay' | 'getReplay' | 'startReplay' | 'cancelReplay'>;
}

/**
 * Replay of a proposal on the last recorded cases. It shows what the proposed definition would have done on the
 * same inputs: evidence for the reviewer, not a verdict. Nothing is deployed and no instance is touched.
 */
export const ReplayPanel: React.FC<ReplayPanelProps> = ({ projectId, proposalId, open, canRun, api = InsightAPI }) => {
  const [run, setRun] = useState<ReplayRun | null>(null);
  const [cases, setCases] = useState('20');
  const [budget, setBudget] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const runs = await api.listReplays(projectId, proposalId);
      setRun(runs.length ? await api.getReplay(projectId, proposalId, runs[0].id) : null);
      setError(null);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Replays could not be loaded');
    }
  }, [api, projectId, proposalId]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (run?.status !== 'RUNNING') return undefined;
    const timer = setInterval(() => { void load(); }, POLL_MS);
    return () => clearInterval(timer);
  }, [run?.status, load]);

  const act = async (action: () => Promise<ReplayRun>) => {
    setBusy(true);
    try {
      setRun(await action());
      setError(null);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'The replay failed');
    } finally {
      setBusy(false);
    }
  };

  const finished = run && !['PLANNED', 'RUNNING'].includes(run.status);
  const aggregate = run?.aggregate;

  return (
    <div className="space-y-3 rounded-xl border border-[#3A322E] bg-[#25201D] p-4">
      <h4 className="flex items-center gap-2 text-[10px] uppercase tracking-wider text-[#A89F91]">
        <History className="h-3.5 w-3.5 text-[#F4A261]" /> Replay on recorded cases
      </h4>
      <p className="text-[11px] text-[#A89F91]">
        Runs this proposal on the inputs of past cases, one model call per agent step, and shows what it would have
        done differently. It is evidence, not a verdict; no instance is changed and nothing is deployed.
      </p>
      {error && <p role="alert" className="text-[11px] text-[#E76F51]">{error}</p>}

      {open && canRun && (!run || finished) && (
        <div className="flex flex-wrap items-end gap-2">
          <label className="text-[10px] text-[#A89F91]">Cases
            <input aria-label="Cases" type="number" min={1} max={100} value={cases}
              onChange={(event) => setCases(event.target.value)}
              className="ml-2 w-16 rounded-lg border border-[#3A322E] bg-[#1A1614] px-2 py-1 text-xs text-[#EAE3D9]" />
          </label>
          <label className="text-[10px] text-[#A89F91]">Budget (USD, optional)
            <input aria-label="Budget" type="number" min={0} step="0.01" value={budget}
              onChange={(event) => setBudget(event.target.value)}
              className="ml-2 w-20 rounded-lg border border-[#3A322E] bg-[#1A1614] px-2 py-1 text-xs text-[#EAE3D9]" />
          </label>
          <button type="button" disabled={busy}
            onClick={() => void act(() => api.planReplay(projectId, proposalId, Number(cases) || undefined,
              budget.trim() === '' ? undefined : Number(budget)))}
            className="rounded-lg bg-[#9D4EDD] px-3 py-1.5 text-xs font-medium text-white disabled:opacity-50">
            Plan replay
          </button>
        </div>
      )}

      {run?.status === 'PLANNED' && (
        <div className="space-y-2 text-xs text-[#EAE3D9]">
          <p>
            {run.cases?.length ?? 0} cases selected. Estimated cost {money(run.estimatedCostUsd, run.estimateUnpriced)},
            from what these cases cost originally.
            {run.withoutInputs > 0 && ` ${run.withoutInputs} finished case${run.withoutInputs === 1 ? '' : 's'} cannot be replayed: their inputs were not kept.`}
          </p>
          <div className="flex gap-2">
            <button type="button" disabled={busy || !canRun || !(run.cases?.length)} onClick={() => void act(() => api.startReplay(projectId, proposalId, run.id))}
              className="inline-flex items-center gap-1 rounded-lg bg-[#2A9D8F] px-3 py-1.5 text-xs font-medium text-[#1A1614] disabled:opacity-50">
              <Play className="h-3 w-3" /> Start replay
            </button>
            <button type="button" disabled={busy || !canRun} onClick={() => void act(() => api.cancelReplay(projectId, proposalId, run.id))}
              className="text-xs text-[#A89F91] hover:underline">Cancel</button>
          </div>
        </div>
      )}

      {run?.status === 'RUNNING' && (
        <div className="flex items-center gap-3 text-xs text-[#EAE3D9]">
          <Loader2 className="h-3.5 w-3.5 animate-spin text-[#9D4EDD]" />
          Replaying… spent {money(run.costUsd, run.costUnpriced)}{run.budgetUsd !== undefined && ` of ${money(run.budgetUsd)}`}
          {canRun && (
            <button type="button" disabled={busy} onClick={() => void act(() => api.cancelReplay(projectId, proposalId, run.id))}
              className="ml-auto inline-flex items-center gap-1 text-[#E76F51] hover:underline">
              <Square className="h-3 w-3" /> Cancel
            </button>
          )}
        </div>
      )}

      {run && finished && aggregate && (
        <div className="space-y-2 text-xs text-[#EAE3D9]">
          <p className="font-semibold">
            {run.status === 'PARTIAL' ? 'Stopped at the budget. ' : run.status === 'CANCELLED' ? 'Cancelled. ' : ''}
            Of {aggregate.cases} cases, the proposal would have behaved differently on {aggregate.changed}
            {' '}and the same on {aggregate.unchanged}.
          </p>
          <ul className="list-inside list-disc text-[11px] text-[#A89F91]">
            {aggregate.needsHuman > 0 && <li>{aggregate.needsHuman} stopped where a reviewer would have to decide (no recorded decision applies).</li>}
            {aggregate.needsExternal > 0 && <li>{aggregate.needsExternal} stopped at a step only a worker, timer or event can run.</li>}
            {aggregate.failed > 0 && <li>{aggregate.failed} failed.</li>}
            {aggregate.skipped > 0 && <li>{aggregate.skipped} skipped.</li>}
            {aggregate.originallyOverridden > 0 && (
              <li>{aggregate.originallyOverridden} were overruled by a reviewer originally; {aggregate.overriddenNowDiffering} would now take a different path.</li>
            )}
            <li>Cost {money(run.costUsd, run.costUnpriced)}.</li>
          </ul>
          <ul className="space-y-1">
            {(run.cases ?? []).map((item) => (
              <li key={item.id} className="rounded-lg border border-[#3A322E] px-3 py-2 text-[11px]">
                <span className="font-mono">{item.originalInstanceId.slice(0, 8)}</span>
                <span className="ml-2">{STATE_LABEL[item.state]}</span>
                {item.changed && item.diff?.firstDivergence && (
                  <span className="ml-2 text-[#F4A261]">
                    differs at step {item.diff.firstDivergence.position + 1}: {describe(item.diff.firstDivergence.original)}
                    {' → '}{describe(item.diff.firstDivergence.replayed)}
                  </span>
                )}
                {item.changed === false && <span className="ml-2 text-[#A89F91]">same path</span>}
                {item.reason && <span className="ml-2 text-[#A89F91]">{item.reason}</span>}
                {item.inputsRedacted && <span className="ml-2 text-[#A89F91]">inputs were masked</span>}
                {item.toolsSkipped.length > 0 && (
                  <span className="ml-2 text-[#A89F91]">tools not run at {item.toolsSkipped.join(', ')}</span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
};
