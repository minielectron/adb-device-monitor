package devicemonitor.presentation.web

// Plain JS throughout (no template literals) so these Kotlin raw strings stay simple.

internal val HOME_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ADB Performance Studio</title>
<style>
  * { box-sizing: border-box; }
  html { scroll-behavior: smooth; }
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
    z-index: -1;
    pointer-events: none;
    opacity: .28;
    background-image: linear-gradient(rgba(255,255,255,.025) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.025) 1px, transparent 1px);
    background-size: 72px 72px;
    mask-image: linear-gradient(to bottom, black, transparent 72%);
  }
  button, input { font: inherit; }
  button, a { -webkit-tap-highlight-color: transparent; }
  a { color: inherit; }
  .ambient { position: fixed; inset: 0; z-index: -1; overflow: hidden; pointer-events: none; }
  .orb { position: absolute; width: 30rem; height: 30rem; border-radius: 50%; filter: blur(90px); opacity: .12; animation: drift 18s ease-in-out infinite alternate; }
  .orb.one { top: 8%; left: -14rem; background: var(--gold); }
  .orb.two { top: 38%; right: -16rem; background: var(--violet); animation-delay: -7s; }
  .orb.three { bottom: -17rem; left: 42%; background: var(--teal); animation-delay: -12s; }
  @keyframes drift { to { transform: translate3d(32px, -24px, 0) scale(1.08); } }
  .appbar {
    position: sticky;
    top: 0;
    z-index: 30;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 24px;
    height: 68px;
    padding: 0 32px;
    border-bottom: 1px solid var(--line);
    background: rgba(7, 7, 7, .78);
    backdrop-filter: blur(22px) saturate(130%);
  }
  .brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
  .brand-mark {
    position: relative;
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
  .brand-copy { min-width: 0; }
  .brand-name { font-size: 14px; font-weight: 700; letter-spacing: .01em; white-space: nowrap; }
  .build { margin-top: 2px; color: var(--quiet); font: 10px/1.3 ui-monospace, SFMono-Regular, Menlo, monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .nav-actions { display: flex; align-items: center; gap: 8px; }
  .nav-link {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 9px 12px;
    border: 1px solid transparent;
    border-radius: 10px;
    color: var(--muted);
    font-size: 12px;
    font-weight: 650;
    text-decoration: none;
    transition: color .2s, background .2s, border-color .2s, transform .2s;
  }
  .nav-link:hover { color: var(--text); background: rgba(255,255,255,.045); border-color: var(--line); transform: translateY(-1px); }
  .nav-link:focus-visible, button:focus-visible, input:focus-visible, .dev:focus-visible { outline: 2px solid var(--gold-bright); outline-offset: 3px; }
  .shell { width: min(1240px, calc(100% - 48px)); margin: 0 auto; padding: 52px 0 88px; }
  .intro { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 24px; margin-bottom: 36px; }
  .eyebrow { margin: 0 0 12px; color: var(--gold); font-size: 11px; font-weight: 800; letter-spacing: .18em; text-transform: uppercase; }
  .intro h1 { max-width: 780px; margin: 0; font-size: clamp(34px, 5vw, 64px); line-height: .98; letter-spacing: -.05em; font-weight: 720; }
  .intro h1 span { color: var(--muted); }
  .intro-copy { max-width: 440px; margin: 18px 0 0; color: var(--muted); font-size: 14px; line-height: 1.65; }
  .signal {
    display: flex;
    align-items: center;
    gap: 9px;
    padding: 10px 13px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--muted);
    background: rgba(255,255,255,.025);
    font: 11px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
  }
  .signal-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--green); box-shadow: 0 0 0 5px rgba(97,216,156,.08), 0 0 16px rgba(97,216,156,.45); }
  .banner {
    display: none;
    align-items: center;
    justify-content: space-between;
    gap: 18px;
    margin-bottom: 20px;
    padding: 16px 18px;
    border: 1px solid rgba(232,182,109,.28);
    border-radius: var(--radius-md);
    background: linear-gradient(100deg, rgba(232,182,109,.13), rgba(232,182,109,.035));
    color: #e8ddcc;
    font-size: 13px;
  }
  .banner a { color: var(--gold-bright); font-weight: 750; text-decoration: none; white-space: nowrap; }
  .download-toast {
    position: fixed;
    z-index: 1200;
    top: 82px;
    right: 24px;
    display: flex;
    align-items: center;
    gap: 10px;
    max-width: min(440px, calc(100% - 32px));
    padding: 13px 16px;
    border: 1px solid rgba(97,216,156,.32);
    border-radius: 12px;
    color: #ddf7e9;
    background: rgba(17,30,24,.97);
    box-shadow: 0 18px 50px rgba(0,0,0,.38);
    font-size: 12px;
    font-weight: 700;
  }
  .download-toast[hidden] { display: none; }
  .download-toast:before { content: "\2713"; color: var(--green); font-size: 15px; }
  .download-toast.error { border-color: rgba(255,118,118,.35); color: #ffd7d7; background: rgba(35,19,19,.97); }
  .download-toast.error:before { content: "!"; color: var(--red); }
  .stage, .control-card, .review-card {
    border: 1px solid var(--line);
    background: linear-gradient(145deg, rgba(27,27,25,.92), rgba(15,15,14,.9));
    box-shadow: inset 0 1px rgba(255,255,255,.025), var(--shadow);
  }
  .stage { margin-bottom: 18px; padding: 26px; border-radius: var(--radius-lg); }
  .stage-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; margin-bottom: 20px; }
  .title-lockup { display: flex; align-items: flex-start; gap: 14px; }
  .step {
    display: grid;
    place-items: center;
    width: 30px;
    height: 30px;
    flex: none;
    border: 1px solid rgba(232,182,109,.24);
    border-radius: 50%;
    color: var(--gold);
    background: rgba(232,182,109,.07);
    font: 700 11px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
  }
  .kicker { margin: 1px 0 5px; color: var(--quiet); font-size: 10px; font-weight: 800; letter-spacing: .15em; text-transform: uppercase; }
  h2, h3 { margin: 0; }
  .stage h2, .control-card h2, .review-card h2 { font-size: 20px; line-height: 1.2; letter-spacing: -.025em; }
  .section-copy { margin: 7px 0 0; color: var(--muted); font-size: 12px; line-height: 1.55; }
  .refresh {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    padding: 8px 11px;
    border: 1px solid var(--line);
    border-radius: 10px;
    color: var(--muted);
    background: rgba(255,255,255,.025);
    cursor: pointer;
    font-size: 11px;
    font-weight: 700;
    transition: color .2s, border-color .2s, background .2s;
  }
  .refresh:hover { color: var(--text); border-color: var(--line-strong); background: rgba(255,255,255,.045); }
  .refresh.loading .refresh-icon { animation: spin .8s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
  .device-layout { display: grid; gap: 18px; }
  .devgrid { display: grid; grid-template-columns: repeat(auto-fill, minmax(235px, 1fr)); gap: 12px; align-content: start; }
  .dev {
    position: relative;
    min-height: 142px;
    overflow: hidden;
    padding: 17px;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    color: var(--text);
    background: rgba(255,255,255,.025);
    cursor: pointer;
    transition: transform .25s cubic-bezier(.2,.8,.2,1), border-color .25s, background .25s, box-shadow .25s;
  }
  .dev:before { content: ""; position: absolute; inset: -1px; opacity: 0; border-radius: inherit; background: radial-gradient(circle at 20% 0%, rgba(232,182,109,.24), transparent 47%); transition: opacity .3s; pointer-events: none; }
  .dev:hover { transform: translateY(-3px); border-color: var(--line-strong); background: rgba(255,255,255,.04); }
  .dev:hover:before, .dev.selected:before { opacity: 1; }
  .dev.selected { border-color: rgba(232,182,109,.54); background: rgba(232,182,109,.07); box-shadow: 0 16px 40px rgba(0,0,0,.26), inset 0 0 0 1px rgba(232,182,109,.08); }
  .dev.dead { opacity: .48; cursor: not-allowed; filter: grayscale(.55); }
  .dev.dead:hover { transform: none; }
  .device-top { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
  .device-glyph { position: relative; width: 24px; height: 34px; border: 1.5px solid var(--gold); border-radius: 6px; opacity: .82; }
  .device-glyph:after { content: ""; position: absolute; bottom: 3px; left: 50%; width: 3px; height: 3px; border-radius: 50%; background: var(--gold); transform: translateX(-50%); }
  .dev .model { position: relative; margin: 15px 0 5px; font-size: 15px; font-weight: 720; letter-spacing: -.015em; }
  .dev .serial { position: relative; overflow: hidden; color: var(--muted); font: 10px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace; text-overflow: ellipsis; white-space: nowrap; }
  .dev .meta { position: relative; margin-top: 7px; color: var(--quiet); font-size: 11px; }
  .badge { display: inline-flex; align-items: center; gap: 6px; padding: 5px 8px; border-radius: 999px; font-size: 9px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
  .badge:before { content: ""; width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
  .badge.ok { color: var(--green); background: rgba(97,216,156,.09); }
  .badge.bad { color: var(--red); background: rgba(255,118,118,.09); }
  .rootchip, .rootbtn { position: relative; display: inline-flex; margin-top: 9px; padding: 5px 8px; border-radius: 8px; font-size: 9px; font-weight: 750; }
  .rootchip.root { color: var(--green); background: rgba(97,216,156,.08); }
  .rootbtn { border: 1px solid rgba(232,182,109,.3); color: var(--gold-bright); background: rgba(232,182,109,.07); cursor: pointer; }
  .rootbtn:disabled { opacity: .5; cursor: wait; }
  .empty { grid-column: 1 / -1; display: grid; place-items: center; min-height: 142px; padding: 24px; border: 1px dashed var(--line-strong); border-radius: var(--radius-md); color: var(--muted); background: rgba(255,255,255,.018); text-align: center; font-size: 12px; line-height: 1.6; }
  .quick-card { padding: 20px; border: 1px solid var(--line); border-radius: var(--radius-lg); background: linear-gradient(145deg, rgba(255,255,255,.032), rgba(255,255,255,.012)); }
  .quick-head { display: flex; align-items: end; justify-content: space-between; gap: 20px; margin-bottom: 15px; }
  .quick-card h3 { margin: 0; font-size: 15px; }
  .quick-card .quick-copy { margin: 6px 0 0; max-width: 610px; color: var(--quiet); font-size: 11px; line-height: 1.55; }
  .diag-context { flex: none; display: flex; align-items: center; gap: 8px; max-width: 330px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 10px; color: var(--muted); background: rgba(0,0,0,.22); font-size: 10px; }
  .diag-context:before { content: ""; flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--gold); box-shadow: 0 0 0 4px rgba(232,182,109,.08); }
  #diagTarget { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .tool-stack { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 12px; }
  .diagnostic-tool { position: relative; overflow: hidden; padding: 16px; border: 1px solid var(--line); border-radius: 14px; background: rgba(0,0,0,.19); transition: transform .24s cubic-bezier(.2,.8,.2,1), border-color .24s, background .24s; }
  .diagnostic-tool:hover { transform: translateY(-2px); border-color: var(--line-strong); background: rgba(255,255,255,.028); }
  .tool-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
  .tool-type { color: var(--quiet); font-size: 8px; font-weight: 800; letter-spacing: .12em; text-transform: uppercase; }
  .diagnostic-tool h4 { margin: 13px 0 4px; font-size: 13px; }
  .diagnostic-tool > p { min-height: 33px; margin: 0 0 11px; color: var(--quiet); font-size: 10px; line-height: 1.55; }
  .tool-requirement { display: flex; align-items: center; gap: 7px; margin-bottom: 13px; color: var(--muted); font-size: 9px; }
  .tool-requirement:before { content: ""; width: 4px; height: 4px; border-radius: 50%; background: currentColor; }
  .tool-button {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    width: 100%;
    padding: 10px 11px;
    border: 1px solid var(--line);
    border-radius: 10px;
    color: var(--text);
    background: rgba(255,255,255,.04);
    text-align: left;
    cursor: pointer;
    transition: transform .2s, background .2s, border-color .2s;
  }
  .tool-button:hover:not(:disabled) { transform: translateX(2px); background: rgba(255,255,255,.065); border-color: var(--line-strong); }
  .tool-button:disabled { opacity: .42; cursor: not-allowed; }
  .tool-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; font-size: 15px; }
  .tool-icon.violet { color: #c3b8ff; background: rgba(158,140,255,.12); }
  .tool-icon.teal { color: #8df1e5; background: rgba(88,214,199,.11); }
  .tool-icon.gold { color: #ffd798; background: rgba(232,182,109,.12); }
  .tool-name { display: block; font-size: 10px; font-weight: 760; }
  .tool-arrow { color: var(--quiet); font-size: 14px; }
  .progress-wrap { display: none; margin-top: 12px; }
  .progress-track { height: 5px; overflow: hidden; border-radius: 999px; background: rgba(255,255,255,.07); }
  .progress-bar { height: 100%; width: 0; border-radius: inherit; transition: width .3s ease, background .2s; }
  .progress-message { margin-top: 6px; color: var(--muted); font-size: 10px; line-height: 1.5; }
  .progress-message a { color: var(--gold-bright); }
  body.report-open { overflow: hidden; }
  .report-viewer { position: fixed; inset: 0; z-index: 1000; display: flex; flex-direction: column; background: var(--bg); }
  .report-viewer[hidden] { display: none; }
  .report-toolbar { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 16px; min-height: 62px; padding: 10px 18px; border-bottom: 1px solid var(--line); background: rgba(9,10,11,.97); box-shadow: 0 10px 30px rgba(0,0,0,.22); }
  .report-toolbar-title { min-width: 0; color: var(--text); font-size: 12px; font-weight: 760; letter-spacing: -.01em; text-align: center; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .report-toolbar-actions { display: flex; justify-content: flex-end; }
  .viewer-button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 36px; padding: 0 12px; border: 1px solid var(--line); border-radius: 10px; color: var(--muted); background: rgba(255,255,255,.035); cursor: pointer; font-size: 10px; font-weight: 750; transition: color .2s, border-color .2s, background .2s, transform .2s; }
  .viewer-button:hover { color: var(--text); border-color: var(--line-strong); background: rgba(255,255,255,.06); }
  .viewer-button:active { transform: scale(.98); }
  .viewer-button.primary { color: #15120d; border-color: rgba(232,182,109,.7); background: var(--gold); }
  .report-body { position: relative; flex: 1; min-height: 0; background: #090a0b; }
  .report-frame { width: 100%; height: 100%; border: 0; background: #090a0b; opacity: 0; transition: opacity .22s ease; }
  .report-viewer.loaded .report-frame { opacity: 1; }
  .report-state { position: absolute; inset: 0; z-index: 1; display: grid; place-items: center; padding: 24px; background: radial-gradient(circle at 50% 34%, rgba(158,140,255,.11), transparent 38%), #090a0b; text-align: center; }
  .report-viewer.loaded .report-state { display: none; }
  .report-state-card { max-width: 380px; }
  .viewer-spinner { width: 28px; height: 28px; margin: 0 auto 15px; border: 2px solid rgba(255,255,255,.1); border-top-color: var(--gold); border-radius: 50%; animation: spin .8s linear infinite; }
  .report-state h3 { margin: 0; font-size: 15px; }
  .report-state p { margin: 7px 0 15px; color: var(--quiet); font-size: 10px; line-height: 1.55; }
  .report-viewer.failed .viewer-spinner { display: none; }
  .report-viewer:not(.failed) #reportRetry { display: none; }
  .workspace { display: grid; grid-template-columns: minmax(0, 1fr) 330px; gap: 18px; align-items: start; }
  .config-stack { display: grid; gap: 14px; min-width: 0; }
  .control-card { overflow: hidden; border-radius: var(--radius-lg); }
  .card-heading { padding: 24px 25px 0; }
  .field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; padding: 22px 25px 26px; }
  .field { display: flex; min-width: 0; flex-direction: column; gap: 7px; }
  .field.span-2 { grid-column: 1 / -1; }
  .field label { color: #c7c3ba; font-size: 10px; font-weight: 780; letter-spacing: .07em; text-transform: uppercase; }
  .field input[type=number], .field input[type=text] {
    width: 100%;
    min-width: 0;
    height: 43px;
    padding: 0 12px;
    border: 1px solid var(--line);
    border-radius: 11px;
    outline: 0;
    color: var(--text);
    background: rgba(4,4,4,.46);
    font-size: 12px;
    transition: border-color .2s, box-shadow .2s, background .2s;
  }
  .field input:hover { border-color: var(--line-strong); }
  .field input:focus { border-color: rgba(232,182,109,.58); background: rgba(4,4,4,.7); box-shadow: 0 0 0 3px rgba(232,182,109,.08); }
  .field .hint { color: var(--quiet); font-size: 9px; line-height: 1.45; }
  .input-action { display: flex; gap: 8px; }
  .input-action input { flex: 1; }
  .browse {
    flex: none;
    padding: 0 14px;
    border: 1px solid var(--line);
    border-radius: 11px;
    color: var(--muted);
    background: rgba(255,255,255,.035);
    cursor: pointer;
    font-size: 10px;
    font-weight: 750;
  }
  .browse:hover { color: var(--text); border-color: var(--line-strong); }
  .disclosure-trigger {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
    width: 100%;
    padding: 22px 25px;
    border: 0;
    color: var(--text);
    background: transparent;
    text-align: left;
    cursor: pointer;
  }
  .disclosure-title { display: flex; align-items: center; gap: 12px; }
  .section-glyph { display: grid; place-items: center; width: 34px; height: 34px; border: 1px solid var(--line); border-radius: 11px; color: var(--gold); background: rgba(232,182,109,.055); font-size: 14px; }
  .disclosure-actions { display: flex; align-items: center; gap: 10px; }
  .count-pill { min-width: 25px; padding: 4px 8px; border-radius: 999px; color: var(--gold-bright); background: rgba(232,182,109,.09); font: 700 9px/1.3 ui-monospace, SFMono-Regular, Menlo, monospace; text-align: center; }
  .chevron { color: var(--quiet); font-size: 11px; transition: transform .3s cubic-bezier(.2,.8,.2,1); }
  .disclosure-trigger.open .chevron { transform: rotate(180deg); }
  .disclosure-body { display: grid; grid-template-rows: 0fr; opacity: .25; transition: grid-template-rows .38s cubic-bezier(.2,.8,.2,1), opacity .25s; }
  .disclosure-body.open { grid-template-rows: 1fr; opacity: 1; }
  .disclosure-inner { overflow: hidden; }
  .disclosure-content { padding: 0 25px 24px; border-top: 1px solid var(--line); }
  .toggle-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; padding-top: 18px; }
  .toggle { display: flex; align-items: flex-start; gap: 12px; min-height: 78px; padding: 13px; border: 1px solid var(--line); border-radius: 13px; background: rgba(255,255,255,.018); transition: border-color .2s, background .2s; }
  .toggle:has(input:checked) { border-color: rgba(232,182,109,.24); background: rgba(232,182,109,.035); }
  .toggle .name { color: #dedad2; font-size: 11px; font-weight: 720; }
  .toggle .desc { margin-top: 4px; color: var(--quiet); font-size: 9px; line-height: 1.45; }
  .switch { position: relative; width: 38px; height: 22px; flex: none; margin-top: 1px; }
  .switch input { position: absolute; opacity: 0; width: 1px; height: 1px; }
  .slider { position: absolute; inset: 0; border: 1px solid var(--line-strong); border-radius: 999px; background: #282826; cursor: pointer; transition: background .25s, border-color .25s; }
  .slider:before { content: ""; position: absolute; width: 14px; height: 14px; top: 3px; left: 3px; border-radius: 50%; background: #8b8881; box-shadow: 0 2px 6px rgba(0,0,0,.35); transition: transform .25s cubic-bezier(.2,.8,.2,1), background .25s; }
  .switch input:checked + .slider { border-color: rgba(232,182,109,.48); background: rgba(232,182,109,.25); }
  .switch input:checked + .slider:before { background: var(--gold-bright); transform: translateX(16px); }
  .switch input:focus-visible + .slider { outline: 2px solid var(--gold-bright); outline-offset: 3px; }
  .sub { grid-column: 1 / -1; display: flex; gap: 12px; padding: 4px 12px 2px 50px; }
  .sub .field { flex: 1; }
  .metric-cluster { margin-top: 14px; padding: 17px; border: 1px solid var(--line); border-radius: 14px; background: rgba(255,255,255,.015); }
  .metric-cluster:first-child { margin-top: 18px; }
  .mgroup-title { color: #d7d2c9; font-size: 10px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }
  .mgroup-hint { margin-left: 7px; color: var(--quiet); font-size: 9px; font-weight: 450; letter-spacing: 0; text-transform: none; }
  .metrics-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 18px; margin-top: 10px; }
  .mcheck { display: flex; align-items: flex-start; gap: 9px; padding: 8px 4px; border-radius: 8px; cursor: pointer; }
  .mcheck:hover { background: rgba(255,255,255,.025); }
  .mcheck input[type=checkbox] { width: 14px; height: 14px; margin: 1px 0 0; flex: none; accent-color: var(--gold); cursor: pointer; }
  .mcheck-label { display: block; color: #cbc7be; font-size: 10px; font-weight: 680; }
  .mcheck-cost { display: block; margin-top: 2px; color: var(--quiet); font-size: 8px; line-height: 1.35; }
  .root-required { display: inline-flex; align-items: center; gap: 4px; margin-left: 6px; color: var(--red); font: 800 7px/1 ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: .08em; vertical-align: 1px; }
  .root-required:before { content: ""; width: 5px; height: 5px; border-radius: 50%; background: var(--red); box-shadow: 0 0 0 3px rgba(255,118,118,.08); }
  .mcheck.root-unavailable { opacity: .46; cursor: not-allowed; }
  .mcheck.root-unavailable:hover { background: transparent; }
  .mcheck input:disabled { cursor: not-allowed; }
  .review-card { position: sticky; top: 86px; overflow: hidden; border-radius: var(--radius-lg); }
  .review-top { padding: 24px 23px 19px; border-bottom: 1px solid var(--line); background: radial-gradient(circle at 100% 0%, rgba(232,182,109,.1), transparent 54%); }
  .summary-device { display: flex; align-items: center; gap: 12px; margin-top: 20px; padding: 13px; border: 1px solid var(--line); border-radius: 14px; background: rgba(255,255,255,.025); }
  .summary-device .device-glyph { width: 19px; height: 28px; flex: none; }
  .summary-device .device-glyph:after { bottom: 2px; }
  .summary-name { overflow: hidden; font-size: 11px; font-weight: 750; text-overflow: ellipsis; white-space: nowrap; }
  .summary-serial { margin-top: 3px; overflow: hidden; color: var(--quiet); font: 8px/1.3 ui-monospace, SFMono-Regular, Menlo, monospace; text-overflow: ellipsis; white-space: nowrap; }
  .summary-body { padding: 9px 23px 22px; }
  .summary-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 11px 0; border-bottom: 1px solid var(--line); }
  .summary-row span:first-child { color: var(--quiet); font-size: 10px; }
  .summary-row strong { max-width: 170px; overflow: hidden; color: #dcd7cd; font: 650 10px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace; text-align: right; text-overflow: ellipsis; white-space: nowrap; }
  .start {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    width: 100%;
    min-height: 49px;
    margin-top: 20px;
    overflow: hidden;
    border: 0;
    border-radius: 13px;
    color: #19130c;
    background: linear-gradient(135deg, #f0c887, #d89c4f);
    box-shadow: 0 12px 28px rgba(216,156,79,.18), inset 0 1px rgba(255,255,255,.42);
    cursor: pointer;
    font-size: 12px;
    font-weight: 820;
    letter-spacing: .01em;
    transition: transform .2s, box-shadow .2s, filter .2s;
  }
  .start:before { content: ""; position: absolute; inset: 0; background: linear-gradient(110deg, transparent 20%, rgba(255,255,255,.35) 45%, transparent 70%); transform: translateX(-120%); transition: transform .6s; }
  .start:hover:not(:disabled) { transform: translateY(-2px); box-shadow: 0 18px 34px rgba(216,156,79,.24), inset 0 1px rgba(255,255,255,.5); }
  .start:hover:not(:disabled):before { transform: translateX(120%); }
  .start:active:not(:disabled) { transform: translateY(0) scale(.99); }
  .start:disabled { color: #77736d; background: #242422; box-shadow: none; cursor: not-allowed; }
  .start.loading .start-play { animation: spin .8s linear infinite; }
  #msg { min-height: 18px; margin-top: 10px; color: var(--red); font-size: 10px; line-height: 1.5; text-align: center; }
  .trust-line { display: flex; justify-content: center; gap: 7px; margin-top: 5px; color: var(--quiet); font-size: 8px; }
  .trust-line:before { content: ""; width: 5px; height: 5px; margin-top: 2px; border-radius: 50%; background: var(--green); }
  .reveal { opacity: 0; animation: reveal .65s cubic-bezier(.2,.8,.2,1) forwards; }
  .reveal.d1 { animation-delay: .06s; }
  .reveal.d2 { animation-delay: .13s; }
  .reveal.d3 { animation-delay: .2s; }
  @keyframes reveal { from { opacity: 0; transform: translateY(14px); } to { opacity: 1; transform: none; } }
  @media (max-width: 980px) {
    .device-layout, .workspace { grid-template-columns: 1fr; }
    .review-card { position: static; }
  }
  @media (max-width: 680px) {
    .appbar { height: auto; min-height: 64px; padding: 12px 18px; }
    .brand-copy { display: none; }
    .nav-link span { display: none; }
    .nav-link { width: 38px; height: 38px; justify-content: center; padding: 0; }
    .shell { width: min(100% - 28px, 1240px); padding-top: 34px; }
    .intro { display: block; }
    .intro h1 { font-size: clamp(34px, 12vw, 50px); }
    .signal { width: fit-content; margin-top: 22px; }
    .stage, .card-heading, .disclosure-trigger, .review-top, .summary-body { padding-left: 18px; padding-right: 18px; }
    .stage-heading { align-items: center; }
    .stage-heading .section-copy { display: none; }
    .device-layout { gap: 12px; }
    .devgrid, .field-grid, .toggle-grid, .metrics-grid { grid-template-columns: 1fr; }
    .quick-card { padding: 16px; }
    .quick-head { display: block; }
    .diag-context { max-width: none; margin-top: 12px; }
    .tool-stack { grid-template-columns: 1fr; }
    .field-grid { padding-left: 18px; padding-right: 18px; }
    .field.span-2 { grid-column: auto; }
    .sub { display: grid; grid-template-columns: 1fr; padding-left: 0; }
    .mgroup-hint { display: block; margin: 4px 0 0; }
    .report-toolbar { grid-template-columns: auto minmax(0,1fr) auto; gap: 8px; min-height: 58px; padding: 9px 10px; }
    .viewer-button { width: 38px; padding: 0; }
    .viewer-button .viewer-label { display: none; }
  }
  @media (prefers-reduced-motion: reduce) {
    html { scroll-behavior: auto; }
    *, *:before, *:after { animation-duration: .01ms !important; animation-iteration-count: 1 !important; transition-duration: .01ms !important; }
    .reveal { opacity: 1; }
  }
</style>
</head>
<body>
<div class="ambient" aria-hidden="true"><span class="orb one"></span><span class="orb two"></span><span class="orb three"></span></div>
<header class="appbar">
  <div class="brand">
    <div class="brand-mark">APS</div>
    <div class="brand-copy">
      <div class="brand-name">ADB Performance Studio</div>
      <div class="build">Build <span id="buildtag">loading…</span></div>
    </div>
  </div>
  <nav class="nav-actions" aria-label="Application">
    <a class="nav-link" href="/player-debugger" title="Debug multi-player playback and fallbacks"><b aria-hidden="true">&#9654;</b><span>Player Debugger</span></a>
    <a class="nav-link" href="/release-guard" title="Open the offline performance release gate"><b aria-hidden="true">&#9670;</b><span>Release Guard</span></a>
    <a class="nav-link" href="/compare" title="Compare previous runs"><b aria-hidden="true">&#8644;</b><span>Compare runs</span></a>
    <a class="nav-link" href="/api/diagnostics" title="Download logs and thread diagnostics"><b aria-hidden="true">&#8595;</b><span>Diagnostics</span></a>
  </nav>
</header>
<div class="download-toast" id="downloadToast" role="status" aria-live="polite" aria-atomic="true" hidden><span id="downloadToastText"></span></div>

<main class="shell">
  <section class="intro reveal">
    <div>
      <p class="eyebrow">Performance intelligence</p>
      <h1>See what your device <span>is really doing.</span></h1>
      <p class="intro-copy">Measure system pressure, app behavior, media playback and frame health across Android phones, TVs, STBs and Fire sticks.</p>
    </div>
    <div class="signal"><span class="signal-dot"></span>ADB ENGINE READY</div>
  </section>

  <div class="banner reveal" id="banner" role="status">
    <span>A monitoring session is already in progress.</span>
    <a href="/dashboard" onclick="this.href='/dashboard?v='+Date.now()">Resume live dashboard &rarr;</a>
  </div>

  <section class="stage reveal d1" aria-labelledby="device-title">
    <div class="stage-heading">
      <div class="title-lockup">
        <span class="step">01</span>
        <div><p class="kicker">Target</p><h2 id="device-title">Choose a device</h2><p class="section-copy">Select the Android target you want to inspect.</p></div>
      </div>
      <button class="refresh" id="refreshBtn" type="button" onclick="loadDevices()"><span class="refresh-icon" aria-hidden="true">&#8635;</span> Scan devices</button>
    </div>
    <div class="device-layout">
      <div class="devgrid" id="devices" role="listbox" aria-label="Connected ADB devices"><div class="empty">Scanning for authorized ADB devices…</div></div>
      <aside class="quick-card" id="snapshot-box" aria-labelledby="quick-title">
        <div class="quick-head"><div><p class="kicker">Independent tools</p><h3 id="quick-title">Quick diagnostics</h3><p class="quick-copy">Capture focused evidence without configuring or starting a monitoring run. Each report is generated locally and remains usable offline.</p></div><div class="diag-context"><span id="diagTarget">Select an authorized target</span></div></div>
        <div class="tool-stack">
          <article class="diagnostic-tool">
            <div class="tool-heading"><span class="tool-icon violet" aria-hidden="true">&#9672;</span><span class="tool-type">System profile</span></div>
            <h4>Device snapshot</h4><p>Hardware, Android configuration, memory footprint and top services in one portable report.</p>
            <div class="tool-requirement">Authorized ADB connection</div>
            <button class="tool-button" type="button" id="snapBtn" onclick="takeSnapshot()" disabled><span class="tool-name">Capture device profile</span><span class="tool-arrow">&rarr;</span></button>
            <div class="progress-wrap" id="snapProgWrap" aria-live="polite"><div class="progress-track"><div class="progress-bar" id="snapProg" style="background:#9e8cff"></div></div><div class="progress-message" id="snapMsg"></div></div>
          </article>
          <article class="diagnostic-tool">
            <div class="tool-heading"><span class="tool-icon teal" aria-hidden="true">&#9638;</span><span class="tool-type">Process memory</span></div>
            <h4>Memory snapshot</h4><p>Rank processes by PSS, RSS, USS and VSS with a searchable standalone report.</p>
            <div class="tool-requirement">Root preferred; safe RSS fallback</div>
            <button class="tool-button" type="button" id="procBtn" onclick="takeProcrank()" disabled><span class="tool-name">Capture memory profile</span><span class="tool-arrow">&rarr;</span></button>
            <div class="progress-wrap" id="procProgWrap" aria-live="polite"><div class="progress-track"><div class="progress-bar" id="procProg" style="background:#58d6c7"></div></div><div class="progress-message" id="procMsg"></div></div>
          </article>
          <article class="diagnostic-tool">
            <div class="tool-heading"><span class="tool-icon gold" aria-hidden="true">&#8862;</span><span class="tool-type">UI structure</span></div>
            <h4>Layout Inspector</h4><p>Match visible Android elements with resource IDs, types, states and exact screen positions.</p>
            <div class="tool-requirement">Captures screen imagery and visible UI text</div>
            <button class="tool-button" type="button" id="layoutBtn" onclick="takeLayoutInspector()" disabled><span class="tool-name">Inspect current screen</span><span class="tool-arrow">&rarr;</span></button>
            <div class="progress-wrap" id="layoutProgWrap" aria-live="polite"><div class="progress-track"><div class="progress-bar" id="layoutProg" style="background:#e8b66d"></div></div><div class="progress-message" id="layoutMsg"></div></div>
          </article>
        </div>
      </aside>
    </div>
  </section>

  <div class="workspace reveal d2">
    <div class="config-stack">
      <section class="control-card" aria-labelledby="essentials-title">
        <div class="card-heading">
          <div class="title-lockup"><span class="step">02</span><div><p class="kicker">Configure</p><h2 id="essentials-title">Run essentials</h2><p class="section-copy">Set the measurement window and where results should be stored.</p></div></div>
        </div>
        <div class="field-grid">
          <div class="field"><label for="duration">Duration</label><input type="number" id="duration" value="300" min="1"><span class="hint">Total run time in seconds</span></div>
          <div class="field"><label for="interval">Sampling interval</label><input type="number" id="interval" value="2" min="0.5" step="0.5"><span class="hint">Seconds between measurements</span></div>
          <div class="field"><label for="package">App package <span aria-hidden="true">·</span> optional</label><input type="text" id="package" placeholder="com.example.tv"><span class="hint">Enables app memory, CPU and frame metrics</span></div>
          <div class="field"><label for="label">Run label <span aria-hidden="true">·</span> optional</label><input type="text" id="label" placeholder="tunnel-on"><span class="hint">Tags the result folder and PDF report</span></div>
          <div class="field span-2"><label for="outputDir">Reports folder</label><div class="input-action"><input type="text" id="outputDir" placeholder="Loading default location…"><button class="browse" type="button" id="browseBtn" onclick="pickFolder()">Browse…</button></div><span class="hint">Run folders contain the PDF, CSV, logs and captured traces</span></div>
        </div>
      </section>

      <section class="control-card" aria-labelledby="capture-title">
        <button class="disclosure-trigger open" id="captureHdr" type="button" onclick="toggleCapture()" aria-expanded="true" aria-controls="captureCfg">
          <span class="disclosure-title"><span class="section-glyph" aria-hidden="true">&#9678;</span><span><span class="kicker">Optional instrumentation</span><span id="capture-title" style="display:block;font-size:16px;font-weight:720">Capture tools</span></span></span>
          <span class="disclosure-actions"><span class="count-pill" id="captureCount">1 on</span><span class="chevron" aria-hidden="true">&#9660;</span></span>
        </button>
        <div class="disclosure-body open" id="captureCfg" aria-hidden="false"><div class="disclosure-inner"><div class="disclosure-content">
          <div class="toggle-grid">
            <div class="toggle"><label class="switch"><input type="checkbox" id="logcat" aria-label="Logcat capture" checked><span class="slider"></span></label><div><div class="name">Logcat capture</div><div class="desc">Save device logs and detect crashes, ANRs, LMK kills and playback anomalies.</div></div></div>
            <div class="toggle"><label class="switch"><input type="checkbox" id="zap" aria-label="Channel zapping"><span class="slider"></span></label><div><div class="name">Channel zapping</div><div class="desc">Send CHANNEL_UP on a schedule and mark each zap on the report timeline.</div></div></div>
            <div class="sub" id="zapcfg" style="display:none"><div class="field"><label for="zapMs">Zap every</label><input type="number" id="zapMs" value="5000" min="100"><span class="hint">Milliseconds</span></div><div class="field"><label for="zapCount">Stop after</label><input type="number" id="zapCount" placeholder="Until duration"><span class="hint">Optional zap count</span></div></div>
            <div class="toggle"><label class="switch"><input type="checkbox" id="jank" aria-label="UI jank and frame phases"><span class="slider"></span></label><div><div class="name">UI jank and frame phases</div><div class="desc">Measure janky frames and input, animation, layout and draw cost.</div></div></div>
            <div class="toggle"><label class="switch"><input type="checkbox" id="profile" aria-label="Main-thread profiler"><span class="slider"></span></label><div><div class="name">Main-thread profiler</div><div class="desc">Use ART sampling to identify hot methods in a debuggable app.</div></div></div>
            <div class="toggle"><label class="switch"><input type="checkbox" id="perfetto" aria-label="Perfetto system trace"><span class="slider"></span></label><div><div class="name">Perfetto system trace</div><div class="desc">Record scheduler, graphics, view and input slices for offline analysis.</div></div></div>
          </div>
        </div></div></div>
      </section>

      <section class="control-card" aria-labelledby="metrics-title">
        <button class="disclosure-trigger" id="metricsHdr" type="button" onclick="toggleMetrics()" aria-expanded="false" aria-controls="metricsCfg">
          <span class="disclosure-title"><span class="section-glyph" aria-hidden="true">&#8759;</span><span><span class="kicker">Signal selection</span><span id="metrics-title" style="display:block;font-size:16px;font-weight:720">Performance metrics</span></span></span>
          <span class="disclosure-actions"><span class="count-pill" id="metricCount">2 on</span><span class="chevron" aria-hidden="true">&#9660;</span></span>
        </button>
        <div class="disclosure-body" id="metricsCfg" aria-hidden="true" inert><div class="disclosure-inner"><div class="disclosure-content">
          <div class="metric-cluster">
            <div class="mgroup-title">System and compute <span class="mgroup-hint">CPU, load, thermal and network signals</span></div>
            <div class="metrics-grid">
              <label class="mcheck"><input type="checkbox" id="m_cpu" checked><span><span class="mcheck-label">CPU utilization</span><span class="mcheck-cost">Free · /proc/stat</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_cpuidle"><span><span class="mcheck-label">CPU idle</span><span class="mcheck-cost">Free · /proc/stat</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_percore" checked><span><span class="mcheck-label">Per-core CPU</span><span class="mcheck-cost">Free · /proc/stat cpuN</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_load"><span><span class="mcheck-label">Load average</span><span class="mcheck-cost">Negligible · /proc/loadavg</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_thermal"><span><span class="mcheck-label">CPU temperature</span><span class="mcheck-cost">Low · thermal sysfs</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_freq"><span><span class="mcheck-label">CPU frequency</span><span class="mcheck-cost">Low · scaling_cur_freq</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_network"><span><span class="mcheck-label">Network throughput</span><span class="mcheck-cost">Low · /proc/net/dev</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_topprocs" data-root-required="true" checked><span><span class="mcheck-label">Top process CPU <span class="root-required" aria-label="Requires rooted ADB device">ROOT</span></span><span class="mcheck-cost">Medium · process stat reads</span></span></label>
            </div>
          </div>
          <div class="metric-cluster">
            <div class="mgroup-title">Memory pressure <span class="mgroup-hint">Capacity, stalls, reclaim and top consumers</span></div>
            <div class="metrics-grid">
              <label class="mcheck"><input type="checkbox" id="m_mem"><span><span class="mcheck-label">Memory amount</span><span class="mcheck-cost">Free · /proc/meminfo</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_mempct" checked><span><span class="mcheck-label">Memory used</span><span class="mcheck-cost">Free · /proc/meminfo</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_swap"><span><span class="mcheck-label">Swap used</span><span class="mcheck-cost">Free · /proc/meminfo</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_zone"><span><span class="mcheck-label">Zone watermarks</span><span class="mcheck-cost">Free · /proc/zoneinfo</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_psi" checked><span><span class="mcheck-label">PSI pressure stall</span><span class="mcheck-cost">Low · /proc/pressure</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_pgfault"><span><span class="mcheck-label">Major page faults</span><span class="mcheck-cost">Low · /proc/vmstat</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_reclaim" onchange="if(this.checked){var l=document.getElementById('logcat');if(l)l.checked=true;}"><span><span class="mcheck-label">Direct reclaim</span><span class="mcheck-cost">Logcat · pressure events</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_topmem" checked><span><span class="mcheck-label">Top process memory</span><span class="mcheck-cost">Low · single ps call</span></span></label>
            </div>
          </div>
          <div class="metric-cluster">
            <div class="mgroup-title">App and media <span class="mgroup-hint">Package-aware process and playback signals</span></div>
            <div class="metrics-grid">
              <label class="mcheck"><input type="checkbox" id="m_pss"><span><span class="mcheck-label">App PSS breakdown</span><span class="mcheck-cost">Medium · dumpsys meminfo</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_appcpu"><span><span class="mcheck-label">App process CPU</span><span class="mcheck-cost">Low · process stat</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_maincpu" data-root-required="true"><span><span class="mcheck-label">Main-thread CPU <span class="root-required" aria-label="Requires rooted ADB device">ROOT</span></span><span class="mcheck-cost">Low · task stat</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_mediacpu" data-root-required="true"><span><span class="mcheck-label">Media and HAL CPU <span class="root-required" aria-label="Requires rooted ADB device">ROOT</span></span><span class="mcheck-cost">Low · media process PIDs</span></span></label>
              <label class="mcheck"><input type="checkbox" id="m_playback" onchange="if(this.checked){var l=document.getElementById('logcat');if(l)l.checked=true;}"><span><span class="mcheck-label">Playback and tunnel</span><span class="mcheck-cost">Logcat · STB video state</span></span></label>
            </div>
          </div>
        </div></div></div>
      </section>
    </div>

    <aside class="review-card reveal d3" aria-labelledby="review-title">
      <div class="review-top"><div class="title-lockup"><span class="step">03</span><div><p class="kicker">Review</p><h2 id="review-title">Ready to measure</h2></div></div>
        <div class="summary-device"><span class="device-glyph" aria-hidden="true"></span><div style="min-width:0"><div class="summary-name" id="sumDevice">Waiting for a device</div><div class="summary-serial" id="sumSerial">Select an authorized target</div></div></div>
      </div>
      <div class="summary-body">
        <div class="summary-row"><span>Duration</span><strong id="sumDuration">300 sec</strong></div>
        <div class="summary-row"><span>Sampling</span><strong id="sumInterval">Every 2 sec</strong></div>
        <div class="summary-row"><span>Package</span><strong id="sumPackage">System-wide</strong></div>
        <div class="summary-row"><span>Capture tools</span><strong id="sumCaptures">1 enabled</strong></div>
        <div class="summary-row"><span>Metrics</span><strong id="sumMetrics">2 enabled</strong></div>
        <button class="start" id="startBtn" type="button" onclick="startRun()" disabled><span class="start-play" aria-hidden="true">&#9654;</span><span id="startLabel">Launch performance run</span></button>
        <div id="msg" role="status" aria-live="polite"></div>
        <div class="trust-line">Runs locally over ADB</div>
      </div>
    </aside>
  </div>
</main>
<section class="report-viewer" id="reportViewer" role="dialog" aria-modal="true" aria-labelledby="reportViewerTitle" aria-describedby="reportStateText" hidden>
  <div class="report-toolbar">
    <button class="viewer-button" id="reportBack" type="button" onclick="closeReport()" aria-label="Back to Studio"><span aria-hidden="true">&#8592;</span><span class="viewer-label">Back to Studio</span></button>
    <div class="report-toolbar-title" id="reportViewerTitle">Diagnostic report</div>
    <div class="report-toolbar-actions"><button class="viewer-button" id="reportExternal" type="button" onclick="openReportExternally()" aria-label="Open report externally"><span class="viewer-label">Open externally</span><span aria-hidden="true">&#8599;</span></button></div>
  </div>
  <div class="report-body">
    <div class="report-state" id="reportState" role="status" aria-live="polite"><div class="report-state-card"><div class="viewer-spinner" aria-hidden="true"></div><h3 id="reportStateTitle">Preparing report</h3><p id="reportStateText">Loading the locally generated diagnostic report…</p><button class="viewer-button primary" id="reportRetry" type="button" onclick="retryReport()">Retry report</button></div></div>
    <iframe class="report-frame" id="reportFrame" title="Diagnostic report" referrerpolicy="no-referrer"></iframe>
  </div>
</section>
<script>
var HOME_DRAFT_KEY = 'aps.home.draft.v1';
var homeDraftValues = {};
var selected = null;
var selectedDevice = null;
var lastDevices = [];
var runBusy = false;
var reportPaths = { '/api/snapshot-report': 'Device Snapshot', '/api/procrank-report': 'Memory Snapshot', '/api/layout-inspector-report': 'Layout Inspector' };
var currentReportPath = null;
var reportTrigger = null;
var reportTimer = null;
var downloadToastTimer = null;
var ROOT_METRIC_IDS = ['m_topprocs', 'm_maincpu', 'm_mediacpu'];

function showDownloadToast(success, fileName) {
  var toast = document.getElementById('downloadToast');
  var text = document.getElementById('downloadToastText');
  toast.classList.toggle('error', !success);
  text.textContent = success
    ? 'Diagnostics saved to Downloads — ' + (fileName || 'diagnostics bundle')
    : 'Diagnostics download failed. Try exporting again.';
  toast.hidden = false;
  clearTimeout(downloadToastTimer);
  downloadToastTimer = setTimeout(function () { toast.hidden = true; }, 8000);
}

window.addEventListener('studio-download-finished', function (event) {
  var detail = event.detail || {};
  if (detail.kind === 'diagnostics') showDownloadToast(detail.success === true, detail.fileName);
});

function saveHomeDraft() {
  var values = {};
  Array.prototype.forEach.call(document.querySelectorAll('input[id]'), function (input) {
    values[input.id] = input.type === 'checkbox'
      ? (input.dataset.rootPreference == null ? input.checked : input.dataset.rootPreference === 'true')
      : input.value;
  });
  localStorage.setItem(HOME_DRAFT_KEY, JSON.stringify({
    values: values,
    selected: selected,
    captureOpen: document.getElementById('captureCfg').classList.contains('open'),
    metricsOpen: document.getElementById('metricsCfg').classList.contains('open')
  }));
}

function applyRootMetricAvailability() {
  var rooted = !!selectedDevice && selectedDevice.root === 'root';
  ROOT_METRIC_IDS.forEach(function (id) {
    var input = document.getElementById(id);
    var label = input.closest('label');
    if (!rooted) {
      if (input.dataset.rootPreference == null) input.dataset.rootPreference = String(input.checked);
      input.checked = false;
      input.disabled = true;
      label.classList.add('root-unavailable');
      label.title = 'Requires a rooted ADB device';
    } else {
      if (input.dataset.rootPreference != null) input.checked = input.dataset.rootPreference === 'true';
      delete input.dataset.rootPreference;
      input.disabled = false;
      label.classList.remove('root-unavailable');
      label.removeAttribute('title');
    }
  });
}

function rootMetricEnabled(id) {
  return !!selectedDevice && selectedDevice.root === 'root' && document.getElementById(id).checked;
}

function setPanelState(headerId, contentId, open) {
  var header = document.getElementById(headerId);
  var content = document.getElementById(contentId);
  content.classList.toggle('open', open);
  header.classList.toggle('open', open);
  header.setAttribute('aria-expanded', open ? 'true' : 'false');
  content.setAttribute('aria-hidden', open ? 'false' : 'true');
  if (open) content.removeAttribute('inert'); else content.setAttribute('inert', '');
}

function restoreHomeDraft() {
  try {
    var draft = JSON.parse(localStorage.getItem(HOME_DRAFT_KEY) || 'null');
    if (!draft) return;
    homeDraftValues = draft.values || {};
    Object.keys(homeDraftValues).forEach(function (id) {
      var input = document.getElementById(id);
      if (!input) return;
      if (input.type === 'checkbox') input.checked = !!homeDraftValues[id]; else input.value = homeDraftValues[id];
    });
    selected = draft.selected || null;
    setPanelState('captureHdr', 'captureCfg', draft.captureOpen !== false);
    setPanelState('metricsHdr', 'metricsCfg', draft.metricsOpen === true);
    document.getElementById('zapcfg').style.display = document.getElementById('zap').checked ? 'flex' : 'none';
  } catch (_) {}
}

function setReportBackgroundInert(inert) {
  [document.querySelector('.appbar'), document.querySelector('main')].forEach(function (el) {
    if (!el) return;
    el.inert = inert;
    if (inert) el.setAttribute('aria-hidden', 'true'); else el.removeAttribute('aria-hidden');
  });
}

function loadReportFrame() {
  var viewer = document.getElementById('reportViewer');
  var frame = document.getElementById('reportFrame');
  viewer.className = 'report-viewer loading';
  document.getElementById('reportStateTitle').textContent = 'Preparing report';
  document.getElementById('reportStateText').textContent = 'Loading the locally generated diagnostic report…';
  clearTimeout(reportTimer);
  reportTimer = setTimeout(showReportError, 12000);
  frame.src = currentReportPath + '?viewer=' + Date.now();
}

function openReport(path, trigger) {
  if (!Object.prototype.hasOwnProperty.call(reportPaths, path)) return false;
  currentReportPath = path;
  reportTrigger = trigger || document.activeElement;
  var viewer = document.getElementById('reportViewer');
  document.getElementById('reportViewerTitle').textContent = reportPaths[path];
  document.getElementById('reportFrame').title = reportPaths[path] + ' report';
  viewer.hidden = false;
  document.body.classList.add('report-open');
  setReportBackgroundInert(true);
  loadReportFrame();
  document.getElementById('reportBack').focus();
  return true;
}

function reportLoaded() {
  var viewer = document.getElementById('reportViewer');
  if (viewer.hidden || !currentReportPath) return;
  clearTimeout(reportTimer);
  viewer.className = 'report-viewer loaded';
}

function showReportError() {
  var viewer = document.getElementById('reportViewer');
  if (viewer.hidden || !currentReportPath) return;
  viewer.className = 'report-viewer failed';
  document.getElementById('reportStateTitle').textContent = 'Report did not load';
  document.getElementById('reportStateText').textContent = 'The local report took too long to open. Retry it or return to Studio.';
}

function retryReport() { if (currentReportPath) loadReportFrame(); }

function openReportExternally() {
  if (currentReportPath && Object.prototype.hasOwnProperty.call(reportPaths, currentReportPath)) {
    window.open(currentReportPath, '_blank', 'noopener');
  }
}

function closeReport() {
  var viewer = document.getElementById('reportViewer');
  if (viewer.hidden) return;
  clearTimeout(reportTimer);
  currentReportPath = null;
  viewer.hidden = true;
  viewer.className = 'report-viewer';
  document.getElementById('reportFrame').src = 'about:blank';
  document.body.classList.remove('report-open');
  setReportBackgroundInert(false);
  var focusTarget = reportTrigger;
  reportTrigger = null;
  if (focusTarget && document.contains(focusTarget)) focusTarget.focus();
}

document.getElementById('reportFrame').addEventListener('load', reportLoaded);
document.getElementById('reportFrame').addEventListener('error', showReportError);
document.addEventListener('keydown', function (event) {
  if (event.key === 'Escape' && !document.getElementById('reportViewer').hidden) closeReport();
});

var devicesLoading = false;
function escapeHtml(value) {
  return String(value || '').replace(/[&<>"']/g, function (ch) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
  });
}

function deviceName(dev) {
  var match = /(?:^|\s)model:([^\s]+)/.exec(dev.desc || '');
  return match ? match[1].replace(/_/g, ' ') : (dev.desc || dev.serial);
}

function renderDevices(devices) {
  var grid = document.getElementById('devices');
  var usable = devices.filter(function (dev) { return dev.state === 'device'; });
  if (!devices.length) {
    selected = null;
    selectedDevice = null;
    grid.innerHTML = '<div class="empty">No authorized device found.<br>Connect over USB or <b>adb connect &lt;ip&gt;</b>, then scan again.</div>';
    applyRootMetricAvailability();
    updateSummary();
    return;
  }
  if (!usable.some(function (dev) { return dev.serial === selected; })) selected = usable.length ? usable[0].serial : null;
  selectedDevice = devices.filter(function (dev) { return dev.serial === selected; })[0] || null;
  var html = '';
  devices.forEach(function (dev) {
    var ready = dev.state === 'device';
    var isSelected = ready && dev.serial === selected;
    var serial = escapeHtml(dev.serial);
    var state = escapeHtml(dev.state);
    var model = escapeHtml(deviceName(dev));
    var android = dev.android ? ('Android ' + escapeHtml(dev.android)) : 'Android version unavailable';
    var cls = 'dev' + (ready ? '' : ' dead') + (isSelected ? ' selected' : '');
    var badge = ready ? '<span class="badge ok">ready</span>' : '<span class="badge bad">' + state + '</span>';
    var rootHtml = '';
    if (dev.root === 'root') rootHtml = '<span class="rootchip root">&#10003; Root enabled</span>';
    else if (dev.root === 'available') rootHtml = '<button class="rootbtn" type="button" data-rootserial="' + serial + '">Enable adb root</button>';
    html += '<div class="' + cls + '" data-serial="' + serial + '" data-usable="' + ready + '" role="option" aria-selected="' + isSelected + '" tabindex="' + (ready ? '0' : '-1') + '">'
          + '<div class="device-top"><span class="device-glyph" aria-hidden="true"></span>' + badge + '</div>'
          + '<div class="model">' + model + '</div><div class="serial">' + serial + '</div>'
          + '<div class="meta">' + android + '</div>' + rootHtml + '</div>';
  });
  grid.innerHTML = html;
  Array.prototype.forEach.call(grid.children, function (el) {
    if (el.dataset.usable !== 'true') return;
    var choose = function () { selected = el.dataset.serial; renderDevices(lastDevices); saveHomeDraft(); };
    el.onclick = choose;
    el.onkeydown = function (event) {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); choose(); }
    };
  });
  Array.prototype.forEach.call(document.querySelectorAll('.rootbtn'), function (btn) {
    btn.onclick = function (event) {
      event.stopPropagation();
      btn.disabled = true;
      btn.textContent = 'Restarting adbd…';
      var p = new URLSearchParams();
      p.set('serial', btn.dataset.rootserial);
      fetch('/api/root', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: p.toString() })
        .then(function (r) { return r.json(); })
        .then(function (d) {
          if (!d.ok) document.getElementById('msg').textContent = d.error || 'adb root failed';
          setTimeout(loadDevices, 1500);
        })
        .catch(function () { document.getElementById('msg').textContent = 'Could not restart adbd.'; setTimeout(loadDevices, 1500); });
    };
  });
  applyRootMetricAvailability();
  updateSummary();
}

function loadDevices() {
  if (devicesLoading) return;   // never stack polls behind a slow adb — that exhausts the browser's connections
  devicesLoading = true;
  document.getElementById('refreshBtn').classList.add('loading');
  fetch('/api/devices').then(function (r) { return r.json(); }).then(function (d) {
    devicesLoading = false;
    document.getElementById('refreshBtn').classList.remove('loading');
    runBusy = !!d.busy;
    document.getElementById('banner').style.display = runBusy ? 'flex' : 'none';
    lastDevices = d.devices || [];
    renderDevices(lastDevices);
  }).catch(function () {
    devicesLoading = false;
    document.getElementById('refreshBtn').classList.remove('loading');
    if (!lastDevices.length) document.getElementById('devices').innerHTML = '<div class="empty">Device scan failed. Check ADB, then <button class="refresh" type="button" onclick="loadDevices()">try again</button>.</div>';
    document.getElementById('msg').textContent = 'Could not refresh ADB devices.';
  });
}

document.getElementById('zap').onchange = function () {
  document.getElementById('zapcfg').style.display = this.checked ? 'flex' : 'none';
  updateSummary();
  saveHomeDraft();
};

function togglePanel(headerId, contentId) {
  var content = document.getElementById(contentId);
  var open = !content.classList.contains('open');
  setPanelState(headerId, contentId, open);
  saveHomeDraft();
}

function toggleCapture() { togglePanel('captureHdr', 'captureCfg'); }
function toggleMetrics() { togglePanel('metricsHdr', 'metricsCfg'); }

var CAPTURE_IDS = ['logcat', 'zap', 'jank', 'profile', 'perfetto'];
var METRIC_IDS = ['m_cpu', 'm_cpuidle', 'm_percore', 'm_load', 'm_thermal', 'm_freq', 'm_network', 'm_topprocs',
  'm_mem', 'm_mempct', 'm_swap', 'm_zone', 'm_psi', 'm_pgfault', 'm_reclaim', 'm_topmem',
  'm_pss', 'm_appcpu', 'm_maincpu', 'm_mediacpu', 'm_playback'];

function checkedCount(ids) {
  return ids.reduce(function (count, id) { return count + (document.getElementById(id).checked ? 1 : 0); }, 0);
}

function updateSummary() {
  var captureCount = checkedCount(CAPTURE_IDS);
  var metricCount = checkedCount(METRIC_IDS);
  var duration = document.getElementById('duration').value || '—';
  var interval = document.getElementById('interval').value || '—';
  var packageName = document.getElementById('package').value.trim();
  document.getElementById('sumDevice').textContent = selectedDevice ? deviceName(selectedDevice) : 'Waiting for a device';
  document.getElementById('sumSerial').textContent = selectedDevice ? selectedDevice.serial : 'Select an authorized target';
  document.getElementById('diagTarget').textContent = selectedDevice ? ('Target: ' + deviceName(selectedDevice) + ' · ' + selectedDevice.serial) : 'Select an authorized target';
  document.getElementById('sumDuration').textContent = duration + ' sec';
  document.getElementById('sumInterval').textContent = 'Every ' + interval + ' sec';
  document.getElementById('sumPackage').textContent = packageName || 'System-wide';
  document.getElementById('sumCaptures').textContent = captureCount + ' enabled';
  document.getElementById('sumMetrics').textContent = metricCount + ' enabled';
  document.getElementById('captureCount').textContent = captureCount + ' on';
  document.getElementById('metricCount').textContent = metricCount + ' on';
  document.getElementById('startBtn').disabled = !selected || runBusy;
  document.getElementById('snapBtn').disabled = !selected;
  document.getElementById('procBtn').disabled = !selected;
  document.getElementById('layoutBtn').disabled = !selected;
}

function setLaunchState(active) {
  var btn = document.getElementById('startBtn');
  btn.classList.toggle('loading', active);
  document.getElementById('startLabel').textContent = active ? 'Starting measurement…' : 'Launch performance run';
  btn.disabled = active || !selected || runBusy;
}

function startRun() {
  var msg = document.getElementById('msg');
  msg.textContent = '';
  if (!selected) { msg.textContent = 'Select a device first.'; document.getElementById('devices').focus(); return; }
  var p = new URLSearchParams();
  p.set('serial', selected);
  p.set('duration', document.getElementById('duration').value);
  p.set('interval', document.getElementById('interval').value);
  p.set('zap', document.getElementById('zap').checked);
  p.set('zapMs', document.getElementById('zapMs').value);
  if (document.getElementById('zapCount').value) p.set('zapCount', document.getElementById('zapCount').value);
  p.set('package', document.getElementById('package').value);
  p.set('label', document.getElementById('label').value);
  p.set('outputDir', document.getElementById('outputDir').value);
  p.set('logcat', document.getElementById('logcat').checked);
  p.set('jank', document.getElementById('jank').checked);
  p.set('profile', document.getElementById('profile').checked);
  p.set('perfetto', document.getElementById('perfetto').checked);
  p.set('cpuUtil', document.getElementById('m_cpu').checked);
  p.set('cpuIdle', document.getElementById('m_cpuidle').checked);
  p.set('perCore', document.getElementById('m_percore').checked);
  p.set('memoryMb', document.getElementById('m_mem').checked);
  p.set('memoryPct', document.getElementById('m_mempct').checked);
  p.set('swap', document.getElementById('m_swap').checked);
  p.set('zoneinfo', document.getElementById('m_zone').checked);
  p.set('psi', document.getElementById('m_psi').checked);
  p.set('load', document.getElementById('m_load').checked);
  p.set('pageFaults', document.getElementById('m_pgfault').checked);
  p.set('reclaim', document.getElementById('m_reclaim').checked);
  p.set('thermal', document.getElementById('m_thermal').checked);
  p.set('cpuFreq', document.getElementById('m_freq').checked);
  p.set('network', document.getElementById('m_network').checked);
  p.set('topProcs', rootMetricEnabled('m_topprocs'));
  p.set('topMem', document.getElementById('m_topmem').checked ? 'true' : 'false');
  p.set('playback', document.getElementById('m_playback').checked ? 'true' : 'false');
  p.set('appPss', document.getElementById('m_pss').checked);
  p.set('appCpu', document.getElementById('m_appcpu').checked);
  p.set('mainCpu', rootMetricEnabled('m_maincpu'));
  p.set('mediaCpu', rootMetricEnabled('m_mediacpu'));
  setLaunchState(true);
  fetch('/api/start', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: p.toString() })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      if (d.ok) { window.location = '/dashboard?v=' + Date.now(); }
      else { msg.textContent = d.error || 'Failed to start'; setLaunchState(false); }
    })
    .catch(function () { msg.textContent = 'Request failed'; setLaunchState(false); });
}

// Device snapshot — one-shot STB capabilities capture → PDF with progress bar.
function takeSnapshot() {
  if (!selected) { document.getElementById('msg').textContent = 'Select a device first.'; return; }
  var btn = document.getElementById('snapBtn'); btn.disabled = true;
  var wrap = document.getElementById('snapProgWrap'); wrap.style.display = 'block';
  var bar = document.getElementById('snapProg'); var msg = document.getElementById('snapMsg');
  bar.style.background = '#9e8cff'; bar.style.width = '5%'; msg.textContent = 'Starting capture…';
  var p = new URLSearchParams(); p.set('serial', selected);
  fetch('/api/snapshot', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: p.toString() })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      if (!d.ok) { msg.textContent = d.error || 'Failed to start'; btn.disabled = false; return; }
      pollSnapshot(btn, bar, msg);
    })
    .catch(function () { msg.textContent = 'Request failed'; btn.disabled = false; });
}
function pollSnapshot(btn, bar, msg) {
  fetch('/api/snapshot-status').then(function (r) { return r.json(); }).then(function (d) {
    bar.style.width = Math.max(5, d.progress) + '%';
    if (d.error) { bar.style.background = '#ff7676'; msg.textContent = 'Capture failed: ' + d.error + ' — retry when ready.'; btn.disabled = false; return; }
    if (d.done) {
      bar.style.width = '100%'; bar.style.background = '#61d89c';
      msg.innerHTML = '✓ Snapshot ready — <a href="/api/snapshot-report">open the interactive report</a>';
      msg.querySelector('a').onclick = function (event) { event.preventDefault(); openReport('/api/snapshot-report', btn); };
      btn.disabled = false;
      openReport('/api/snapshot-report', btn);
      return;
    }
    msg.textContent = 'Capturing device capabilities… ' + d.progress + '%';
    setTimeout(function () { pollSnapshot(btn, bar, msg); }, 600);
  }).catch(function () { setTimeout(function () { pollSnapshot(btn, bar, msg); }, 1000); });
}

// Procrank snapshot — one-shot per-process VSS/RSS/PSS/USS capture → interactive HTML.
function takeProcrank() {
  if (!selected) { document.getElementById('msg').textContent = 'Select a device first.'; return; }
  var btn = document.getElementById('procBtn'); btn.disabled = true;
  var wrap = document.getElementById('procProgWrap'); wrap.style.display = 'block';
  var bar = document.getElementById('procProg'); var msg = document.getElementById('procMsg');
  bar.style.background = '#58d6c7'; bar.style.width = '5%'; msg.textContent = 'Starting capture…';
  var p = new URLSearchParams(); p.set('serial', selected);
  fetch('/api/procrank-snapshot', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: p.toString() })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      if (!d.ok) { msg.textContent = d.error || 'Failed to start'; btn.disabled = false; return; }
      pollProcrank(btn, bar, msg);
    })
    .catch(function () { msg.textContent = 'Request failed'; btn.disabled = false; });
}
function pollProcrank(btn, bar, msg) {
  fetch('/api/procrank-status').then(function (r) { return r.json(); }).then(function (d) {
    bar.style.width = Math.max(5, d.progress) + '%';
    if (d.error) { bar.style.background = '#ff7676'; msg.textContent = 'Capture failed: ' + d.error + ' — retry when ready.'; btn.disabled = false; return; }
    if (d.done) {
      bar.style.width = '100%'; bar.style.background = '#61d89c';
      msg.innerHTML = '✓ Procrank ready — <a href="/api/procrank-report">open the interactive report</a>';
      msg.querySelector('a').onclick = function (event) { event.preventDefault(); openReport('/api/procrank-report', btn); };
      btn.disabled = false;
      openReport('/api/procrank-report', btn);
      return;
    }
    msg.textContent = 'Capturing per-process memory… ' + d.progress + '%';
    setTimeout(function () { pollProcrank(btn, bar, msg); }, 600);
  }).catch(function () { setTimeout(function () { pollProcrank(btn, bar, msg); }, 1000); });
}

// Layout Inspector — current UI hierarchy + screenshot in a standalone interactive HTML file.
function takeLayoutInspector() {
  if (!selected) { document.getElementById('msg').textContent = 'Select a device first.'; return; }
  var btn = document.getElementById('layoutBtn'); btn.disabled = true;
  var wrap = document.getElementById('layoutProgWrap'); wrap.style.display = 'block';
  var bar = document.getElementById('layoutProg'); var msg = document.getElementById('layoutMsg');
  bar.style.background = '#e8b66d'; bar.style.width = '5%'; msg.textContent = 'Capturing screen structure…';
  var p = new URLSearchParams(); p.set('serial', selected);
  fetch('/api/layout-inspector', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: p.toString() })
    .then(function (r) { return r.json().then(function (body) { if (!r.ok || body.ok === false) throw new Error(body.error || 'Failed to start'); return body; }); })
    .then(function () { pollLayoutInspector(btn, bar, msg); })
    .catch(function (error) { bar.style.background = '#ff7676'; msg.textContent = error.message || 'Request failed'; btn.disabled = false; });
}
function pollLayoutInspector(btn, bar, msg) {
  fetch('/api/layout-inspector-status').then(function (r) { return r.json(); }).then(function (d) {
    bar.style.width = Math.max(5, d.progress || 0) + '%';
    if (d.error) { bar.style.background = '#ff7676'; msg.textContent = 'Capture failed: ' + d.error + ' — retry when ready.'; btn.disabled = false; return; }
    if (d.done) {
      bar.style.width = '100%'; bar.style.background = '#61d89c';
      msg.innerHTML = '✓ Layout ready — <a href="/api/layout-inspector-report">open the inspector</a>';
      msg.querySelector('a').onclick = function (event) { event.preventDefault(); openReport('/api/layout-inspector-report', btn); };
      btn.disabled = false;
      openReport('/api/layout-inspector-report', btn);
      return;
    }
    msg.textContent = 'Reading hierarchy and screenshot… ' + (d.progress || 0) + '%';
    setTimeout(function () { pollLayoutInspector(btn, bar, msg); }, 500);
  }).catch(function () { setTimeout(function () { pollLayoutInspector(btn, bar, msg); }, 1000); });
}

// Native folder picker (opens an OS dialog on the machine running the app).
function pickFolder() {
  var btn = document.getElementById('browseBtn');
  if (btn.disabled) return;
  btn.disabled = true;
  var orig = btn.innerHTML;
  btn.innerHTML = 'Opening…';
  var cur = encodeURIComponent(document.getElementById('outputDir').value || '');
  fetch('/api/pick-folder?initial=' + cur).then(function (r) { return r.json(); }).then(function (d) {
    btn.disabled = false; btn.innerHTML = orig;
    if (d && d.path) { document.getElementById('outputDir').value = d.path; saveHomeDraft(); updateSummary(); }
    else if (d && d.supported === false) { alert('The folder picker isn’t available here — type the path manually.'); }
  }).catch(function () { btn.disabled = false; btn.innerHTML = orig; });
}

// Prefill the Reports folder with the server's default (writable) location.
fetch('/api/defaults').then(function (r) { return r.json(); }).then(function (d) {
  var el = document.getElementById('outputDir');
  if (el && d && d.reportsDir) { if (!Object.prototype.hasOwnProperty.call(homeDraftValues, 'outputDir')) el.value = d.reportsDir; el.placeholder = d.reportsDir; }
  var b = document.getElementById('buildtag');
  if (b && d && d.build) { b.textContent = d.build; }
  updateSummary();
}).catch(function () {});

Array.prototype.forEach.call(document.querySelectorAll('input'), function (input) {
  input.addEventListener('input', function () { updateSummary(); saveHomeDraft(); });
  input.addEventListener('change', function () { updateSummary(); saveHomeDraft(); });
});
window.addEventListener('beforeunload', saveHomeDraft);
restoreHomeDraft();
applyRootMetricAvailability();
updateSummary();
loadDevices();
setInterval(loadDevices, 5000);
</script>
</body>
</html>
"""
