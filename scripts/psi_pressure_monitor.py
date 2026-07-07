#!/usr/bin/env python3
"""
Poll Android /proc/pressure (PSI) *some avg10* only (cpu, memory, io) via adb.
Logcat is captured in the background; after you stop, we parse the log file for
Choreographer "Skipped N frames" and ActivityManager / WindowManager "Displayed …
+Nms" lines for time-to-initial-display (TTID) and on-chart activity markers (real
activities only).

Time alignment: PSI uses host monotonic time with t=0 at the same session as
`adb shell date +%s` (e0). Log lines use that e0 plus the device timezone from
`getprop persist.sys.timezone` so log x-values share the same origin. Note: kernel
PSI avg10 is a ~10s rolling window, so it will not line up sharply with instant
log events; also system pressure and one app’s skipped frames are only loosely related.

PSI (kernel): only the *some* line, avg10 field, for cpu / memory / io.

Usage:
  pip install plotly
  python3 psi_pressure_monitor.py report.html

Writes report.csv, report.logcat.txt, and the HTML. Samples every 1s until Ctrl+C.
"""

from __future__ import annotations

import argparse
import csv
import math
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import IO, Any
from zoneinfo import ZoneInfo

PSI_PATHS = ("cpu", "memory", "io")

# --- Logcat patterns (see Scripts/SystemPulseV4.py) ---
# Duration is +123ms or +1s51ms / +2s483ms (ActivityManager formats long TTID with "s").
_DISPLAYED_DUR = r"\+(?:(?P<ds>\d+)s)?(?P<dm>\d+)ms"
DISPLAYED_RE_DUMPSTATE = re.compile(
    r"^(?P<time>\d{4}-\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+)\s+"
    r"(?P<pid>\d+)-(?P<tid>\d+)\s+"
    r"ActivityTaskManager\s+system_server\s+"
    r"(?P<level>[VDIWEF])\s+Displayed\s+"
    r"(?P<activity>[^\s:]+):\s*"
    + _DISPLAYED_DUR
    + r"\.?\s*$",
    re.IGNORECASE,
)
# Log line time (threadtime / time): allow multiple spaces after MM-DD (adb varies).
LOG_TIME_MM = r"(?P<time>\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)"
# Most devices log "ActivityManager" (not only ActivityTaskManager).
_DISPLAYED_CORE = (
    r"(?:ActivityManager|ActivityTaskManager).*:?\s*Displayed\s+"
    r"(?P<activity>[^\s]+):\s*"
    + _DISPLAYED_DUR
)
DISPLAYED_RE_LOGCAT = re.compile(
    LOG_TIME_MM + r".*?" + _DISPLAYED_CORE,
    re.IGNORECASE | re.DOTALL,
)
DISPLAYED_RE_FULLDATE = re.compile(
    r"(?P<time>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+).*?" + _DISPLAYED_CORE,
    re.IGNORECASE | re.DOTALL,
)
# No ActivityManager in between (OEM) — still "Displayed pkg/.Act: +Nms" or +Nsms
DISPLAYED_RE_FALLBACK = re.compile(
    LOG_TIME_MM + r".*?Displayed\s+(?P<activity>[^\s]+):\s*" + _DISPLAYED_DUR,
    re.IGNORECASE | re.DOTALL,
)
SKIPPED_FRAMES_RE = re.compile(
    LOG_TIME_MM
    + r".*?Skipped\s+(?P<frames>\d+)\s+frames!"
)
_LINE_RE = re.compile(
    r"^(some|full)\s+"
    r"avg10=([0-9.]+)\s+avg60=([0-9.]+)\s+avg300=([0-9.]+)\s+total=([0-9]+)\s*$"
)


def run_cmd(
    args: list[str], timeout: float = 30.0, check: bool = True
) -> subprocess.CompletedProcess:
    return subprocess.run(
        args, capture_output=True, text=True, timeout=timeout, check=check
    )


def adb_base(serial: str | None) -> list[str]:
    base = ["adb"]
    if serial:
        base += ["-s", serial]
    return base


def adb_shell(
    args: list[str], serial: str | None, timeout: float = 30.0
) -> str:
    cmd = adb_base(serial) + ["shell", *args]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError(
            f"adb shell failed: {' '.join(cmd)}\nstderr: {p.stderr}"
        )
    return p.stdout


