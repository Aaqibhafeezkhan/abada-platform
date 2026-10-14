import { useCallback, useState } from 'react';
import { bind, play, setEnabled, unlock, type PlayOptions, type SoundName } from 'cuelume';

/**
 * Interaction sounds for Studio, synthesized in the browser by cuelume (Web
 * Audio, no audio files, no network). Controls opt in with `data-cuelume-*`
 * attributes; outcomes (toasts) play from code. The on/off choice is a
 * per-browser preference, so it lives in localStorage like the panel layout.
 */
const SOUND_PREF_KEY = 'abada.studio.sound';

const readSoundPreference = (): boolean => {
  try {
    return localStorage.getItem(SOUND_PREF_KEY) !== 'off';
  } catch {
    return true;
  }
};

let initialized = false;

/** Applies the stored preference and delegates every `data-cuelume-*` control. */
export const initSound = (): void => {
  if (initialized || typeof document === 'undefined') return;
  initialized = true;
  setEnabled(readSoundPreference());
  bind(document);
};

let lastCue: { name: SoundName; at: number } | null = null;

/**
 * Plays a cue; a no-op while sound is off or the browser blocks audio. One
 * action that repeats a handler (deleting several selected nodes) plays once.
 */
export const playCue = (name: SoundName, options?: PlayOptions): void => {
  const now = Date.now();
  if (lastCue && lastCue.name === name && now - lastCue.at < 60) return;
  lastCue = { name, at: now };
  play(name, options);
};

/** React binding for the header Sound toggle. */
export const useSoundPreference = (): [boolean, () => void] => {
  const [enabled, setEnabledState] = useState<boolean>(readSoundPreference);
  const toggle = useCallback(() => {
    const next = !enabled;
    setEnabledState(next);
    setEnabled(next);
    try { localStorage.setItem(SOUND_PREF_KEY, next ? 'on' : 'off'); } catch { /* ignore */ }
    if (next) {
      // Turning sound on is a user gesture: start audio inside it, then confirm.
      void unlock().then((ready) => { if (ready) play('toggle'); });
    }
  }, [enabled]);
  return [enabled, toggle];
};
