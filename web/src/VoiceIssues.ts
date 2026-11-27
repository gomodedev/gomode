// Owns the browser voice problem list: one entry per source key, raised and resolved by its source.

import { createSignal, type Accessor, type Setter } from "solid-js";

/**
 * Settings fields whose problems show at their control while settings are open, and in the
 * overlay's list while they are closed, never in both.
 */
export const VOICE_SETTINGS_ISSUE_KEYS = ["choices", "choices.input", "choices.output", "voiceName"] as const;

export type VoiceSettingsIssueKey = (typeof VOICE_SETTINGS_ISSUE_KEYS)[number];

/** The source of a problem; for a settings field, the field. */
export type VoiceIssueKey =
  | VoiceSettingsIssueKey
  | "audioDevices"
  | "audioInput"
  | "audioOutput"
  | "connection"
  | "gateway"
  | "micLevel"
  | "microphone"
  | "microphoneProcessing"
  | "playback";

/** The one operation that can clear a problem, run from a user gesture; label names its message. */
export interface VoiceIssueRecovery {
  label: "enableMicrophoneMeter" | "enableSound";
  run: () => Promise<void>;
}

export interface VoiceIssue {
  readonly key: VoiceIssueKey;
  readonly message: string;
  readonly recovery: VoiceIssueRecovery | null;
}

export function voiceSettingsIssue(key: VoiceIssueKey): key is VoiceSettingsIssueKey {
  return (VOICE_SETTINGS_ISSUE_KEYS as readonly VoiceIssueKey[]).includes(key);
}

/**
 * VoiceIssues holds every current problem the browser voice component reports, oldest first.
 *
 * - Each key appears at most once. Raising a present key replaces its entry in place, so a retry
 *   that fails again stays one entry.
 * - The source resolves its key when the cause clears: a route switch that succeeds, sound that
 *   starts, a conversation that reaches session.ready.
 * - Dismissal resolves the listed keys at once. A condition that still holds returns when its
 *   source meets it again.
 * - An entry keeps its one recovery operation, which this owner runs.
 * - The owner depends only on Solid, so it loads without the lazy voice runtime.
 *
 * The set of keys is closed, so the list is bounded; the overlay shows only the newest few.
 */
export class VoiceIssues {
  readonly list: Accessor<readonly VoiceIssue[]>;
  private readonly _setList: Setter<readonly VoiceIssue[]>;

  constructor() {
    const [list, setList] = createSignal<readonly VoiceIssue[]>([]);
    this.list = list;
    this._setList = setList;
  }

  raise(key: VoiceIssueKey, message: string, recovery: VoiceIssueRecovery | null): void {
    this._setList((list) => {
      const index = list.findIndex((issue) => issue.key === key);
      const issue = { key, message, recovery };
      if (index < 0) return [...list, issue];
      const current = list[index];
      // Keep the entry's identity, and so its rendered element, when nothing visible changes.
      if (current?.message === message && current.recovery?.label === recovery?.label) return list;
      return list.map((entry, i) => (i === index ? issue : entry));
    });
  }

  resolve(...keys: VoiceIssueKey[]): void {
    this._setList((list) =>
      list.some((issue) => keys.includes(issue.key)) ? list.filter((issue) => !keys.includes(issue.key)) : list,
    );
  }

  message(key: VoiceIssueKey): string | null {
    return this.list().find((issue) => issue.key === key)?.message ?? null;
  }

  /** Run the key's current recovery operation; the source resolves the key when it succeeds. */
  recover(key: VoiceIssueKey): Promise<void> {
    return (
      this.list()
        .find((issue) => issue.key === key)
        ?.recovery?.run() ?? Promise.resolve()
    );
  }
}
