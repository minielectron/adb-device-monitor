#!/bin/bash
# Fake `adb` for testing without a device:  java -jar <jar> --web 8080 --adb scripts/fake-adb.sh
#   FAKE_ADB_DELAY=<sec>  delay every shell command (simulates a slow device)
#   FAKE_ADB_HANG=1       shell commands never return (simulates a frozen device → watchdog/timeout paths)
#   FAKE_ADB_HANG_FILE=<path>  same, but only while that file exists (freeze the device mid-run with `touch`)
args="$*"
case "$1" in
  devices) echo "List of devices attached"; echo "FAKE0001               device product:fake model:FakeSTB transport_id:1"; exit 0;;
  start-server|kill-server) exit 0;;
esac
[ "$1" = "-s" ] && shift 2
case "$1" in
  logcat)
    [ "$2" = "-c" ] && exit 0
    while true; do echo "$(date '+%m-%d %H:%M:%S.000')  1000  1000 I Fake    : tick"; sleep 1; done;;
  pull) : > "$3"; exit 0;;
  root) echo "adbd is already running as root"; exit 0;;
  shell) ;;
  *) exit 0;;
esac
[ -n "$FAKE_ADB_HANG" ] && sleep 100000
[ -n "$FAKE_ADB_HANG_FILE" ] && [ -f "$FAKE_ADB_HANG_FILE" ] && sleep 100000
[ -n "$FAKE_ADB_DELAY" ] && sleep "$FAKE_ADB_DELAY"
cmd="$2"
case "$cmd" in
  *===STAT===*)
    t=$(( $(date +%s%N) / 10000000 ))
    echo "===STAT==="; echo "cpu  $((t/4)) 0 $((t/8)) $((t*4)) 10 0 5 0 0 0"
    for c in 0 1 2 3; do echo "cpu$c $((t/16)) 0 $((t/32)) $t 2 0 1 0 0 0"; done
    echo "===MEM==="; echo "MemTotal: 2048000 kB"; echo "MemFree: 300000 kB"; echo "MemAvailable: 900000 kB"
    echo "Buffers: 20000 kB"; echo "Cached: 500000 kB"; echo "SwapTotal: 512000 kB"; echo "SwapFree: 400000 kB"
    echo "===LOAD==="; echo "1.20 0.90 0.70 2/800 1234"
    echo "===PSI_CPU==="; echo "some avg10=1.50 avg60=1.00 avg300=0.50 total=1"
    echo "===PSI_MEM==="; echo "some avg10=0.20 avg60=0.10 avg300=0.00 total=1"; echo "full avg10=0.00 avg60=0.00 avg300=0.00 total=0"
    echo "===PSI_IO==="; echo "some avg10=0.30 avg60=0.10 avg300=0.00 total=1"; echo "full avg10=0.00 avg60=0.00 avg300=0.00 total=0"
    ;;
  *ro.product.manufacturer*) echo Fake; echo STB-1; echo 12; echo 31; echo 4;;
  *ro.build.version.release*) echo 12; echo "uid=2000(shell) gid=2000(shell)"; echo user; echo 0;;
  *"ps -A"*) echo "PID RSS NAME"; echo "100 120000 system_server"; echo "200 90000 com.fake.app"; echo "300 40000 surfaceflinger";;
  date*) date '+%m-%d %H:%M:%S';;
  "echo ok") echo ok;;
  *) ;;
esac
exit 0
