# Authoring a skill

A skill is a small asset you write to **`w/skills/<name>`** — your own skill
space. Once written it appears in your skills index and you can load it whenever
it's relevant.

## Shape

- `name` — short kebab-case id, matching the last path segment
- `description` — ONE line: what it does and when to load it (this is all you
  see in the index, so make it a good trigger)
- `content.contentType` — `"text/markdown"`
- `content.inline` — the skill body: the actual instructions, in markdown
- `skill` — optional extras, e.g. `{ "tools": ["v/ops/..."] }` to bring in
  operations while the skill is loaded, or `{ "skills": ["w/skills/other"] }`
  to reveal a sub-skill

## How

Use the `covia_write` tool. For example, to teach yourself the user's email
style, write path `w/skills/email-style` with value:

```json
{
  "name": "email-style",
  "description": "How the user likes emails written. Load when drafting email.",
  "content": { "contentType": "text/markdown",
               "inline": "Keep emails short and warm; sign off with just a first name; ..." },
  "skill": {}
}
```

Then it's part of you: load it with the skill-load tool when the task calls for
it. Keep each skill focused and give it a precise description. Confirm with the
user before saving a skill on their behalf, and tell them what you captured.
