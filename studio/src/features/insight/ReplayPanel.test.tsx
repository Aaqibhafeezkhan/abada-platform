import React, { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReplayRun } from '@/api/insight';
import { buttons, byLabel, click, render, type } from '@/test/render';
import { ReplayPanel } from './ReplayPanel';

vi.mock('@/api/insight', () => ({ InsightAPI: {} }));

const base: ReplayRun = {
  id: 'run-1', proposalId: 7, status: 'PLANNED', requestedCases: 20, withoutInputs: 2, estimateUnpriced: false,
  estimatedCostUsd: 0.12, costUsd: 0, costUnpriced: false, requestedBy: 'alice', createdAt: '2026-12-01T10:00:00Z',
  cases: [],
};
const settle = () => act(async () => { await Promise.resolve(); await Promise.resolve(); });
const button = (container: Element, label: string) =>
  buttons(container).find((item) => item.textContent?.trim().includes(label));

afterEach(() => { document.body.innerHTML = ''; });

describe('replay panel', () => {
  it('plans a replay, shows the estimate and the cases that cannot be replayed, then starts it', async () => {
    const api = {
      listReplays: vi.fn().mockResolvedValue([]),
      planReplay: vi.fn().mockResolvedValue({ ...base, cases: [{ id: 1, originalInstanceId: 'abc12345', state: 'PENDING' }] }),
      getReplay: vi.fn(),
      startReplay: vi.fn().mockResolvedValue({ ...base, status: 'RUNNING' }),
      cancelReplay: vi.fn(),
    };
    const container = render(<ReplayPanel projectId="p" proposalId={7} open canRun api={api as never} />);
    await settle();
    type(byLabel<HTMLInputElement>(container, 'Cases'), '10');
    type(byLabel<HTMLInputElement>(container, 'Budget'), '0.5');
    await settle();
    click(button(container, 'Plan replay')!);
    await settle();
    expect(api.planReplay).toHaveBeenCalledWith('p', 7, 10, 0.5);
    expect(container.textContent).toContain('Estimated cost $0.1200');
    expect(container.textContent).toContain('2 finished cases cannot be replayed');
    click(button(container, 'Start replay')!);
    await settle();
    expect(api.startReplay).toHaveBeenCalledWith('p', 7, 'run-1');
    expect(container.textContent).toContain('Replaying');
  });

  it('summarises a finished run in plain terms and points at where each case differs', async () => {
    const finished: ReplayRun = {
      ...base, status: 'COMPLETED', costUsd: 0.04,
      aggregate: { cases: 2, unchanged: 1, changed: 1, needsHuman: 1, needsExternal: 0, failed: 0, skipped: 0,
        originallyOverridden: 1, overriddenNowDiffering: 1 },
      cases: [
        { id: 1, originalInstanceId: 'abc12345', state: 'NEEDS_HUMAN', changed: true, inputsRedacted: true,
          toolsSkipped: ['draft'], costUnpriced: false, reason: "no recorded decision for 'escalate'",
          diff: { changed: true, firstDivergence: { position: 0,
            original: { activityId: 'draft', kind: 'AGENT', outcome: 'OK' },
            replayed: { activityId: 'draft', kind: 'AGENT', outcome: 'LOW_CONFIDENCE' } } } },
        { id: 2, originalInstanceId: 'def67890', state: 'DONE', changed: false, inputsRedacted: false, toolsSkipped: [],
          costUnpriced: false },
      ],
    };
    const api = { listReplays: vi.fn().mockResolvedValue([finished]), getReplay: vi.fn().mockResolvedValue(finished),
      planReplay: vi.fn(), startReplay: vi.fn(), cancelReplay: vi.fn() };
    const container = render(<ReplayPanel projectId="p" proposalId={7} open canRun api={api as never} />);
    await settle();
    expect(container.textContent).toContain('would have behaved differently on 1 and the same on 1');
    expect(container.textContent).toContain('1 were overruled by a reviewer originally; 1 would now take a different path');
    expect(container.textContent).toContain('differs at step 1: draft agent OK → draft agent LOW_CONFIDENCE');
    expect(container.textContent).toContain('inputs were masked');
    expect(container.textContent).toContain('tools not run at draft');
    expect(container.textContent).toContain('evidence, not a verdict');
  });

  it('offers no way to plan to someone who cannot run replays', async () => {
    const api = { listReplays: vi.fn().mockResolvedValue([]), getReplay: vi.fn(), planReplay: vi.fn(),
      startReplay: vi.fn(), cancelReplay: vi.fn() };
    const container = render(<ReplayPanel projectId="p" proposalId={7} open canRun={false} api={api as never} />);
    await settle();
    expect(button(container, 'Plan replay')).toBeUndefined();
  });
});
