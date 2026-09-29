#!/usr/bin/env bash
# Run a V3 probe/validator class on the Windows JDK using this project's Gradle test
# runtime classpath. This is the reproducible form of the documented CLI evidence
# commands (e.g. `V3AbstractCases verify`, `V3TopologyComparison`), for use without
# waiting for a full Gradle test run. The same invariants also run under
# `tools/gradlew.sh test --offline` (V3AbstractCasesTest / V3TopologyComparisonTest).
#
# Usage: tools/run-v3-probe.sh <fully.qualified.ClassName> [args...]
#
# Why the classpath goes into an @argfile: it is ~9.5 KB, which overflows cmd.exe's
# ~8191-char command-line limit. A batch `set CP=<long>` is silently truncated and an
# inline `-cp` fails the same way. A Java @argfile has no such limit. The @ reference
# is passed as a *relative* Windows path because WSL mangles inline backslash args.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRATCH="$ROOT/build/v3-probe"
mkdir -p "$SCRATCH"
cd "$ROOT"

# One Gradle invocation exports the exact test runtime classpath (Windows path form).
cat > "$SCRATCH/init.gradle" <<'GRADLE'
allprojects {
    tasks.register('dumpTestCp') {
        doLast {
            def f = new File(rootProject.projectDir, 'build/v3-probe/test-cp.txt')
            f.text = sourceSets.test.runtimeClasspath.files.join(';')
        }
    }
}
GRADLE
tools/gradlew.sh -I build/v3-probe/init.gradle dumpTestCp --offline -q >/dev/null 2>&1

{
  printf -- '-cp\n'
  cat "$SCRATCH/test-cp.txt"
  printf '\n'
  printf '%s\n' "$@"
} | sed 's/$/\r/' > "$SCRATCH/args.txt"

exec "/mnt/c/Program Files/Java/jdk-17.0.18+8/bin/java.exe" @build\\v3-probe\\args.txt
