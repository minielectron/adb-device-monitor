package devicemonitor.presentation.web

/**
 * Tiny script injected into every page: forwards browser-side failures (uncaught errors, unhandled
 * promise rejections, console.error) to POST /api/clientlog so they appear in adb-monitor.log next
 * to the server-side lines. Throttled client-side; never throws.
 */
internal const val CLIENT_LOG_JS = """
(function () {
  var sent = 0, last = '';
  function send(kind, msg) {
    try {
      msg = String(msg);
      if (msg === last || sent > 50) return;
      last = msg; sent++;
      var body = kind + ' @ ' + location.pathname + ': ' + msg;
      if (navigator.sendBeacon) navigator.sendBeacon('/api/clientlog', body);
      else fetch('/api/clientlog', { method: 'POST', body: body }).catch(function () {});
    } catch (e) {}
  }
  window.addEventListener('error', function (e) {
    send('error', (e.message || 'error') + ' (' + (e.filename || '?') + ':' + (e.lineno || 0) + ':' + (e.colno || 0) + ')' +
      (e.error && e.error.stack ? ' | ' + e.error.stack : ''));
  });
  window.addEventListener('unhandledrejection', function (e) {
    var r = e.reason;
    send('unhandledrejection', r && r.stack ? r.stack : r);
  });
  var orig = console.error;
  console.error = function () {
    try { send('console.error', Array.prototype.slice.call(arguments).map(function (a) { return a && a.stack ? a.stack : String(a); }).join(' ')); } catch (e) {}
    return orig.apply(console, arguments);
  };
})();
"""
