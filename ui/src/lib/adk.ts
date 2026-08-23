import type { A2uiMessage } from "@a2ui/web_core/v0_9";

const APP_NAME = "wren_analyst";
const API_BASE_URL = (import.meta.env.VITE_ADK_BASE_URL ?? "").replace(/\/$/, "");

function apiUrl(path: string): string {
  return `${API_BASE_URL}${path}`;
}

export type AdkPart = {
  text?: string;
  functionCall?: {
    id?: string;
    name?: string;
    args?: Record<string, unknown>;
  };
  functionResponse?: {
    id?: string;
    name?: string;
    response?: unknown;
  };
  inlineData?: {
    data?: string;
    mimeType?: string;
  };
};

export type AdkEvent = {
  id?: string;
  author?: string;
  partial?: boolean;
  content?: {
    role?: string;
    parts?: AdkPart[];
  };
};

type Session = {
  id: string;
};

type RunTurnOptions = {
  userId: string;
  sessionId: string;
  prompt: string;
  signal?: AbortSignal;
  onEvent: (event: AdkEvent) => void;
};

function requestError(response: Response, detail: string): Error {
  return new Error(`${response.status} ${response.statusText}${detail ? ` — ${detail}` : ""}`);
}

export async function createAdkSession(userId: string): Promise<Session> {
  const appsResponse = await fetch(apiUrl("/list-apps"), { headers: { Accept: "application/json" } });
  if (!appsResponse.ok) {
    throw requestError(appsResponse, await appsResponse.text());
  }

  const apps = (await appsResponse.json()) as string[];
  if (!apps.includes(APP_NAME)) {
    throw new Error(`ADK agent “${APP_NAME}” is not registered.`);
  }

  const response = await fetch(apiUrl(`/apps/${APP_NAME}/users/${userId}/sessions`), {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ state: {} }),
  });
  if (!response.ok) {
    throw requestError(response, await response.text());
  }

  return (await response.json()) as Session;
}

function parseSseBlock(block: string): AdkEvent | undefined {
  const data = block
    .split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart())
    .join("\n")
    .trim();

  if (!data || data === "[DONE]") return undefined;
  return JSON.parse(data) as AdkEvent;
}

export async function runAdkTurn({
  userId,
  sessionId,
  prompt,
  signal,
  onEvent,
}: RunTurnOptions): Promise<void> {
  const response = await fetch(apiUrl("/run_sse"), {
    method: "POST",
    signal,
    headers: {
      Accept: "text/event-stream",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      appName: APP_NAME,
      userId,
      sessionId,
      newMessage: {
        role: "user",
        parts: [{ text: prompt }],
      },
      streaming: true,
    }),
  });

  if (!response.ok) {
    throw requestError(response, await response.text());
  }
  if (!response.body) {
    throw new Error("The ADK server returned an empty response stream.");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() ?? "";

    for (const block of blocks) {
      const event = parseSseBlock(block);
      if (event) onEvent(event);
    }
    if (done) break;
  }

  const finalEvent = parseSseBlock(buffer);
  if (finalEvent) onEvent(finalEvent);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isA2uiMessage(value: unknown): value is A2uiMessage {
  if (!isRecord(value) || (value.version !== "v0.9" && value.version !== "v0.9.1")) {
    return false;
  }
  return ["createSurface", "updateComponents", "updateDataModel", "deleteSurface"].some(
    (key) => key in value,
  );
}

function collectA2uiMessages(value: unknown, result: A2uiMessage[]): void {
  if (isA2uiMessage(value)) {
    result.push(value);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item) => collectA2uiMessages(item, result));
    return;
  }
  if (isRecord(value)) {
    Object.values(value).forEach((item) => collectA2uiMessages(item, result));
  }
}

function parseJsonText(text: string): unknown | undefined {
  const trimmed = text.trim();
  const fenced = trimmed.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i)?.[1];
  const candidate = fenced ?? trimmed;
  if (!(candidate.startsWith("{") || candidate.startsWith("["))) return undefined;

  try {
    return JSON.parse(candidate);
  } catch {
    return undefined;
  }
}

function decodeInlineData(part: AdkPart): unknown | undefined {
  const encoded = part.inlineData?.data;
  if (!encoded) return undefined;

  try {
    const decoded = atob(encoded);
    const wrapped = decoded.match(/<a2a_datapart_json>([\s\S]*?)<\/a2a_datapart_json>/)?.[1];
    return JSON.parse(wrapped ?? decoded);
  } catch {
    return undefined;
  }
}

export function readA2uiMessages(event: AdkEvent): A2uiMessage[] {
  const result: A2uiMessage[] = [];
  for (const part of event.content?.parts ?? []) {
    if (part.text) collectA2uiMessages(parseJsonText(part.text), result);
    collectA2uiMessages(decodeInlineData(part), result);
    collectA2uiMessages(part.functionResponse?.response, result);
  }
  return result;
}

export function readText(event: AdkEvent): string {
  return (event.content?.parts ?? [])
    .map((part) => {
      if (!part.text) return "";
      const a2ui: A2uiMessage[] = [];
      collectA2uiMessages(parseJsonText(part.text), a2ui);
      return a2ui.length > 0 ? "" : part.text;
    })
    .join("");
}

export function readToolNames(event: AdkEvent): string[] {
  return (event.content?.parts ?? [])
    .map((part) => part.functionCall?.name)
    .filter((name): name is string => Boolean(name));
}

export { APP_NAME };
