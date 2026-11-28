// Builds voice sessions for tests, so a constructor change touches only this file.

import { vi } from "./expect";
import type { ThinkingEarcon } from "../src/ThinkingEarcon";
import type { VoiceChimePlayer } from "../src/VoiceChime";
import { VoiceIssues } from "../src/VoiceIssues";
import { VoiceSession } from "../src/VoiceSession";

/** A session with its own problem list; audio defaults to the production players. */
export function createVoiceSession(audio?: { chime?: VoiceChimePlayer; thinking?: ThinkingEarcon }): VoiceSession {
  return new VoiceSession(new VoiceIssues(), audio?.chime, audio?.thinking);
}

/** A chime that records its calls and plays nothing. */
export function silentChime(): VoiceChimePlayer {
  return { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() };
}

/** A thinking earcon that records its calls and plays nothing. */
export function silentThinking(): ThinkingEarcon {
  return { prepare: vi.fn(), setThinking: vi.fn() };
}
