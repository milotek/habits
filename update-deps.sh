#!/usr/bin/env bash
# Regenerate deps.json. Run this after changing dependencies in build.gradle.kts,
# otherwise the Nix build fails on the missing artifacts.
#
# The data override is needed because gradle.fetchDeps defaults to writing
# deps.json next to the package definition, which for a flake is a read-only
# store path. The script is built rather than `nix run`, because fetchDeps
# produces a bare executable file, not the $out/bin/ layout `nix run` expects.
set -euo pipefail
cd "$(dirname "$0")"
expr="(builtins.getFlake \"path:$PWD\").packages.\${builtins.currentSystem}.default.mitmCache.updateScript.override { data = \"$PWD/deps.json\"; }"
script=$(nix build --impure --no-link --print-out-paths --expr "$expr")
exec "$script"
