#!/usr/bin/env python3
import re
import sys
import unicodedata
from datetime import datetime
from plotly.subplots import make_subplots
import plotly.graph_objects as go

# --------------------------------------------------
# Args
# --------------------------------------------------
if len(sys.argv) != 3:
    print("Usage: python3 SystemPulseV3.py <logcat_file> <output_name>")
    sys.exit(1)

LOG_FILE = sys.argv[1]
output_name = sys.argv[2]
# --------------------------------------------------
# Storage
# --------------------------------------------------
disp_labels, disp_times, disp_full, disp_x = [], [], [], []
full_labels, full_times, full_full, full_x = [], [], [], []
davey_labels, davey_times, davey_x = [], [], []
skipped_labels, skipped_times, skipped_x = [], [], []
anr_timestamps, anr_activities, anr_reasons, anr_memory_pressure = [], [], [], []
memory_info = []  # List of dicts: {process, timestamp, pss_mb, native_heap_mb}
fatal_exceptions = []  # List of dicts: {timestamp, process, exception_type, message, pid}
lowmemorykiller_events = []  # List of dicts: {timestamp, process, pid, uid, oom_score_adj, rss_kb, swap_kb, reason}
disp_idx = full_idx = davey_idx = skipped_idx = 0
all_timestamps = []
reference_year = 2026  # All logs are assumed to be from 2026
first_log_timestamp = None  # First timestamp from log file
last_log_timestamp = None  # Last timestamp from log file
# --------------------------------------------------
# Regex (NEW LOG FORMAT – ROBUST)
# --------------------------------------------------
#DISPLAYED_RE = re.compile(
#    r"(?P<time>\d{4}-\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*Displayed\s+(?P<activity>[^\s:]+):\s+\+(?P<duration>\d+)ms",
#    re.IGNORECASE
#)
# Dumpstate / some tools: full date + "pid-tid ActivityTaskManager system_server I Displayed ..."
DISPLAYED_RE_DUMPSTATE = re.compile(
    r"^(?P<time>\d{4}-\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+)\s+"
    r"(?P<pid>\d+)-(?P<tid>\d+)\s+"
    r"ActivityTaskManager\s+system_server\s+"
    r"(?P<level>[VDIWEF])\s+Displayed\s+"
    r"(?P<activity>[^\s:]+):\s+\+(?P<duration>\d+)ms\.?\s*$",
    re.IGNORECASE,
)

# Standard adb logcat: "03-05 16:34:36.287 I/ActivityTaskManager(  614): Displayed pkg/Act: +693ms"
DISPLAYED_RE_LOGCAT = re.compile(
    r"(?P<time>\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*?"
    r"ActivityTaskManager.*?:\s*Displayed\s+"
    r"(?P<activity>[^\s:]+):\s*\+(?P<duration>\d+)ms",
    re.IGNORECASE,
)

# Full date on same line as logcat prefix (rare): "2026-03-05 ... ActivityTaskManager ... Displayed ..."
DISPLAYED_RE_FULLDATE = re.compile(
    r"(?P<time>\d{4}-\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*?"
    r"ActivityTaskManager.*?:\s*Displayed\s+"
    r"(?P<activity>[^\s:]+):\s*\+(?P<duration>\d+)ms",
    re.IGNORECASE,
)

FULLY_DRAWN_RE = re.compile(
    r"(?P<time>\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*Fully\s+drawn\s+(?P<activity>[\w\.\$/]+).*?:\s*\+(?:(?P<sec>\d+)s)?(?P<ms>\d+)ms",
    re.IGNORECASE
)

DAVEY_RE = re.compile(
    r"(?P<time>\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*Davey!\s+duration=(?P<duration>\d+)ms",
    re.IGNORECASE
)

SKIPPED_FRAMES = re.compile(
    r"(?P<time>\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*Skipped\s+(?P<frames>\d+)\s+frames!"
)

ANR_START_RE = re.compile(
    r"(?P<time>\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*ActivityManager:\s+ANR\s+in\s+(?P<package>[^\s]+)\s+\((?P<activity>[^)]+)\)"
)

ANR_REASON_RE = re.compile(
    r".*ActivityManager:\s+Reason:\s+(?P<reason>.+)"
)

ANR_MEMORY_PRESSURE_RE = re.compile(
    r".*ActivityManager:\s+some\s+avg10=(?P<avg10>[\d.]+)"
)

MEMINFO_HEADER_RE = re.compile(
    r"(?:(?P<time>\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*?)?\*\*\s+MEMINFO\s+in\s+pid\s+\d+\s+\[(?P<process>[^\]]+)\]\s+\*\*",
    re.IGNORECASE
)

MEMINFO_NATIVE_HEAP_RE = re.compile(
    r"Native\s+Heap\s+(?P<pss>\d+)",
    re.IGNORECASE
)

MEMINFO_TOTAL_RE = re.compile(
    r"TOTAL\s+(?P<pss>\d+)",
    re.IGNORECASE
)

# Fatal Exception patterns
FATAL_EXCEPTION_RE = re.compile(
    r"(?P<time>\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*AndroidRuntime:\s+FATAL\s+EXCEPTION:\s+(?P<thread>[^\n]+)"
)

FATAL_EXCEPTION_PROCESS_RE = re.compile(
    r"Process:\s+(?P<process>[^,]+),\s+PID:\s+(?P<pid>\d+)"
)

FATAL_EXCEPTION_TYPE_RE = re.compile(
    r"(?P<exception_type>[\w\.]+Exception|[\w\.]+Error):\s+(?P<message>.+)"
)

FATAL_SIGNAL_RE = re.compile(
    r"(?P<time>\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*Fatal\s+signal\s+(?P<signal>\d+)\s+\((?P<signal_name>[^)]+)\),\s+code\s+(?P<code>\d+)\s+\((?P<code_name>[^)]+)\),\s+fault\s+addr\s+(?P<fault_addr>[^\s]+).*pid\s+(?P<pid>\d+)\s+\((?P<process>[^)]+)\)"
)

# LowMemoryKiller pattern - Kill events
LOWMEMORYKILLER_RE = re.compile(
    r"(?P<time>\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*lowmemorykiller:\s+Kill\s+'(?P<process>[^']+)'\s+\((?P<pid>\d+)\),\s+uid\s+(?P<uid>\d+),\s+oom_score_adj\s+(?P<oom_score_adj>\d+)\s+to\s+free\s+(?P<rss>\d+)kB\s+rss,\s+(?P<swap>\d+)kB\s+swap;\s+reason:\s+(?P<reason>.+)",
    re.IGNORECASE
)

# LowMemoryKiller pattern - find_and_kill_process events
LOWMEMORYKILLER_FIND_RE = re.compile(
    r"(?P<time>\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+).*lowmemorykiller:\s+find_and_kill_process\s+(?P<oom_score_adj>\d+),\s+(?P<result>.+?)!",
    re.IGNORECASE
)

# General timestamp patterns to extract first and last timestamps from log
TIMESTAMP_FULL_RE = re.compile(
    r"(?P<time>\d{4}-\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+)"
)

TIMESTAMP_SHORT_RE = re.compile(
    r"(?P<time>\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+)"
)

# Dumpstate header: "== dumpstate: 2026-02-20 02:21:02" (no milliseconds)
DUMPSTATE_HEADER_RE = re.compile(
    r"==\s*dumpstate:\s*(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})"
)

