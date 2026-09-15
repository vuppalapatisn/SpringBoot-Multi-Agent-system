# Brief — 04 MCP Server

**Purpose:** publish tools to agents you do not control.

**Read first:** `04-mcp-server-tools/docs/CFG.md`. Pairs with project 05.

**Start in:** `service/RefundDesk` — all policy lives there; `tools/RefundMcpTools` is an adapter.

**Do not break**
- No `approve*` MCP tool, ever. Separation of duty is contract-tested.
- No amount or recipient parameter on any tool.
- Policy stays testable with no MCP in the picture.
- Annotation hints stay honest (`destructiveHint = true` on `issueRefund`).
- Tool descriptions stay factual: they are injected into someone else's model context.
- Reads keep working when the kill switch is on.

**Changing the published contract** (tool set, name, schema, hint) is a breaking change for clients
that pinned it. Update `McpServerContractTest` in the same commit.

**Tests:** 17, no model needed at all.
