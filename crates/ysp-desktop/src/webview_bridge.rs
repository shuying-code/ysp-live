use anyhow::{bail, Context, Result};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tao::dpi::LogicalSize;
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoop};
use tao::window::WindowBuilder;
use url::Url;
use wry::WebViewBuilder;
use ysp_core::{BridgeEvent, ChannelCatalog};

use crate::logging;
use crate::mpv::MpvController;

const DEFAULT_START_URL: &str = "https://yangshipin.cn/tv/home?pid=600152137";

#[derive(Debug)]
struct Config {
    start_url: String,
    visible: bool,
    run_seconds: Option<u64>,
}

pub fn run() -> Result<()> {
    logging::init();
    if let Some(path) = logging::log_path() {
        logging::info(&format!("日志文件：{}", path.display()));
    }
    let config = Config::parse()?;
    logging::info(&format!(
        "YSP Live 原型启动；WebView={}；mpv={}",
        if config.visible { "可见" } else { "隐藏" },
        crate::mpv::locate_mpv()
            .map(|path| path.display().to_string())
            .unwrap_or_else(|| "未找到".to_string())
    ));

    let event_loop = EventLoop::new();
    let window = WindowBuilder::new()
        .with_title("YSP Live Auth Bridge")
        .with_inner_size(LogicalSize::new(
            if config.visible { 960.0 } else { 2.0 },
            if config.visible { 540.0 } else { 2.0 },
        ))
        .with_visible(config.visible)
        .with_decorations(config.visible)
        .build(&event_loop)
        .context("创建窗口失败")?;

    if !config.visible {
        window.set_visible(false);
    }

    let mpv = Arc::new(Mutex::new(MpvController::new()));
    {
        let guard = mpv.lock().expect("mpv state lock");
        crate::mpv::assert_mpv_available(&guard)?;
        if guard.executable().is_none() {
            bail!("mpv unavailable");
        }
    }

    let fatal = Arc::new(AtomicBool::new(false));
    let handler_mpv = Arc::clone(&mpv);
    let handler_fatal = Arc::clone(&fatal);
    let ipc = move |request: wry::http::Request<String>| {
        let raw = request.body();
        match BridgeEvent::from_ipc(raw) {
            Ok(BridgeEvent::LiveInfo(info)) => {
                logging::info(&format!(
                    "鉴权桥取得 HLS：host={}，清晰度={}，格式数={}",
                    info.play_url.host(),
                    info.definition.as_deref().unwrap_or("unknown"),
                    info.formats.len()
                ));
                let result = handler_mpv
                    .lock()
                    .map_err(|_| anyhow::anyhow!("mpv 状态锁损坏"))
                    .and_then(|mut controller| controller.play(&info.play_url));
                if let Err(error) = result {
                    logging::error(&format!("原生播放启动失败: {error:#}"));
                    handler_fatal.store(true, Ordering::Relaxed);
                }
            }
            Ok(BridgeEvent::Ready { url }) => {
                logging::info(&format!(
                    "鉴权桥页面就绪：{}",
                    url.as_deref().unwrap_or("about:blank")
                ));
            }
            Ok(BridgeEvent::Error { code, message }) => {
                logging::error(&format!(
                    "鉴权桥页面错误：{} {}",
                    code.as_deref().unwrap_or("-"),
                    message
                ));
            }
            Err(error) => {
                logging::error(&format!("拒绝桥消息: {error}"));
            }
        }
    };

    let builder = WebViewBuilder::new()
        .with_url(&config.start_url)
        .with_autoplay(true)
        .with_initialization_script(include_str!("../../../assets/auth_bridge.js"))
        .with_ipc_handler(ipc);

    let _webview = builder.build(&window).context("创建 WebView2 失败")?;
    logging::info("WebView2 已创建，等待官方播放器鉴权");

    let deadline = config
        .run_seconds
        .map(|seconds| Instant::now() + Duration::from_secs(seconds));
    let check_interval = Duration::from_secs(2);
    let handler_mpv = mpv;

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::WaitUntil(Instant::now() + check_interval);

        if fatal.load(Ordering::Relaxed) {
            logging::error("发生致命错误，退出");
            *control_flow = ControlFlow::Exit;
            return;
        }
        if deadline.is_some_and(|deadline| Instant::now() >= deadline) {
            logging::info("达到运行时间限制，退出");
            *control_flow = ControlFlow::Exit;
            return;
        }

        let started = handler_mpv
            .lock()
            .map(|controller| controller.started_once())
            .unwrap_or(false);
        let running = handler_mpv
            .lock()
            .map(|mut controller| controller.is_running())
            .unwrap_or(false);
        if started && !running {
            logging::info("mpv 已结束，退出鉴权桥");
            *control_flow = ControlFlow::Exit;
            return;
        }

        if let Event::WindowEvent {
            event: WindowEvent::CloseRequested,
            ..
        } = event
        {
            *control_flow = ControlFlow::Exit;
        }
    });
}

