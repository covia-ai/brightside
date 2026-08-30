# .claude

Claude Code's per-project directory. The agent instructions themselves live in
[`../AGENTS.md`](../AGENTS.md) (with [`../CLAUDE.md`](../CLAUDE.md) as the pointer
Claude Code reads); nothing here changes what the agent is told to do.

What git keeps and ignores here (`../.gitignore`):

| File | Tracked | Purpose |
|---|---|---|
| `README.md` | yes | this note |
| `settings.json` | yes, if present | project-wide Claude Code settings shared by everyone working on Brightside |
| `settings.local.json` | no | one person's own permissions and overrides |
| anything else | no | personal state |

Keep shared settings minimal and explain them in a commit; personal ones
belong in `settings.local.json`.
