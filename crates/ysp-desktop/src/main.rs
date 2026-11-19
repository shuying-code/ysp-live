mod logging;
mod mpv;
mod webview_bridge;

fn main() {
    if let Err(error) = webview_bridge::run() {
        logging::init();
        logging::error(&format!("{error:#}"));
        std::process::exit(1);
    }
}
