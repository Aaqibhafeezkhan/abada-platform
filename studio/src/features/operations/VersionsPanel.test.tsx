import React, { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DefinitionVersionDTO } from '@/api/engine';
import { buttons, byLabel, click, render, type } from '@/test/render';
import { VersionsPanel } from './VersionsPanel';

const api = vi.hoisted(() => ({ getVersions: vi.fn(), getVersionDiff: vi.fn(), rollbackProcess: vi.fn() }));
vi.mock('@/api/engine', () => ({ EngineAPI: api }));

const version = (patch: Partial<DefinitionVersionDTO>): DefinitionVersionDTO => ({
  version: 1, deploymentId: 'd1', checksum: 'c', schemaType: 'APL_NATIVE', createdAt: '2026-12-01T10:00:00Z',
  deployedBy: 'alice', origin: 'DEPLOY', rollbackOf: null, proposalId: null, comment: null, runningInstances: 0,
  current: false, ...patch,
});
const settle = () => act(async () => { await Promise.resolve(); await Promise.resolve(); });
const button = (container: Element, label: string) =>
  buttons(container).find((item) => item.textContent?.trim().includes(label));

afterEach(() => { vi.clearAllMocks(); document.body.innerHTML = ''; });

const open = async (canRollBack: boolean) => {
  api.getVersions.mockResolvedValue([
    version({ version: 2, deploymentId: 'd2', current: true, runningInstances: 3 }),
    version({ version: 1 }),
  ]);
  const container = render(<VersionsPanel projectId="p" processKeys={['loan']} canRollBack={canRollBack} />);
  click(button(container, 'Versions and rollback')!);
  await settle();
  return container;
};

describe('versions panel', () => {
  it('lists versions and offers no rollback to a viewer', async () => {
    const container = await open(false);
    expect(api.getVersions).toHaveBeenCalledWith('p', 'loan');
    expect(container.textContent).toContain('v2 (current)');
    expect(container.textContent).toContain('3 running');
    expect(button(container, 'Roll back')).toBeUndefined();
  });

  it('rolls back with a reason, against the version it saw as current', async () => {
    api.rollbackProcess.mockResolvedValue({});
    const container = await open(true);
    click(button(container, 'Roll back')!);
    expect(container.textContent).toContain('Deploy the source of v1 as v3');
    expect(container.textContent).toContain('3 running instances on v2 keep running there');
    expect(button(container, 'Roll back to v1')!.disabled).toBe(true);
    type(byLabel<HTMLInputElement>(container, 'Reason'), 'Wording regressed approvals');
    await settle();
    click(button(container, 'Roll back to v1')!);
    await settle();
    expect(api.rollbackProcess).toHaveBeenCalledWith('p', 'loan', 1, 'Wording regressed approvals', 2);
  });

  it('shows the source diff against the current version', async () => {
    api.getVersionDiff.mockResolvedValue({ from: 1, to: 2, identical: false, tooLarge: false, unified: '-a\n+b\n' });
    const container = await open(true);
    click(button(container, 'Diff vs current')!);
    await settle();
    expect(api.getVersionDiff).toHaveBeenCalledWith('p', 'loan', 1, 2);
    expect(byLabel(container, 'Source diff').textContent).toContain('+b');
  });
});