# --------------------------------------------------
# SAFE FILE READER (FIXES macOS ISSUES)
# --------------------------------------------------
def read_log_lines(path):
    """
    Robust reader:
    - Handles UTF-8, UTF-16 (LE/BE), BOM
    - Removes null bytes
    - Normalizes line endings
    """
    with open(path, "rb") as f:
        raw = f.read()

    # UTF-16 with BOM
    if raw.startswith(b'\xff\xfe') or raw.startswith(b'\xfe\xff'):
        text = raw.decode("utf-16", errors="ignore")
    else:
        # Try UTF-8 first
        try:
            text = raw.decode("utf-8", errors="ignore")
        except UnicodeDecodeError:
            text = raw.decode("latin-1", errors="ignore")

    # Normalize line endings
    return text.replace("\r", "").split("\n")

# --------------------------------------------------
# Helpers
# --------------------------------------------------
def normalize(line: str) -> str:
    return unicodedata.normalize("NFKD", line).strip()

def pkg_only(activity: str) -> str:
    return activity.split("/")[0]

def normalize_timestamp(dt: datetime) -> datetime:
    """Normalize timestamp to have a year. All logs are assumed to be from 2026."""
    global reference_year
    # Always use 2026 as the reference year
    reference_year = 2026
    # If timestamp has year 1900 (default when no year in format) or any other year, set it to 2026
    if dt.year != 2026:
        dt = dt.replace(year=2026)
    return dt