def device_zone_for_logs(serial: str | None) -> ZoneInfo:
    """Android logcat timestamps are wall clock on the device; use persist.sys.timezone."""
    try:
        name = adb_shell(["getprop", "persist.sys.timezone"], serial, 5.0).strip()
        if name and name not in ("0", ""):
            return ZoneInfo(name)
    except Exception:
        pass
    return ZoneInfo("UTC")


def ensure_adb() -> None:
    try:
        run_cmd(["adb", "version"], timeout=5, check=True)
    except Exception as e:
        print("ERROR: adb not found or not working:", e, file=sys.stderr)
        sys.exit(1)


def adb_connect(host_port: str) -> None:
    p = run_cmd(["adb", "connect", host_port], timeout=20, check=False)
    print(p.stdout or "", end="")
    if p.returncode != 0:
        print(p.stderr, file=sys.stderr, end="")
        sys.exit(1)


def parse_psi_file(content: str) -> dict[str, dict[str, float]]:
    """Parse 'some' / 'full' lines -> { name -> {avg10, avg60, avg300, total} }."""
    result: dict[str, dict[str, float]] = {}
    for line in content.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = _LINE_RE.match(line)
        if not m:
            continue
        result[m.group(1)] = {
            "avg10": float(m.group(2)),
            "avg60": float(m.group(3)),
            "avg300": float(m.group(4)),
            "total": float(m.group(5)),
        }
    return result


def read_all_psi(serial: str | None) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for name in PSI_PATHS:
        path = f"/proc/pressure/{name}"
        try:
            raw = adb_shell(["cat", path], serial, timeout=15.0)
        except Exception as e:
            out[name] = {"_error": str(e)}
            continue
        parsed = parse_psi_file(raw)
        if not parsed:
            out[name] = {"_error": "no PSI lines parsed", "_raw": raw[:500]}
        else:
            out[name] = parsed
    return out


def _parse_displayed_time_for_log(time_str: str) -> datetime:
    """Display / dumpstate / logcat time formats for Activity 'Displayed' lines."""
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


def match_displayed_line(line: str) -> re.Match[str] | None:
    for cre in (
        DISPLAYED_RE_DUMPSTATE,
        DISPLAYED_RE_FULLDATE,
        DISPLAYED_RE_LOGCAT,
        DISPLAYED_RE_FALLBACK,
    ):
        m = cre.search(line)
        if m:
            return m
    return None


def duration_ms_from_displayed_match(m: re.Match[str]) -> int | None:
    """Convert +507ms, +1s51ms, +2s483ms, etc. to total milliseconds."""
    try:
        ds = m.group("ds")
        dm = m.group("dm")
    except IndexError:
        return None
    if not dm:
        return None
    sec = int(ds) if ds else 0
    return sec * 1000 + int(dm)


def log_timestamp_to_elapsed_s(
    time_str: str, e0: int, tz: ZoneInfo
) -> float | None:
    """
    Seconds since `e0` (Unix time on device) for this log line's wall time.

    Log lines use the device local calendar (MM-DD …). `e0` is taken the same
    session as PSI; we interpret both in `tz` (persist.sys.timezone) so x-axis
    matches host monotonic PSI when combined with t_mono_anchor.
    """
    try:
        t_log = _parse_displayed_time_for_log(time_str)
    except ValueError:
        return None
    t0 = datetime.fromtimestamp(e0, tz=tz)
    if t_log.year <= 1901:
        try:
            line_dt = t0.replace(
                month=t_log.month,
                day=t_log.day,
                hour=t_log.hour,
                minute=t_log.minute,
                second=t_log.second,
                microsecond=t_log.microsecond,
            )
        except (ValueError, OSError):
            return None
    else:
        if t_log.tzinfo is None:
            line_dt = t_log.replace(tzinfo=tz)
        else:
            line_dt = t_log
    d = (line_dt - t0).total_seconds()
    if d < -6 * 3600:
        try:
            line_dt2 = line_dt.replace(year=line_dt.year + 1)
            d2 = (line_dt2 - t0).total_seconds()
            if abs(d2) < abs(d):
                d = d2
        except (ValueError, OSError):
            pass
    if d < -6 * 3600 or d > 7 * 24 * 3600:
        return None
    return d


