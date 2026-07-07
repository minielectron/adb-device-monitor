#!/usr/bin/env python3
"""
Sample `dumpsys meminfo <package>` App Summary PSS (**KB**) and resumed activity; **CPU panel**
uses one **`top -n 1`** snapshot per interval for **process CPU %** and **system idle %** when
parsable, with **fallback** to `/proc/<pid>/stat` (process) and `/proc/stat` (idle). CSV + plots:
PSS (MB) on top; CPU + idle below (dual Y-axis). **Session averages** sit in a summary strip
under both charts (below activity-change labels), not over the plot area.

Requirements:
  pip install plotly
  # Optional: pip install kaleido  (static PNG/PDF from --plot file.png)

Simple usage:
  python3 scripts/MemCPUMonitor.py -p com.telekom.onetv.tv.hr --plot mem6.html

Optional:
  python3 scripts/MemCPUMonitor.py -p com.telekom.onetv.tv.hr -i 10 -d 300 -o custom.csv --plot out.html
  python3 scripts/MemCPUMonitor.py -p com.telekom.onetv.tv.hr --stagger 1.0
  python3 scripts/MemCPUMonitor.py -p com.telekom.onetv.tv.hr --no-cpu --plot mem_pss_only.html
  python3 scripts/MemCPUMonitor.py -p com.telekom.onetv.tv.hr --cpu-package com.android.vending

Meminfo, activity dumpsys, and `top` are separated by short sleeps (see --stagger).
Process CPU and system idle use the **same** `top` line semantics: per-core scaled %% (e.g. 4 cores → up to ~400%%%% idle / %%cpu). Chart Y range grows with the data; `/proc/stat` idle fallback stays 0–100%%%%.
"""

from __future__ import annotations

import argparse
import csv
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

# Category lines in App Summary (label: first PSS number in KB).
CATEGORY_LABELS = [
    "Java Heap",
    "Native Heap",
    "Code",
    "Stack",
    "Graphics",
    "Private Other",
    "System",
    "Unknown",
]

TOTAL_PSS_LABEL = "Total PSS"
PSS_LABELS = CATEGORY_LABELS + [TOTAL_PSS_LABEL]


def _pss_pattern(label: str) -> re.Pattern:
    escaped = re.escape(label)
    return re.compile(rf"^\s*{escaped}:\s+(\d+)", re.MULTILINE)


PSS_REGEXES = {label: _pss_pattern(label) for label in CATEGORY_LABELS}
KB_PER_MB = 1024.0

# CPU chart Y range (minimum upper bound; actual max may auto-expand from samples).
CPU_Y_AXIS_MIN = 0
CPU_Y_AXIS_MAX = 400

# Android `top` reports multi-core %cpu as sum across cores (e.g. 400%%cpu + 144%%idle on 4 cores).
TOP_MULTICORE_PERCENT_CAP = 6400.0

# Default pause between adb-heavy phases (meminfo vs activity vs top) to reduce overlap load.
DEFAULT_SAMPLE_STAGGER_SEC = 0.75

# Idle axis (secondary Y on CPU panel; same scale as CPU / top)
IDLE_Y_AXIS_MIN = 0
IDLE_Y_AXIS_MAX = CPU_Y_AXIS_MAX


def adb_shell(args: list[str], timeout: float = 60.0) -> str:
    cmd = ["adb", "shell", *args]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError(f"adb failed: {' '.join(cmd)}\nstderr: {p.stderr}")
    return p.stdout


def parse_total_pss_kb(stdout: str) -> int | None:
    marker = stdout.find("App Summary")
    window = stdout[marker:] if marker != -1 else stdout

    patterns = [
        re.compile(r"^\s*TOTAL:\s*(\d+)", re.MULTILINE),
        re.compile(r"^\s*Total PSS:\s*(\d+)", re.MULTILINE | re.IGNORECASE),
    ]
    for pat in patterns:
        m = pat.search(window)
        if m:
            return int(m.group(1))

    for line in window.splitlines():
        s = line.strip()
        m = re.match(r"^(\d+)\s+TOTAL\s*$", s, re.IGNORECASE)
        if m:
            return int(m.group(1))
    return None


