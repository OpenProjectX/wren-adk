import { useCallback, useEffect, useRef, useState } from "react";
import type { A2uiClientAction, A2uiMessage, SurfaceModel } from "@a2ui/web_core/v0_9";
import { MessageProcessor } from "@a2ui/web_core/v0_9";
import type { ReactComponentImplementation } from "@a2ui/react/v0_9";
import { basicCatalog } from "@a2ui/react/v0_9";
import { starterSurfaceMessages } from "../lib/a2ui";

type ActionHandler = (action: A2uiClientAction) => void | Promise<void>;

export function useA2ui(onAction: ActionHandler) {
  const actionHandler = useRef(onAction);
  const starterLoaded = useRef(false);
  actionHandler.current = onAction;

  const [processor] = useState(
    () =>
      new MessageProcessor<ReactComponentImplementation>(
        [basicCatalog],
        (action) => actionHandler.current(action),
        { version: "v0.9" },
      ),
  );
  const [surfaces, setSurfaces] = useState<SurfaceModel<ReactComponentImplementation>[]>([]);

  const syncSurfaces = useCallback(() => {
    setSurfaces(Array.from(processor.model.surfacesMap.values()));
  }, [processor]);

  const processMessages = useCallback(
    (messages: A2uiMessage[]) => {
      if (messages.length === 0) return;
      processor.processMessages(messages);
      syncSurfaces();
    },
    [processor, syncSurfaces],
  );

  useEffect(() => {
    const created = processor.onSurfaceCreated(syncSurfaces);
    const deleted = processor.onSurfaceDeleted(syncSurfaces);
    if (!starterLoaded.current) {
      starterLoaded.current = true;
      processMessages(starterSurfaceMessages);
    }

    return () => {
      created.unsubscribe();
      deleted.unsubscribe();
    };
  }, [processMessages, processor, syncSurfaces]);

  return { surfaces, processMessages };
}