def _displayed_to_elapsed(
    m: re.Match[str], e0: int, tz: ZoneInfo
) -> tuple[float, str, int] | None:
    ts = m.group("time")
    el = log_timestamp_to_elapsed_s(ts, e0, tz)
    if el is None:
        return None
    act = m.group("activity")
    dur = duration_ms_from_displayed_match(m)
    if dur is None:
        return None
    return (el, act, dur)


def activity_short_name(raw: str) -> str:
    """Component short name: com.foo/.Main -> Main, com.pkg.Act -> Act (inner class stipped)."""
    raw = raw.strip()
    if not raw or raw.startswith("startActivity"):
        return raw
    if "/" in raw:
        part = raw.split("/")[-1]
    else:
        part = raw
    if "." in part:
        part = part.rsplit(".", 1)[-1]
    return part.split("$", 1)[0]


@dataclass
class LogcatEvents:
    """Parsed from post-run log file (aligned to session by device epoch e0)."""

    # Real "Displayed ... +Nms" only: (elapsed, short, dur_ms, full_component)
    ttid: list[tuple[float, str, int, str]]
    # Same events for the skipped-frames row (diamonds + vlines; not OOM or other log lines)
    displayed: list[tuple[float, str, int, str]]
    skipped: list[tuple[float, int]]  # elapsed_s, frames


def parse_logcat_file(path: Path, e0: int, tz: ZoneInfo) -> LogcatEvents:
    ttid: list[tuple[float, str, int, str]] = []
    displayed: list[tuple[float, str, int, str]] = []
    skipped: list[tuple[float, int]] = []
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as e:
        print(f"Warning: could not read {path}: {e}", file=sys.stderr)
        return LogcatEvents(ttid=ttid, displayed=displayed, skipped=skipped)

    for line in text.splitlines():
        if not line.strip():
            continue
        m = match_displayed_line(line)
        if m:
            got = _displayed_to_elapsed(m, e0, tz)
            if got is not None:
                el, act, dur = got
                short = activity_short_name(act)
                ttid.append((el, short, dur, act))
                displayed.append((el, short, dur, act))
            continue
        m2 = SKIPPED_FRAMES_RE.search(line)
        if m2:
            el = log_timestamp_to_elapsed_s(m2.group("time"), e0, tz)
            if el is not None:
                skipped.append((el, int(m2.group("frames"))))
            continue
    ttid.sort(key=lambda x: x[0])
    displayed.sort(key=lambda x: x[0])
    skipped.sort(key=lambda x: x[0])
    return LogcatEvents(ttid=ttid, displayed=displayed, skipped=skipped)


def start_logcat_to_file(
    serial: str | None, log_path: Path
) -> tuple[int, float, subprocess.Popen[Any], IO[str]]:
    run_cmd(adb_base(serial) + ["logcat", "-c"], check=False, timeout=15.0)
    raw = adb_shell(["date", "+%s"], serial, timeout=10.0).strip()
    e0 = int(float(raw)) if raw else int(time.time())
    # Host monotonic origin for PSI: same instant as e0 (no gap before sampling).
    t_mono_anchor = time.monotonic()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    logf = open(log_path, "w", encoding="utf-8", errors="replace")
    # *:S silences by default, then whitelisted tags. ActivityManager:* and
    # WindowManager / ActivityTaskManager: "Displayed … +Nms" (TTID) can appear
    # on any of them depending on build.
    cmd = adb_base(serial) + [
        "logcat",
        "-v",
        "threadtime",
        "ActivityManager:*",
        "Choreographer:I",
        "WindowManager:I",
        "ActivityTaskManager:I",
        "*:S",
    ]
    try:
        proc = subprocess.Popen(
            cmd,
            stdout=logf,
            stderr=subprocess.DEVNULL,
        )
    except OSError as e:
        logf.close()
        raise RuntimeError(f"Failed to start adb logcat: {e}") from e
    return (e0, t_mono_anchor, proc, logf)


def stop_logcat(proc: subprocess.Popen[Any] | None, logf: IO[str] | None) -> None:
    if logf is not None:
        try:
            logf.flush()
        except OSError:
            pass
    if proc is not None and proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=4.0)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=2.0)
    if logf is not None:
        try:
            logf.close()
        except OSError:
            pass