def parse_meminfo_pss(stdout: str) -> dict[str, int | None]:
    out: dict[str, int | None] = {}
    for label in CATEGORY_LABELS:
        m = PSS_REGEXES[label].search(stdout)
        if m:
            out[label] = int(m.group(1))
        else:
            out[label] = None
    out[TOTAL_PSS_LABEL] = parse_total_pss_kb(stdout)
    return out


def get_clock_ticks_per_sec() -> int:
    try:
        v = adb_shell(["getconf", "CLK_TCK"], timeout=5.0).strip()
        return max(1, int(v))
    except Exception:
        return 100


def get_primary_pid_for_package(package: str) -> int | None:
    try:
        out = adb_shell(["pidof", package.strip()], timeout=10.0).strip()
    except Exception:
        return None
    if not out:
        return None
    for tok in out.split():
        try:
            return int(tok)
        except ValueError:
            continue
    return None


def parse_proc_stat_utime_stime(stat_line: str) -> tuple[int, int] | None:
    rpar = stat_line.rfind(")")
    if rpar == -1:
        return None
    rest = stat_line[rpar + 2 :].strip()
    parts = rest.split()
    if len(parts) < 13:
        return None
    try:
        utime = int(parts[11])
        stime = int(parts[12])
        return utime, stime
    except (ValueError, IndexError):
        return None


def run_top_snapshot() -> str | None:
    """One-shot `top` over adb; format varies by device."""
    for cmd in (
        ["top", "-n", "1"],
        ["top", "-b", "-n", "1"],
        ["sh", "-c", "top -n 1"],
    ):
        try:
            out = adb_shell(cmd, timeout=25.0)
            if out and len(out.strip()) > 30:
                return out
        except Exception:
            continue
    return None


def parse_top_idle_percent(top_text: str) -> float | None:
    """
    Extract system idle %% from the aggregated `top` CPU line.

    On Android multi-core, idle is **not** capped at 100: it matches %%cpu totals (e.g.
    ``144%%idle`` with ``400%%cpu`` on four cores).
    """
    patterns = [
        re.compile(r"(\d+\.?\d*)\s*%\s*idle\b", re.IGNORECASE),
        re.compile(r"\bidle\b\D{0,20}(\d+\.?\d*)\s*%", re.IGNORECASE),
        re.compile(r"(\d+\.?\d*)\s*%\s*id\b", re.IGNORECASE),  # Cpu(s): .. %id
        re.compile(r"%cpu[^:]{0,120}(\d+\.?\d*)\s*%\s*idle", re.IGNORECASE),
    ]
    for line in top_text.splitlines():
        low = line.lower()
        if "idle" not in low and "%id" not in low and " id" not in low:
            continue
        for pat in patterns:
            m = pat.search(line)
            if m:
                v = float(m.group(1))
                if 0 <= v <= TOP_MULTICORE_PERCENT_CAP:
                    return v
    for line in top_text.splitlines():
        if "cpu" not in line.lower():
            continue
        for m in re.finditer(r"(\d+\.?\d*)\s*%\s*idle", line, re.IGNORECASE):
            v = float(m.group(1))
            if 0 <= v <= TOP_MULTICORE_PERCENT_CAP:
                return v
    return None


