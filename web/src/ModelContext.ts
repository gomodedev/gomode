// Loads advertised server-owned model resources and joins their texts within the model budget.

import { mcpClient, type McpToolCallOptions } from "./McpClient";
import { MODEL_RESOURCE_BUDGET, MODEL_CONTEXT_BUDGET } from "./ConversationContext";

export function boundedModelText(text: string, limit: number): string {
  let end = Math.min(text.length, limit);
  const last = text.charCodeAt(end - 1);
  if (end < text.length && last >= 0xd800 && last <= 0xdbff) end--;
  return text.slice(0, end);
}

/** Join server-owned resource texts within the budget; a text that does not fit is dropped whole. */
export function modelContextText(texts: Iterable<string>): string {
  const sections: string[] = [];
  let remaining = MODEL_CONTEXT_BUDGET;
  for (const raw of texts) {
    const text = raw.trim();
    if (!text || text.length > MODEL_RESOURCE_BUDGET || text.length + 2 > remaining) continue;
    sections.push(text);
    remaining -= text.length + 2;
  }
  return sections.join("\n\n");
}

/**
 * The text of each advertised model resource, by URI, for a new session's setup. Reads are advisory and fail
 * independently of voice transport setup; a resource that fails is absent.
 */
export async function readInitialModelContext(options: McpToolCallOptions): Promise<Map<string, string>> {
  try {
    const { resources } = await mcpClient.listModelResources(undefined, options);
    const texts = await Promise.all(
      resources.map(({ uri }) => mcpClient.readTextResource(uri, undefined, options).catch(() => null)),
    );
    return new Map(
      resources.flatMap(({ uri }, index) => {
        const text = texts[index];
        return typeof text === "string" ? [[uri, text] as const] : [];
      }),
    );
  } catch {
    return new Map();
  }
}
