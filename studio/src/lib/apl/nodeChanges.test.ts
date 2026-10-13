import { describe, expect, it } from 'vitest';
import type { WorkflowFile } from '@/types';
import { nodeChanges } from './nodeChanges';

const node = (id: string, extra: Record<string, unknown> = {}) =>
  ({ id, type: 'agent', title: id, description: '', x: 0, y: 0, ...extra }) as never;
const file = (...nodes: unknown[]): WorkflowFile => ({ nodes, edges: [] }) as unknown as WorkflowFile;

describe('node changes of a refinement', () => {
  it('lists added, changed and removed nodes and ignores canvas position', () => {
    const before = file(node('a'), node('b'), node('c'));
    const after = file(node('a', { x: 99, y: 40 }), node('b', { description: 'new' }), node('d'));
    expect(nodeChanges(before, after)).toEqual([
      { id: 'b', kind: 'changed' }, { id: 'd', kind: 'added' }, { id: 'c', kind: 'removed' },
    ]);
  });

  it('is empty when nothing changed', () => {
    expect(nodeChanges(file(node('a')), file(node('a')))).toEqual([]);
  });
});
