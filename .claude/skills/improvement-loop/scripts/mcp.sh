#!/bin/sh
# Calls one of Tau's MCP tools as the owner and prints its text result.
#   mcp.sh <tool> '<json arguments>'
# Exit status 1 when the tool reports an error (its text is still printed),
# 2 when Tau cannot be reached. Reads the owner's tool token from
# ~/.brightside/tool-token.json (Settings: keep the tool token), never prints it.
# MCP_TIMEOUT (seconds, default 900) bounds a long agent turn.
home="${BRIGHTSIDE_HOME:-$HOME/.brightside}"
token="$home/tool-token.json"
[ -f "$token" ] || { echo "Tau is not running (no tool token at $token)" >&2; exit 2; }
command -v cygpath >/dev/null 2>&1 && token=$(cygpath -w "$token")
python -I - "$1" "${2:-{\}}" "$token" "${MCP_URL:-http://127.0.0.1:8085/mcp}" "${MCP_TIMEOUT:-900}" <<'EOF'
import json, sys, urllib.request
tool, args, token, url, timeout = sys.argv[1:6]
sys.stdout.reconfigure(encoding="utf-8")
auth = json.load(open(token, encoding="utf-8"))["Authorization"]
body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
	"params": {"name": tool, "arguments": json.loads(args)}}).encode()
req = urllib.request.Request(url, body, {"Authorization": auth, "Content-Type": "application/json",
	"Accept": "application/json, text/event-stream"})
try:
	text = urllib.request.urlopen(req, timeout=float(timeout)).read().decode("utf-8")
except OSError as e:
	print("Tau cannot be reached: %s" % e, file=sys.stderr); sys.exit(2)
text = text[text.find("{"):]
reply = json.loads(text)
if "error" in reply:
	print(json.dumps(reply["error"])); sys.exit(1)
result = reply["result"]
out = result.get("structuredContent")
print(json.dumps(out, ensure_ascii=False) if out is not None else result["content"][0]["text"])
sys.exit(1 if result.get("isError") else 0)
EOF