def parse_top_process_cpu_percent(top_text: str, pid: int) -> float | None:
    """
    Match `top` process row by PID; read %%CPU.

    Some builds print ``140`` in the %%CPU column **without** a ``%%`` suffix (only the header
    shows [%CPU]). Prefer ``…140%%…`` tokens when present; else use the token after run state.
    """
    pid_re = re.compile(rf"^\s*{pid}\b")
    run_state_re = re.compile(r"^[SRDZTWIK]$", re.IGNORECASE)

    for raw in top_text.splitlines():
        if not pid_re.match(raw):
            continue
        matches = re.findall(r"(\d+\.?\d*)%", raw)
        best: float | None = None
        for s in reversed(matches):
            try:
                v = float(s)
            except ValueError:
                continue
            if 0 <= v <= TOP_MULTICORE_PERCENT_CAP:
                best = v
                break
        if best is not None:
            return best

        parts = raw.split()
        state_idx: int | None = None
        for i, p in enumerate(parts):
            if run_state_re.match(p):
                state_idx = i
                break
        if state_idx is not None and state_idx + 1 < len(parts):
            tok = parts[state_idx + 1].rstrip("%")
            if ":" in tok:
                return None
            try:
                v = float(tok)
            except ValueError:
                return None
            if 0 <= v <= TOP_MULTICORE_PERCENT_CAP:
                return v

        if len(parts) >= 10 and parts[0] == str(pid):
            tok = parts[8].rstrip("%")
            if ":" not in tok:
                try:
                    v = float(tok)
                except ValueError:
                    return None
                if 0 <= v <= TOP_MULTICORE_PERCENT_CAP:
                    return v
    return None


def read_proc_stat_cpu_aggregates() -> tuple[int, int] | None:
    """
    First `cpu` line in /proc/stat: (idle jiffies, total jiffies) for idle%% fallback.
    """
    try:
        out = adb_shell(["cat", "/proc/stat"], timeout=10.0)
    except Exception:
        return None
    for line in out.splitlines():
        if not line.startswith("cpu "):
            continue
        parts = line.split()
        if len(parts) < 5:
            return None
        try:
            nums = [int(x) for x in parts[1:]]
        except ValueError:
            return None
        idle = nums[3]
        total = sum(nums)
        return idle, total
    return None


def read_process_cpu_times(pid: int) -> tuple[int, int] | None:
    try:
        content = adb_shell(["cat", f"/proc/{pid}/stat"], timeout=10.0)
    except Exception:
        return None
    line = content.strip().splitlines()
    if not line:
        return None
    return parse_proc_stat_utime_stime(line[0])


def get_resumed_activity() -> str:
    try:
        out = adb_shell(["dumpsys", "activity", "activities"], timeout=30.0)
    except Exception as e:
        return f"<adb error: {e}>"

    for line in out.splitlines():
        line = line.strip()
        if "mResumedActivity" in line or "topResumedActivity" in line:
            if "/" in line:
                parts = line.split()
                for p in parts:
                    if "/" in p and not p.startswith("{"):
                        return p[:120]
            return line[:200]
    try:
        w = adb_shell(["dumpsys", "window", "windows"], timeout=15.0)
        for line in w.splitlines():
            if "mCurrentFocus" in line or "mFocusedApp" in line:
                return line.strip()[:200]
    except Exception:
        pass
    return "unknown"


