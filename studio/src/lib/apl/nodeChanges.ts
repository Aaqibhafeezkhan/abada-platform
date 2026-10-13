import type { WorkflowFile } from '@/types';

export interface NodeChange { id: string; kind: 'added' | 'changed' | 'removed' }

const comparable = (node: unknown): string =>
  JSON.stringify(node, (key, value) => (key === 'x' || key === 'y' ? undefined : value));

/**
 * Which nodes a refinement added, changed or removed, by comparing the process before and after (canvas
 * position is ignored). A node the candidate does not mention compares equal, so it is not listed.
 */
export function nodeChanges(before: WorkflowFile, after: WorkflowFile): NodeChange[] {
  const previous = new Map(before.nodes.map((node) => [node.id, node]));
  const next = new Map(after.nodes.map((node) => [node.id, node]));
  const changes: NodeChange[] = [];
  for (const node of after.nodes) {
    const old = previous.get(node.id);
    if (!old) changes.push({ id: node.id, kind: 'added' });
    else if (comparable(old) !== comparable(node)) changes.push({ id: node.id, kind: 'changed' });
  }
  for (const node of before.nodes) {
    if (!next.has(node.id)) changes.push({ id: node.id, kind: 'removed' });
  }
  return changes;
}
