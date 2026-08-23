# Wren Analyst UI

A Bun + Vite + React client for the `wren_analyst` Google ADK agent. It uses
the official A2UI v0.9 React renderer and consumes ADK's session and SSE APIs.

## Develop

Start the Spring application from the repository root:

```shell
./gradlew :app:bootRun
```

Then start Vite in another terminal:

```shell
cd ui
bun install
bun run dev
```

Open <http://localhost:5173>. Vite proxies `/list-apps`, `/apps`, and
`/run_sse` to <http://localhost:8080>.

## Build

```shell
bun run build
```

The production bundle is written to `ui/dist`. Serve it behind the same origin
as the ADK API, or set `VITE_ADK_BASE_URL` at build time and configure the ADK
server's allowed origins accordingly.

## A2UI flow

The UI passes v0.9/v0.9.1 protocol messages through `MessageProcessor` and
renders each surface with `A2uiSurface`. It recognizes A2UI JSON in ADK text,
function responses, and A2A data-part wrappers. A2UI actions are translated
into follow-up turns so buttons and bound form values stay in the conversation.
