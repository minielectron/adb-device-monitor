#!/bin/bash
# End-to-end smoke test without a device: start → sample → stop → report → compare list → diagnostics.
# Usage: scripts/smoke-test.sh [path/to/jar] [port]
cd "$(dirname "$0")/.." || exit 1
JAR="${1:-build/libs/adb-device-monitor-1.0.0-all.jar}"; PORT="${2:-8099}"
TMP="$(mktemp -d)"; URL="http://localhost:$PORT"; fail=0
ok()  { echo "  PASS  $1"; }
bad() { echo "  FAIL  $1"; fail=1; }
java -Duser.home="$TMP/home" -jar "$JAR" --web "$PORT" --adb "$PWD/scripts/fake-adb.sh" > "$TMP/out.txt" 2>&1 &
PID=$!; trap 'kill $PID 2>/dev/null' EXIT
for i in $(seq 1 40); do curl -s "$URL/api/defaults" >/dev/null && break; sleep 0.25; done

curl -s "$URL/api/devices" | grep -q '"serial":"FAKE0001"' && ok "device list" || bad "device list"
curl -s -X POST -d "serial=FAKE0001&duration=120&interval=1&outputDir=$TMP/reports&label=smoke" "$URL/api/start" | grep -q '"ok":true' && ok "start" || bad "start"
sleep 5
curl -s "$URL/api/session?since=0" | grep -q '"status":"running"' && ok "running + sampling" || bad "running"
t0=$(date +%s); curl -s -X POST "$URL/api/stop" >/dev/null
for i in $(seq 1 60); do curl -s "$URL/api/session?since=99999" | grep -q '"status":"done"' && break; sleep 0.25; done
el=$(( $(date +%s) - t0 ))
curl -s "$URL/api/session?since=99999" | grep -q '"status":"done"' && [ $el -le 10 ] && ok "stop → done in ${el}s" || bad "stop (took ${el}s)"
RUN="$(ls -d "$TMP"/reports/run_* 2>/dev/null | head -1)"
for f in report.pdf samples.csv run.log device-info.log; do [ -s "$RUN/$f" ] && ok "$f written" || bad "$f missing"; done
curl -s "$URL/api/runs" | grep -q '"label":"smoke"' && ok "run listed for comparison" || bad "compare list"
curl -s -X POST -d "error @ /dashboard: smoke" "$URL/api/clientlog" >/dev/null
LOG="$TMP/home/ADB Device Monitor/adb-monitor.log"
grep -q "\[client\] error @ /dashboard: smoke" "$LOG" && ok "browser errors reach the log" || bad "clientlog"
grep -q "\[adb\] .*ms exit=" "$LOG" && ok "adb command trace in log" || bad "adb trace"
curl -s -o "$TMP/d.zip" "$URL/api/diagnostics"; unzip -l "$TMP/d.zip" 2>/dev/null | grep -q threads.txt && ok "diagnostics zip" || bad "diagnostics zip"
[ $fail = 0 ] && echo "ALL PASSED" || { echo "FAILURES — see $TMP"; exit 1; }