def flatten_row(
    elapsed: float,
    ts_iso: str,
    sample: dict[str, Any],
    *,
    csv_fieldnames: list[str] | None = None,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "timestamp_iso": ts_iso,
        "elapsed_s": f"{elapsed:.3f}",
    }
    for res in PSI_PATHS:
        block = sample.get(res)
        if block is None:
            continue
        if isinstance(block, dict) and "_error" in block:
            row[f"{res}__error"] = str(block["_error"])
            continue
        if not isinstance(block, dict):
            continue
        if "some" in block and "avg10" in block["some"]:
            row[f"{res}__some__avg10"] = f"{block['some']['avg10']:.6f}"
    if csv_fieldnames:
        for k in csv_fieldnames:
            if k not in row:
                row[k] = ""
    return row


def fieldnames_for_csv() -> list[str]:
    base: list[str] = ["timestamp_iso", "elapsed_s"]
    for res in PSI_PATHS:
        base.append(f"{res}__some__avg10")
    for res in PSI_PATHS:
        base.append(f"{res}__error")
    return base


@dataclass
class SeriesBuffer:
    """PSI *some* avg10 only, per resource."""

    elapsed: list[float] = field(default_factory=list)
    by_res: dict[str, list[float]] = field(default_factory=dict)

    def append(self, elapsed: float, sample: dict[str, Any]) -> None:
        self.elapsed.append(elapsed)
        for res in PSI_PATHS:
            block = sample.get(res)
            val = math.nan
            if isinstance(block, dict) and "_error" not in block:
                s = block.get("some")
                if isinstance(s, dict) and "avg10" in s:
                    val = float(s["avg10"])
            self.by_res.setdefault(res, []).append(val)


# Distinct colors for cpu / memory / io (some avg10 only)
_COLOR_CYCLE = [
    "#2563EB",
    "#1D4ED8",
    "#7C3AED",
    "#5B21B6",
    "#059669",
    "#047857",
    "#0D9488",
    "#DC2626",
    "#B91C1C",
    "#EA580C",
    "#D97706",
    "#CA8A04",
    "#A16207",
    "#7C2D12",
    "#BE123C",
]


