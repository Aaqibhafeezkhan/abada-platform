import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SidePanel } from './SidePanel';
import { byLabel, click, render } from '@/test/render';

const BOUNDS = { defaultWidth: 256, minWidth: 200, maxWidth: 480 };

const panel = (side: 'left' | 'right' = 'left') => render(
  <SidePanel id="test" side={side} label="Explorer" bounds={BOUNDS}><p>content</p></SidePanel>);

const key = (element: Element, name: string) =>
  act(() => { element.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true })); });

const widthOf = (container: Element) => (container.querySelector('[data-panel="test"]') as HTMLElement).style.width;

describe('SidePanel', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.useRealTimers());

  it('starts pinned beside the canvas at its default width', () => {
    const container = panel();
    expect(widthOf(container)).toBe('256px');
    expect(container.textContent).toContain('content');
    expect(byLabel(container, 'Unpin Explorer to give the canvas more room').getAttribute('aria-pressed')).toBe('true');
  });

  it('resizes from the keyboard within its bounds, resets on double-click and remembers the width', () => {
    vi.useFakeTimers();
    const container = panel();
    const handle = byLabel(container, 'Resize Explorer');
    key(handle, 'ArrowRight');
    expect(widthOf(container)).toBe('272px');
    for (let i = 0; i < 40; i++) key(handle, 'ArrowRight');
    expect(handle.getAttribute('aria-valuenow')).toBe('480');

    act(() => { handle.dispatchEvent(new MouseEvent('dblclick', { bubbles: true })); });
    expect(widthOf(container)).toBe('256px');

    key(handle, 'ArrowLeft');
    act(() => { vi.advanceTimersByTime(300); });
    expect(widthOf(panel())).toBe('240px');
  });

  it('grows toward the canvas on the right side too', () => {
    const container = panel('right');
    key(byLabel(container, 'Resize Explorer'), 'ArrowLeft');
    expect(widthOf(container)).toBe('272px');
  });

  it('folds into a rail when unpinned and opens over the canvas until Escape', () => {
    const container = panel();
    click(byLabel(container, 'Unpin Explorer to give the canvas more room'));
    expect(container.textContent).not.toContain('content');
    expect(localStorage.getItem('abada.studio.panel.test.pinned')).toBe('false');

    click(byLabel(container, 'Open Explorer'));
    expect(container.querySelector('[role="dialog"]')?.textContent).toContain('content');
    key(window.document.body, 'Escape');
    expect(container.querySelector('[role="dialog"]')).toBeNull();

    click(byLabel(container, 'Open Explorer'));
    click(byLabel(container, 'Pin Explorer beside the canvas'));
    expect(container.querySelector('[role="dialog"]')).toBeNull();
    expect(widthOf(container)).toBe('256px');
  });
});
