import { useEffect, useRef, useState } from 'react';

/** Size limits and defaults of a designer side panel. */
export interface SidePanelBounds {
  defaultWidth: number;
  minWidth: number;
  maxWidth: number;
}

export const clampWidth = (width: number, bounds: SidePanelBounds) =>
  Math.min(bounds.maxWidth, Math.max(bounds.minWidth, Math.round(width)));

const widthKey = (id: string) => `abada.studio.panel.${id}.width`;
const pinnedKey = (id: string) => `abada.studio.panel.${id}.pinned`;

const read = (key: string): string | null => {
  try {
    return localStorage.getItem(key);
  } catch {
    return null; // Storage unavailable (privacy mode): defaults apply.
  }
};

const write = (key: string, value: string) => {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Storage unavailable: the layout lasts for this session only.
  }
};

export const readPanelWidth = (id: string, bounds: SidePanelBounds): number => {
  const stored = Number.parseInt(read(widthKey(id)) ?? '', 10);
  return Number.isFinite(stored) ? clampWidth(stored, bounds) : bounds.defaultWidth;
};

export const readPanelPinned = (id: string): boolean => read(pinnedKey(id)) !== 'false';

/**
 * Width and pin state of one side panel, remembered per browser. A panel starts pinned at its default
 * width. Width writes are debounced because a resize drag changes it on every pointer move.
 */
export function useSidePanelPrefs(id: string, bounds: SidePanelBounds) {
  const [width, setWidth] = useState(() => readPanelWidth(id, bounds));
  const [pinned, setPinned] = useState(() => readPanelPinned(id));
  const widthRef = useRef(width);
  widthRef.current = width;

  useEffect(() => {
    const timer = window.setTimeout(() => write(widthKey(id), String(widthRef.current)), 250);
    return () => window.clearTimeout(timer);
  }, [id, width]);

  // Flush the last drag position when the panel unmounts before the debounce fires.
  useEffect(() => () => write(widthKey(id), String(widthRef.current)), [id]);

  useEffect(() => write(pinnedKey(id), String(pinned)), [id, pinned]);

  return { width, setWidth: (next: number) => setWidth(clampWidth(next, bounds)), pinned, setPinned };
}
