// Adapts a monitor's context sink to one (text, generate) callback, as the model receives updates.

import { modelContextText } from "../src/ModelContext";
import type { ServiceContextSink } from "../src/ServiceNotifications";

export function contextSink(inject: (text: string, generate: boolean) => void): ServiceContextSink {
  return {
    setServiceContext: (changes) => inject(modelContextText(changes.values()), false),
    announce: (text) => inject(text, true),
  };
}
