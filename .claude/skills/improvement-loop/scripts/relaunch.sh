#!/bin/sh
# Asks Odin to relaunch Tau into a jar, saying why, waits for the new process,
# and prints its info. Tau starts its successor itself, so it outlives this
# session.
#   relaunch.sh "<why>" [jar]   jar: file://code/brightside/target/brightside.jar
#                               by default; file://code/last-good.jar is the
#                               rollback
# Odin judges every relaunch, so <why> says what it is for and, for a new
# build, what changed and how it was tested ("tests: 463 green"). The build's
# commit and the rollback go with it. If Odin holds instead of relaunching, his
# answer is printed and the script fails: answer what he asked, or take it to
# the owner. Never relaunch the same jar again without the reasons he asked for.
here=$(dirname "$0")
why="$1"
jar="${2:-file://code/brightside/target/brightside.jar}"
[ -n "$why" ] || { echo 'usage: relaunch.sh "<why>" [jar]' >&2; exit 2; }
worktree="${W:-$HOME/.brightside/code/brightside}"
build=$(git -C "$worktree" log -1 --format='%h %s' 2>/dev/null)

pid_now() {
	"$here/mcp.sh" grid_run '{"operation":"v/ops/brightside/info"}' 2>/dev/null \
		| python -I -c "import json,sys;print(json.loads(sys.stdin.read()).get('pid',''))" 2>/dev/null
}

# What Odin's task says: its status, then his latest words.
odin_said() {
	"$here/mcp.sh" covia_read "{\"path\":\"j/$1\"}" 2>/dev/null | python -I -c '
import json, sys
sys.stdout.reconfigure(encoding="utf-8")
try:
	v = json.loads(sys.stdin.read()).get("value") or {}
except ValueError:
	sys.exit()
said = v.get("output") or v.get("error") or v.get("progress")
if isinstance(said, dict):
	said = "\n".join(str(x) for x in said.values())
print(v.get("status", ""))
print(said if isinstance(said, str) else json.dumps(said, ensure_ascii=False))
'
}

before=$(pid_now)
request=$(python -I - "$jar" "$why" "$build" <<'EOF'
import json, sys
jar, why, build = sys.argv[1:4]
context = "Why: " + why
if jar.endswith("code/brightside/target/brightside.jar") and build:
	context += "\nThe build: the loop's worktree at " + build + "."
context += ("\nThe rollback: file://code/last-good.jar, the last build that ran here and passed the"
	" machine checks.")
print(json.dumps({"operation": "v/ops/brightside/ask-odin", "input": {
	"request": "Relaunch Tau into " + jar + " (brightside:relaunch with that jar). This is the daily"
		" self-improvement loop, whose relaunches the owner has approved as a standing decision.",
	"context": context, "timeout": 300000}}))
EOF
)
# Odin's job may still be running when the old venue shuts down under it: that
# is the relaunch under way, so only any other failure stops here.
out=$("$here/mcp.sh" grid_run "$request") \
	|| printf '%s' "$out" | grep -q '"status": "running"' \
	|| { printf '%s\n' "$out"; echo "Odin did not relaunch" >&2; exit 1; }
printf '%s\n' "$out"
task=$(printf '%s' "$out" | python -I -c "import json,sys;print(json.loads(sys.stdin.read()).get('id',''))" 2>/dev/null)

# The old process answers, then stops; the newcomer takes over the store and
# writes a fresh tool token. Wait for a different pid (up to five minutes),
# stopping early if Odin's task ends without one.
i=0
while [ $i -lt 100 ]; do
	pid=$(pid_now)
	if [ -n "$pid" ] && [ "$pid" != "$before" ]; then
		"$here/mcp.sh" grid_run '{"operation":"v/ops/brightside/info"}'
		exit 0
	fi
	if [ -n "$task" ] && [ -n "$pid" ]; then
		case "$(odin_said "$task" | head -1)" in
			COMPLETE|FAILED|CANCELLED|REJECTED)
				echo "Odin did not relaunch. He said:" >&2
				odin_said "$task" | tail -n +2 >&2
				exit 1 ;;
		esac
	fi
	i=$((i + 1))
	timeout 3 tail -f /dev/null 2>/dev/null
done
echo "Tau did not come back from the relaunch (old pid $before)." >&2
if [ -n "$task" ]; then
	echo "Odin is holding it. He said:" >&2
	odin_said "$task" | tail -n +2 >&2
fi
exit 1