def _parse_displayed_time(time_str: str) -> datetime:
    """Parse Displayed line timestamp (full date or MM-DD logcat)."""
    time_str = time_str.strip()
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(time_str, fmt)
        except ValueError:
            continue
    for fmt in ("%m-%d %H:%M:%S.%f", "%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(time_str, fmt)
        except ValueError:
            continue
    raise ValueError(f"Unparseable time: {time_str!r}")


def match_displayed_line(line: str):
    """Return a regex match for a Displayed (TTID) line, or None."""
    for cre in (DISPLAYED_RE_DUMPSTATE, DISPLAYED_RE_FULLDATE, DISPLAYED_RE_LOGCAT):
        m = cre.search(line)
        if m:
            return m
    return None

# --------------------------------------------------
# Parse log
# --------------------------------------------------
def parse_log():
    global disp_idx, full_idx, davey_idx, skipped_idx
    global disp_x, full_x, davey_x, skipped_x, all_timestamps, reference_year
    global anr_timestamps, anr_activities, anr_reasons, anr_memory_pressure
    global memory_info, fatal_exceptions, lowmemorykiller_events
    global first_log_timestamp, last_log_timestamp

    print(f"[INFO] Opening logcat file: {LOG_FILE}")

    lines = read_log_lines(LOG_FILE)
    print(f"[INFO] Total lines read: {len(lines)}")

    # Extract first and last timestamps from log file for full timeline
    # 1. Parse dumpstate header (if present) to get report date for filtering
    # 2. Collect ALL timestamps from the entire file
    # 3. Filter to same calendar day as dumpstate (excludes stats from other days)
    # 4. Use min/max for full range (first occurrence would miss earlier data, e.g. 01:22 vs 02:19)
    report_date = None
    for line in lines[:50]:  # Dumpstate header is typically in first few lines
        m = DUMPSTATE_HEADER_RE.search(normalize(line))
        if m:
            try:
                report_date = datetime(
                    int(m.group(1)), int(m.group(2)), int(m.group(3))
                ).date()
            except (ValueError, IndexError):
                pass
            break

    all_file_timestamps = []
    for line in lines:
        normalized_line = normalize(line)
        # Try full timestamp format first (YYYY-MM-DD HH:MM:SS.mmm)
        m = TIMESTAMP_FULL_RE.search(normalized_line)
        if m:
            try:
                ts = datetime.strptime(m.group("time"), "%Y-%m-%d %H:%M:%S.%f")
                ts = normalize_timestamp(ts)
                if report_date is None or ts.date() == report_date:
                    all_file_timestamps.append(ts)
            except ValueError:
                pass
        else:
            # Try short timestamp format (MM-DD HH:MM:SS.mmm)
            m = TIMESTAMP_SHORT_RE.search(normalized_line)
            if m:
                try:
                    ts = datetime.strptime(m.group("time"), "%m-%d %H:%M:%S.%f")
                    ts = normalize_timestamp(ts)
                    if report_date is None or ts.date() == report_date:
                        all_file_timestamps.append(ts)
                except ValueError:
                    pass

    if all_file_timestamps:
        first_log_timestamp = min(all_file_timestamps)
        last_log_timestamp = max(all_file_timestamps)

    # State tracking for multi-line ANR parsing
    anr_in_progress = False
    current_anr_time = None
    current_anr_activity = None
    current_anr_reason = None
    current_anr_memory = None

    for i, line in enumerate(lines):
        line = normalize(line)

        # Displayed (TTID)
        m = match_displayed_line(line)
        if m:
            disp_idx += 1

            # X-axis time (datetime)
            t = _parse_displayed_time(m.group("time"))
            t = normalize_timestamp(t)
            all_timestamps.append(t)

            # Y-axis duration in ms
            total = int(m.group("duration"))

            # Activity/process label
            act = m.group("activity")

            # Store
            disp_labels.append(f"{pkg_only(act)} [#{disp_idx}]")
            disp_x.append(t)
            disp_times.append(total)
            disp_full.append(act)
            continue

        # Fully Drawn (TTFD)
        m = FULLY_DRAWN_RE.search(line)
        if m:
            full_idx += 1

            sec = int(m.group("sec") or 0)
            total = sec * 1000 + int(m.group("ms"))

            act = m.group("activity")
            
            # X-axis time (datetime)
            t = datetime.strptime(m.group("time"), "%m-%d %H:%M:%S.%f")
            t = normalize_timestamp(t)
            all_timestamps.append(t)

            full_labels.append(f"{pkg_only(act)} [#{full_idx}]")
            full_x.append(t)
            full_times.append(total)
            full_full.append(act)
            continue

        # Davey
        m = DAVEY_RE.search(line)
        if m:
            davey_idx += 1
            t = datetime.strptime(m.group("time"), "%m-%d %H:%M:%S.%f")
            t = normalize_timestamp(t)
            all_timestamps.append(t)
            davey_x.append(t)
            davey_times.append(int(m.group("duration")))
            continue

        # Skipped frames
        m = SKIPPED_FRAMES.search(line)
        if m:
            skipped_idx += 1
            t = datetime.strptime(m.group("time"), "%m-%d %H:%M:%S.%f")
            t = normalize_timestamp(t)
            all_timestamps.append(t)
            skipped_x.append(t)
            skipped_times.append(int(m.group("frames")))
            continue

        # ANR detection (multi-line parsing)
        m = ANR_START_RE.search(line)
        if m:
            anr_in_progress = True
            current_anr_time = datetime.strptime(m.group("time"), "%m-%d %H:%M:%S.%f")
            current_anr_time = normalize_timestamp(current_anr_time)
            current_anr_activity = m.group("activity")
            current_anr_reason = None
            current_anr_memory = None
            # Look ahead for reason and memory pressure (next 10 lines)
            lookahead_end = min(i + 10, len(lines))
            for j in range(i + 1, lookahead_end):
                next_line = normalize(lines[j])
                # Check for reason
                if current_anr_reason is None:
                    reason_match = ANR_REASON_RE.search(next_line)
                    if reason_match:
                        current_anr_reason = reason_match.group("reason").strip()
                # Check for memory pressure
                if current_anr_memory is None:
                    memory_match = ANR_MEMORY_PRESSURE_RE.search(next_line)
                    if memory_match:
                        current_anr_memory = memory_match.group("avg10")
                # Stop if we've found both or hit end of ANR block
                if current_anr_reason and current_anr_memory:
                    break
                # Stop if we hit a new ANR or other log entry
                if ANR_START_RE.search(next_line) or match_displayed_line(next_line):
                    break
            
            # Store ANR data if we have at least activity and time
            if current_anr_time and current_anr_activity:
                anr_timestamps.append(current_anr_time)
                anr_activities.append(current_anr_activity)
                anr_reasons.append(current_anr_reason if current_anr_reason else "Unknown")
                anr_memory_pressure.append(current_anr_memory if current_anr_memory else "N/A")
                all_timestamps.append(current_anr_time)
            
            # Reset state
            anr_in_progress = False
            current_anr_time = None
            current_anr_activity = None
            current_anr_reason = None
            current_anr_memory = None
            continue

        # MEMINFO parsing (for com.telekom.onetv processes)
        # Try both patterns - with and without timestamp requirement
        m = MEMINFO_HEADER_RE.search(line)
        # Also try a simpler pattern that just looks for MEMINFO header
        if not m:
            simple_meminfo = re.search(r"\*\*\s+MEMINFO\s+in\s+pid\s+\d+\s+\[(?P<process>[^\]]+)\]\s+\*\*", line, re.IGNORECASE)
            if simple_meminfo:
                # Create a match-like object
                class SimpleMatch:
                    def __init__(self, process, time_str):
                        self.process = process
                        self.time_str = time_str
                    def group(self, name):
                        if name == "process":
                            return self.process
                        elif name == "time":
                            return self.time_str
                        return None
                # Look for timestamp in current or previous lines
                time_str = None
                time_match = re.search(r"(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+)", line)
                if time_match:
                    time_str = time_match.group(1)
                else:
                    # Look backwards for timestamp
                    for k in range(max(0, i - 5), i):
                        prev_line = normalize(lines[k])
                        time_match = re.search(r"(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+)", prev_line)
                        if time_match:
                            time_str = time_match.group(1)
                            break
                m = SimpleMatch(simple_meminfo.group("process"), time_str)
        
        if m:
            process_name = m.group("process").strip()
            # Only process if it starts with com.telekom.onetv (case-insensitive)
            if process_name.lower().startswith("com.telekom.onetv"):
                # Handle timestamp - might be optional or on previous line
                time_str = m.group("time")
                if time_str:
                    mem_time = datetime.strptime(time_str, "%m-%d %H:%M:%S.%f")
                    mem_time = normalize_timestamp(mem_time)
                else:
                    # If no timestamp on this line, try to get it from previous lines or use a default
                    # Look backwards up to 5 lines for a timestamp
                    mem_time = None
                    for k in range(max(0, i - 5), i):
                        prev_line = normalize(lines[k])
                        time_match = re.search(r"(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+)", prev_line)
                        if time_match:
                            try:
                                mem_time = datetime.strptime(time_match.group(1), "%m-%d %H:%M:%S.%f")
                                mem_time = normalize_timestamp(mem_time)
                                break
                            except ValueError:
                                continue
                    # If still no timestamp found, use the first available timestamp or current time
                    if mem_time is None:
                        if all_timestamps:
                            mem_time = all_timestamps[0]  # Use first timestamp as reference
                        else:
                            mem_time = datetime.now()  # Fallback to current time
                
                # Look ahead for Native Heap and TOTAL lines (next 50 lines should be enough)
                lookahead_end = min(i + 50, len(lines))
                native_heap_pss = None
                total_pss = None
                
                for j in range(i + 1, lookahead_end):
                    next_line = normalize(lines[j])
                    
                    # Check for Native Heap line
                    if native_heap_pss is None:
                        native_match = MEMINFO_NATIVE_HEAP_RE.search(next_line)
                        if native_match:
                            native_heap_pss = int(native_match.group("pss"))
                    
                    # Check for TOTAL line
                    if total_pss is None:
                        total_match = MEMINFO_TOTAL_RE.search(next_line)
                        if total_match:
                            total_pss = int(total_match.group("pss"))
                    
                    # Stop if we've found both or hit another MEMINFO header
                    if native_heap_pss is not None and total_pss is not None:
                        break
                    if MEMINFO_HEADER_RE.search(next_line):
                        break
                
                # Store memory info if we found the data
                if total_pss is not None:
                    memory_info.append({
                        'process': process_name,
                        'timestamp': mem_time,
                        'pss_mb': round(total_pss / 1024.0, 3),  # Convert KB to MB
                        'native_heap_mb': round(native_heap_pss / 1024.0, 3) if native_heap_pss is not None else None
                    })
                    all_timestamps.append(mem_time)
            continue

        # Fatal Exception - AndroidRuntime
        m = FATAL_EXCEPTION_RE.search(line)
        if m:
            fatal_time = datetime.strptime(m.group("time"), "%m-%d %H:%M:%S.%f")
            fatal_time = normalize_timestamp(fatal_time)
            thread = m.group("thread").strip()
            
            # Look ahead for process and exception details (next 10 lines)
            lookahead_end = min(i + 10, len(lines))
            process_name = None
            pid = None
            exception_type = None
            exception_message = None
            
            for j in range(i + 1, lookahead_end):
                next_line = normalize(lines[j])
                
                # Check for process and PID
                if process_name is None:
                    proc_match = FATAL_EXCEPTION_PROCESS_RE.search(next_line)
                    if proc_match:
                        process_name = proc_match.group("process")
                        pid = proc_match.group("pid")
                
                # Check for exception type and message
                if exception_type is None:
                    exc_match = FATAL_EXCEPTION_TYPE_RE.search(next_line)
                    if exc_match:
                        exception_type = exc_match.group("exception_type")
                        exception_message = exc_match.group("message").strip()
                        # Truncate long messages
                        if len(exception_message) > 200:
                            exception_message = exception_message[:200] + "..."
                
                # Stop if we've found all info or hit another fatal exception
                if process_name and exception_type:
                    break
                if FATAL_EXCEPTION_RE.search(next_line) or FATAL_SIGNAL_RE.search(next_line):
                    break
            
            # Store fatal exception
            fatal_exceptions.append({
                'timestamp': fatal_time,
                'process': process_name if process_name else "Unknown",
                'pid': pid if pid else "N/A",
                'exception_type': exception_type if exception_type else "Unknown Exception",
                'message': exception_message if exception_message else f"FATAL EXCEPTION in thread: {thread}",
                'type': 'AndroidRuntime'
            })
            all_timestamps.append(fatal_time)
            continue

        # Fatal Signal - libc
        m = FATAL_SIGNAL_RE.search(line)
        if m:
            fatal_time = datetime.strptime(m.group("time"), "%m-%d %H:%M:%S.%f")
            fatal_time = normalize_timestamp(fatal_time)
            process_name = m.group("process")
            pid = m.group("pid")
            signal = m.group("signal")
            signal_name = m.group("signal_name")
            code = m.group("code")
            code_name = m.group("code_name")
            fault_addr = m.group("fault_addr")
            
            # Store fatal signal
            fatal_exceptions.append({
                'timestamp': fatal_time,
                'process': process_name,
                'pid': pid,
                'exception_type': f"SIG{signal_name}",
                'message': f"Fatal signal {signal} ({signal_name}), code {code} ({code_name}), fault addr {fault_addr}",
                'type': 'libc'
            })
            all_timestamps.append(fatal_time)
            continue

        # LowMemoryKiller
        m = LOWMEMORYKILLER_RE.search(line)
        if m:
            lmk_time = datetime.strptime(m.group("time"), "%m-%d %H:%M:%S.%f")
            lmk_time = normalize_timestamp(lmk_time)
            process_name = m.group("process")
            pid = m.group("pid")
            uid = m.group("uid")
            oom_score_adj = int(m.group("oom_score_adj"))
            rss_kb = int(m.group("rss"))
            swap_kb = int(m.group("swap"))
            reason = m.group("reason").strip()
            
            # Store lowmemorykiller event
            lowmemorykiller_events.append({
                'timestamp': lmk_time,
                'process': process_name,
                'pid': pid,
                'uid': uid,
                'oom_score_adj': oom_score_adj,
                'rss_kb': rss_kb,
                'swap_kb': swap_kb,
                'reason': reason,
                'type': 'kill'
            })
            all_timestamps.append(lmk_time)
            continue

        # LowMemoryKiller - find_and_kill_process events
        m = LOWMEMORYKILLER_FIND_RE.search(line)
        if m:
            lmk_time = datetime.strptime(m.group("time"), "%m-%d %H:%M:%S.%f")
            lmk_time = normalize_timestamp(lmk_time)
            oom_score_adj = int(m.group("oom_score_adj"))
            result = m.group("result").strip()
            
            # Store lowmemorykiller find event
            lowmemorykiller_events.append({
                'timestamp': lmk_time,
                'process': 'N/A',
                'pid': 'N/A',
                'uid': 'N/A',
                'oom_score_adj': oom_score_adj,
                'rss_kb': None,
                'swap_kb': None,
                'reason': f"find_and_kill_process: {result}",
                'type': 'find'
            })
            all_timestamps.append(lmk_time)
            continue

    # Post-process: normalize all timestamps to use 2026
    reference_year = 2026
    if all_timestamps:
        # Update all timestamps to use 2026
        for i, ts in enumerate(all_timestamps):
            if ts.year != reference_year:
                all_timestamps[i] = ts.replace(year=reference_year)
        # Update all stored timestamps
        disp_x = [ts.replace(year=reference_year) if ts.year != reference_year else ts for ts in disp_x]
        full_x = [ts.replace(year=reference_year) if ts.year != reference_year else ts for ts in full_x]
        davey_x = [ts.replace(year=reference_year) if ts.year != reference_year else ts for ts in davey_x]
        skipped_x = [ts.replace(year=reference_year) if ts.year != reference_year else ts for ts in skipped_x]
        # Update memory info timestamps
        for mem in memory_info:
            if mem['timestamp'].year != reference_year:
                mem['timestamp'] = mem['timestamp'].replace(year=reference_year)
        # Update ANR timestamps
        for i, ts in enumerate(anr_timestamps):
            if ts.year != reference_year:
                anr_timestamps[i] = ts.replace(year=reference_year)
        # Update fatal exception timestamps
        for fe in fatal_exceptions:
            if fe['timestamp'].year != reference_year:
                fe['timestamp'] = fe['timestamp'].replace(year=reference_year)
        # Update lowmemorykiller event timestamps
        for lmk in lowmemorykiller_events:
            if lmk['timestamp'].year != reference_year:
                lmk['timestamp'] = lmk['timestamp'].replace(year=reference_year)
        # Update first and last log timestamps
        if first_log_timestamp and first_log_timestamp.year != reference_year:
            first_log_timestamp = first_log_timestamp.replace(year=reference_year)
        if last_log_timestamp and last_log_timestamp.year != reference_year:
            last_log_timestamp = last_log_timestamp.replace(year=reference_year)

    print("[INFO] Finished reading logcat file")
# --------------------------------------------------
# Plot
# --------------------------------------------------
def generate_html(output_name):
    print(f"[INFO] Found Displayed logs   : {len(disp_times)}")
    print(f"[INFO] Found Fully Drawn logs : {len(full_times)}")
    print(f"[INFO] Found Davey logs       : {len(davey_times)}")
    print(f"[INFO] Found skipped frame logs       : {len(skipped_times)}")
    print(f"[INFO] Found ANR events      : {len(anr_timestamps)}")
    print(f"[INFO] Found memory info     : {len(memory_info)}")
    print(f"[INFO] Found fatal exceptions: {len(fatal_exceptions)}")
    print(f"[INFO] Found lowmemorykiller events: {len(lowmemorykiller_events)}")
    if not (disp_times or full_times or davey_times or skipped_times or anr_timestamps or memory_info or fatal_exceptions or lowmemorykiller_events):
        print("[ERROR] No matching logs found. Nothing to plot.")
        return

    print("[INFO] Generating HTML output")

    # Determine number of rows based on available data
    num_rows = 4
    if memory_info:
        num_rows += 1
    if lowmemorykiller_events:
        num_rows += 1
    if anr_timestamps:
        num_rows += 1
    if fatal_exceptions:
        num_rows += 1
    
    subplot_titles = [
            "Displayed Time (TTID)",
            "Fully Drawn Time (TTFD)",
            "Davey Duration (UI Jank)",
            "Skipped frames (Overloaded Mainthread)"
        ]
    if memory_info:
        subplot_titles.append("Memory Usage (PSS & Native Heap)")
    if lowmemorykiller_events:
        subplot_titles.append("LowMemoryKiller Events")
    if anr_timestamps:
        subplot_titles.append("ANR Events")
    if fatal_exceptions:
        subplot_titles.append("Fatal Exceptions")
    
    fig = make_subplots(
        rows=num_rows,
        cols=1,
        subplot_titles=subplot_titles
    )

    if disp_times:
        # Scatter (not Bar): Bar width=500 is ~500ms on a date x-axis — invisible over multi-hour logs
        disp_colors = ['red' if time > 500 else 'steelblue' for time in disp_times]
        fig.add_trace(
            go.Scatter(
                x=disp_x,
                y=disp_times,
                mode='markers',
                marker=dict(
                    symbol='square',
                    size=14,
                    color=disp_colors,
                    line=dict(width=1, color='navy'),
                ),
                name='TTID',
                customdata=list(zip(disp_labels, disp_full)),
                hovertemplate="<b>%{customdata[1]}</b><br>%{customdata[0]}<br>Displayed: %{y}ms<br>Time: %{x}<extra></extra>",
            ),
            row=1, col=1,
        )
        
        # Add horizontal line at 500ms threshold
        fig.add_hline(
            y=500,
            line_dash="dash",
            line_color="red",
            annotation_text="500ms threshold",
            annotation_position="right",
            row=1, col=1
        )

    if full_times:
        # Color bars red if they exceed 1000ms, otherwise blue
        bar_colors = ['red' if time > 1000 else 'steelblue' for time in full_times]
        
        fig.add_trace(
            go.Bar(
                x=full_x,
                y=full_times,
                width=500,
                marker_color=bar_colors,
                customdata=list(zip(full_labels, full_full)),
                hovertemplate="<b>%{customdata[1]}</b><br>%{customdata[0]}<br>Fully drawn: %{y} ms<br>Time: %{x}<extra></extra>"
            ),
            row=2, col=1
        )
        
        # Add horizontal line at 1000ms threshold
        fig.add_hline(
            y=1000,
            line_dash="dash",
            line_color="red",
            annotation_text="1000ms threshold",
            annotation_position="right",
            row=2, col=1
        )

    # Davey and Skipped frames: use Scatter markers so they stay visible with large time ranges
    # (Bar with width=500ms becomes invisible when x-axis spans hours/days)
    if davey_times:
        fig.add_trace(
            go.Scatter(
                x=davey_x,
                y=davey_times,
                mode='markers',
                marker=dict(
                    symbol='diamond',
                    size=12,
                    color='coral',
                    line=dict(width=1, color='darkred')
                ),
                name='Davey',
                hovertemplate="Davey<br>Duration: %{y}ms<br>Time: %{x}<extra></extra>"
            ),
            row=3, col=1
        )
        
    if skipped_times:
        fig.add_trace(
            go.Scatter(
                x=skipped_x,
                y=skipped_times,
                mode='markers',
                marker=dict(
                    symbol='circle',
                    size=10,
                    color='darkorange',
                    line=dict(width=1, color='orangered')
                ),
                name='Skipped frames',
                hovertemplate="Skipped<br>Frames: %{y}<br>Time: %{x}<extra></extra>"
            ),
            row=4, col=1
        )
    
    # Memory Usage Graph
    if memory_info:
        # Group memory info by process and extract data
        memory_timestamps = [mem['timestamp'] for mem in memory_info]
        memory_pss = [mem['pss_mb'] for mem in memory_info]
        memory_native_heap = [mem['native_heap_mb'] if mem['native_heap_mb'] is not None else 0 for mem in memory_info]
        memory_processes = [mem['process'] for mem in memory_info]
        
        memory_row = 5
        # Add PSS trace
        fig.add_trace(
            go.Scatter(
                x=memory_timestamps,
                y=memory_pss,
                mode='lines+markers',
                name='PSS (MB)',
                line=dict(color='blue', width=2),
                marker=dict(size=6, color='blue'),
                customdata=memory_processes,
                hovertemplate="<b>%{customdata}</b><br>PSS: %{y:.2f} MB<br>Time: %{x}<extra></extra>"
            ),
            row=memory_row, col=1
        )
        
        # Add Native Heap trace (if available)
        if any(mem['native_heap_mb'] is not None for mem in memory_info):
            fig.add_trace(
                go.Scatter(
                    x=memory_timestamps,
                    y=memory_native_heap,
                    mode='lines+markers',
                    name='Native Heap (MB)',
                    line=dict(color='orange', width=2),
                    marker=dict(size=6, color='orange'),
                    customdata=memory_processes,
                    hovertemplate="<b>%{customdata}</b><br>Native Heap: %{y:.2f} MB<br>Time: %{x}<extra></extra>"
                ),
                row=memory_row, col=1
            )
    
    # LowMemoryKiller Events
    if lowmemorykiller_events:
        # Separate kill events and find events
        kill_events = [lmk for lmk in lowmemorykiller_events if lmk.get('type') == 'kill']
        find_events = [lmk for lmk in lowmemorykiller_events if lmk.get('type') == 'find']
        
        lmk_row = 5
        if memory_info:
            lmk_row = 6
        
        # Add kill events (diamond markers)
        if kill_events:
            kill_timestamps = [lmk['timestamp'] for lmk in kill_events]
            kill_oom_scores = [lmk['oom_score_adj'] for lmk in kill_events]
            fig.add_trace(
                go.Scatter(
                    x=kill_timestamps,
                    y=kill_oom_scores,
                    mode='markers',
                    marker=dict(
                        symbol='diamond',
                        size=15,
                        color='purple',
                        line=dict(width=2, color='darkviolet')
                    ),
                    customdata=[(lmk['process'], lmk['pid'], lmk['uid'], lmk['rss_kb'], lmk['swap_kb'], lmk['reason']) for lmk in kill_events],
                    hovertemplate="<b>LowMemoryKiller - Kill</b><br>Process: %{customdata[0]}<br>PID: %{customdata[1]}<br>UID: %{customdata[2]}<br>OOM Score Adj: %{y}<br>RSS: %{customdata[3]} kB<br>Swap: %{customdata[4]} kB<br>Reason: %{customdata[5]}<br>Time: %{x}<extra></extra>",
                    name="LMK Kill"
                ),
                row=lmk_row, col=1
            )
        
        # Add find events (circle markers)
        if find_events:
            find_timestamps = [lmk['timestamp'] for lmk in find_events]
            find_oom_scores = [lmk['oom_score_adj'] for lmk in find_events]
            fig.add_trace(
                go.Scatter(
                    x=find_timestamps,
                    y=find_oom_scores,
                    mode='markers',
                    marker=dict(
                        symbol='circle',
                        size=12,
                        color='orange',
                        line=dict(width=2, color='darkorange')
                    ),
                    customdata=[(lmk['oom_score_adj'], lmk['reason']) for lmk in find_events],
                    hovertemplate="<b>LowMemoryKiller - Find</b><br>OOM Score Adj: %{customdata[0]}<br>Result: %{customdata[1]}<br>Time: %{x}<extra></extra>",
                    name="LMK Find"
                ),
                row=lmk_row, col=1
            )
    
    # ANR Events
    if anr_timestamps:
        # Create markers for ANR events (all set to 1 for visibility)
        anr_values = [1] * len(anr_timestamps)
        anr_row = 5
        if memory_info:
            anr_row += 1
        if lowmemorykiller_events:
            anr_row += 1
        fig.add_trace(
            go.Scatter(
                x=anr_timestamps,
                y=anr_values,
                mode='markers',
                marker=dict(
                    symbol='x',
                    size=15,
                    color='red',
                    line=dict(width=2, color='darkred')
                ),
                customdata=list(zip(anr_activities, anr_reasons, anr_memory_pressure)),
                hovertemplate="<b>ANR Event</b><br>Activity: %{customdata[0]}<br>Reason: %{customdata[1]}<br>Memory Pressure: %{customdata[2]}<br>Time: %{x}<extra></extra>",
                name="ANR"
            ),
            row=anr_row, col=1
        )
    
    # Fatal Exceptions
    if fatal_exceptions:
        fatal_timestamps = [fe['timestamp'] for fe in fatal_exceptions]
        fatal_values = [1] * len(fatal_exceptions)
        fatal_row = 5
        if memory_info:
            fatal_row += 1
        if lowmemorykiller_events:
            fatal_row += 1
        if anr_timestamps:
            fatal_row += 1
        fig.add_trace(
            go.Scatter(
                x=fatal_timestamps,
                y=fatal_values,
                mode='markers',
                marker=dict(
                    symbol='triangle-down',
                    size=18,
                    color='darkred',
                    line=dict(width=2, color='black')
                ),
                customdata=[(fe['process'], fe['exception_type'], fe['message'], fe['pid'], fe['type']) for fe in fatal_exceptions],
                hovertemplate="<b>Fatal Exception</b><br>Process: %{customdata[0]}<br>PID: %{customdata[3]}<br>Type: %{customdata[1]}<br>Source: %{customdata[4]}<br>Message: %{customdata[2]}<br>Time: %{x}<extra></extra>",
                name="Fatal Exception"
            ),
            row=fatal_row, col=1
        )
    
    # Set x-axis to show timeline from start to end of logcat
    # Use first_log_timestamp and last_log_timestamp if available, otherwise use all_timestamps
    if first_log_timestamp and last_log_timestamp:
        min_time = first_log_timestamp
        max_time = last_log_timestamp
    elif all_timestamps:
        min_time = min(all_timestamps)
        max_time = max(all_timestamps)
    else:
        min_time = None
        max_time = None
    
    if min_time and max_time:
        # Add padding (5% on each side or minimum 60 seconds)
        from datetime import timedelta
        time_range = (max_time - min_time).total_seconds()
        padding_seconds = max(time_range * 0.05, 60)  # 5% padding or minimum 60 seconds
        padding_delta = timedelta(seconds=padding_seconds)
        xaxis_range = [min_time - padding_delta, max_time + padding_delta]
    else:
        xaxis_range = None
    
    # Adjust height based on number of subplots
    base_height = 300
    total_height = base_height * num_rows
    
    fig.update_layout(
        height=total_height,
        showlegend=False,
        title="Logcat Launch & UI Performance Analysis"
    )
    
    # Update all x-axes to show timeline
    for i in range(1, num_rows + 1):
        fig.update_xaxes(
            title_text="Time",
            type="date",
            range=xaxis_range,
            row=i, col=1
        )
    
    # Update Memory Usage y-axis
    if memory_info:
        memory_row = 5
        fig.update_yaxes(
            title_text="Memory (MB)",
            row=memory_row, col=1
        )
    
    # Update LowMemoryKiller y-axis
    if lowmemorykiller_events:
        lmk_row = 5
        if memory_info:
            lmk_row = 6
        fig.update_yaxes(
            title_text="OOM Score Adj",
            row=lmk_row, col=1
        )
    
    # Update ANR y-axis to show just markers
    if anr_timestamps:
        anr_row = 5
        if memory_info:
            anr_row += 1
        if lowmemorykiller_events:
            anr_row += 1
        fig.update_yaxes(
            title_text="ANR Events",
            showticklabels=False,
            range=[0, 2],
            row=anr_row, col=1
        )
    
    # Update Fatal Exception y-axis to show just markers
    if fatal_exceptions:
        fatal_row = 5
        if memory_info:
            fatal_row += 1
        if lowmemorykiller_events:
            fatal_row += 1
        if anr_timestamps:
            fatal_row += 1
        fig.update_yaxes(
            title_text="Fatal Exceptions",
            showticklabels=False,
            range=[0, 2],
            row=fatal_row, col=1
        )
    
    # Find activities that exceed 500ms threshold (TTID)
    exceeded_activities = []
    if disp_times:
        for i, (time, label, full_act) in enumerate(zip(disp_times, disp_labels, disp_full)):
            if time > 500:
                exceeded_activities.append({
                    'activity': full_act,
                    'label': label,
                    'time': time,
                    'timestamp': disp_x[i]
                })
    
    # Find activities that exceed 1000ms threshold (TTFD)
    exceeded_ttfd_activities = []
    if full_times:
        for i, (time, label, full_act) in enumerate(zip(full_times, full_labels, full_full)):
            if time > 1000:
                exceeded_ttfd_activities.append({
                    'activity': full_act,
                    'label': label,
                    'time': time,
                    'timestamp': full_x[i]
                })
    
    # Create HTML with exceeded activities list
    output_name += ".html"
    
    # Generate the plot HTML
    html_string = fig.to_html(include_plotlyjs='cdn')
    
    # Build exceeded activities sections
    exceeded_sections = []
    
    # TTID (Displayed Time) exceeded activities
    if exceeded_activities:
        ttid_section = """
    <div style="margin: 20px; padding: 20px; background-color: #f8f9fa; border: 2px solid #dc3545; border-radius: 5px;">
        <h2 style="color: #dc3545; margin-top: 0;">⚠️ Displayed Time (TTID) - Activities Exceeding 500ms Threshold</h2>
        <table style="width: 100%; border-collapse: collapse; margin-top: 10px;">
            <thead>
                <tr style="background-color: #dc3545; color: white;">
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Activity Name</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Label</th>
                    <th style="padding: 10px; text-align: right; border: 1px solid #ddd;">Time (ms)</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Timestamp</th>
                </tr>
            </thead>
            <tbody>
"""
        for item in exceeded_activities:
            ttid_section += f"""
                <tr style="background-color: #fff;">
                    <td style="padding: 10px; border: 1px solid #ddd;"><b>{item['activity']}</b></td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{item['label']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd; text-align: right; color: #dc3545; font-weight: bold;">{item['time']} ms</td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{item['timestamp'].strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}</td>
                </tr>
"""
        ttid_section += f"""
            </tbody>
        </table>
        <p style="margin-top: 15px; color: #666; font-style: italic;">Total: {len(exceeded_activities)} activity/activities exceeded the 500ms threshold</p>
    </div>
"""
        exceeded_sections.append(ttid_section)
    else:
        ttid_section = """
    <div style="margin: 20px; padding: 20px; background-color: #d4edda; border: 2px solid #28a745; border-radius: 5px;">
        <h2 style="color: #28a745; margin-top: 0;">✓ Displayed Time (TTID) - All Activities Within 500ms Threshold</h2>
        <p style="color: #155724; margin: 10px 0;">All displayed activities are performing within the acceptable 500ms threshold.</p>
    </div>
"""
        exceeded_sections.append(ttid_section)
    
    # TTFD (Fully Drawn Time) exceeded activities
    if exceeded_ttfd_activities:
        ttfd_section = """
    <div style="margin: 20px; padding: 20px; background-color: #f8f9fa; border: 2px solid #dc3545; border-radius: 5px;">
        <h2 style="color: #dc3545; margin-top: 0;">⚠️ Fully Drawn Time (TTFD) - Activities Exceeding 1000ms Threshold</h2>
        <table style="width: 100%; border-collapse: collapse; margin-top: 10px;">
            <thead>
                <tr style="background-color: #dc3545; color: white;">
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Activity Name</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Label</th>
                    <th style="padding: 10px; text-align: right; border: 1px solid #ddd;">Time (ms)</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Timestamp</th>
                </tr>
            </thead>
            <tbody>
"""
        for item in exceeded_ttfd_activities:
            ttfd_section += f"""
                <tr style="background-color: #fff;">
                    <td style="padding: 10px; border: 1px solid #ddd;"><b>{item['activity']}</b></td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{item['label']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd; text-align: right; color: #dc3545; font-weight: bold;">{item['time']} ms</td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{item['timestamp'].strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}</td>
                </tr>
"""
        ttfd_section += f"""
            </tbody>
        </table>
        <p style="margin-top: 15px; color: #666; font-style: italic;">Total: {len(exceeded_ttfd_activities)} activity/activities exceeded the 1000ms threshold</p>
    </div>
"""
        exceeded_sections.append(ttfd_section)
    else:
        ttfd_section = """
    <div style="margin: 20px; padding: 20px; background-color: #d4edda; border: 2px solid #28a745; border-radius: 5px;">
        <h2 style="color: #28a745; margin-top: 0;">✓ Fully Drawn Time (TTFD) - All Activities Within 1000ms Threshold</h2>
        <p style="color: #155724; margin: 10px 0;">All fully drawn activities are performing within the acceptable 1000ms threshold.</p>
    </div>
"""
        exceeded_sections.append(ttfd_section)
    
    # ANR Summary Section
    if anr_timestamps:
        anr_section = """
    <div style="margin: 20px; padding: 20px; background-color: #fff3cd; border: 2px solid #ffc107; border-radius: 5px;">
        <h2 style="color: #856404; margin-top: 0;">⚠️ ANR (Application Not Responding) Events</h2>
        <table style="width: 100%; border-collapse: collapse; margin-top: 10px;">
            <thead>
                <tr style="background-color: #ffc107; color: #000;">
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Activity Name</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Reason</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Memory Pressure (avg10)</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Timestamp</th>
                </tr>
            </thead>
            <tbody>
"""
        for i, (activity, reason, memory, timestamp) in enumerate(zip(anr_activities, anr_reasons, anr_memory_pressure, anr_timestamps)):
            anr_section += f"""
                <tr style="background-color: {'#fff' if i % 2 == 0 else '#f8f9fa'};">
                    <td style="padding: 10px; border: 1px solid #ddd;"><b>{activity}</b></td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{reason}</td>
                    <td style="padding: 10px; border: 1px solid #ddd; text-align: right;">{memory}</td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{timestamp.strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}</td>
                </tr>
"""
        anr_section += f"""
            </tbody>
        </table>
        <p style="margin-top: 15px; color: #856404; font-style: italic;">Total: {len(anr_timestamps)} ANR event(s) detected</p>
    </div>
"""
        exceeded_sections.append(anr_section)
    else:
        anr_section = """
    <div style="margin: 20px; padding: 20px; background-color: #d4edda; border: 2px solid #28a745; border-radius: 5px;">
        <h2 style="color: #28a745; margin-top: 0;">✓ No ANR Events Detected</h2>
        <p style="color: #155724; margin: 10px 0;">No Application Not Responding (ANR) events were found in the logcat.</p>
    </div>
"""
        exceeded_sections.append(anr_section)
    
    # Memory Info Table Section (for com.telekom.onetv processes)
    if memory_info:
        memory_section = """
    <div style="margin: 20px; padding: 20px; background-color: #e7f3ff; border: 2px solid #0066cc; border-radius: 5px;">
        <h2 style="color: #004085; margin-top: 0;">📊 Memory Information - com.telekom.onetv Processes</h2>
        <table style="width: 100%; border-collapse: collapse; margin-top: 10px;">
            <thead>
                <tr style="background-color: #0066cc; color: white;">
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Process Name</th>
                    <th style="padding: 10px; text-align: right; border: 1px solid #ddd;">PSS (MB)</th>
                    <th style="padding: 10px; text-align: right; border: 1px solid #ddd;">Native Heap (MB)</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Timestamp</th>
                </tr>
            </thead>
            <tbody>
"""
        for i, mem in enumerate(memory_info):
            memory_section += f"""
                <tr style="background-color: {'#fff' if i % 2 == 0 else '#f8f9fa'};">
                    <td style="padding: 10px; border: 1px solid #ddd;"><b>{mem['process']}</b></td>
                    <td style="padding: 10px; border: 1px solid #ddd; text-align: right; font-weight: bold;">{mem['pss_mb']} MB</td>
                    <td style="padding: 10px; border: 1px solid #ddd; text-align: right;">{mem['native_heap_mb'] if mem['native_heap_mb'] is not None else 'N/A'} {'' if mem['native_heap_mb'] is None else 'MB'}</td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{mem['timestamp'].strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}</td>
                </tr>
"""
        memory_section += f"""
            </tbody>
        </table>
        <p style="margin-top: 15px; color: #004085; font-style: italic;">Total: {len(memory_info)} memory snapshot(s) captured</p>
    </div>
"""
        exceeded_sections.append(memory_section)
    else:
        memory_section = """
    <div style="margin: 20px; padding: 20px; background-color: #d4edda; border: 2px solid #28a745; border-radius: 5px;">
        <h2 style="color: #28a745; margin-top: 0;">✓ Memory Information - No com.telekom.onetv Processes Found</h2>
        <p style="color: #155724; margin: 10px 0;">No memory information for com.telekom.onetv processes was found in the logcat.</p>
    </div>
"""
        exceeded_sections.append(memory_section)
    
    # LowMemoryKiller Summary Section
    if lowmemorykiller_events:
        # Separate kill events and find events
        kill_events = [lmk for lmk in lowmemorykiller_events if lmk.get('type') == 'kill']
        find_events = [lmk for lmk in lowmemorykiller_events if lmk.get('type') == 'find']
        
        # Calculate statistics for all events
        oom_scores = [lmk['oom_score_adj'] for lmk in lowmemorykiller_events]
        rss_values = [lmk['rss_kb'] for lmk in lowmemorykiller_events if lmk.get('rss_kb') is not None]
        swap_values = [lmk['swap_kb'] for lmk in lowmemorykiller_events if lmk.get('swap_kb') is not None]
        
        avg_oom = sum(oom_scores) / len(oom_scores) if oom_scores else 0
        max_oom = max(oom_scores) if oom_scores else 0
        min_oom = min(oom_scores) if oom_scores else 0
        
        avg_rss = sum(rss_values) / len(rss_values) if rss_values else 0
        max_rss = max(rss_values) if rss_values else 0
        
        avg_swap = sum(swap_values) / len(swap_values) if swap_values else 0
        max_swap = max(swap_values) if swap_values else 0
        
        # Group by process to see which processes were killed most (only for kill events)
        process_counts = {}
        for lmk in kill_events:
            process = lmk['process']
            if process != 'N/A':
                process_counts[process] = process_counts.get(process, 0) + 1
        
        most_killed_process = max(process_counts.items(), key=lambda x: x[1]) if process_counts else None
        
        # Group by reason
        reason_counts = {}
        for lmk in lowmemorykiller_events:
            reason = lmk['reason']
            reason_counts[reason] = reason_counts.get(reason, 0) + 1
        
        # Group find events by OOM score
        find_oom_counts = {}
        for lmk in find_events:
            oom = lmk['oom_score_adj']
            find_oom_counts[oom] = find_oom_counts.get(oom, 0) + 1
        
        lmk_section = """
    <div style="margin: 20px; padding: 20px; background-color: #fff3cd; border: 2px solid #ff9800; border-radius: 5px;">
        <h2 style="color: #856404; margin-top: 0;">⚠️ LowMemoryKiller Events</h2>
        
        <div style="margin: 20px 0; padding: 15px; background-color: #fff; border: 1px solid #ff9800; border-radius: 5px;">
            <h3 style="color: #856404; margin-top: 0;">Statistics</h3>
            <table style="width: 100%; border-collapse: collapse;">
                <thead>
                    <tr style="background-color: #ff9800; color: white;">
                        <th style="padding: 8px; text-align: left; border: 1px solid #ddd;">Metric</th>
                        <th style="padding: 8px; text-align: right; border: 1px solid #ddd;">OOM Score Adj</th>
                        <th style="padding: 8px; text-align: right; border: 1px solid #ddd;">RSS (kB)</th>
                        <th style="padding: 8px; text-align: right; border: 1px solid #ddd;">Swap (kB)</th>
                    </tr>
                </thead>
                <tbody>
                    <tr style="background-color: #fff;">
                        <td style="padding: 8px; border: 1px solid #ddd;"><b>Average</b></td>
                        <td style="padding: 8px; border: 1px solid #ddd; text-align: right; font-weight: bold;">{:.1f}</td>
                        <td style="padding: 8px; border: 1px solid #ddd; text-align: right;">{:.1f}</td>
                        <td style="padding: 8px; border: 1px solid #ddd; text-align: right;">{:.1f}</td>
                    </tr>
                    <tr style="background-color: #f8f9fa;">
                        <td style="padding: 8px; border: 1px solid #ddd;"><b>Maximum</b></td>
                        <td style="padding: 8px; border: 1px solid #ddd; text-align: right; color: #dc3545; font-weight: bold;">{:.1f}</td>
                        <td style="padding: 8px; border: 1px solid #ddd; text-align: right; color: #dc3545;">{:.1f}</td>
                        <td style="padding: 8px; border: 1px solid #ddd; text-align: right; color: #dc3545;">{:.1f}</td>
                    </tr>
                    <tr style="background-color: #fff;">
                        <td style="padding: 8px; border: 1px solid #ddd;"><b>Minimum</b></td>
                        <td style="padding: 8px; border: 1px solid #ddd; text-align: right;">{:.1f}</td>
                        <td style="padding: 8px; border: 1px solid #ddd; text-align: right;">-</td>
                        <td style="padding: 8px; border: 1px solid #ddd; text-align: right;">-</td>
                    </tr>
                </tbody>
            </table>
            <p style="margin-top: 10px; color: #856404; font-size: 0.9em;">
                <b>Total Kill Events:</b> {total_kills}<br>
                <b>Total Find Events:</b> {total_finds}<br>
                <b>Most Killed Process:</b> {most_killed}<br>
                <b>Top Kill Reasons:</b> {top_reasons}<br>
                <b>Find Events by OOM Score:</b> {find_oom_summary}
            </p>
        </div>
        
        <h3 style="color: #856404; margin-top: 20px;">Kill Events</h3>
        <table style="width: 100%; border-collapse: collapse; margin-top: 10px;">
            <thead>
                <tr style="background-color: #ff9800; color: white;">
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Process Name</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">PID</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">UID</th>
                    <th style="padding: 10px; text-align: right; border: 1px solid #ddd;">OOM Score Adj</th>
                    <th style="padding: 10px; text-align: right; border: 1px solid #ddd;">RSS (kB)</th>
                    <th style="padding: 10px; text-align: right; border: 1px solid #ddd;">Swap (kB)</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Reason</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Timestamp</th>
                </tr>
            </thead>
            <tbody>
""".format(
            avg_oom, avg_rss, avg_swap,
            max_oom, max_rss, max_swap,
            min_oom,
            total_kills=len(kill_events),
            total_finds=len(find_events),
            most_killed=f"{most_killed_process[0]} ({most_killed_process[1]} times)" if most_killed_process else "N/A",
            top_reasons=", ".join([f"{reason} ({count}x)" for reason, count in sorted(reason_counts.items(), key=lambda x: x[1], reverse=True)[:3]]) if reason_counts else "N/A",
            find_oom_summary=", ".join([f"OOM {oom} ({count}x)" for oom, count in sorted(find_oom_counts.items(), key=lambda x: x[1], reverse=True)[:5]]) if find_oom_counts else "N/A"
        )
        for i, lmk in enumerate(kill_events):
            lmk_section += f"""
                <tr style="background-color: {'#fff' if i % 2 == 0 else '#f8f9fa'};">
                    <td style="padding: 10px; border: 1px solid #ddd;"><b>{lmk['process']}</b></td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{lmk['pid']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{lmk['uid']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd; text-align: right; font-weight: bold; color: #dc3545;">{lmk['oom_score_adj']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd; text-align: right;">{lmk['rss_kb']} kB</td>
                    <td style="padding: 10px; border: 1px solid #ddd; text-align: right;">{lmk['swap_kb']} kB</td>
                    <td style="padding: 10px; border: 1px solid #ddd; max-width: 400px; word-wrap: break-word;">{lmk['reason']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{lmk['timestamp'].strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}</td>
                </tr>
"""
        lmk_section += f"""
            </tbody>
        </table>
        <p style="margin-top: 15px; color: #856404; font-style: italic;">Total: {len(kill_events)} kill event(s)</p>
        
        <h3 style="color: #856404; margin-top: 20px;">Find Events (find_and_kill_process)</h3>
        <table style="width: 100%; border-collapse: collapse; margin-top: 10px;">
            <thead>
                <tr style="background-color: #ff9800; color: white;">
                    <th style="padding: 10px; text-align: right; border: 1px solid #ddd;">OOM Score Adj</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Result</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Timestamp</th>
                </tr>
            </thead>
            <tbody>
"""
        for i, lmk in enumerate(find_events):
            lmk_section += f"""
                <tr style="background-color: {'#fff' if i % 2 == 0 else '#f8f9fa'};">
                    <td style="padding: 10px; border: 1px solid #ddd; text-align: right; font-weight: bold; color: #dc3545;">{lmk['oom_score_adj']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd; max-width: 400px; word-wrap: break-word;">{lmk['reason']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{lmk['timestamp'].strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}</td>
                </tr>
"""
        lmk_section += f"""
            </tbody>
        </table>
        <p style="margin-top: 15px; color: #856404; font-style: italic;">Total: {len(find_events)} find event(s)</p>
    </div>
"""
        exceeded_sections.append(lmk_section)
    else:
        lmk_section = """
    <div style="margin: 20px; padding: 20px; background-color: #d4edda; border: 2px solid #28a745; border-radius: 5px;">
        <h2 style="color: #28a745; margin-top: 0;">✓ No LowMemoryKiller Events Detected</h2>
        <p style="color: #155724; margin: 10px 0;">No LowMemoryKiller events were found in the logcat.</p>
    </div>
"""
        exceeded_sections.append(lmk_section)
    
    # Fatal Exception Summary Section
    if fatal_exceptions:
        fatal_section = """
    <div style="margin: 20px; padding: 20px; background-color: #f8d7da; border: 2px solid #dc3545; border-radius: 5px;">
        <h2 style="color: #721c24; margin-top: 0;">🚨 Fatal Exceptions</h2>
        <table style="width: 100%; border-collapse: collapse; margin-top: 10px;">
            <thead>
                <tr style="background-color: #dc3545; color: white;">
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Process Name</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">PID</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Exception Type</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Source</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Message</th>
                    <th style="padding: 10px; text-align: left; border: 1px solid #ddd;">Timestamp</th>
                </tr>
            </thead>
            <tbody>
"""
        for i, fe in enumerate(fatal_exceptions):
            fatal_section += f"""
                <tr style="background-color: {'#fff' if i % 2 == 0 else '#f8f9fa'};">
                    <td style="padding: 10px; border: 1px solid #ddd;"><b>{fe['process']}</b></td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{fe['pid']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd; color: #dc3545; font-weight: bold;">{fe['exception_type']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{fe['type']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd; max-width: 400px; word-wrap: break-word;">{fe['message']}</td>
                    <td style="padding: 10px; border: 1px solid #ddd;">{fe['timestamp'].strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}</td>
                </tr>
"""
        fatal_section += f"""
            </tbody>
        </table>
        <p style="margin-top: 15px; color: #721c24; font-style: italic;">Total: {len(fatal_exceptions)} fatal exception(s) detected</p>
    </div>
"""
        exceeded_sections.append(fatal_section)
    else:
        fatal_section = """
    <div style="margin: 20px; padding: 20px; background-color: #d4edda; border: 2px solid #28a745; border-radius: 5px;">
        <h2 style="color: #28a745; margin-top: 0;">✓ No Fatal Exceptions Detected</h2>
        <p style="color: #155724; margin: 10px 0;">No fatal exceptions were found in the logcat.</p>
    </div>
"""
        exceeded_sections.append(fatal_section)
    
    # Combine all sections and insert before closing body tag
    all_sections = "".join(exceeded_sections) + "\n</body>\n</html>"
    html_string = html_string.replace('</body>', all_sections)
    
    # Write the modified HTML
    with open(output_name, 'w', encoding='utf-8') as f:
        f.write(html_string)
    
    if exceeded_activities:
        print(f"[INFO] Found {len(exceeded_activities)} activities exceeding 500ms threshold (TTID)")
    else:
        print("[INFO] All activities are within 500ms threshold (TTID)")
    
    if exceeded_ttfd_activities:
        print(f"[INFO] Found {len(exceeded_ttfd_activities)} activities exceeding 1000ms threshold (TTFD)")
    else:
        print("[INFO] All activities are within 1000ms threshold (TTFD)")
    
    if anr_timestamps:
        print(f"[INFO] Found {len(anr_timestamps)} ANR events")
    
    if memory_info:
        print(f"[INFO] Found {len(memory_info)} memory info entries for com.telekom.onetv processes")
    
    if fatal_exceptions:
        print(f"[INFO] Found {len(fatal_exceptions)} fatal exceptions")
    
    if lowmemorykiller_events:
        print(f"[INFO] Found {len(lowmemorykiller_events)} lowmemorykiller events")
    
    print(f"[INFO][SUCCESS] {output_name} generated")

# --------------------------------------------------
# Main
# --------------------------------------------------
if __name__ == "__main__":
    parse_log()
    generate_html(output_name)
