import { beforeEach, describe, expect, it, vi } from 'vitest';

const cuelume = vi.hoisted(() => ({
  bind: vi.fn(),
  play: vi.fn(),
  setEnabled: vi.fn(),
  unlock: vi.fn(() => Promise.resolve(true)),
}));
vi.mock('cuelume', () => cuelume);

import { SoundToggle } from './SoundToggle';
import { byLabel, click, render } from '@/test/render';

describe('SoundToggle', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
  });

  it('starts on and mutes every sound when switched off', () => {
    const toggle = byLabel<HTMLButtonElement>(render(<SoundToggle />), 'Interface sounds');
    expect(toggle.getAttribute('role')).toBe('switch');
    expect(toggle.getAttribute('aria-checked')).toBe('true');

    click(toggle);

    expect(toggle.getAttribute('aria-checked')).toBe('false');
    expect(cuelume.setEnabled).toHaveBeenLastCalledWith(false);
    expect(localStorage.getItem('abada.studio.sound')).toBe('off');
  });

  it('remembers the choice and starts audio when switched back on', async () => {
    localStorage.setItem('abada.studio.sound', 'off');
    const toggle = byLabel<HTMLButtonElement>(render(<SoundToggle />), 'Interface sounds');
    expect(toggle.getAttribute('aria-checked')).toBe('false');

    click(toggle);
    await Promise.resolve();

    expect(toggle.getAttribute('aria-checked')).toBe('true');
    expect(cuelume.setEnabled).toHaveBeenLastCalledWith(true);
    expect(cuelume.unlock).toHaveBeenCalled();
    expect(localStorage.getItem('abada.studio.sound')).toBe('on');
  });
});
