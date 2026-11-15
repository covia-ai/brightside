#!/bin/sh
# Covia for the loop, from the sibling checkout's committed develop, never its
# working tree (other sessions work there).
#   covia.sh status          what develop has gained since the installed build,
#                            and whether any of it touches store formats
#   covia.sh install [sha]   build that commit (default: develop) outside the
#                            checkout and install the modules Tau needs
# The installed commit is recorded in ~/.brightside/code/.covia-installed.
set -e
# Default: the covia checkout beside this repository's main checkout (from a
# worktree too).
common=$(git -C "$(dirname "$0")" rev-parse --path-format=absolute --git-common-dir)
covia="${COVIA_DIR:-$(dirname "$(dirname "$common")")/covia}"
home="${BRIGHTSIDE_HOME:-$HOME/.brightside}"
mark="$home/code/.covia-installed"
modules="venue,workbench,covia-discord,covia-telegram,covia-claude-code,covia-speech"
installed=$(cat "$mark" 2>/dev/null || true)
develop=$(git -C "$covia" rev-parse develop)

case "$1" in
status)
	version=$(git -C "$covia" show "$develop:pom.xml" | sed -n 's:.*<version>\(.*-SNAPSHOT\)</version>.*:\1:p' | head -1)
	echo "installed: ${installed:-none}"
	echo "develop:   $develop ($version)"
	[ -n "$installed" ] || exit 0
	[ "$installed" = "$develop" ] && { echo "up to date"; exit 0; }
	echo "--- commits"
	git -C "$covia" log --no-merges --format='%h %s' "$installed..$develop"
	echo "--- main code changed"
	git -C "$covia" diff --stat "$installed" "$develop" -- '*/src/main/**' | tail -15
	echo "--- store formats (venue migrations, adapter state versions)"
	# The venue version at a commit, wherever VenueMigrations.java lives
	# then. Not finding it is an error, never "unchanged".
	venueVersion() {
		local f
		f=$(git -C "$covia" ls-tree -r --name-only "$1" | grep '/VenueMigrations\.java$' | head -1)
		[ -n "$f" ] && git -C "$covia" show "$1:$f" \
			| sed -n 's/.*long VERSION *= *\([0-9]*\);.*/\1/p' | head -1
	}
	old=$(venueVersion "$installed"); new=$(venueVersion "$develop")
	if [ -z "$old" ] || [ -z "$new" ]; then
		echo "ERROR: cannot read the venue version (VenueMigrations.java) at both commits" >&2
		exit 3
	fi
	[ "$old" = "$new" ] && echo "venue version unchanged ($new)" || echo "CHANGED: venue version $old -> $new"
	git -C "$covia" diff -G 'stateVersion|stateSteps' --stat "$installed" "$develop" -- '*/src/main/**' | tail -5
	;;
install)
	sha=$(git -C "$covia" rev-parse "${2:-develop}")
	dir="${TMPDIR:-${TEMP:-/tmp}}/covia-$sha"
	rm -rf "$dir" && mkdir -p "$dir"
	git -C "$covia" archive "$sha" | tar -x -C "$dir"
	(cd "$dir" && mvn -q install -DskipTests -pl "$modules" -am)
	mkdir -p "$(dirname "$mark")" && echo "$sha" > "$mark"
	rm -rf "$dir"
	echo "installed $sha"
	;;
*)
	echo "usage: covia.sh status | install [sha]" >&2; exit 64
	;;
esac