def run_loop(
    package: str,
    interval_sec: float,
    duration_sec: float | None,
    csv_path: Path,
    plot_path: Path | None,
    *,
    track_cpu: bool = True,
    cpu_package: str | None = None,
    stagger_sec: float = DEFAULT_SAMPLE_STAGGER_SEC,
) -> None:
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = (
        ["timestamp_iso", "elapsed_s"]
        + PSS_LABELS
        + ["process_pid", "cpu_percent", "cpu_idle_percent", "top_activity"]
    )

    start = time.monotonic()
    last_activity: str | None = None
    rows: list[dict] = []

    hz = get_clock_ticks_per_sec() if track_cpu else 100
    cpu_target = (cpu_package or package).strip()
    prev_cpu_sample_elapsed: float | None = None
    prev_cpu_ticks: int | None = None
    prev_proc_stat_idle: tuple[int, int] | None = None
    prev_proc_stat_sample_elapsed: float | None = None

    with csv_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()

        try:
            while True:
                elapsed = time.monotonic() - start
                if duration_sec is not None and elapsed >= duration_sec:
                    break

                ts = datetime.now(timezone.utc).astimezone()
                ts_iso = ts.isoformat(timespec="seconds")

                # Phase 1: meminfo only (heavy dumpsys).
                try:
                    mem_out = adb_shell(["dumpsys", "meminfo", package], timeout=45.0)
                    pss = parse_meminfo_pss(mem_out)
                    mem_ok = True
                except Exception as e:
                    pss = {label: None for label in PSS_LABELS}
                    activity = f"<meminfo error: {e}>"
                    mem_ok = False

                if stagger_sec > 0:
                    time.sleep(stagger_sec)

                # Phase 2: activity (separate dumpsys), only if meminfo succeeded.
                if mem_ok:
                    activity = get_resumed_activity()

                pid_val: int | None = None
                cpu_pct: float | None = None
                cpu_idle_pct: float | None = None
                if track_cpu:
                    if stagger_sec > 0:
                        time.sleep(stagger_sec)
                    cpu_read_elapsed = time.monotonic() - start
                    pid_val = get_primary_pid_for_package(cpu_target)

                    top_out = run_top_snapshot()
                    if top_out:
                        cpu_idle_pct = parse_top_idle_percent(top_out)
                        if pid_val is not None:
                            cpu_pct = parse_top_process_cpu_percent(top_out, pid_val)

                    if cpu_pct is None and pid_val is not None:
                        times = read_process_cpu_times(pid_val)
                        if (
                            times is not None
                            and prev_cpu_ticks is not None
                            and prev_cpu_sample_elapsed is not None
                        ):
                            wall = cpu_read_elapsed - prev_cpu_sample_elapsed
                            dticks = times[0] + times[1] - prev_cpu_ticks
                            if wall > 0 and hz > 0:
                                cpu_pct = 100.0 * dticks / (hz * wall)
                        if times is not None:
                            prev_cpu_ticks = times[0] + times[1]
                            prev_cpu_sample_elapsed = cpu_read_elapsed
                        else:
                            prev_cpu_ticks = None
                            prev_cpu_sample_elapsed = None
                    else:
                        if cpu_pct is not None:
                            prev_cpu_sample_elapsed = cpu_read_elapsed
                            times_fb = read_process_cpu_times(pid_val) if pid_val is not None else None
                            if times_fb is not None:
                                prev_cpu_ticks = times_fb[0] + times_fb[1]
                            else:
                                prev_cpu_ticks = None

                    # Always refresh /proc/stat baseline when track_cpu so idle fallback stays aligned.
                    agg_stat = read_proc_stat_cpu_aggregates()
                    if agg_stat is not None:
                        if cpu_idle_pct is None and prev_proc_stat_idle is not None:
                            id1, tot1 = prev_proc_stat_idle
                            id2, tot2 = agg_stat
                            dit = id2 - id1
                            dtot = tot2 - tot1
                            if (
                                dtot > 0
                                and prev_proc_stat_sample_elapsed is not None
                            ):
                                wall = cpu_read_elapsed - prev_proc_stat_sample_elapsed
                                if wall > 0:
                                    cpu_idle_pct = max(
                                        0.0, min(100.0, 100.0 * dit / dtot)
                                    )
                        prev_proc_stat_idle = agg_stat
                        prev_proc_stat_sample_elapsed = cpu_read_elapsed

                row: dict = {
                    "timestamp_iso": ts_iso,
                    "elapsed_s": f"{elapsed:.1f}",
                    **{k: (pss[k] if pss[k] is not None else "") for k in PSS_LABELS},
                    "process_pid": pid_val if (track_cpu and pid_val is not None) else "",
                    "cpu_percent": (f"{cpu_pct:.2f}" if (track_cpu and cpu_pct is not None) else ""),
                    "cpu_idle_percent": (
                        f"{cpu_idle_pct:.2f}" if (track_cpu and cpu_idle_pct is not None) else ""
                    ),
                    "top_activity": activity,
                }

                w.writerow(row)
                f.flush()
                row_for_plot = {
                    **row,
                    **{k: pss[k] for k in PSS_LABELS},
                    "process_pid": pid_val,
                    "cpu_percent": cpu_pct,
                    "cpu_idle_percent": cpu_idle_pct,
                }
                rows.append(row_for_plot)

                act_marker = ""
                if last_activity is not None and activity != last_activity:
                    act_marker = " [ACTIVITY CHANGED]"
                last_activity = activity

                cpu_msg = ""
                if track_cpu:
                    idle_s = f"{cpu_idle_pct:.1f}%" if cpu_idle_pct is not None else "—"
                    cpu_s = f"{cpu_pct:.1f}%" if cpu_pct is not None else "—"
                    cpu_msg = f"  pid={pid_val}  proc_cpu={cpu_s}  idle={idle_s}"

                print(
                    f"{ts_iso}  elapsed={elapsed:6.1f}s  "
                    f"Total={pss.get(TOTAL_PSS_LABEL)}  "
                    f"Java={pss.get('Java Heap')}  Native={pss.get('Native Heap')}  "
                    f"Gfx={pss.get('Graphics')}{cpu_msg}{act_marker}"
                )
                print(f"    activity: {activity[:100]}")

                if duration_sec is not None and (time.monotonic() - start) + interval_sec >= duration_sec:
                    remaining = duration_sec - (time.monotonic() - start)
                    if remaining <= 0:
                        break

                time.sleep(interval_sec)

        except KeyboardInterrupt:
            print("\nStopped by user (Ctrl+C).", file=sys.stderr)

    if plot_path and rows:
        _plot(rows, plot_path, PSS_LABELS, show_cpu=track_cpu)
        print("Note: CSV columns are PSS in KB (dumpsys); chart PSS axis is MB (KB/1024).")
        if track_cpu:
            print(
                "Process CPU: prefer `top -n 1`; else /proc/<pid>/stat deltas. "
                "Idle: prefer top; else /proc/stat. Session averages are on the chart."
            )


