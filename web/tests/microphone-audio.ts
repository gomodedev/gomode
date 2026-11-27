// Web Audio doubles expose capture sample windows, activation, retired graphs, and chime notes to voice tests.

import { vi } from "./expect";

export class FakeMicrophoneContext {
  static instances: FakeMicrophoneContext[] = [];
  state = "suspended";
  currentTime = 0;
  onstatechange: (() => void) | null = null;
  sample = 0.01;
  gains: Array<{
    gain: {
      value: number;
      cancelScheduledValues: ReturnType<typeof vi.fn>;
      setValueAtTime: ReturnType<typeof vi.fn>;
      linearRampToValueAtTime: ReturnType<typeof vi.fn>;
    };
    connect: ReturnType<typeof vi.fn>;
    disconnect: ReturnType<typeof vi.fn>;
  }> = [];
  destinations: Array<{ stream: MediaStream }> = [];
  source = { connect: vi.fn(), disconnect: vi.fn() };
  analyser = {
    fftSize: 0,
    getFloatTimeDomainData: (samples: Float32Array) => samples.fill(this.sample),
    disconnect: vi.fn(),
  };
  resume = vi.fn(async () => {
    this.state = "running";
  });
  close = vi.fn(async () => {
    this.state = "closed";
  });
  constructor() {
    FakeMicrophoneContext.instances.push(this);
  }
  createAnalyser() {
    return this.analyser;
  }
  createMediaStreamSource() {
    return this.source;
  }
  createGain() {
    const gain = {
      gain: { value: 1, cancelScheduledValues: vi.fn(), setValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn() },
      connect: vi.fn(),
      disconnect: vi.fn(),
    };
    this.gains.push(gain);
    return gain;
  }
  // The voice chime plays through the same context: one oscillator per note.
  oscillators: Array<{ start: ReturnType<typeof vi.fn>; stop: ReturnType<typeof vi.fn> }> = [];
  destination = {};
  createOscillator() {
    const oscillator = {
      type: "sine",
      frequency: { setValueAtTime: vi.fn() },
      connect: vi.fn(<T>(node: T) => node),
      disconnect: vi.fn(),
      start: vi.fn(),
      stop: vi.fn(),
    };
    this.oscillators.push(oscillator);
    return oscillator;
  }
  createMediaStreamDestination() {
    const track = { kind: "audio", enabled: true, stop: vi.fn() };
    const destination = {
      stream: { getTracks: () => [track], getAudioTracks: () => [track] } as unknown as MediaStream,
    };
    this.destinations.push(destination);
    return destination;
  }
}

export function microphoneFrames(): () => void {
  let next = 0;
  const frames = new Map<number, FrameRequestCallback>();
  vi.stubGlobal(
    "requestAnimationFrame",
    vi.fn((callback: FrameRequestCallback) => {
      frames.set(++next, callback);
      return next;
    }),
  );
  vi.stubGlobal("cancelAnimationFrame", (id: number) => frames.delete(id));
  return () => {
    const current = [...frames.values()];
    frames.clear();
    for (const callback of current) callback(0);
  };
}