def plot_plotly(
    buf: SeriesBuffer,
    out_path: Path,
    events: LogcatEvents,
) -> None:
    try:
        import plotly.graph_objects as go
        from plotly.subplots import make_subplots
    except ImportError:
        print(
            "plotly not installed; skipping plot. pip install plotly",
            file=sys.stderr,
        )
        return

    fig = make_subplots(
        rows=3,
        cols=1,
        shared_xaxes=True,
        vertical_spacing=0.08,
        row_heights=[0.36, 0.34, 0.30],
        subplot_titles=(
            "PSI some avg10 (cpu / memory / io)",
            "Skipped frames + activity (Displayed only)",
            "Time to initial display (TTID) from Displayed +Nms",
        ),
    )
    ci = 0
    for res in PSI_PATHS:
        ys = buf.by_res.get(res) or []
        if not ys:
            continue
        color = _COLOR_CYCLE[ci % len(_COLOR_CYCLE)]
        ci += 1
        fig.add_trace(
            go.Scatter(
                x=buf.elapsed,
                y=ys,
                mode="lines",
                name=f"{res} some avg10",
                line=dict(width=2, color=color),
                hovertemplate=(
                    "<b>%{fullData.name}</b><br>"
                    "t=%{x:.1f} s<br>PSI=%{y:.2f}%%<extra></extra>"
                ),
            ),
            row=1,
            col=1,
        )

    t_max = max((t[2] for t in events.ttid), default=600.0)
    t_y_hi = max(800.0, t_max * 1.1, 550.0)

    sk_max = max((f for _, f in events.skipped), default=0)
    y_sk_hi = max(120.0, sk_max * 1.1, 1.0)
    y_sk_range: list[float] = [0, y_sk_hi]
    y_act_line = y_sk_hi * 0.88

    if events.skipped:
        xs, ys = zip(*events.skipped)
        fig.add_trace(
            go.Scatter(
                x=list(xs),
                y=list(ys),
                mode="markers",
                name="Skipped frames",
                marker=dict(
                    symbol="circle",
                    size=9,
                    color="darkorange",
                    line=dict(width=1, color="orangered"),
                ),
                hovertemplate="Skipped %{y} frames<br>t=%{x:.1f} s<extra></extra>",
            ),
            row=2,
            col=1,
        )

    if events.displayed:
        dmax = 80
        dlist = events.displayed[:dmax]
        dx = [t for t, _, _, _ in dlist]
        dtext: list[str] = []
        hov: list[str] = []
        for _, short, dur, full in dlist:
            a = short if len(short) <= 40 else short[:37] + "…"
            dtext.append(f"{a} +{dur}ms")
            hov.append(full)
        dy = [y_act_line] * len(dx)
        fig.add_trace(
            go.Scatter(
                x=dx,
                y=dy,
                mode="markers",
                name="Activity markers",
                cliponaxis=False,
                marker=dict(
                    symbol="diamond",
                    size=12,
                    color="#10b981",
                    line=dict(width=1.5, color="#047857"),
                ),
                customdata=hov,
                hovertemplate=(
                    "<b>%{customdata}</b><br>"
                    "t=%{x:.1f} s<extra></extra>"
                ),
            ),
            row=2,
            col=1,
        )
        for x, label in zip(dx, dtext, strict=True):
            fig.add_annotation(
                x=x,
                y=y_act_line,
                text=label,
                showarrow=False,
                textangle=-90,
                xanchor="center",
                yanchor="bottom",
                yshift=10,
                font=dict(size=7, color="#064e3b", family="sans-serif"),
                row=2,
                col=1,
            )
        for x, _, _, _ in dlist:
            for r in (1, 2, 3):
                fig.add_vline(
                    x=x,
                    line_width=1.5,
                    line_dash="dot",
                    line_color="rgba(5, 150, 105, 0.45)",
                    row=r,
                    col=1,
                )

    if events.ttid:
        cols = list(zip(*events.ttid))
        t_el = list(cols[0])
        t_short = list(cols[1])
        t_dur = list(cols[2])
        t_full = list(cols[3])
        colors = ["red" if d > 500 else "steelblue" for d in t_dur]
        cd = list(zip(t_full, t_short, t_dur))
        fig.add_trace(
            go.Scatter(
                x=t_el,
                y=t_dur,
                mode="markers",
                name="TTID (ms)",
                marker=dict(
                    size=9,
                    color=colors,
                    line=dict(width=0.5, color="rgba(0,0,0,0.35)"),
                ),
                customdata=cd,
                hovertemplate=(
                    "<b>%{customdata[0]}</b><br>"
                    "TTID: %{customdata[2]} ms<br>"
                    "t=%{x:.1f} s<extra></extra>"
                ),
            ),
            row=3,
            col=1,
        )
        fig.add_hline(
            y=500,
            line_dash="dash",
            line_color="red",
            line_width=1.5,
            row=3,
            col=1,
        )
    else:
        fig.add_annotation(
            x=0.5,
            y=0.5,
            xref="x3 domain",
            yref="y3 domain",
            text="No Displayed (TTID) lines in this log",
            showarrow=False,
            font=dict(size=12, color="gray"),
        )

    fig.update_layout(
        title=dict(
            text="PSI + logcat (skipped/activity, then TTID)",
            x=0.5,
            xanchor="center",
        ),
        legend=dict(
            orientation="h",
            yanchor="top",
            y=-0.1,
            x=0.5,
            xanchor="center",
            font=dict(size=9),
        ),
        margin=dict(t=130, b=200, l=60, r=50),
        hovermode="x unified",
        template="plotly_white",
        height=1080,
    )
    fig.update_xaxes(
        title_text="Session time (s)",
        showgrid=True,
        gridcolor="rgba(0,0,0,0.08)",
        row=3,
        col=1,
    )
    fig.update_yaxes(
        title_text="PSI some avg10 %",
        range=[0, 100],
        tickformat=".2f",
        showgrid=True,
        gridcolor="rgba(0,0,0,0.08)",
        row=1,
        col=1,
    )
    fig.update_yaxes(
        title_text="Skipped frames and activity",
        range=y_sk_range,
        showgrid=True,
        gridcolor="rgba(0,0,0,0.08)",
        row=2,
        col=1,
    )
    fig.update_yaxes(
        title_text="Time to initial display (ms)",
        range=[0, t_y_hi],
        showgrid=True,
        gridcolor="rgba(0,0,0,0.08)",
        row=3,
        col=1,
    )

    out_path = out_path.resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    if not out_path.suffix:
        out_path = out_path.with_suffix(".html")
    if out_path.suffix.lower() in (".html", ".htm"):
        fig.write_html(
            str(out_path),
            include_plotlyjs="cdn",
            config=dict(displayModeBar=True, scrollZoom=True),
        )
        print(f"Wrote interactive chart: {out_path}")
        return
    try:
        fig.write_image(str(out_path), width=1200, height=1100, scale=2)
        print(f"Wrote static image: {out_path}")
    except Exception as e:
        print(
            f"Static export failed ({e}). Use .html or: pip install kaleido",
            file=sys.stderr,
        )


