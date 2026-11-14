// Reads and picks filter chips, the radio groups that settings render for model choices.

import { screen } from "@solidjs/testing-library";

/** The chip group named name. */
export function chips(name: string | RegExp): HTMLElement {
  return screen.getByRole("radiogroup", { name });
}

/** The chip in group whose radio value is value. */
export function chip(group: HTMLElement, value: string): HTMLInputElement {
  const radio = [...group.querySelectorAll("input")].find((input) => input.value === value);
  if (radio === undefined) throw new Error(`No chip with value ${JSON.stringify(value)}`);
  return radio;
}

/** The checked chip's value, or null when none is checked. */
export function checkedValue(group: HTMLElement): string | null {
  return [...group.querySelectorAll("input")].find((input) => input.checked)?.value ?? null;
}

/** Whether every chip in group is disabled. */
export function allDisabled(group: HTMLElement): boolean {
  return [...group.querySelectorAll("input")].every((input) => input.disabled);
}
