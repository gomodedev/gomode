// Renders voice problems as one line or a bulleted list with one X, without the lazy voice runtime.

import { createSignal, For, Show, type Accessor } from "solid-js";
import type { VoiceIssue, VoiceIssueKey, VoiceIssues } from "./VoiceIssues";
import type { VoiceMessages } from "./VoiceMessages";
import VoiceIcon from "./VoiceIcon";
import controls from "./VoiceControls.module.css";
import styles from "./VoiceIssueList.module.css";

/** The list shows the newest problems up to this count. */
const MAX_SHOWN_ISSUES = 3;
/** Longer problem messages are cut with "…"; the full text stays in the tooltip. */
const MAX_ISSUE_CHARS = 160;

function cutIssueMessage(message: string): string {
  return message.length <= MAX_ISSUE_CHARS ? message : `${message.slice(0, MAX_ISSUE_CHARS - 1).trimEnd()}…`;
}

/**
 * One problem renders as a line; several as a bulleted list, oldest first. The X resolves every
 * shown problem. A recovery stays aria-disabled until its operation settles.
 */
export default function VoiceIssueList(props: {
  owner: VoiceIssues;
  issues: readonly VoiceIssue[];
  messages: Accessor<VoiceMessages>;
  /** Class of the panel, for the surface its placement needs. */
  class?: string;
  /** Moves focus once the X or a recovery removed the focused control. */
  restoreFocus?: () => void;
}) {
  const shown = () => props.issues.slice(-MAX_SHOWN_ISSUES);
  const [running, setRunning] = createSignal<ReadonlySet<VoiceIssueKey>>(new Set());
  const recover = async (key: VoiceIssueKey) => {
    if (running().has(key)) return;
    setRunning((keys) => new Set(keys).add(key));
    try {
      await props.owner.recover(key);
    } finally {
      setRunning((keys) => new Set([...keys].filter((other) => other !== key)));
    }
    // The recovered entry's button is gone; keep focus in the panel unless the user moved it.
    const focused = document.activeElement;
    if (!props.owner.list().some((issue) => issue.key === key) && (focused === null || focused === document.body))
      props.restoreFocus?.();
  };
  const dismiss = () => {
    props.owner.resolve(...props.issues.map((issue) => issue.key));
    props.restoreFocus?.();
  };
  const Issue = (issue: VoiceIssue) => {
    const message = () => issue.message || props.messages().connectionFailed;
    const cut = () => cutIssueMessage(message());
    return (
      <>
        <span class={styles.issueMessage} title={cut() === message() ? undefined : message()}>
          {cut()}
        </span>
        <Show when={issue.recovery}>
          {(recovery) => (
            <button
              type="button"
              class={styles.recovery}
              aria-disabled={running().has(issue.key)}
              onClick={() => void recover(issue.key)}
            >
              {props.messages()[recovery().label]}
            </button>
          )}
        </Show>
      </>
    );
  };
  return (
    <Show when={shown().length > 0}>
      <div class={`${styles.panel} ${props.class ?? ""}`}>
        <div role="alert" class={styles.issues}>
          <Show
            when={shown().length > 1}
            fallback={<For each={shown()}>{(issue) => <p class={styles.issueLine}>{Issue(issue)}</p>}</For>}
          >
            <ul class={styles.issueList}>
              <For each={shown()}>{(issue) => <li>{Issue(issue)}</li>}</For>
            </ul>
          </Show>
        </div>
        <button
          type="button"
          class={controls.control}
          aria-label={props.messages().dismissErrors}
          title={props.messages().dismissErrors}
          onClick={dismiss}
        >
          <VoiceIcon name="close" />
        </button>
      </div>
    </Show>
  );
}
