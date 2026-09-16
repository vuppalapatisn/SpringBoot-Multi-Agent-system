# Running with Docker Desktop on macOS

Step by step, including every value you need to supply.

**Short version:** there is exactly **one** variable to set — `ANTHROPIC_API_KEY` — and eight of the
nine projects need it. Project 04 needs nothing, because an MCP server has no model in it.

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

Get a key from [console.anthropic.com](https://console.anthropic.com/) → **API Keys**. It looks like
`sk-ant-api03-…`.

Create a `.env` file in the repository root. Compose reads it automatically, and `.env` is already
in `.gitignore`, so the key cannot be committed by accident:

```bash
cat > .env <<'EOF'
ANTHROPIC_API_KEY=sk-ant-api03-replace-me
EOF
```

That is the whole configuration. Everything else has a working default.

> **Why `.env` and not `export`?** Both work, but `docker compose` interpolates the entire file
> before it picks which services to start — so the variable has to be set even when you only want
> project 04, which does not use it. A `.env` file makes that a non-issue. If you would rather not
> create one, prefix the command instead:
> `ANTHROPIC_API_KEY=unused docker compose up 04-mcp-server`

### Every variable, and whether you need it

| Variable | Needed? | Default | What it does |
|----------|---------|---------|--------------|
| `ANTHROPIC_API_KEY` | **yes**, for 8 of 9 | none — startup fails loudly without it | the model provider credential. Never baked into an image. |
| `JAVA_OPTS` | no | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | JVM flags. The percentage means one image behaves sensibly under any memory limit. |
| `AGENTIC_TOOLS_EXECUTIONMODE` | no | `EXECUTE` | project 02's kill switch: `EXECUTE` \| `DRY_RUN` \| `DISABLED` |
| `AGENTIC_MCPSERVER_EXECUTIONENABLED` | no | `true` | project 04's kill switch. `false` stops refunds; reads keep working. |
| `SPRING_APPLICATION_JSON` | no | set for you | how project 05 is pointed at project 04. See [§5](#5-the-mcp-pair). |

**Project 04 needs no API key at all.** That is the point of it: an MCP server is an ordinary Spring
Boot service that publishes tools, which is exactly why it has to enforce its own policy rather than
trust a well-behaved caller.

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
| `error while interpolating services: required variable ANTHROPIC_API_KEY is missing` | No `.env` and no exported variable. See [§2](#2-provide-the-one-value-you-need). Compose interpolates the whole file, so this happens even for project 04. |
| Container exits at once; log says `Could not resolve placeholder 'ANTHROPIC_API_KEY'` | The variable reached compose but not the container. Check `docker compose config` shows a value, and that `.env` is in the repo root next to `docker-compose.yml`. |
| `401` / `authentication_error` from the provider | The key is present but wrong, revoked, or has no credit. `curl` it directly against the Anthropic API to confirm. |
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
