use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

static LOG_FILE: OnceLock<Mutex<File>> = OnceLock::new();
static LOG_PATH: OnceLock<PathBuf> = OnceLock::new();

pub fn init() {
    let base = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(PathBuf::from))
        .unwrap_or_else(std::env::temp_dir)
        .join("ysp-live");

    if std::fs::create_dir_all(&base).is_err() {
        return;
    }

    let path = base.join("ysp-live.log");
    let Ok(file) = OpenOptions::new().create(true).append(true).open(&path) else {
        return;
    };

    let _ = LOG_PATH.set(path);
    let _ = LOG_FILE.set(Mutex::new(file));
}

pub fn log_path() -> Option<&'static PathBuf> {
    LOG_PATH.get()
}

pub fn info(message: &str) {
    let elapsed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let line = format!("[{elapsed}] {message}");
    eprintln!("{line}");

    if let Some(file) = LOG_FILE.get() {
        if let Ok(mut file) = file.lock() {
            let _ = writeln!(file, "{line}");
            let _ = file.flush();
        }
    }
}

pub fn error(message: &str) {
    info(&format!("ERROR {message}"));
}
