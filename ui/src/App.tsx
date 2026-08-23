import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { A2uiClientAction } from "@a2ui/web_core/v0_9";
import { renderMarkdown } from "@a2ui/markdown-it";
import { A2uiSurface, MarkdownContext } from "@a2ui/react/v0_9";
import {
  ArrowUp,
  Blocks,
  Braces,
  Check,
  CircleAlert,
  Database,
  ExternalLink,
  LoaderCircle,
  Menu,
  Plus,
  Sparkles,
  Square,
  TerminalSquare,
  Wrench,
  X,
} from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { useA2ui } from "./hooks/useA2ui";
import {
  APP_NAME,
  createAdkSession,
  readA2uiMessages,
  readText,
  readToolNames,
  readToolResults,
  reportA2uiDiagnostic,
  runAdkTurn,
} from "./lib/adk";

type ConnectionState = "connecting" | "ready" | "error";

type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  pending?: boolean;
  error?: boolean;
};

type ToolActivity = {
  id: string;
  callId?: string;
  name: string;
  status: "running" | "completed" | "failed";
  error?: string;
};

const USER_ID = `wren-ui-${crypto.randomUUID()}`;

const suggestions = [
  "Which models can I query?",
  "How many orders are there?",
  "Compare revenue by month",
];

function promptForAction(action: A2uiClientAction): string {
  const prompt = action.context.prompt;
  if (typeof prompt === "string" && prompt.trim()) return prompt;

  const context = Object.keys(action.context).length > 0 ? ` with ${JSON.stringify(action.context)}` : "";
  return `Continue by handling the “${action.name}” interaction${context}.`;
}

function StatusBadge({ state }: { state: ConnectionState }) {
  const labels = {
    connecting: "Connecting",
    ready: "Agent online",
    error: "Disconnected",
  };

  return (
    <div className={`status-badge status-${state}`} role="status">
      <span className="status-dot" aria-hidden="true" />
      {labels[state]}
    </div>
  );
}

