use anyhow::{bail, Context, Result};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use ysp_core::PlayUrl;

use crate::logging;

pub struct MpvController {
    executable: Option<PathBuf>,
    child: Option<Child>,
    started_once: bool,
}

impl MpvController {
    pub fn new() -> Self {
        Self {
            executable: locate_mpv(),
            child: None,
            started_once: false,
        }
    }

    pub fn executable(&self) -> Option<&Path> {
        self.executable.as_deref()
    }

    pub fn started_once(&self) -> bool {
        self.started_once
    }

    pub fn is_running(&mut self) -> bool {
        let Some(child) = self.child.as_mut() else {
            return false;
        };
        match child.try_wait() {
            Ok(None) => true,
            Ok(Some(status)) => {
                logging::info(&format!("mpv 已退出: {status}"));
                self.child = None;
                false
            }
            Err(error) => {
                logging::error(&format!("等待 mpv 状态失败: {error}"));
                false
            }
        }
    }

    pub fn play(&mut self, stream: &PlayUrl) -> Result<()> {
        let executable = self
            .executable
            .clone()
            .context("未找到 mpv，可通过 YSP_MPV 指定 mpv.exe 路径")?;

        self.stop();

        let mut command = Command::new(&executable);
        command
            .arg("--no-config")
            .arg("--force-window=yes")
            .arg("--fullscreen=yes")
            .arg("--ontop")
            .arg("--no-border")
            .arg("--no-osc")
            .arg("--keep-open=no")
            .arg("--idle=no")
            .arg("--hwdec=auto-safe")
            .arg("--vo=gpu-next")
            .arg("--cache=yes")
            .arg("--demuxer-max-bytes=128MiB")
            .arg("--network-timeout=15")
            .arg("--volume=100")
            .arg("--title=YSP Live")
            .arg(
                "--http-header-fields=Referer: https://yangshipin.cn/\r\n\
                 User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) \
                 AppleWebKit/537.36 (KHTML, like Gecko) \
                 Chrome/131.0.0.0 Safari/537.36",
            )
            .arg(stream.as_str())
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());

        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }

        let child = command
            .spawn()
            .with_context(|| format!("启动 mpv 失败: {}", executable.display()))?;

        logging::info(&format!(
            "已启动 mpv: {}，HLS 主机 {}",
            executable.display(),
            stream.host()
        ));
        self.child = Some(child);
        self.started_once = true;
        Ok(())
    }

    pub fn stop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

impl Drop for MpvController {
    fn drop(&mut self) {
        self.stop();
    }
}

pub fn locate_mpv() -> Option<PathBuf> {
    let mut candidates = Vec::new();

    if let Some(configured) = std::env::var_os("YSP_MPV") {
        candidates.push(PathBuf::from(configured));
    }

    if let Ok(executable) = std::env::current_exe() {
        if let Some(directory) = executable.parent() {
            candidates.push(directory.join(if cfg!(windows) { "mpv.exe" } else { "mpv" }));
            candidates.push(directory.join("mpv").join(if cfg!(windows) {
                "mpv.exe"
            } else {
                "mpv"
            }));
        }
    }

    if let Some(path) = std::env::var_os("PATH") {
        for directory in std::env::split_paths(&path) {
            candidates.push(directory.join(if cfg!(windows) { "mpv.exe" } else { "mpv" }));
        }
    }

    #[cfg(windows)]
    {
        candidates.push(PathBuf::from(r"C:\Program Files\mpv\mpv.exe"));
        candidates.push(PathBuf::from(r"C:\Program Files (x86)\mpv\mpv.exe"));
        candidates.push(PathBuf::from(
            r"F:\codex工作区\wallpaper-rs\bundled-mpv\mpv.exe",
        ));
    }

    #[cfg(all(unix, not(target_os = "macos")))]
    {
        candidates.push(PathBuf::from("/usr/bin/mpv"));
        candidates.push(PathBuf::from("/usr/local/bin/mpv"));
        candidates.push(PathBuf::from("/snap/bin/mpv"));
    }

    #[cfg(target_os = "macos")]
    {
        candidates.push(PathBuf::from("/opt/homebrew/bin/mpv"));
        candidates.push(PathBuf::from("/usr/local/bin/mpv"));
    }

    candidates.into_iter().find(|candidate| candidate.is_file())
}

pub fn assert_mpv_available(controller: &MpvController) -> Result<()> {
    if controller.executable().is_none() {
        bail!("未找到 mpv。请安装 mpv，或设置环境变量 YSP_MPV 指向 mpv 可执行文件。");
    }
    Ok(())
}
