# Running with Docker Desktop on macOS

Step by step, including every value you need to supply.

**Short version:** pick a provider and give it a key. Two variables:

```bash
AI_CHAT_PROVIDER=anthropic       ANTHROPIC_API_KEY=sk-ant-...
# or
AI_CHAT_PROVIDER=google-genai    GEMINI_API_KEY=AIza...
```

Only the selected provider's key is required. Project 04 needs neither, because an MCP server has
no model in it. See [§2](#2-provide-the-one-value-you-need) and
[§2a](#2a-using-google-gemini-instead-of-anthropic).

---

## 0. Prerequisites

| | |
|---|---|
| Docker Desktop | 4.x with Compose v2 (bundled) |
| Disk | ~4 GB for images, plus the Maven cache during builds |
| Memory | Docker Desktop's default is fine for a few services; see [§8](#8-memory) for all nine |
| Apple Silicon | **Works natively.** Both base images publish `linux/arm64`, so no Rosetta and no `--platform` flag |

Install Docker Desktop:

```bash
brew install --cask docker
```

…or download it from [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/).

**Then launch Docker Desktop from Applications and wait for the whale icon in the menu bar to stop
animating.** The CLI talks to a daemon that only runs while the app is running — "cannot connect to
the Docker daemon" almost always means the app is not started.

Verify:

```bash
docker --version          # Docker version 27.x or newer
docker compose version    # Docker Compose version v2.x  (note: space, not a hyphen)
docker info | grep -i arch   # aarch64 on Apple Silicon, x86_64 on Intel
```

---

## 1. Get the code

```bash
git clone https://github.com/vuppalapatisn/SpringBoot-Multi-Agent-system.git
cd SpringBoot-Multi-Agent-system
```

---

## 2. Provide the one value you need

Copy the template and fill in a key:

```bash
cp .env.example .env
```

`.env` is gitignored, so the key cannot be committed by accident. Compose injects it into the
containers with `env_file`, which matters: a variable **absent** from `.env` is absent from the
container, so the application's placeholder stays unresolved and it fails fast with a clear
message. Passing an empty string instead would let it start with a blank key and fail later, on the
first model call.

For Anthropic (the default), get a key from
[console.anthropic.com](https://console.anthropic.com/) → **API Keys**; it looks like
`sk-ant-api03-…`. Your `.env` then reads:

```bash
AI_CHAT_PROVIDER=anthropic
ANTHROPIC_API_KEY=sk-ant-api03-…
```

That is the whole configuration. Everything else has a working default.

### Every variable, and whether you need it

| Variable | Needed? | Default | What it does |
|----------|---------|---------|--------------|
| `AI_CHAT_PROVIDER` | no | `anthropic` | `anthropic` \| `google-genai`. Which provider is active. |
| `ANTHROPIC_API_KEY` | only if provider is `anthropic` | none — startup fails loudly | Anthropic credential. Never baked into an image. |
| `GEMINI_API_KEY` | only if provider is `google-genai` | none — startup fails loudly | Gemini credential. |
| `GEMINI_MODEL` | no | `gemini-2.5-flash` | which Gemini model to use |
| `JAVA_OPTS` | no | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | JVM flags. The percentage means one image behaves sensibly under any memory limit. |
| `AGENTIC_TOOLS_EXECUTIONMODE` | no | `EXECUTE` | project 02's kill switch: `EXECUTE` \| `DRY_RUN` \| `DISABLED` |
| `AGENTIC_MCPSERVER_EXECUTIONENABLED` | no | `true` | project 04's kill switch. `false` stops refunds; reads keep working. |
| `SPRING_APPLICATION_JSON` | no | set for you | how project 05 is pointed at project 04. See [§5](#5-the-mcp-pair). |

**Project 04 needs no API key at all**, from either provider. That is the point of it: an MCP server
is an ordinary Spring Boot service that publishes tools, which is exactly why it has to enforce its
own policy rather than trust a well-behaved caller. `docker compose up 04-mcp-server` works with no
`.env` at all.

---

## 2a. Using Google Gemini instead of Anthropic

Supported, and it is a **configuration change only** — no code, no rebuild of anything but the
config. Put this in `.env`:

```bash
AI_CHAT_PROVIDER=google-genai
GEMINI_API_KEY=AIza…
# optional
GEMINI_MODEL=gemini-2.5-flash
```

Get the key from [aistudio.google.com/apikey](https://aistudio.google.com/apikey) (the Gemini
Developer API). Then run as normal:

```bash
docker compose up -d 06-workflow
```

### Why it works without touching the code

Nothing in `src/main` imports a provider-specific type — every `ChatClient` is built with the
neutral `ChatOptions.builder()`. Both starters are on the classpath and
`spring.ai.model.chat` decides which auto-configuration activates.

The compatibility question that actually matters is whether Gemini's options carry the two
capabilities this repository leans on, and they do:

```java
public class GoogleGenAiChatOptions
        implements ToolCallingChatOptions, StructuredOutputChatOptions
```

That is the same pair as `AnthropicChatOptions`. Tool calling is skipped entirely by
`ToolCallingAdvisor` unless the model's options implement `ToolCallingChatOptions`, and `.entity()`
structured output relies on `StructuredOutputChatOptions` — so projects 02, 05, 08 and 09 work on
either provider.

This is asserted rather than asserted-in-prose:
[`GeminiProviderTest`](../01-chatclient-foundation/src/test/java/io/github/vuppalapatisn/agentic/foundation/GeminiProviderTest.java)
boots the whole application context on Gemini **with no Anthropic key present at all**, and checks
both interfaces and the bound model name.

### Property mapping

| Concept | Anthropic | Gemini |
|---------|-----------|--------|
| starter | `spring-ai-starter-model-anthropic` | `spring-ai-starter-model-google-genai` |
| selector value | `anthropic` | `google-genai` |
| key | `spring.ai.anthropic.api-key` | `spring.ai.google.genai.api-key` |
| model | `spring.ai.anthropic.chat.options.model` | `spring.ai.google.genai.chat.options.model` |
| token ceiling | `…chat.options.max-tokens` | `…chat.options.max-output-tokens` |
| default here | `claude-sonnet-5` | `gemini-2.5-flash` |

### Vertex AI instead of the Developer API

An API key selects the Gemini Developer API. For Vertex AI, drop `GEMINI_API_KEY` and set:

```bash
SPRING_AI_GOOGLE_GENAI_VERTEXAI=true
SPRING_AI_GOOGLE_GENAI_PROJECTID=your-gcp-project
SPRING_AI_GOOGLE_GENAI_LOCATION=europe-west1
```

…and mount application-default credentials into the container. If both a key and project/location
are present, Spring AI logs which one it chose and defaults to the Developer API.

### Two caveats worth knowing

* **Project 03's embeddings are unaffected.** It uses the repo's own offline
  `HashingEmbeddingModel`, so RAG behaves identically on either provider. If you want real
  embeddings, `spring-ai-starter-model-google-genai-embedding` exists at the same version.
* **Gemini is stricter about tool JSON schemas** than Anthropic. Spring AI ships a
  `GoogleGenAiToolCallingManager` decorator for exactly this, and it is *not* wired by default. If
  a tool call fails with a schema complaint on Gemini, register that manager as a bean. None of the
  tools here has hit it, but they are deliberately simple — single `String` parameters.

---

## 3. Build

Start with **one** project. The first build of any project downloads its Maven dependencies, which
takes a few minutes; nine projects resolve independently, so `build` for all of them is a coffee
break, not a moment.

```bash
docker compose build 06-workflow
```

Later builds of the same project are fast: the `Dockerfile` copies `pom.xml` before `src`, so a
code-only change reuses the cached dependency layer.

To build everything:

```bash
docker compose build          # all nine; expect 10-20 minutes cold, depending on your connection
```

---

## 4. Run one project

```bash
docker compose up -d 06-workflow
docker compose ps
```

```
NAME                             STATUS                   PORTS
agentic-reference-06-workflow-1  Up 12 seconds (healthy)  0.0.0.0:8086->8086/tcp
```

**Wait for `(healthy)`.** The first health probe runs ~10 s after start; until then the status reads
`(health: starting)`, which is normal, not a hang.

Try it:

```bash
curl -s localhost:8086/api/refunds/run \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"A-1204","message":"The cable stopped working after two days."}' | jq
```

Follow the logs, then stop:

```bash
docker compose logs -f 06-workflow
docker compose down                 # stops and removes; add -v to drop volumes too
```

### Service names, ports and a first request

Service names are what you pass to `docker compose`; ports follow project number → `808N`.

| Service | Port | Key? | Try |
|---------|------|------|-----|
| `01-foundation` | 8081 | yes | `POST /api/refunds/classify` `{"orderId":"A-1187","message":"Never arrived."}` |
| `02-guardrails` | 8082 | yes | `POST /api/refunds/handle` — then `GET /api/approvals` |
| `03-rag` | 8083 | yes | `POST /api/policy/ask` `{"tenant":"acme","question":"Parcel never received - refundable?"}` |
| `04-mcp-server` | 8084 | **no** | `GET /admin/approvals` |
| `05-mcp-client` | 8085 | yes | `POST /api/refunds/handle` — then `GET /api/mcp/tools` |
| `06-workflow` | 8086 | yes | `POST /api/refunds/run` |
| `07-state-machine` | 8087 | yes | `POST /api/refunds/start` — then `GET /api/runs/{id}/transitions` |
| `08-agent-loop` | 8088 | yes | `POST /api/refunds/run` |
| `09-multi-agent` | 8089 | yes | `POST /api/refunds/run` — then `GET /api/agents/authority` |

Every service also serves `GET /actuator/health`. Each project's own `README.md` has the full
endpoint list and more example requests.

Seeded orders, in every project: `A-1204` (\$89.90, pays automatically) · `A-1187` (\$240, needs one
approver) · `A-0988` (\$1,899, needs two) · `A-1310` (in transit, declined by rule).

---

## 5. The MCP pair

Projects 04 and 05 are a client/server pair, and compose wires them together:

```bash
docker compose up -d 04-mcp-server 05-mcp-client
```

The client has `depends_on: condition: service_healthy`, so it waits for the server's health check
rather than racing it — expect roughly 10–20 s before the client starts.

```bash
curl -s localhost:8085/api/refunds/handle -H 'Content-Type: application/json' \
  -d '{"orderId":"A-1187","message":"The parcel never arrived."}' | jq

# what the client's trust boundary admitted, withheld, and why
curl -s localhost:8085/api/mcp/tools | jq
```

Inside the compose network the client reaches the server at `http://04-mcp-server:8084` — the
service name, resolved by Docker's DNS, not `localhost`. That override is passed as
`SPRING_APPLICATION_JSON` because the connection is a map entry whose key contains a hyphen
(`refund-desk`), and environment-variable relaxed binding cannot express that.

---

## 6. Kill switches, without a rebuild

```bash
AGENTIC_TOOLS_EXECUTIONMODE=DRY_RUN docker compose up -d 02-guardrails
```

Now project 02 validates and audits everything but performs no effect — the behaviour Phase 10 of
the [design checklist](00-DESIGN-CHECKLIST.md) requires of a kill switch.

> **Mind that name.** Spring's relaxed binding replaces dots with underscores and **removes
> hyphens**, so `agentic.tools.execution-mode` becomes `AGENTIC_TOOLS_EXECUTIONMODE`.
> `AGENTIC_TOOLS_EXECUTION_MODE` binds to `agentic.tools.execution.mode`, which does not exist, and
> is ignored **silently**. A kill switch that quietly does nothing is worse than no kill switch.

---

## 7. Prove project 07's durability claim

This is worth doing, because it is the difference between projects 06 and 07 and the container makes
it concrete. The state lives in a named volume mounted at `/app/data`.

```bash
docker compose up -d 07-state-machine

# start a run that needs a human: $240 is above the automatic tier
RUN=$(curl -s localhost:8087/api/refunds/start -H 'Content-Type: application/json' \
      -d '{"orderId":"A-1187","message":"The parcel never arrived."}' | jq -r .runId)

# restart the container - a real process death, not a pause
docker compose restart 07-state-machine
sleep 20

# the run is still exactly where it was
curl -s "localhost:8087/api/runs/$RUN" | jq '{state, gateRule, amountMinor}'
curl -s "localhost:8087/api/runs/$RUN/transitions" | jq
```

`state` is still `AWAITING_APPROVAL`, and the transition history survived. Do the same against
project 06 and the pending approval is gone — which is the migration trigger written down in
[06's CFG](../06-workflow-orchestration/docs/CFG.md#5-approval-points).

`docker compose down` keeps the volume; `docker compose down -v` deletes it.

---

## 8. Memory

`mem_limit: 1g` in `docker-compose.yml` is a **ceiling, not a reservation**. Each service idles at
roughly 250–400 MB, so all nine together want about 3–4 GB of actual use, which fits inside Docker
Desktop's usual default allocation.

If a container dies with exit code **137**, it was OOM-killed. Raise the VM's memory in
**Docker Desktop → Settings → Resources → Memory** (8 GB is comfortable for all nine), or just run
fewer services — two or three at a time is how this repo is normally used.

---

## 9. Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| `Cannot connect to the Docker daemon` | Docker Desktop is not running. Launch it and wait for the menu-bar icon to settle. |
| Container exits at once; log says `Could not resolve placeholder 'ANTHROPIC_API_KEY'` | The active provider's key is not reaching the container. Check `.env` sits next to `docker-compose.yml`, and that `AI_CHAT_PROVIDER` matches the key you supplied. |
| Same, but `'GEMINI_API_KEY'` | You set `AI_CHAT_PROVIDER=google-genai` without `GEMINI_API_KEY`. See [§2a](#2a-using-google-gemini-instead-of-anthropic). |
| `401` / `authentication_error` / `API_KEY_INVALID` | The key is present but wrong, revoked, or out of credit. Test it directly against the provider's API. |
| Startup fails with two `ChatModel` candidates | `spring.ai.model.chat` is not set. Both starters are on the classpath by design; the selector must pick one. `AI_CHAT_PROVIDER` supplies it and defaults to `anthropic`. |
| Gemini tool call fails complaining about the schema | Gemini is stricter than Anthropic here. Register Spring AI's `GoogleGenAiToolCallingManager` — see the caveats in [§2a](#2a-using-google-gemini-instead-of-anthropic). |
| `env_file … required` rejected by compose | Compose older than v2.24. Update Docker Desktop, or change `env_file` to the plain `- .env` form and create the file. |
| `Bind for 0.0.0.0:8086 failed: port is already allocated` | Something else holds the port: `lsof -i :8086`. Stop it, or remap in compose (`"9086:8086"`). |
| Status stuck at `(health: starting)` | Give it ~45 s. If it stays there: `docker compose logs <service>`. |
| Exit code `137` | OOM-killed. See [§8](#8-memory). |
| `docker-compose: command not found` | Compose v2 is a subcommand: `docker compose`, with a space. |
| Build very slow the first time | Each project resolves its own Maven dependencies. Expected. Subsequent builds reuse the cached dependency layer. |
| `no matching manifest for linux/arm64` | Should not happen — both base images are multi-arch. If you forced `--platform linux/amd64`, drop the flag. |

---

## 10. Using the published images instead of building

CI publishes every project to GHCR on each push to `main`, so you can skip the build entirely:

```bash
docker run --rm -p 8086:8086 \
  -e ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
  ghcr.io/vuppalapatisn/workflow-orchestration:latest
```

Image names are the Maven artifactIds: `chatclient-foundation`, `tool-calling-guardrails`,
`rag-grounded-answers`, `mcp-server-tools`, `mcp-client-agent`, `workflow-orchestration`,
`state-machine-orchestration`, `autonomous-agent-loop`, `multi-agent-supervisor`.

Tags: `latest`, the branch name, `sha-<commit>`, and `x.y.z` for `v*` tags. If the package is
private, `docker login ghcr.io` with a personal access token that has `read:packages`.

---

## 11. Cleaning up

```bash
docker compose down                     # stop and remove containers
docker compose down -v                  # also delete project 07's volume
docker compose down --rmi local         # also delete the images this repo built
docker system prune -a                  # reclaim everything unused (affects all your projects)
```