function App() {
  const [connection, setConnection] = useState<ConnectionState>("connecting");
  const [sessionId, setSessionId] = useState<string>();
  const [sessionError, setSessionError] = useState<string>();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [activity, setActivity] = useState<ToolActivity[]>([]);
  const [toolError, setToolError] = useState<{ name: string; message: string }>();
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const abortController = useRef<AbortController | undefined>(undefined);
  const initialSessionRequested = useRef(false);
  const transcriptEnd = useRef<HTMLDivElement>(null);
  const sendPromptRef = useRef<(prompt: string) => Promise<void>>(async () => undefined);

  const handleA2uiAction = useCallback((action: A2uiClientAction) => {
    void sendPromptRef.current(promptForAction(action));
  }, []);
  const handleA2uiError = useCallback((error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    setToolError({ name: "A2UI renderer", message });
    void reportA2uiDiagnostic(message);
  }, []);
  const { surfaces, processMessages } = useA2ui(handleA2uiAction, handleA2uiError);

  const startSession = useCallback(async () => {
    abortController.current?.abort();
    setConnection("connecting");
    setSessionError(undefined);
    setSessionId(undefined);
    setMessages([]);
    setActivity([]);
    setToolError(undefined);

    try {
      const session = await createAdkSession(USER_ID);
      setSessionId(session.id);
      setConnection("ready");
    } catch (error) {
      setConnection("error");
      setSessionError(error instanceof Error ? error.message : "Could not connect to ADK.");
    }
  }, []);

  useEffect(() => {
    if (!initialSessionRequested.current) {
      initialSessionRequested.current = true;
      void startSession();
    }
    return () => abortController.current?.abort();
  }, [startSession]);

  useEffect(() => {
    transcriptEnd.current?.scrollIntoView({ behavior: busy ? "smooth" : "auto", block: "end" });
  }, [busy, messages]);

  const sendPrompt = useCallback(
    async (rawPrompt: string) => {
      const prompt = rawPrompt.trim();
      if (!prompt || !sessionId || busy) return;

      const userMessage: ChatMessage = {
        id: crypto.randomUUID(),
        role: "user",
        content: prompt,
      };
      const assistantId = crypto.randomUUID();
      setMessages((current) => [
        ...current,
        userMessage,
        { id: assistantId, role: "assistant", content: "", pending: true },
      ]);
      setInput("");
      setBusy(true);
      setSessionError(undefined);
      setToolError(undefined);
      setSidebarOpen(false);

      const controller = new AbortController();
      abortController.current = controller;
      let renderedSurface = false;

      try {
        await runAdkTurn({
          userId: USER_ID,
          sessionId,
          prompt,
          signal: controller.signal,
          onEvent: (event) => {
            const a2uiMessages = readA2uiMessages(event);
            if (a2uiMessages.length > 0) {
              processMessages(a2uiMessages);
              renderedSurface = true;
            }

            const toolNames = readToolNames(event);
            if (toolNames.length > 0) {
              setActivity((current) => [
                ...current,
                ...toolNames.map((name) => ({
                  id: crypto.randomUUID(),
                  name,
                  status: "running" as const,
                })),
              ]);
            }

            const toolResults = readToolResults(event);
            if (toolResults.length > 0) {
              const latestError = [...toolResults].reverse().find((result) => result.error);
              if (latestError?.error) {
                setToolError({ name: latestError.name, message: latestError.error });
              }
              setActivity((current) => {
                const next = [...current];
                for (const result of toolResults) {
                  let index = -1;
                  for (let candidate = next.length - 1; candidate >= 0; candidate -= 1) {
                    if (next[candidate].name === result.name && next[candidate].status === "running") {
                      index = candidate;
                      break;
                    }
                  }
                  const completed: ToolActivity = {
                    id: index >= 0 ? next[index].id : crypto.randomUUID(),
                    callId: result.id,
                    name: result.name,
                    status: result.error ? "failed" : "completed",
                    error: result.error,
                  };
                  if (index >= 0) next[index] = completed;
                  else next.push(completed);
                }
                return next;
              });
            }

            const text = readText(event);
            if (text) {
              setMessages((current) =>
                current.map((message) =>
                  message.id === assistantId
                    ? { ...message, content: message.content + text, pending: true }
                    : message,
                ),
              );
            }
          },
        });

        setMessages((current) =>
          current.map((message) => {
            if (message.id !== assistantId) return message;
            const fallback = renderedSurface ? "I added an interactive result to the workspace." : "Done.";
            return { ...message, content: message.content.trim() || fallback, pending: false };
          }),
        );
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError") {
          setMessages((current) =>
            current.map((message) =>
              message.id === assistantId
                ? { ...message, content: message.content.trim() || "Stopped.", pending: false }
                : message,
            ),
          );
          return;
        }
        const detail = error instanceof Error ? error.message : "The agent request failed.";
        setMessages((current) =>
          current.map((message) =>
            message.id === assistantId
              ? { ...message, content: detail, pending: false, error: true }
              : message,
          ),
        );
      } finally {
        setBusy(false);
        abortController.current = undefined;
      }
    },
    [busy, processMessages, sessionId],
  );

  sendPromptRef.current = sendPrompt;

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void sendPrompt(input);
  };

  const stop = () => abortController.current?.abort();

  const latestTools = useMemo(() => activity.slice(-4).reverse(), [activity]);

  return (
    <div className="app-shell">
      <aside className={`sidebar ${sidebarOpen ? "sidebar-open" : ""}`}>
        <div className="brand-row">
          <div className="brand-mark" aria-hidden="true">
            <Braces size={18} strokeWidth={2.2} />
          </div>
          <div>
            <div className="brand-name">Wren Analyst</div>
            <div className="brand-meta">Governed intelligence</div>
          </div>
          <button className="mobile-close icon-button" onClick={() => setSidebarOpen(false)} aria-label="Close menu">
            <X size={18} />
          </button>
        </div>

        <button className="new-session-button" onClick={() => void startSession()} disabled={busy}>
          <Plus size={17} aria-hidden="true" />
          New analysis
        </button>

        <nav className="sidebar-section" aria-label="Suggested analyses">
          <div className="section-label">Suggested</div>
          {suggestions.map((suggestion) => (
            <button
              className="nav-prompt"
              key={suggestion}
              onClick={() => void sendPrompt(suggestion)}
              disabled={connection !== "ready" || busy}
            >
              <Database size={15} aria-hidden="true" />
              <span>{suggestion}</span>
            </button>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="agent-card">
            <div className="agent-icon"><Sparkles size={16} aria-hidden="true" /></div>
            <div>
              <div className="agent-name">{APP_NAME}</div>
              <div className="agent-meta">Google ADK · Wren MCP</div>
            </div>
          </div>
          <a className="dev-ui-link" href="/dev-ui" target="_blank" rel="noreferrer">
            Open ADK Dev UI
            <ExternalLink size={14} aria-hidden="true" />
          </a>
        </div>
      </aside>

      <div className="workspace">
        <header className="topbar">
          <button className="menu-button icon-button" onClick={() => setSidebarOpen(true)} aria-label="Open menu">
            <Menu size={19} />
          </button>
          <div>
            <h1>Analytics workspace</h1>
            <p>Ask in plain language. Query governed models.</p>
          </div>
          <StatusBadge state={connection} />
        </header>

        <div className="workspace-grid">
          <main className="conversation">
            <section className="hero-copy" aria-labelledby="workspace-heading">
              <div className="eyebrow"><Blocks size={14} aria-hidden="true" /> A2UI workspace</div>
              <h2 id="workspace-heading">Answers you can act on.</h2>
              <p>
                Explore your semantic model, run governed analysis, and keep the result in one conversational thread.
              </p>
            </section>

            <section className="a2ui-workspace" aria-label="Interactive agent surfaces">
              {surfaces.map((surface) => (
                <div className="a2ui-surface-frame" key={surface.id}>
                  <MarkdownContext.Provider value={renderMarkdown}>
                    <A2uiSurface surface={surface} />
                  </MarkdownContext.Provider>
                </div>
              ))}
            </section>

            {sessionError && (
              <div className="connection-error" role="alert">
                <CircleAlert size={18} aria-hidden="true" />
                <div>
                  <strong>Could not reach the agent</strong>
                  <span>{sessionError}</span>
                </div>
                <button onClick={() => void startSession()}>Retry</button>
              </div>
            )}

            {toolError && (
              <div className="tool-error-banner" role="status">
                <CircleAlert size={18} aria-hidden="true" />
                <div>
                  <strong>{toolError.name} rejected output</strong>
                  <span>{toolError.message}</span>
                </div>
              </div>
            )}

            <section className="transcript" aria-live="polite" aria-label="Conversation">
              {messages.map((message) => (
                <article className={`message message-${message.role} ${message.error ? "message-error" : ""}`} key={message.id}>
                  {message.role === "assistant" && (
                    <div className="assistant-avatar" aria-hidden="true"><Sparkles size={14} /></div>
                  )}
                  <div className="message-body">
                    <div className="message-role">{message.role === "user" ? "You" : "Wren"}</div>
                    {message.content ? (
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown>
                    ) : (
                      <div className="thinking-line">
                        <LoaderCircle size={15} className="spin" aria-hidden="true" />
                        Working through the model
                      </div>
                    )}
                  </div>
                </article>
              ))}
              <div ref={transcriptEnd} />
            </section>

            <div className="composer-wrap">
              <form className="composer" onSubmit={submit}>
                <label className="sr-only" htmlFor="analyst-prompt">Ask Wren Analyst</label>
                <textarea
                  id="analyst-prompt"
                  value={input}
                  onChange={(event) => setInput(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" && !event.shiftKey) {
                      event.preventDefault();
                      if (input.trim()) void sendPrompt(input);
                    }
                  }}
                  placeholder={connection === "ready" ? "Ask about your data…" : "Waiting for the agent…"}
                  rows={1}
                  disabled={connection !== "ready"}
                />
                {busy ? (
                  <button className="send-button stop-button" type="button" onClick={stop} aria-label="Stop response">
                    <Square size={14} fill="currentColor" />
                  </button>
                ) : (
                  <button className="send-button" type="submit" disabled={!input.trim() || connection !== "ready"} aria-label="Send question">
                    <ArrowUp size={18} />
                  </button>
                )}
              </form>
              <div className="composer-note">Enter to send · Shift + Enter for a new line</div>
            </div>
          </main>

          <aside className="context-rail" aria-label="Live analysis context">
            <section className="context-panel">
              <div className="context-heading">
                <span>Live context</span>
                <span className={`live-indicator live-${connection}`}>
                  <span /> {connection === "ready" ? "Live" : "Offline"}
                </span>
              </div>
              <dl className="context-stats">
                <div><dt>Agent</dt><dd>{APP_NAME}</dd></div>
                <div><dt>Surfaces</dt><dd>{surfaces.length}</dd></div>
                <div><dt>Tool calls</dt><dd>{activity.length}</dd></div>
              </dl>
            </section>

            <section className="context-panel">
              <div className="context-heading"><span>Recent activity</span></div>
              <div className="activity-list">
                {latestTools.length === 0 ? (
                  <div className="empty-activity">
                    <TerminalSquare size={18} aria-hidden="true" />
                    Tool calls will appear here.
                  </div>
                ) : (
                  latestTools.map((tool) => (
                    <div className={`activity-item activity-${tool.status}`} key={tool.id}>
                      <div className="activity-icon"><Wrench size={14} aria-hidden="true" /></div>
                      <div>
                        <strong>{tool.name}</strong>
                        <span title={tool.error}>
                          {tool.error ?? (tool.status === "running" ? "Running" : "Completed")}
                        </span>
                      </div>
                      {tool.status === "failed" ? (
                        <CircleAlert size={14} className="activity-error" aria-hidden="true" />
                      ) : tool.status === "running" ? (
                        <LoaderCircle size={14} className="activity-running spin" aria-hidden="true" />
                      ) : (
                        <Check size={14} className="activity-check" aria-hidden="true" />
                      )}
                    </div>
                  ))
                )}
              </div>
            </section>

            <section className="guardrail-card">
              <Database size={18} aria-hidden="true" />
              <div>
                <strong>Semantic layer active</strong>
                <p>Queries use governed Wren models, not physical warehouse tables.</p>
              </div>
            </section>
          </aside>
        </div>
      </div>
    </div>
  );
}

export default App;