def run_sample_loop(
    serial: str | None,
    interval_sec: float,
    duration_sec: float | None,
    csv_path: Path,
    plot_path: Path | None,
    do_plot: bool,
    e0: int,
    t_mono_anchor: float,
    log_path: Path,
    logcat_proc: subprocess.Popen[Any] | None,
    logf: IO[str] | None,
    device_tz: ZoneInfo,
) -> None:
    # Same t=0 for PSI as for e0 (wall) + t_mono (host); do not reset here.
    start = t_mono_anchor
    buf = SeriesBuffer()
    fn = fieldnames_for_csv()
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with csv_path.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fn, extrasaction="ignore")
            w.writeheader()
            try:
                while True:
                    elapsed = time.monotonic() - start
                    if duration_sec is not None and elapsed >= duration_sec:
                        break
                    ts = datetime.now(timezone.utc).astimezone()
                    ts_iso = ts.isoformat(timespec="milliseconds")
                    sample = read_all_psi(serial)
                    buf.append(elapsed, sample)
                    row = flatten_row(
                        elapsed,
                        ts_iso,
                        sample,
                        csv_fieldnames=fn,
                    )
                    w.writerow(row)
                    f.flush()
                    parts = []
                    for res in PSI_PATHS:
                        b = sample.get(res)
                        if isinstance(b, dict) and "_error" in b:
                            parts.append(f"{res}=ERR")
                        elif (
                            isinstance(b, dict)
                            and "some" in b
                            and "avg10" in b["some"]
                        ):
                            parts.append(
                                f"{res}_some_avg10={b['some']['avg10']:.2f}"
                            )
                    print(
                        f"elapsed={elapsed:7.1f}s  " + "  ".join(parts),
                        flush=True,
                    )
                    if (
                        duration_sec is not None
                        and (time.monotonic() - start) + interval_sec
                        >= duration_sec
                    ):
                        if duration_sec - (time.monotonic() - start) <= 0:
                            break
                    time.sleep(interval_sec)
            except KeyboardInterrupt:
                print("\nStopped by user (Ctrl+C).", file=sys.stderr)
    finally:
        stop_logcat(logcat_proc, logf)

    print(f"Parsing logcat: {log_path.resolve()}", flush=True)
    log_events = parse_logcat_file(log_path, e0, device_tz)
    if buf.elapsed:
        emax = max(buf.elapsed)
        if log_events.skipped:
            smax = max(t for t, _ in log_events.skipped)
            smin = min(t for t, _ in log_events.skipped)
        else:
            smax = smin = 0.0
        if log_events.displayed:
            dmax = max(t for t, _, _, _ in log_events.displayed)
        else:
            dmax = 0.0
        tmax = max((t[0] for t in log_events.ttid), default=0.0)
        print(
            f"  Time range check — PSI: 0…{emax:.1f}s  "
            f"log (skipped): {smin:.1f}…{smax:.1f}s  "
            f"markers: …{dmax:.1f}s  TTID: …{tmax:.1f}s  (if log ≫ PSI, check device TZ)",
            flush=True,
        )
        print(
            f"  TTID (Displayed): {len(log_events.ttid)}  "
            f"Activity markers: {len(log_events.displayed)}  "
            f"Skipped frames: {len(log_events.skipped)}",
            flush=True,
        )
    if not log_events.displayed:
        print(
            "  Tip: No activity markers or TTID — the log has no 'Displayed … +Nms' "
            "lines (Choreographer skipped frames may still be present).",
            flush=True,
        )
    if do_plot and plot_path and buf.elapsed:
        plot_plotly(buf, plot_path, log_events)
    elif do_plot and plot_path:
        print("No samples; skip plot.")