impl Config {
    fn parse() -> Result<Self> {
        let mut start_url = DEFAULT_START_URL.to_string();
        let mut visible = std::env::var_os("YSP_BRIDGE_VISIBLE").is_some();
        let mut run_seconds = std::env::var("YSP_RUN_SECONDS")
            .ok()
            .and_then(|value| value.parse::<u64>().ok());

        let mut args = std::env::args().skip(1);
        while let Some(argument) = args.next() {
            match argument.as_str() {
                "--help" | "-h" => {
                    print_help();
                    std::process::exit(0);
                }
                "--list-channels" => {
                    let catalog = ChannelCatalog::bundled()?;
                    for channel in &catalog.channels {
                        println!(
                            "{}\t{}\t{}\t{}",
                            channel.kind.label(),
                            channel.name,
                            channel.pid,
                            channel.badge().unwrap_or("-")
                        );
                    }
                    std::process::exit(0);
                }
                "--debug-visible" | "--visible" => visible = true,
                "--channel" => {
                    let name = args.next().context("--channel 需要频道名称，例如 CCTV1")?;
                    let catalog = ChannelCatalog::bundled()?;
                    let channel = catalog
                        .find_name(&name)
                        .with_context(|| format!("找不到频道: {name}"))?;
                    start_url = channel.page_url()?;
                }
                "--pid" => {
                    let pid = args.next().context("--pid 需要频道 PID")?;
                    ysp_core::validate_pid(&pid)?;
                    start_url = format!("https://yangshipin.cn/tv/home?pid={pid}");
                }
                "--url" => {
                    start_url = args.next().context("--url 需要完整地址")?;
                }
                "--run-seconds" => {
                    let value = args.next().context("--run-seconds 需要秒数")?;
                    run_seconds = Some(value.parse().context("--run-seconds 必须是整数")?);
                }
                other if !other.starts_with('-') => {
                    start_url = other.to_string();
                }
                other => bail!("未知参数: {other}"),
            }
        }

        validate_start_url(&start_url)?;

        Ok(Self {
            start_url,
            visible,
            run_seconds,
        })
    }
}

fn validate_start_url(raw: &str) -> Result<()> {
    let url = Url::parse(raw).context("启动 URL 无效")?;
    if url.scheme() != "https" {
        bail!("启动 URL 必须使用 HTTPS");
    }
    let host = url.host_str().context("启动 URL 缺少主机名")?;
    if host != "yangshipin.cn" && !host.ends_with(".yangshipin.cn") {
        bail!("启动 URL 必须位于 yangshipin.cn 域名");
    }
    Ok(())
}

fn print_help() {
    println!(
        "YSP Live 隐藏 WebView + mpv 原型\n\
         \n\
         默认加载: {DEFAULT_START_URL}\n\
         \n\
         参数:\n\
           --channel <名称>     按频道名称启动，例如 CCTV1\n\
           --pid <PID>          按频道 PID 启动\n\
           --url <HTTPS URL>    指定 yangshipin.cn 页面\n\
           --debug-visible      显示 1:1 调试窗口\n\
           --run-seconds <秒>   自动退出，用于自动化验证\n\
           --list-channels      列出内置频道并退出\n\
         \n\
         环境变量:\n\
           YSP_MPV              mpv 可执行文件路径\n\
           YSP_BRIDGE_VISIBLE   非空时显示鉴权 WebView\n\
           YSP_RUN_SECONDS      自动退出秒数\n"
    );
}
