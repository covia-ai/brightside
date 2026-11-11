#!/bin/sh
# Asks Odin to relaunch Tau into a jar, waits for the new process, and prints
# its info. Tau starts its successor itself, so it outlives this session.
#   relaunch.sh [jar]   default file://code/brightside/target/brightside.jar;
#                       file://code/last-good.jar is the rollback
here=$(dirname "$0")
jar="${1:-file://code/brightside/target/brightside.jar}"
before=$("$here/mcp.sh" grid_run '{"operation":"v/ops/brightside/info"}' | python -I -c "import json,sys;print(json.loads(sys.stdin.read()).get('pid',''))" 2>/dev/null)
request=$(python -I -c "import json,sys;print(json.dumps({'operation':'v/ops/brightside/ask-odin','input':{'request':'Relaunch Tau into '+sys.argv[1]+' (brightside:relaunch with that jar). This is the daily self-improvement loop, whose relaunches the owner has approved as a standing decision.','timeout':300000}}))" "$jar")
"$here/mcp.sh" grid_run "$request" || { echo "Odin did not relaunch" >&2; exit 1; }
# The old process answers, then stops; the newcomer takes over the store and
# writes a fresh tool token. Wait for a different pid (up to five minutes).
i=0
while [ $i -lt 100 ]; do
	pid=$("$here/mcp.sh" grid_run '{"operation":"v/ops/brightside/info"}' 2>/dev/null \
		| python -I -c "import json,sys;print(json.loads(sys.stdin.read()).get('pid',''))" 2>/dev/null)
	if [ -n "$pid" ] && [ "$pid" != "$before" ]; then
		"$here/mcp.sh" grid_run '{"operation":"v/ops/brightside/info"}'
		exit 0
	fi
	i=$((i + 1))
	timeout 3 tail -f /dev/null 2>/dev/null
done
echo "Tau did not come back from the relaunch (old pid $before)" >&2
exit 1
