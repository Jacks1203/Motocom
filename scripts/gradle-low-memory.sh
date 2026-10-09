#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# A lock covers every build/check; cgroup limits cover ALL descendant processes.
exec 9>/tmp/motocom-build.lock
flock -n 9 || { echo 'Ya hay una compilación de Motocom en marcha.' >&2; exit 1; }
# The lock dies with this shell, but an orphaned build can outlive it: refuse to stack another.
if pgrep -f 'org.gradle.wrapper.GradleWrapperMain|org.gradle.launcher' >/dev/null; then
  echo 'Hay otro proceso de Gradle vivo (quizá huérfano). Revísalo antes de compilar:' >&2
  pgrep -af 'org.gradle.wrapper.GradleWrapperMain|org.gradle.launcher' | cut -c1-160 >&2
  exit 1
fi
# Keep this shell alive so it holds the lock even if systemd-run closes extra fds.
systemd-run --user --scope --quiet \
  -p MemoryHigh=850M -p MemoryMax=1100M -p MemorySwapMax=256M \
  -p OOMPolicy=kill \
  ./gradlew --no-daemon --max-workers=1 "$@"