def _row_float(row: dict, key: str) -> float | None:
    v = row.get(key)
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str) and v.strip():
        try:
            return float(v.strip())
        except ValueError:
            return None
    return None


def _session_numeric_mean(rows: list[dict], key: str) -> float | None:
    vals: list[float] = []
    for r in rows:
        v = r.get(key)
        if v is not None and isinstance(v, (int, float)):
            vals.append(float(v))
        elif v is not None and isinstance(v, str) and v.strip():
            try:
                vals.append(float(v))
            except ValueError:
                pass
    if not vals:
        return None
    return sum(vals) / len(vals)


def _plot(rows: list[dict], plot_path: Path, labels: list[str], *, show_cpu: bool) -> None:
    try:
        import plotly.graph_objects as go
        from plotly.subplots import make_subplots
    except ImportError:
        print("plotly not installed; skipping plot. pip install plotly", file=sys.stderr)
        return

    elapsed = [float(r["elapsed_s"]) for r in rows]
    activities = [str(r.get("top_activity", "")) for r in rows]

    if show_cpu:
        fig = make_subplots(
            rows=2,
            cols=1,
            shared_xaxes=True,
            vertical_spacing=0.14,
            row_heights=[0.58, 0.42],
            specs=[[{}], [{"secondary_y": True}]],
            subplot_titles=(
                "PSS (MB) — dumpsys meminfo (KB ÷ 1024)",
                "Process CPU & idle — one top snapshot (multi-core %; e.g. ~400 max on 4 cores)",
            ),
        )
        target_row_pss = 1
        target_row_cpu = 2
    else:
        fig = make_subplots(rows=1, cols=1)
        target_row_pss = 1
        target_row_cpu = None

    palette = [
        "#636EFA",
        "#EF553B",
        "#00CC96",
        "#AB63FA",
        "#FFA15A",
        "#19D3F3",
        "#FF6692",
        "#B6E880",
    ]

    cat_idx = 0
    for label in labels:
        ys_mb: list[float | None] = []
        for r in rows:
            v = r.get(label)
            if v is not None:
                ys_mb.append(float(v) / KB_PER_MB)
            else:
                ys_mb.append(None)

        if label == TOTAL_PSS_LABEL:
            color = "#111827"
            width = 3.5
            trace_name = TOTAL_PSS_LABEL
        else:
            color = palette[cat_idx % len(palette)]
            width = 2
            cat_idx += 1
            trace_name = label

        fig.add_trace(
            go.Scatter(
                x=elapsed,
                y=ys_mb,
                mode="lines",
                name=trace_name,
                line=dict(width=width, color=color),
                connectgaps=False,
                customdata=activities,
                hovertemplate=(
                    "<b>%{fullData.name}</b><br>"
                    "Time: %{x:.1f} s<br>"
                    "PSS: %{y:.2f} MB (dumpsys KB / 1024)<br>"
                    "Activity: %{customdata}<extra></extra>"
                ),
            ),
            row=target_row_pss,
            col=1,
        )

    if show_cpu and target_row_cpu is not None:
        cpu_y: list[float | None] = []
        idle_y: list[float | None] = []
        for r in rows:
            cpu_y.append(_row_float(r, "cpu_percent"))
            idle_y.append(_row_float(r, "cpu_idle_percent"))
        fig.add_trace(
            go.Scatter(
                x=elapsed,
                y=cpu_y,
                mode="lines",
                name="Proc CPU %",
                line=dict(width=2.5, color="#B45309"),
                connectgaps=False,
                customdata=activities,
                hovertemplate=(
                    "<b>Process CPU</b><br>"
                    "Time: %{x:.1f} s<br>"
                    "CPU: %{y:.1f} %<br>"
                    "Activity: %{customdata}<extra></extra>"
                ),
            ),
            row=target_row_cpu,
            col=1,
            secondary_y=False,
        )
        fig.add_trace(
            go.Scatter(
                x=elapsed,
                y=idle_y,
                mode="lines+markers",
                name="System idle %",
                line=dict(width=3, color="#047857", dash="dot"),
                marker=dict(size=5, color="#047857", line=dict(width=0)),
                connectgaps=True,
                customdata=activities,
                hovertemplate=(
                    "<b>System idle</b><br>"
                    "Time: %{x:.1f} s<br>"
                    "Idle: %{y:.1f} %<br>"
                    "Activity: %{customdata}<extra></extra>"
                ),
            ),
            row=target_row_cpu,
            col=1,
            secondary_y=True,
        )
        if not any(v is not None for v in idle_y):
            print(
                "MemCPUMonitor: no cpu_idle_percent samples (top/proc idle parse); "
                "idle line omitted.",
                file=sys.stderr,
            )

    activity_change_xs: list[float] = []
    annotation_specs: list[tuple[float, str]] = []
    prev_act: str | None = None
    max_activity_labels = 40
    label_count = 0

    for j, act in enumerate(activities):
        if prev_act is not None and act != prev_act:
            x = elapsed[j]
            activity_change_xs.append(x)
            if label_count < max_activity_labels:
                short = act if len(act) <= 70 else act[:67] + "..."
                annotation_specs.append((x, short))
                label_count += 1
        prev_act = act

    for x in activity_change_xs:
        fig.add_vline(
            x=x,
            line=dict(color="rgba(80,80,80,0.45)", width=1, dash="dash"),
            row=target_row_pss,
            col=1,
        )
        if show_cpu and target_row_cpu is not None:
            fig.add_vline(
                x=x,
                line=dict(color="rgba(80,80,80,0.45)", width=1, dash="dash"),
                row=target_row_cpu,
                col=1,
            )

    # Activity-change labels: in bottom margin, above session summary (more negative y = lower).
    annotations: list[dict] = []
    activity_label_paper_y = -0.035
    for x, short in annotation_specs:
        annotations.append(
            dict(
                x=x,
                y=activity_label_paper_y,
                xref="x",
                yref="paper",
                text=short,
                showarrow=False,
                xanchor="center",
                yanchor="top",
                font=dict(size=8, color="#374151"),
                textangle=-90,
            )
        )

    avg_lines: list[str] = []
    if rows:
        for label in labels:
            mb_vals: list[float] = []
            for r in rows:
                v = r.get(label)
                if v is not None and isinstance(v, (int, float)):
                    mb_vals.append(float(v) / KB_PER_MB)
            if mb_vals:
                m = sum(mb_vals) / len(mb_vals)
                short = label if label != TOTAL_PSS_LABEL else "Total PSS"
                avg_lines.append(f"{short}: {m:.2f} MB avg")
        if show_cpu:
            m_cpu = _session_numeric_mean(rows, "cpu_percent")
            m_idle = _session_numeric_mean(rows, "cpu_idle_percent")
            if m_cpu is not None:
                avg_lines.append(f"Proc CPU: {m_cpu:.2f}% avg")
            if m_idle is not None:
                avg_lines.append(f"System idle: {m_idle:.2f}% avg")
    avg_summary_inner = "<br>".join(avg_lines) if avg_lines else ""

    # Room for rotated activity strings; extra room for summary block under both subplots.
    activity_band = 110 + min(label_count, max_activity_labels) * 5
    summary_band = (56 + len(avg_lines) * 16) if avg_lines else 0
    bottom_margin = max(200, activity_band + summary_band + 40)

    title = (
        "Memory + CPU monitor (shared activity markers)"
        if show_cpu
        else "dumpsys meminfo — PSS (MB) vs time (shared activity markers)"
    )

    # Summary: lowest strip in the bottom margin (below activity text).
    summary_block: list[dict] = []
    if avg_summary_inner:
        summary_block.append(
            dict(
                text=(
                    "<b>Session summary (averages)</b><br>"
                    + avg_summary_inner
                ),
                xref="paper",
                yref="paper",
                x=0.5,
                y=-0.20,
                xanchor="center",
                yanchor="top",
                align="center",
                showarrow=False,
                borderpad=10,
                bordercolor="#94a3b8",
                borderwidth=1,
                bgcolor="rgba(248,250,252,0.97)",
                font=dict(size=10, color="#0f172a"),
            )
        )

    all_annotations = annotations + summary_block
    top_margin = 130 if show_cpu else 120

    fig.update_layout(
        title=dict(text=title, x=0.5, xanchor="center", pad=dict(t=8)),
        legend=dict(orientation="h", yanchor="bottom", y=1.06, xanchor="center", x=0.5),
        margin=dict(t=top_margin, b=bottom_margin, l=72, r=90),
        hovermode="x unified",
        template="plotly_white",
        annotations=all_annotations,
    )
    fig.update_xaxes(title_text="Time since start (s)", showgrid=True, gridcolor="rgba(0,0,0,0.1)")
    fig.update_yaxes(showgrid=True, gridcolor="rgba(0,0,0,0.1)")

    cpu_row_y_max = float(CPU_Y_AXIS_MAX)
    if show_cpu and target_row_cpu is not None:
        sampled: list[float] = []
        for r in rows:
            for key in ("cpu_percent", "cpu_idle_percent"):
                v = _row_float(r, key)
                if v is not None:
                    sampled.append(v)
        if sampled:
            cpu_row_y_max = max(float(CPU_Y_AXIS_MAX), max(sampled) * 1.08)

    if show_cpu and target_row_cpu is not None:
        fig.update_yaxes(title_text="PSS (MB)", row=1, col=1)
        fig.update_yaxes(
            title_text="Proc CPU % (top, multi-core sum)",
            range=[CPU_Y_AXIS_MIN, cpu_row_y_max],
            row=2,
            col=1,
            secondary_y=False,
        )
        fig.update_yaxes(
            title_text="Idle % (top, multi-core sum)",
            range=[IDLE_Y_AXIS_MIN, cpu_row_y_max],
            row=2,
            col=1,
            secondary_y=True,
            showline=True,
            linecolor="#047857",
            linewidth=2,
            mirror=False,
            showgrid=False,
        )
    else:
        fig.update_yaxes(title_text="PSS (MB)", row=1, col=1)

    plot_path.parent.mkdir(parents=True, exist_ok=True)
    suffix = plot_path.suffix.lower()

    if suffix in (".html", ".htm"):
        fig.write_html(
            str(plot_path),
            include_plotlyjs="cdn",
            config=dict(displayModeBar=True, scrollZoom=True),
        )
        print(f"Wrote interactive Plotly chart: {plot_path}")
        return

    if suffix in (".png", ".jpg", ".jpeg", ".webp", ".pdf", ".svg"):
        try:
            fig.write_image(str(plot_path), width=1200, height=700, scale=2)
        except Exception as e:
            print(
                f"Could not write {plot_path} ({e}). "
                "Install kaleido: pip install kaleido\n"
                "Or use --plot out.html.",
                file=sys.stderr,
            )
            return
        print(f"Wrote static image: {plot_path}")
        return

    html_path = plot_path.with_suffix(".html")
    fig.write_html(
        str(html_path),
        include_plotlyjs="cdn",
        config=dict(displayModeBar=True, scrollZoom=True),
    )
    print(f"Wrote interactive Plotly chart: {html_path}")