def default_serial_for_one_device() -> str | None:
    try:
        out = run_cmd(["adb", "devices"], timeout=10, check=True).stdout
    except Exception:
        return None
    serials: list[str] = []
    for line in out.splitlines()[1:]:
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    if len(serials) == 1:
        return serials[0]
    return None


def _list_adb_devices() -> list[str]:
    out = run_cmd(["adb", "devices"], timeout=10, check=True).stdout
    devs: list[str] = []
    for line in out.splitlines()[1:]:
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devs.append(parts[0])
    return devs


def resolve_adb_serial() -> str | None:
    """Pick adb target: single device, or ANDROID_SERIAL if several are connected."""
    maybe = default_serial_for_one_device()
    if maybe is not None:
        print(f"Device: {maybe}")
        return maybe
    try:
        devs = _list_adb_devices()
    except Exception as e:
        print("adb devices failed:", e, file=sys.stderr)
        sys.exit(1)
    if not devs:
        print(
            "No device in 'adb devices' (state must be 'device'). "
            "Connect USB, authorize, or run `adb connect`.",
            file=sys.stderr,
        )
        sys.exit(1)
    if len(devs) > 1:
        want = os.environ.get("ANDROID_SERIAL", "").strip()
        if want and want in devs:
            print(f"Device (ANDROID_SERIAL): {want}")
            return want
        print(
            "Several devices are connected. Set environment ANDROID_SERIAL to one of:\n  "
            + "\n  ".join(devs),
            file=sys.stderr,
        )
        sys.exit(1)
    print(f"Device: {devs[0]}")
    return devs[0]


def main() -> None:
    ap = argparse.ArgumentParser(
        description=(
            "Sample Android PSI (some avg10) + logcat; HTML combines PSI, "
            "Choreographer skipped frames, and ActivityManager Displayed lines."
        ),
    )
    ap.add_argument(
        "html_out",
        type=Path,
        metavar="OUTPUT.html",
        help="Output Plotly HTML file (CSV is the same path with a .csv suffix)",
    )
    args = ap.parse_args()
    ensure_adb()
    serial = resolve_adb_serial()

    plot_path = args.html_out
    if not plot_path.suffix:
        plot_path = plot_path.with_suffix(".html")
    elif plot_path.suffix.lower() not in (".html", ".htm"):
        plot_path = plot_path.with_suffix(".html")
    csv_path = plot_path.with_suffix(".csv")
    log_path = plot_path.with_suffix(".logcat.txt")

    print(f"HTML:   {plot_path.resolve()}")
    print(f"CSV:    {csv_path.resolve()}")
    print(f"Logcat: {log_path.resolve()}")
    print("Sampling every 1s until Ctrl+C…\n", flush=True)

    # Smoke test: read once
    try:
        t = read_all_psi(serial)
        for res in PSI_PATHS:
            b = t.get(res)
            if isinstance(b, dict) and "_error" in b:
                print(
                    f"Warning: {res}: {b.get('_error')!s}",
                    file=sys.stderr,
                )
    except Exception as e:
        print(f"Cannot read PSI from device: {e}", file=sys.stderr)
        print(
            "Kernel may lack CONFIG_PSI or /proc/pressure (Android 9+ on many devices).",
            file=sys.stderr,
        )
        sys.exit(1)

    try:
        e0, t_mono_anchor, logcat_proc, logf = start_logcat_to_file(
            serial, log_path
        )
    except Exception as e:
        print(f"ERROR: could not start logcat: {e}", file=sys.stderr)
        sys.exit(1)

    device_tz = device_zone_for_logs(serial)
    print(
        f"Log time zone (device): {device_tz.key} — "
        f"PSI t=0 matches e0 and monotonic anchor together.\n",
        flush=True,
    )

    run_sample_loop(
        serial=serial,
        interval_sec=1.0,
        duration_sec=None,
        csv_path=csv_path,
        plot_path=plot_path,
        do_plot=True,
        e0=e0,
        t_mono_anchor=t_mono_anchor,
        log_path=log_path,
        logcat_proc=logcat_proc,
        logf=logf,
        device_tz=device_tz,
    )


if __name__ == "__main__":
    main()
