import React from 'react';
import { Volume2, VolumeX } from 'lucide-react';
import { useSoundPreference } from '@/lib/sound';

/**
 * Small switch that turns every Studio interaction sound on or off. The
 * choice is remembered in this browser.
 */
export const SoundToggle: React.FC<{ className?: string }> = ({ className = '' }) => {
  const [soundOn, toggleSound] = useSoundPreference();
  return (
    <button
      type="button"
      role="switch"
      aria-checked={soundOn}
      aria-label="Interface sounds"
      title={soundOn ? 'Sounds on — click to mute all interface sounds' : 'Sounds off — click to turn interface sounds on'}
      onClick={toggleSound}
      className={`flex h-8 shrink-0 items-center gap-1.5 rounded-lg border border-[#3A322E] bg-[#1A1614] px-2 text-[#A89F91] hover:text-[#EAE3D9] hover:bg-[#2F2926] transition-all ${className}`}
    >
      {soundOn ? <Volume2 className="w-3.5 h-3.5" /> : <VolumeX className="w-3.5 h-3.5" />}
      <span className={`relative inline-flex h-3.5 w-6 items-center rounded-full transition-colors ${soundOn ? 'bg-[#9D4EDD]' : 'bg-[#3A322E]'}`}>
        <span className={`absolute h-2.5 w-2.5 rounded-full bg-[#EAE3D9] shadow transition-transform ${soundOn ? 'translate-x-[11px]' : 'translate-x-0.5'}`} />
      </span>
    </button>
  );
};
