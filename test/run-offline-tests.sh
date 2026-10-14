#!/usr/bin/env bash
# Compiles and runs the offline/static test harness for pfe-devsecops
# without needing a live Jenkins controller. Requires a `groovy-all` jar
# (the one bundled in any Jenkins install works: war/WEB-INF/lib/groovy-all-*.jar)
# passed as $1, or GROOVY_ALL_JAR env var.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_ROOT="$(cd "$HERE/.." && pwd)"
GROOVY_JAR="${1:-${GROOVY_ALL_JAR:-}}"

if [ -z "$GROOVY_JAR" ] || [ ! -f "$GROOVY_JAR" ]; then
    echo "Usage: $0 /path/to/groovy-all-*.jar  (or set GROOVY_ALL_JAR)" >&2
    exit 2
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "== Compiling src/ (syntax + basic semantic check) =="
java -cp "$GROOVY_JAR" org.codehaus.groovy.tools.FileSystemCompiler -d "$OUT" \
    "$LIB_ROOT"/src/org/pfe/devsecops/*.groovy

echo "== Compiling vars/devSecOpsPipeline.groovy (syntax check; Jenkins DSL steps resolve only at runtime) =="
java -cp "$GROOVY_JAR:$OUT" org.codehaus.groovy.tools.FileSystemCompiler -d "$OUT" \
    "$LIB_ROOT"/vars/devSecOpsPipeline.groovy

echo "== Compiling test harness (FakeSteps + offline_tests + acr_publisher_tests) =="
java -cp "$GROOVY_JAR:$OUT" org.codehaus.groovy.tools.FileSystemCompiler -d "$OUT" \
    "$HERE"/FakeSteps.groovy "$HERE"/offline_tests.groovy "$HERE"/acr_publisher_tests.groovy

echo "== Running executable offline tests =="
LIB_ROOT="$LIB_ROOT" java -cp "$GROOVY_JAR:$OUT" offline_tests

# R50 -- publication ACR + provenance. Entierement simule : aucun identifiant,
# aucun push, aucun appel Azure.
echo "== Running ACR publication tests (fully mocked) =="
LIB_ROOT="$LIB_ROOT" java -cp "$GROOVY_JAR:$OUT" acr_publisher_tests

# Le harnais Groovy verifie le CONTRAT des scripts generes. Celui-ci verifie
# leur COMPORTEMENT dans le meme shell que Jenkins -- propagation du statut et
# non-exposition du secret -- ce qu'aucune assertion de texte ne peut prouver.
echo "== Running shell behaviour tests (status propagation, secret exposure) =="
bash "$HERE/shell_status_tests.sh"
