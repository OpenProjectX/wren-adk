# wren-adk

A Spring Boot starter that gives a [Google ADK](https://github.com/google/adk-java)
agent governed access to a warehouse through the
[Wren](https://github.com/Canner/WrenAI) semantic layer — plus Google ADK's
built-in Dev UI for exercising it, plus a production-oriented React/A2UI
client for richer interaction.

The agent writes SQL against **model names**. Wren expands each model into a
CTE, resolves the real schema, prunes columns and translates dialect, so the
model never has to know the physical table layout — and cannot query anything
outside the MDL.

```
React + A2UI ─┐
              ├──SSE──▶ Spring Boot app ──ADK──▶ LLM
ADK Dev UI ───┘              │
                             │ MCP (stdio or streamable HTTP)
                             ▼
                        wren serve mcp ──▶ Postgres / BigQuery / Snowflake / …
```

## Modules

| Module | Artifact | Purpose |
|---|---|---|
| `core` | `core` | `WrenToolsets`, `WrenAgents`, `WrenMcpSettings` — no Spring |
| `autoconfigure` | `wren-adk-spring-boot-autoconfigure` | `@AutoConfiguration`, `wren.adk.*` properties |
| `starter` | `wren-adk-spring-boot-starter` | Dependency aggregator |
| `app` | — | ADK Dev UI host, Jib image, integration tests |
| `ui` | `@openprojectx/wren-adk-ui` | Bun, Vite, React and the official A2UI renderer |

## Quick start

```shell
cp .env.example .env      # fill in ANTHROPIC_API_KEY and WREN_PROJECT_HOME
./gradlew :app:bootRun
```

Open <http://localhost:8080/dev-ui>. The application registers the
Spring-created `wren_analyst` with ADK's `AgentStaticLoader`; ADK supplies the
browser assets, session API, SSE execution endpoint, traces, and evaluation UI.

For the richer application UI, run a second terminal:

```shell
cd ui
bun install
bun run dev
```

Then open <http://localhost:5173>. Vite proxies the ADK API to the Spring app,
while `@a2ui/react` renders interactive v0.9/v0.9.1 surfaces. See
[`ui/README.md`](ui/README.md) for production configuration.

> **Development only.** Google documents ADK's Dev UI as a development and
> debugging tool, not a production frontend. Put it behind appropriate network
> controls and replace it with an authenticated application UI for production.

`.env` is read at startup by [spring-dotenv](https://github.com/paulschwarz/spring-dotenv)
and is gitignored, so keys never reach a config file or the shell history.

`WREN_PROJECT_HOME` must contain a built Wren project — `wren_project.yml`,
`models/`, and `target/mdl.json` from `wren context build`. Warehouse
credentials are resolved by the `wren` CLI from its own profile.

## LLM providers

ADK Java ships Claude support in **core** — `com.anthropic:anthropic-java` and
`anthropic-java-vertex` are compile-scope dependencies, not optional extras. So
Anthropic needs no additional wiring.

| `provider` | Backend | Key | Default model |
|---|---|---|---|
| `ANTHROPIC` | `com.google.adk.models.Claude` | `ANTHROPIC_API_KEY` | `claude-sonnet-4-5` |
| `GEMINI` | `com.google.adk.models.Gemini` | `GOOGLE_API_KEY` | `gemini-2.0-flash` |

### Anthropic-compatible endpoints

Any endpoint speaking the Anthropic API works — Aliyun MaaS, an internal
router, a Bedrock-style proxy — including ones serving non-Claude models.

```ini
LLM_PROVIDER=anthropic
ANTHROPIC_BASE_URL=https://…/apps/anthropic
ANTHROPIC_AUTH_TOKEN=…          # Authorization: Bearer  (most gateways)
# ANTHROPIC_API_KEY=…           # x-api-key              (api.anthropic.com)
ANTHROPIC_MODEL=qwen3.8-max
```

Both auth styles are supported; the bearer token wins when both are set,
because a gateway that issues one usually ignores `x-api-key`.

> **Thinking blocks.** ADK's `Claude` converts only `text` and `tool_use`
> response blocks — anything else throws `UnsupportedOperationException`
> (`Claude.anthropicContentBlockToPart`). Several gateways emit a `thinking`
> block ahead of the text, which breaks every call. Those methods are private,
> so this starter fixes it on the request side instead:
> `anthropic.disable-thinking` (**on by default**) sends
> `thinking: {type: "disabled"}` via a dynamic proxy around the client
> (`ThinkingDisabledClient`). Set it to `false` if your endpoint never emits
> thinking blocks — and drop it entirely once ADK handles them.

For anything else — OpenAI, vLLM, Ollama, LiteLLM — declare your own
`BaseLlm` bean; ADK core also ships `ChatCompletionsClient` for
OpenAI-compatible endpoints, and `contrib/spring-ai` and `contrib/langchain4j`
bridge further providers.

> One asymmetry worth knowing: **`Gemini` validates credentials when it is
> constructed**, so a missing key throws at startup. `Claude` does not — it
> fails at first use instead.

## Wren's official skill guides

Wren publishes workflow guides through its CLI (`wren skills get usage`). The
agent loads the `usage` guide at startup and appends it to the instruction, so
the querying workflow is the vendor's own rather than a paraphrase.

One wrinkle: those guides are written for an agent driving the **CLI** —
`wren --sql '...'`, `wren memory recall -q '...'`. An ADK agent has no shell,
only Wren's **MCP tools**. Handed the guide verbatim a model will try to run
commands it cannot run, so `WrenSkills` prepends a translation table
(`wren --sql` → `run_sql`, `wren memory recall` → `recall_queries`, …) and tells
the model to skip the install/configure sections.

Loading shells out to the CLI, so it works when `wren` is on PATH — the wrenai
base image, or `STDIO` transport. With a remote HTTP Wren it degrades quietly to
the built-in instruction. Disable with `wren.adk.skills.enabled=false`.

## Configuration

```yaml
wren:
  adk:
    provider: ANTHROPIC          # or GEMINI
    model: ""                    # blank = the provider's default
    agent-name: wren_analyst
    read-only: true              # withhold Wren's store_query write tool
    transpile-only: false        # true = plan SQL but never touch the warehouse
    instruction: ""              # blank keeps the built-in analytics prompt
    skills:
      enabled: true              # append Wren's own guides to the instruction
      names: [usage]             # `usage` is the querying workflow
      command: wren
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}       # x-api-key
      auth-token: ${ANTHROPIC_AUTH_TOKEN:} # Authorization: Bearer (wins)
      base-url: ${ANTHROPIC_BASE_URL:}     # blank = api.anthropic.com
      max-tokens: 8192
      disable-thinking: true               # see the note above
    gemini:
      api-key: ${GOOGLE_API_KEY:}
    mcp:
      transport: STDIO           # or STREAMABLE_HTTP
      command: wren              # STDIO: spawned as a child process
      args: [serve, mcp]
      project-home: /project
      env: {}                    # extra env for the spawned process
      url: http://127.0.0.1:8080/mcp   # STREAMABLE_HTTP only
      headers: {}
      timeout: 60s
```

Every bean is `@ConditionalOnMissingBean`, so declaring your own `BaseLlm`,
`LlmAgent`, `BaseSessionService` or `McpToolset` replaces the default. Set
`wren.adk.enabled=false` to switch the whole thing off.

### Transport

**`STDIO`** spawns `wren serve mcp` as a child process. Nothing listens on the
network, and the process lifetime is tied to the Spring context — the toolset
bean's `destroyMethod = "close"` terminates it on shutdown. This is the default
and the right choice for the container image below, which already has the CLI
on `PATH`.

**`STREAMABLE_HTTP`** talks to a Wren instance over HTTP: a sidecar on loopback,
or a shared service.

> Wren holds MCP session state **in-process**. A request routed to a different
> replica is rejected with `Session not found`, so a shared multi-replica
> endpoint needs session affinity hashed on the `mcp-session-id` header. One
> Wren instance also serves **one query at a time** (single pooled connection),
> so size replicas to peak concurrent queries. Co-locating one Wren per agent
> avoids both problems.

### Safety gating

`read-only: true` (default) withholds `store_query`. `transpile-only: true`
passes `--no-connect`, disabling `run_sql`, `dry_run` and `query_cube` — the
agent can still explore the schema and plan SQL but cannot reach the warehouse.
Pair with `strict_mode` in the Wren CLI's own `~/.wren/config.json` to reject
any SQL referencing tables outside the MDL.

## Container image

Built with Jib on `ghcr.io/openprojectx/wrenai`, which carries the `wren` CLI,
a JDK, Python and Node — so `STDIO` transport works with nothing else
installed.

```shell
./gradlew :app:jibDockerBuild --no-configuration-cache \
  -PwrenBaseImage=docker://ghcr.io/openprojectx/wrenai:0.1.0 \
  -PappImage=wren-adk-app
```

`--no-configuration-cache` is required: Jib 3.5.4 is not compatible with
Gradle's configuration cache, which this build enables by default.

Push to a registry with `:app:jib` instead. Override `-PwrenBaseImage` /
`-PappImage` to retarget; the `docker://` prefix reads the base from the local
Docker daemon rather than a registry.

The image runs as uid 10001 (non-root), exposes 8080, and sets
`WREN_PROJECT_HOME=/project` — mount your Wren project there.

## Local development

```shell
cp .env.example .env      # fill in the LLM credentials
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Opens ADK's Dev UI on <http://localhost:8080>. The `local` profile points at a
Wren project on disk (`/data/Git/WrenAI-demo` by default — override with
`WREN_PROJECT_HOME`), spawns `wren serve mcp` over STDIO, and turns on verbose
tracing.

`bootRun` reads `.env` two ways: its working directory is set to the repo root
so spring-dotenv finds the file, *and* the values are passed as process
environment variables. Sourcing `.env` into your shell is not sufficient — the
Gradle daemon is long-lived, so forked JVMs inherit its stale environment.

### Reading the trace

`wren.adk.verbose` plus the log levels in `application-local.yaml` produce a
turn-by-turn trace. The line that matters most shows the tool the model chose
and the SQL it wrote:

```
DEBUG Claude       Claude response: Message{id=msg_…, content=[…]}
DEBUG BaseLlmFlow  event: {…"functionCalls":[FunctionCall{id=toolu_…,
                   name=run_sql, args={sql=SELECT COUNT(*) AS order_count
                   FROM orders}}]…}
DEBUG BaseLlmFlow  Ending flow execution based on final response
```

`io.modelcontextprotocol` at DEBUG adds the raw JSON-RPC to and from
`wren serve mcp`, including the tool schemas it advertises — decisive when a
tool call misbehaves.

> `wren.adk.verbose` registers ADK's `LoggingPlugin`, which would give a
> purpose-built trace. On **ADK 1.8.0 it emits nothing through the Dev UI**:
> verified at TRACE with the bean confirmed present and created before the
> runner, even though `RunnerService` does call `Runner.Builder.plugins(...)`.
> The log levels above are what actually produce the trace. The bean is kept
> because the wiring is correct and costs nothing.

Verbose tracing logs prompts and tool payloads, so it is off by default and
enabled only in the `local` profile.

## Tests

Integration tests run against real containers: Postgres seeded with an e-shop
fixture, and Wren serving MCP over HTTP against it.

```shell
./gradlew :app:test
```

Test tasks read the project `.env` directly. Sourcing it into your shell is not
enough — the Gradle daemon is long-lived, so test JVMs inherit its stale
environment rather than yours. The `.env` contents are also fingerprinted as a
task input, otherwise the build cache replays a result produced when those
variables were absent, and the live tests appear to "skip" forever.

| Test | Asserts |
|---|---|
| `EshopFixtureTest` | Row counts, order totals reconcile to line items, no orphaned FKs, status enum |
| `WrenMcpIntegrationTest` | Wren exposes its tool surface; `store_query` stays hidden when read-only |
| `WrenAdkAutoConfigurationTest` | The starter wires a toolset, agent and runner, and the ADK Dev UI loader discovers the agent |
| `WrenLlmProviderTest` | `provider` selects the right `BaseLlm`; per-provider default models |
| `WrenSkillsTest` | Guide loading, front-matter stripping, and quiet degradation when the CLI is absent |
| `AnthropicCompatibleLiveTest` | Live call against the configured endpoint — **skipped unless `ANTHROPIC_BASE_URL` is set** |
| `WrenAgentLiveTest` | Full loop: model calls Wren tools, Wren queries Postgres, model answers from the result — also gated on `ANTHROPIC_BASE_URL` |

### On tool-use reliability

`WrenAgentLiveTest` asserts the agent actually *called* a tool, not merely that
it produced a plausible answer. That check earns its keep: during development
the model once replied `180` to a row count it had never queried — the real
value is 212. A semantic layer only protects you if the model uses it.

The instruction was hardened in response (it now states outright that the agent
has no knowledge of the database and must call a tool before asserting any
fact). Measured afterwards against qwen3.8-max on an Aliyun MaaS endpoint:
**5/5 runs called `list_models` then `run_sql` and answered correctly.** Worth
re-measuring if you change model or instruction — this is a model-behaviour
property, not a code guarantee.

The fixture (`app/src/test/resources/db/eshop.sql`, 1,120 rows across 8 tables)
is a referentially-intact subset of the WrenAI demo dataset. **It is synthetic —
do not treat its numbers as real trading data.**

The Wren container image is `ghcr.io/openprojectx/wrenai:0.1.0` by default;
override with the `WREN_IMAGE` environment variable. It starts by creating a
connection profile from `connection.yml` placeholders, compiling the MDL, then
serving — so a healthy container means the semantic layer is genuinely ready.

No test calls the LLM. That would need `GOOGLE_API_KEY` and would make a fast,
deterministic suite slow, flaky and billable; what these tests own is the
wiring.

## Building

Requires JDK 21+.

```shell
./gradlew build
```
