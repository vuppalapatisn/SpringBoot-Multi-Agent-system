# CLAUDE.md — 05 MCP Client with a Trust Boundary

Read [`docs/CFG.md`](docs/CFG.md) first. Repo-wide rules: [`../CLAUDE.md`](../CLAUDE.md).
Pairs with [project 04](../../04-mcp-server-tools/), the server.

## Entry points

| | |
|---|---|
| HTTP | `web/RemoteAgentController` |
| Agent | `service/RemoteRefundAgent` |
| **The trust boundary** | `trust/RemoteToolPolicy` ← start here |
| Allowlist entries | `trust/TrustedTool`, `config/McpTrustProperties` |
| Wiring (filter, prefixes) | `config/McpClientTrustConfig` |
| Admissions + rug-pull alarm | `trust/ToolAdmissionLog` |

## Invariants

1. **Deny by default.** A tool absent from `allowed-tools` is never offered. Do not add a
   "trust this server entirely" shortcut.
2. **`getRefundStatus` stays out of the allowlist.** It is the demonstration that the allowlist,
   not the server's tool list, is the decision. `allowlistIsExplicit` enforces it.
3. **The server name is part of the tool identity.** Never match on tool name alone.
4. **Descriptions are untrusted.** Keep the instruction-shape scan; never render a remote
   description into the system prompt.
5. **Our `expected-class` outranks the server's hints.** A hint is evidence, not authority.
6. **Filter before the model, not after.** `McpToolFilter` runs before a `ToolCallback` exists, so
   a withheld tool's description never enters the context window.
7. **Degrade, do not throw.** No server or no admitted tools ⇒ explain and attempt nothing.
   `degradesWhenNoToolsAreAvailable` enforces it.
8. **Tool results are `R1` data.** Nothing may let a result select a tool or fill an argument.

## Adding a remote tool

1. Update `docs/CFG.md` (boundary table + trust-boundary row).
2. Add an `allowed-tools` entry with **our** `expected-class` and a real `reviewed-by`.
3. Connect with a blank fingerprint, read `GET /api/mcp/tools`, **review the definition**, pin the
   hash, set `fail-closed-on-change: true`.
4. Add a `spring.ai.tools.limits.max-calls-per-tool` entry using the **prefixed** name.
5. Add a `RemoteToolPolicyTest` case if the tool introduces a new shape of risk.

## Tests

`mvn -q test` — 15 tests, no network, **no MCP server required**. Keep it that way:
`RemoteToolPolicyTest` uses synthetic `McpSchema.Tool` fixtures, and `McpClientContextTest` sets
`spring.ai.mcp.client.enabled=false`. A test that needs project 04 running does not belong in the
default build.

## Known exception

Shipped config has `fail-closed-on-change: false` and blank fingerprints — the honest first-contact
state, since you cannot pin what you have never seen. Do not "fix" this by inventing fingerprints;
follow the pinning workflow in the README.
