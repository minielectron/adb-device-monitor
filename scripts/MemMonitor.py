#!/usr/bin/env python3
"""
Periodically sample `adb shell dumpsys meminfo <package>` App Summary PSS (values in **KB**
per Android) and the top (resumed) activity, log to CSV (still **KB**), and plot time vs PSS
with activity-change markers. **Charts convert KB → MB** (divide by 1024) for readability.

Requirements:
  pip install plotly
  # Optional: pip install kaleido   (only if exporting --plot something.png)

Usage:
  python3 scripts/monitor_meminfo_pss.py --package com.telekom.onetv.tv.hr --interval 5 --duration 300
  python3 scripts/monitor_meminfo_pss.py -p com.telekom.onetv.tv.hr -i 10 -o ./mem_samples.csv --plot mem_pss.html

  # Static PNG (requires kaleido):
  python3 scripts/monitor_meminfo_pss.py -p com.telekom.onetv.tv.hr --plot mem_pss.png

  # Run until Ctrl+C (no --duration):
  python3 scripts/monitor_meminfo_pss.py -p com.telekom.onetv.tv.hr -i 5
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

# Total process PSS — parsed separately (format varies slightly by Android build).
TOTAL_PSS_LABEL = "Total PSS"

# CSV / plot column order: categories first, then total, then activity.
PSS_LABELS = CATEGORY_LABELS + [TOTAL_PSS_LABEL]

# Match: optional spaces, label, colon, first integer (PSS KB)
def _pss_pattern(label: str) -> re.Pattern:
    escaped = re.escape(label)
    return re.compile(rf"^\s*{escaped}:\s+(\d+)", re.MULTILINE)


PSS_REGEXES = {label: _pss_pattern(label) for label in CATEGORY_LABELS}

# dumpsys meminfo reports App Summary PSS in kilobytes; plots use mebibytes (KB / 1024).
KB_PER_MB = 1024.0


def adb_shell(args: list[str], timeout: float = 60.0) -> str:
    cmd = ["adb", "shell", *args]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError(f"adb failed: {' '.join(cmd)}\nstderr: {p.stderr}")
    return p.stdout


def parse_total_pss_kb(stdout: str) -> int | None:
    """
    Total PSS for the process (KB). Prefer the App Summary block; match common
    `dumpsys meminfo` line shapes (TOTAL: N, digits + TOTAL, etc.).
    """
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
    """Extract App Summary PSS (first number, KB) per category + Total PSS. Missing -> None."""
    out: dict[str, int | None] = {}
    for label in CATEGORY_LABELS:
        m = PSS_REGEXES[label].search(stdout)
        if m:
            out[label] = int(m.group(1))
        else:
            out[label] = None
    out[TOTAL_PSS_LABEL] = parse_total_pss_kb(stdout)
    return out


def get_resumed_activity() -> str:
    """
    Best-effort current user-visible activity (short string for CSV/plot).
    """
    try:
        out = adb_shell(["dumpsys", "activity", "activities"], timeout=30.0)
    except Exception as e:
        return f"<adb error: {e}>"

    # Prefer mResumedActivity (varies by Android version)
    for line in out.splitlines():
        line = line.strip()
        if "mResumedActivity" in line or "topResumedActivity" in line:
            # Shorten: take substring after last space chunk containing /
            if "/" in line:
                parts = line.split()
                for p in parts:
                    if "/" in p and not p.startswith("{"):
                        return p[:120]
            return line[:200]
    # Fallback: mCurrentFocus from window
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
) -> None:
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = ["timestamp_iso", "elapsed_s"] + PSS_LABELS + ["top_activity"]

    start = time.monotonic()
    last_activity: str | None = None
    rows: list[dict] = []

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

                try:
                    mem_out = adb_shell(["dumpsys", "meminfo", package], timeout=45.0)
                    pss = parse_meminfo_pss(mem_out)
                except Exception as e:
                    pss = {label: None for label in PSS_LABELS}
                    activity = f"<meminfo error: {e}>"
                else:
                    activity = get_resumed_activity()

                row = {
                    "timestamp_iso": ts_iso,
                    "elapsed_s": f"{elapsed:.1f}",
                    **{k: (pss[k] if pss[k] is not None else "") for k in PSS_LABELS},
                    "top_activity": activity,
                }
                w.writerow(row)
                f.flush()
                rows.append({**row, **{k: pss[k] for k in PSS_LABELS}})

                act_marker = ""
                if last_activity is not None and activity != last_activity:
                    act_marker = " [ACTIVITY CHANGED]"
                last_activity = activity

                print(
                    f"{ts_iso}  elapsed={elapsed:6.1f}s  "
                    f"Total={pss.get(TOTAL_PSS_LABEL)}  "
                    f"Java={pss.get('Java Heap')}  Native={pss.get('Native Heap')}  "
                    f"Gfx={pss.get('Graphics')}{act_marker}"
                )
                print(f"    activity: {activity[:100]}")

                if duration_sec is not None and (time.monotonic() - start) + interval_sec >= duration_sec:
                    # last sample if next sleep would exceed duration
                    remaining = duration_sec - (time.monotonic() - start)
                    if remaining <= 0:
                        break

                time.sleep(interval_sec)

        except KeyboardInterrupt:
            print("\nStopped by user (Ctrl+C).", file=sys.stderr)

    if plot_path and rows:
        _plot(rows, plot_path, PSS_LABELS)
        print("Note: CSV columns are PSS in KB (as reported by dumpsys); chart Y-axis is MB (KB/1024).")


def _plot(rows: list[dict], plot_path: Path, labels: list[str]) -> None:
    try:
        import plotly.graph_objects as go
    except ImportError:
        print(
            "plotly not installed; skipping plot. pip install plotly",
            file=sys.stderr,
        )
        return

    elapsed = [float(r["elapsed_s"]) for r in rows]
    activities = [str(r.get("top_activity", "")) for r in rows]

    fig = go.Figure()

    # Color palette (Plotly qualitative)
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
            )
        )

    # Vertical dashed lines when activity changes; labels sit *below* the plot (paper y < 0)
    shapes: list[dict] = []
    annotations: list[dict] = []
    prev_act: str | None = None
    max_activity_labels = 40
    label_count = 0

    for j, act in enumerate(activities):
        if prev_act is not None and act != prev_act:
            x = elapsed[j]
            shapes.append(
                dict(
                    type="line",
                    xref="x",
                    yref="paper",
                    x0=x,
                    y0=0,
                    x1=x,
                    y1=1,
                    line=dict(color="rgba(80,80,80,0.45)", width=1, dash="dash"),
                )
            )
            if label_count < max_activity_labels:
                short = act if len(act) <= 70 else act[:67] + "..."
                # Below x-axis, aligned with time (x); vertical text reduces overlap
                annotations.append(
                    dict(
                        x=x,
                        y=-0.06,
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
                label_count += 1
        prev_act = act

    # Extra bottom margin for activity strip + optional subtitle
    bottom_margin = max(160, 100 + min(label_count, max_activity_labels) * 3)

    title = "dumpsys meminfo — App Summary PSS (MB) vs time (KB ÷ 1024)"
    fig.update_layout(
        title=dict(text=title, x=0.5, xanchor="center", pad=dict(t=8)),
        xaxis_title="Time since start (s)",
        yaxis_title="PSS (MB)",
        legend=dict(orientation="h", yanchor="bottom", y=1.08, xanchor="center", x=0.5),
        margin=dict(t=110, b=bottom_margin, l=65, r=45),
        hovermode="x unified",
        template="plotly_white",
        shapes=shapes,
        annotations=annotations,
    )
    fig.update_xaxes(showgrid=True, gridcolor="rgba(0,0,0,0.1)")
    fig.update_yaxes(showgrid=True, gridcolor="rgba(0,0,0,0.1)")

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
                "Or use --plot out.html for interactive HTML without kaleido.",
                file=sys.stderr,
            )
            return
        print(f"Wrote static image: {plot_path}")
        return

    # Default: treat unknown extension as HTML
    html_path = plot_path.with_suffix(".html")
    fig.write_html(
        str(html_path),
        include_plotlyjs="cdn",
        config=dict(displayModeBar=True, scrollZoom=True),
    )
    print(f"Wrote interactive Plotly chart: {html_path}")


def main() -> None:
    ap = argparse.ArgumentParser(description="Sample meminfo PSS over adb and plot.")
    ap.add_argument("-p", "--package", default="com.telekom.onetv.tv.hr", help="Package name")
    ap.add_argument(
        "-i", "--interval", type=float, default=5.0, help="Seconds between samples (default 5)"
    )
    ap.add_argument(
        "-d",
        "--duration",
        type=float,
        default=None,
        help="Total seconds to run (omit = run until Ctrl+C)",
    )
    ap.add_argument(
        "-o",
        "--csv",
        type=Path,
        default=Path("meminfo_pss_samples.csv"),
        help="Output CSV path",
    )
    ap.add_argument(
        "--plot",
        type=Path,
        default=Path("meminfo_pss_plot.html"),
        help="Output path: .html = interactive Plotly (default); .png/.pdf needs kaleido (pip install kaleido)",
    )
    ap.add_argument("--no-plot", action="store_true", help="Do not generate chart file")
    args = ap.parse_args()

    # Quick adb check
    try:
        subprocess.run(["adb", "version"], capture_output=True, text=True, timeout=5, check=True)
    except Exception as e:
        print("ERROR: adb not available:", e, file=sys.stderr)
        sys.exit(1)

    plot_path = None if args.no_plot else args.plot

    print(f"Package: {args.package}")
    print(f"Interval: {args.interval}s  Duration: {args.duration or 'unlimited (Ctrl+C)'}")
    print(f"CSV: {args.csv.resolve()}")
    if plot_path:
        print(f"Plot: {plot_path.resolve()}")
    print()

    run_loop(
        package=args.package,
        interval_sec=args.interval,
        duration_sec=args.duration,
        csv_path=args.csv,
        plot_path=plot_path,
    )


if __name__ == "__main__":
    main()
