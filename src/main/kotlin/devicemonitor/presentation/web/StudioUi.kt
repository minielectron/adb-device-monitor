package devicemonitor.presentation.web

/** Shared visual system for the interactive Studio screens. */
internal val STUDIO_UI_CSS = """<style id="studio-ui">
  :root {
    color-scheme: dark;
    --bg: #070707;
    --surface: rgba(21, 21, 20, .88);
    --surface-strong: #171716;
    --surface-soft: #111110;
    --surface-raised: #1c1c1a;
    --line: rgba(255, 255, 255, .09);
    --line-strong: rgba(255, 255, 255, .15);
    --text: #f5f2eb;
    --muted: #aaa69e;
    --quiet: #85817b;
    --gold: #e8b66d;
    --gold-bright: #ffd69a;
    --violet: #9e8cff;
    --teal: #58d6c7;
    --green: #61d89c;
    --amber: #f4b45f;
    --red: #ff7676;
    --blue: #71a8ff;
    --radius-lg: 24px;
    --radius-md: 16px;
    --radius-sm: 11px;
    --shadow: 0 24px 80px rgba(0, 0, 0, .38);
    --shadow-soft: 0 15px 44px rgba(0, 0, 0, .24);
  }
  * { box-sizing: border-box; }
  html { min-width: 320px; scroll-behavior: smooth; }
  body {
    min-width: 320px;
    min-height: 100vh;
    margin: 0;
    overflow-x: hidden;
    background:
      radial-gradient(circle at 15% -10%, rgba(126, 88, 42, .18), transparent 34rem),
      radial-gradient(circle at 92% 16%, rgba(74, 53, 133, .15), transparent 31rem),
      var(--bg);
    color: var(--text);
    font-family: Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    -webkit-font-smoothing: antialiased;
  }
  body:before {
    content: "";
    position: fixed;
    inset: 0;
    z-index: -2;
    pointer-events: none;
    opacity: .25;
    background-image: linear-gradient(rgba(255,255,255,.024) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.024) 1px, transparent 1px);
    background-size: 72px 72px;
    mask-image: linear-gradient(to bottom, black, transparent 78%);
  }
  button, input, select { font: inherit; }
  button, a, select { -webkit-tap-highlight-color: transparent; }
  a { color: inherit; }
  button:focus-visible, a:focus-visible, input:focus-visible, select:focus-visible, [tabindex]:focus-visible {
    outline: 2px solid var(--gold-bright);
    outline-offset: 3px;
  }
  .studio-ambient { position: fixed; inset: 0; z-index: -1; overflow: hidden; pointer-events: none; }
  .studio-orb { position: absolute; width: 30rem; height: 30rem; border-radius: 50%; filter: blur(90px); opacity: .1; animation: studio-drift 18s ease-in-out infinite alternate; }
  .studio-orb.gold { top: 8%; left: -14rem; background: var(--gold); }
  .studio-orb.violet { top: 38%; right: -16rem; background: var(--violet); animation-delay: -7s; }
  .studio-orb.teal { bottom: -17rem; left: 42%; background: var(--teal); animation-delay: -12s; }
  .studio-appbar {
    position: sticky;
    top: 0;
    z-index: 50;
    display: flex;
    align-items: center;
    gap: 16px;
    min-height: 68px;
    padding: 10px 28px;
    border-bottom: 1px solid var(--line);
    background: rgba(7, 7, 7, .82);
    backdrop-filter: blur(22px) saturate(130%);
  }
  .studio-brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
  .studio-brand-mark {
    display: grid;
    place-items: center;
    width: 36px;
    height: 36px;
    flex: none;
    border: 1px solid rgba(232, 182, 109, .42);
    border-radius: 12px;
    background: linear-gradient(145deg, rgba(232,182,109,.22), rgba(232,182,109,.04));
    color: var(--gold-bright);
    font: 700 11px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
    box-shadow: inset 0 1px rgba(255,255,255,.08), 0 8px 30px rgba(232,182,109,.08);
  }
  .studio-brand-copy { min-width: 0; }
  .studio-brand-name { overflow: hidden; color: var(--text); font-size: 14px; font-weight: 760; letter-spacing: .01em; text-overflow: ellipsis; white-space: nowrap; }
  .studio-brand-meta { margin-top: 3px; overflow: hidden; color: var(--quiet); font: 10px/1.3 ui-monospace, SFMono-Regular, Menlo, monospace; text-overflow: ellipsis; white-space: nowrap; }
  .studio-appbar-context { min-width: 0; flex: 1; }
  .studio-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
  .studio-shell { width: min(1360px, calc(100% - 48px)); margin: 0 auto; padding: 40px 0 80px; }
  .studio-eyebrow { margin: 0 0 10px; color: var(--gold); font-size: 10px; font-weight: 850; letter-spacing: .18em; text-transform: uppercase; }
  .studio-page-title { margin: 0; color: var(--text); font-size: clamp(30px, 4vw, 52px); line-height: 1; letter-spacing: -.045em; }
  .studio-page-copy { max-width: 720px; margin: 13px 0 0; color: var(--muted); font-size: 13px; line-height: 1.65; }
  .studio-panel {
    border: 1px solid var(--line);
    border-radius: var(--radius-lg);
    background: linear-gradient(145deg, rgba(27,27,25,.92), rgba(15,15,14,.9));
    box-shadow: inset 0 1px rgba(255,255,255,.025), var(--shadow-soft);
  }
  .studio-section-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; margin-bottom: 14px; }
  .studio-section-title { margin: 0; color: var(--text); font-size: 17px; letter-spacing: -.02em; }
  .studio-section-copy { margin: 5px 0 0; color: var(--quiet); font-size: 11px; line-height: 1.5; }
  .studio-button, .studio-link {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 36px;
    padding: 8px 13px;
    border: 1px solid var(--line);
    border-radius: 10px;
    color: var(--muted);
    background: rgba(255,255,255,.035);
    font-size: 11px;
    font-weight: 760;
    line-height: 1;
    text-decoration: none;
    cursor: pointer;
    transition: color .2s, border-color .2s, background .2s, transform .2s, opacity .2s;
  }
  .studio-button:hover, .studio-link:hover { color: var(--text); border-color: var(--line-strong); background: rgba(255,255,255,.065); transform: translateY(-1px); }
  .studio-button.primary { border-color: rgba(232,182,109,.36); color: #17120b; background: linear-gradient(135deg, var(--gold-bright), var(--gold)); box-shadow: 0 10px 26px rgba(232,182,109,.16); }
  .studio-button.primary:hover { color: #0d0a06; background: linear-gradient(135deg, #ffe3b9, #eec27f); }
  .studio-button.danger { border-color: rgba(255,118,118,.3); color: #ffd5d5; background: rgba(255,118,118,.1); }
  .studio-button.success { border-color: rgba(97,216,156,.3); color: #c8ffe4; background: rgba(97,216,156,.1); }
  .studio-button:disabled { opacity: .46; cursor: not-allowed; transform: none; }
  .studio-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; font-weight: 760; }
  .studio-status:before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; box-shadow: 0 0 0 5px color-mix(in srgb, currentColor 10%, transparent); }
  .studio-status.ok { color: var(--green); }
  .studio-status.warn { color: var(--amber); }
  .studio-status.bad { color: var(--red); }
  .studio-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  .studio-enter { animation: studio-rise .55s cubic-bezier(.2,.8,.2,1) both; }
  @keyframes studio-rise { from { opacity: 0; transform: translateY(12px); } }
  @keyframes studio-drift { to { transform: translate3d(32px, -24px, 0) scale(1.08); } }
  @media (max-width: 760px) {
    .studio-appbar { align-items: flex-start; padding: 10px 16px; }
    .studio-actions { gap: 6px; }
    .studio-button, .studio-link { padding: 8px 10px; }
    .studio-shell { width: min(100% - 28px, 1360px); padding: 28px 0 64px; }
  }
  @media (prefers-reduced-motion: reduce) {
    html { scroll-behavior: auto; }
    *, *:before, *:after { animation-duration: .01ms !important; animation-iteration-count: 1 !important; transition-duration: .01ms !important; }
  }
</style>"""
