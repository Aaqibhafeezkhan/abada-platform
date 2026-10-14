import { describe, expect, it, vi } from 'vitest';
import { NLInputBar } from './NLInputBar';
import { byLabel, click, render } from '@/test/render';

describe('NLInputBar', () => {
  it('says why the last request produced no process, and can be dismissed', () => {
    const onDismissError = vi.fn();
    const container = render(<NLInputBar onGenerateWorkflow={vi.fn()} isGenerating={false} hasActiveWorkflow
      error="409 No AI model is configured, so no process was generated from your description."
      onDismissError={onDismissError} />);

    expect(container.querySelector('[role="alert"]')?.textContent).toContain('No AI model is configured');
    click(byLabel(container, 'Dismiss'));
    expect(onDismissError).toHaveBeenCalledOnce();
  });

  it('shows no alert when the last request succeeded', () => {
    const container = render(<NLInputBar onGenerateWorkflow={vi.fn()} isGenerating={false} hasActiveWorkflow />);
    expect(container.querySelector('[role="alert"]')).toBeNull();
  });
});
