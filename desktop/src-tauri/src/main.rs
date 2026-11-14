// ADB Performance Studio — native macOS shell (Tauri v2).
//
// The UI and all monitoring logic live in the Kotlin engine (the fat jar). This shell:
//   1. starts the engine as a sidecar process on a free loopback port (`--web <port> --sidecar`),
//      using the Java runtime bundled in the app's Resources (falls back to a system `java` in dev);
//   2. shows a splash page, then points the window at http://127.0.0.1:<port>/ once the engine answers;
//   3. saves downloads (PDF report, diagnostics zip) to ~/Downloads and reveals them in Finder;
//   4. opens pop-ups (snapshot reports) and external links in the default browser;
//   5. shuts the engine down cleanly on quit: closing its stdin tells it to finish the active run's
//      report and exit; it is force-killed only if it has not exited after a few seconds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::{SocketAddr, TcpListener, TcpStream};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tauri::path::BaseDirectory;
use tauri::webview::{DownloadEvent, NewWindowResponse};
use tauri::{AppHandle, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder};

/// The running Kotlin engine (None until started / after shutdown).
struct Sidecar(Mutex<Option<Child>>);

fn main() {
    let app = tauri::Builder::default()
        .manage(Sidecar(Mutex::new(None)))
        .setup(|app| {
            let handle = app.handle().clone();
            let downloads = app
                .path()
                .download_dir()
                .unwrap_or_else(|_| std::env::temp_dir());
            let last_download: Arc<Mutex<Option<PathBuf>>> = Arc::new(Mutex::new(None));

            let window = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("ADB Performance Studio")
                .inner_size(1440.0, 920.0)
                .min_inner_size(960.0, 640.0)
                // Keep the window on the engine's pages; anything else goes to the default browser.
                .on_navigation(|url| {
                    if is_internal(url) {
                        true
                    } else {
                        open_external(url.as_str());
                        false
                    }
                })
                // window.open / target=_blank (the interactive snapshot reports) → default browser.
                .on_new_window(|url, _features| {
                    open_external(url.as_str());
                    NewWindowResponse::Deny
                })
                .on_download(move |_webview, event| {
                    match event {
                        DownloadEvent::Requested { destination, .. } => {
                            let name = destination
                                .file_name()
                                .map(|n| n.to_string_lossy().to_string())
                                .unwrap_or_else(|| "download".to_string());
                            let target = unique_path(&downloads, &name);
                            *destination = target.clone();
                            *last_download.lock().unwrap() = Some(target);
                        }
                        DownloadEvent::Finished { success, .. } => {
                            // macOS does not report the saved path here, so we remember it ourselves.
                            if let Some(path) = last_download.lock().unwrap().take() {
                                if success {
                                    let _ = Command::new("open").arg("-R").arg(path).spawn();
                                }
                            }
                        }
                        _ => {}
                    }
                    true
                })
                .build()?;

            // Start the engine off the main thread so the splash paints immediately.
            std::thread::spawn(move || match start_sidecar(&handle) {
                Ok(port) => {
                    let js = format!("window.location.replace('http://127.0.0.1:{}/')", port);
                    let _ = window.eval(&js);
                }
                Err(message) => {
                    let quoted = serde_json::to_string(&message).unwrap_or_else(|_| "\"\"".into());
                    let js = format!("showError({})", quoted);
                    let _ = window.eval(&js);
                }
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building the application");

    app.run(|handle, event| {
        if let RunEvent::Exit = event {
            stop_sidecar(handle);
        }
    });
}

/// Pages served by the engine or by the shell itself.
fn is_internal(url: &tauri::Url) -> bool {
    match url.scheme() {
        "http" | "https" => matches!(url.host_str(), Some("127.0.0.1") | Some("localhost") | Some("tauri.localhost")),
        _ => true, // tauri://, about:, blob:, data:
    }
}

fn open_external(url: &str) {
    let _ = Command::new("open").arg(url).spawn();
}

/// `<dir>/<name>`, or `<dir>/<stem> (2).<ext>` etc. if that already exists.
fn unique_path(dir: &PathBuf, name: &str) -> PathBuf {
    let first = dir.join(name);
    if !first.exists() {
        return first;
    }
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    for n in 2..1000 {
        let candidate = dir.join(format!("{} ({}){}", stem, n, ext));
        if !candidate.exists() {
            return candidate;
        }
    }
    first
}

/// Launch the Kotlin engine and wait until its web server answers. Returns the port.
fn start_sidecar(handle: &AppHandle) -> Result<String, String> {
    // The jar and Java runtime are bundled as resources; env vars override them for development.
    let jar = match std::env::var("ADBMON_JAR") {
        Ok(p) => PathBuf::from(p),
        Err(_) => handle
            .path()
            .resolve("resources/app.jar", BaseDirectory::Resource)
            .map_err(|e| format!("Cannot locate the engine jar: {}", e))?,
    };
    if !jar.exists() {
        return Err(format!("Engine jar not found at {}", jar.display()));
    }
    let bundled_java = handle
        .path()
        .resolve("resources/runtime/bin/java", BaseDirectory::Resource)
        .ok()
        .filter(|p| p.exists());
    let java = match (std::env::var("ADBMON_JAVA"), bundled_java) {
        (Ok(p), _) => PathBuf::from(p),
        (_, Some(p)) => p,
        _ => match std::env::var("JAVA_HOME") {
            Ok(home) => PathBuf::from(home).join("bin").join("java"),
            Err(_) => PathBuf::from("java"),
        },
    };

    let port = free_port()?;
    let mut command = Command::new(&java);
    command
        // The engine uses AWT only for the folder-picker dialog: keep it out of the Dock.
        .arg("-Dapple.awt.UIElement=true")
        .arg("-Xss4m")
        .arg("-jar")
        .arg(&jar)
        .arg("--web")
        .arg(port.to_string())
        .arg("--sidecar");
    // Development aid: ADBMON_ADB=/path/to/scripts/fake-adb.sh runs the app without a device.
    if let Ok(adb) = std::env::var("ADBMON_ADB") {
        command.arg("--adb").arg(adb);
    }
    let mut child = command
        .stdin(Stdio::piped()) // held open for the app's lifetime; EOF tells the engine to exit
        .stdout(Stdio::null()) // the engine writes its own log file (~/ADB Device Monitor/adb-monitor.log)
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("Could not start Java at {}: {}", java.display(), e))?;

    let addr: SocketAddr = ([127, 0, 0, 1], port).into();
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        if TcpStream::connect_timeout(&addr, Duration::from_millis(300)).is_ok() {
            break;
        }
        if let Ok(Some(status)) = child.try_wait() {
            return Err(format!(
                "The engine exited during startup ({}).\nSee ~/ADB Device Monitor/adb-monitor.log\n\njava: {}\njar:  {}",
                status,
                java.display(),
                jar.display()
            ));
        }
        if Instant::now() > deadline {
            let _ = child.kill();
            return Err("The engine did not start within 30 seconds.\nSee ~/ADB Device Monitor/adb-monitor.log".into());
        }
        std::thread::sleep(Duration::from_millis(150));
    }

    *handle.state::<Sidecar>().0.lock().unwrap() = Some(child);
    Ok(port.to_string())
}

fn free_port() -> Result<u16, String> {
    let listener = TcpListener::bind("127.0.0.1:0").map_err(|e| format!("No free local port: {}", e))?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();
    Ok(port)
}

/// Graceful stop: close stdin (engine finalizes the active run and exits), then force-kill on timeout.
fn stop_sidecar(handle: &AppHandle) {
    let child = handle.state::<Sidecar>().0.lock().unwrap().take();
    if let Some(mut child) = child {
        drop(child.stdin.take());
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            match child.try_wait() {
                Ok(Some(_)) => return,
                Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(100)),
                _ => break,
            }
        }
        let _ = child.kill();
        let _ = child.wait();
    }
}
