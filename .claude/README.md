# .claude

Claude Code's per-project directory. The agent instructions themselves live in
[`../AGENTS.md`](../AGENTS.md) (with [`../CLAUDE.md`](../CLAUDE.md) as the pointer
Claude Code reads); nothing here changes what the agent is told to do.

What git keeps and ignores here (`../.gitignore`):

| File | Tracked | Purpose |
|---|---|---|
| `README.md` | yes | this note |
| `skills/*/SKILL.md` | yes | project skills for agents working here — `design-docs` maps the design documents and states the rules for changing them |
| `settings.json` | yes, if present | project-wide Claude Code settings shared by everyone working on Brightside |
| `settings.local.json` | no | one person's own permissions and overrides |
| anything else | no | personal state |

Keep shared settings minimal and explain them in a commit; personal ones
belong in `settings.local.json`.

The MCP server that lets Claude Code see the running Brightside venue is not
here but in the repository root's `.mcp.json` (project scope, git-ignored
because it carries the venue token) — see `docs/CONFIGURATION.md`, *From
Claude Code*, for the file to create.