def main() -> None:
    ap = argparse.ArgumentParser(
        description=(
            "ADB: sample meminfo PSS + CPU; CSV + two-panel HTML (memory + CPU). "
            "Default: every 5s until Ctrl+C."
        ),
        prog="MemCPUMonitor.py",
    )
    ap.add_argument(
        "-p",
        "--package",
        required=True,
        help="Package for dumpsys meminfo (and pidof for CPU unless --cpu-package)",
    )
    ap.add_argument(
        "--plot",
        type=Path,
        default=Path("mem_cpu_monitor.html"),
        help="Chart path (.html recommended; .png needs kaleido)",
    )
    ap.add_argument("--no-plot", action="store_true", help="CSV only")
    ap.add_argument(
        "-o",
        "--csv",
        type=Path,
        default=None,
        help="CSV output (default: same base name as --plot, or mem_cpu_monitor.csv with --no-plot)",
    )
    ap.add_argument(
        "-i",
        "--interval",
        type=float,
        default=5.0,
        help="Seconds between samples (default 5)",
    )
    ap.add_argument(
        "-d",
        "--duration",
        type=float,
        default=None,
        help="Stop after N seconds (default: run until Ctrl+C)",
    )
    cpu_grp = ap.add_mutually_exclusive_group()
    cpu_grp.add_argument(
        "--cpu",
        dest="track_cpu",
        action="store_true",
        help="Include CPU plot (default)",
    )
    cpu_grp.add_argument(
        "--no-cpu",
        dest="track_cpu",
        action="store_false",
        help="Memory chart only",
    )
    ap.set_defaults(track_cpu=True)
    ap.add_argument(
        "--cpu-package",
        default=None,
        help="Package for CPU pidof (default: same as -p)",
    )
    ap.add_argument(
        "--stagger",
        type=float,
        default=DEFAULT_SAMPLE_STAGGER_SEC,
        metavar="SEC",
        help=(
            f"Seconds to sleep between meminfo, activity dumpsys, and CPU sampling "
            f"(default {DEFAULT_SAMPLE_STAGGER_SEC}; 0 to disable pauses)"
        ),
    )
    args = ap.parse_args()

    try:
        subprocess.run(["adb", "version"], capture_output=True, text=True, timeout=5, check=True)
    except Exception as e:
        print("ERROR: adb not available:", e, file=sys.stderr)
        sys.exit(1)

    plot_path = None if args.no_plot else args.plot
    if args.csv is not None:
        csv_path = args.csv
    elif plot_path is not None:
        csv_path = plot_path.with_suffix(".csv")
    else:
        csv_path = Path("mem_cpu_monitor.csv")

    print(f"Package (meminfo): {args.package}")
    if args.track_cpu:
        print(f"Package (CPU): {args.cpu_package or args.package}")
    else:
        print("CPU: off")
    print(
        f"Interval: {args.interval}s  Stagger: {args.stagger}s  "
        f"Duration: {args.duration or 'unlimited (Ctrl+C)'}"
    )
    print(f"CSV: {csv_path.resolve()}")
    if plot_path:
        print(f"Plot: {plot_path.resolve()}")
    print()

    run_loop(
        package=args.package,
        interval_sec=args.interval,
        duration_sec=args.duration,
        csv_path=csv_path,
        plot_path=plot_path,
        track_cpu=args.track_cpu,
        cpu_package=args.cpu_package,
        stagger_sec=max(0.0, args.stagger),
    )


if __name__ == "__main__":
    main()
