# Brief — 05 MCP Client with a Trust Boundary

**Purpose:** consume tools from a server you do not operate.

**Read first:** `05-mcp-client-agent/docs/CFG.md`. Pairs with project 04.

**Start in:** `trust/RemoteToolPolicy` — four rules decide whether the model may see a tool.

**Do not break**
- Deny by default; `getRefundStatus` stays off the allowlist as the demonstration.
- The server name is part of the tool identity.
- Descriptions are untrusted: keep the instruction-shape scan, never render them into a prompt.
- Our `expected-class` outranks the server's hints.
- Filter before the model, not after.
- Degrade rather than throw when no tools are admitted.

**Known state:** shipped config is `fail-closed-on-change: false` with blank fingerprints - the
honest first-contact state. Follow the pinning workflow in the README; do not invent fingerprints.

**Tests:** 15, and **no MCP server required**. Keep it that way.
