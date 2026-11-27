// Owns the browser voice problem list: one entry per source key, raised and resolved by its source.

import { createSignal, type Accessor, type Setter } from "solid-js";

/**
 * Settings fields whose problems show at their control while a settings view is shown, and in the
 * list otherwise, never in both.
 *
 * The session resolves the setup against the gateway and owns the unprefixed keys. VoiceSettings
 * owns each "save." key: its field rejected the user's last edit, so the saved value still applies.
 */
export const VOICE_SETTINGS_ISSUE_KEYS = [
  "choices",
  "choices.input",
  "choices.output",
  "save.browserSpeechRate",
  "save.browserVoiceURI",
  "save.choice",
  "save.languageTag",
  "save.setupMode",
  "save.showReasoning",
  "save.showUsageStats",
  "save.speechInput",
  "save.speechOutput",
  "save.voiceName",
  "voiceName",
] as const;

export type VoiceSettingsIssueKey = (typeof VOICE_SETTINGS_ISSUE_KEYS)[number];

/** The source of a problem; for a settings field, the field. */
export type VoiceIssueKey =
  | VoiceSettingsIssueKey
  | VoiceShellIssueKey
  | "audioDevices"
  | "audioInput"
  | "audioOutput"
  | "connection"
  | "gateway"
  | "micLevel"
  | "microphone"
  | "microphoneProcessing"
  | "playback";

/**
 * BrowserVoiceShell owns these keys and lists them itself until the runtime's overlay mounts:
 * context publication failed, or setup failed before the runtime could load.
 */
export const VOICE_SHELL_ISSUE_KEYS = ["context", "setup"] as const;

export type VoiceShellIssueKey = (typeof VOICE_SHELL_ISSUE_KEYS)[number];

/** The one operation that can clear a problem, run from a user gesture; label names its message. */
export interface VoiceIssueRecovery {
  label: "enableMicrophoneMeter" | "enableSound" | "retry";
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

export function voiceShellIssue(key: VoiceIssueKey): key is VoiceShellIssueKey {
  return (VOICE_SHELL_ISSUE_KEYS as readonly VoiceIssueKey[]).includes(key);
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
 * - A settings field problem shows at its control while any settings view is shown, and is listed
 *   otherwise. Each VoiceSettings view, in the overlay or on a host page, registers while shown.
 * - The owner depends only on Solid, so it loads without the lazy voice runtime.
 *
 * The set of keys is closed, so the list is bounded; the overlay shows only the newest few.
 */
export class VoiceIssues {
  readonly list: Accessor<readonly VoiceIssue[]>;
  private readonly _setList: Setter<readonly VoiceIssue[]>;
  private readonly _settingsViews: Accessor<number>;
  private readonly _setSettingsViews: Setter<number>;

  constructor() {
    const [list, setList] = createSignal<readonly VoiceIssue[]>([]);
    this.list = list;
    this._setList = setList;
    const [settingsViews, setSettingsViews] = createSignal(0);
    this._settingsViews = settingsViews;
    this._setSettingsViews = setSettingsViews;
  }

  /** The problems a list shows: all of them, less the settings fields' while a settings view shows them. */
  listed(): readonly VoiceIssue[] {
    const list = this.list();
    return this._settingsViews() === 0 ? list : list.filter((issue) => !voiceSettingsIssue(issue.key));
  }

  /** Register a shown settings view; call the result once it hides or unmounts. */
  showSettings(): () => void {
    let shown = true;
    this._setSettingsViews((count) => count + 1);
    return () => {
      if (!shown) return;
      shown = false;
      this._setSettingsViews((count) => count - 1);
    };
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

/** The page's one owner, shared by the shell, the session, and every settings view. */
export const voiceIssues = new VoiceIssues();
