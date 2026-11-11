#!/bin/sh
# Machine checks that the running Tau is healthy, without asking a model:
# it answers, its last start reported no problems, its agents are not stuck,
# and the workspace, skills and memory read and write. Prints one line per
# check and exits 1 if any fails.
here=$(dirname "$0")
home="${BRIGHTSIDE_HOME:-$HOME/.brightside}"
fail=0
check() { if [ "$2" = 0 ]; then echo "ok    $1"; else echo "FAIL  $1: $3"; fail=1; fi; }

info=$("$here/mcp.sh" grid_run '{"operation":"v/ops/brightside/info"}' 2>&1); check "Tau answers" $? "$info"

since=$(grep -n "ready at" "$home/logs/brightside.log" | tail -1 | cut -d: -f1)
problems=$(tail -n "+${since:-1}" "$home/logs/brightside.log" | grep -E "Startup check|ERROR" | grep -v "^\s*at " | head -5)
[ -z "$problems" ]; check "last start reported no problems" $? "$problems"

for agent in Brightside ${LOOP_AGENT:-Improver}; do
	status=$("$here/mcp.sh" agent_info "{\"agentId\":\"$agent\"}" 2>&1 | python -I -c "import json,sys
try: print(json.loads(sys.stdin.read()).get('status'))
except Exception: print('unreadable')")
	case "$status" in SUSPENDED|unreadable) false;; *) true;; esac; check "$agent is not stuck ($status)" $? "$status"
done

stamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
"$here/mcp.sh" covia_write "{\"path\":\"w/loop/smoke\",\"value\":\"$stamp\"}" >/dev/null 2>&1
back=$("$here/mcp.sh" covia_read '{"path":"w/loop/smoke"}' 2>&1)
case "$back" in *"$stamp"*) true;; *) false;; esac; check "workspace writes and reads" $? "$back"

skill=$("$here/mcp.sh" covia_read '{"path":"v/skills/brightside/coding/self-improvement"}' 2>&1); check "shipped skills resolve" $? "$skill"
memory=$("$here/mcp.sh" covia_read '{"path":"w/memory"}' 2>&1); check "memory reads" $? "$memory"

exit $fail
