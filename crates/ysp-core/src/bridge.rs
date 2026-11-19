use serde::Deserialize;
use std::fmt;
use std::time::{Duration, Instant};
use url::Url;

use crate::error::CoreError;

pub const DEFAULT_STREAM_REFRESH_AFTER: Duration = Duration::from_secs(2 * 60 * 60);
const MAX_PLAY_URL_LEN: usize = 8192;

#[derive(Clone, PartialEq, Eq)]
pub struct PlayUrl {
    raw: String,
    host: String,
    https: bool,
}

impl PlayUrl {
    pub fn parse(input: &str) -> Result<Self, CoreError> {
        let raw = input.trim();
        if raw.is_empty() {
            return Err(CoreError::EmptyPlayUrl);
        }
        if raw.len() > MAX_PLAY_URL_LEN {
            return Err(CoreError::PlayUrlTooLong);
        }

        let url = Url::parse(raw)?;
        let scheme = url.scheme().to_ascii_lowercase();
        if scheme != "https" && scheme != "http" {
            return Err(CoreError::UnsupportedScheme(scheme));
        }
        if !url.username().is_empty() || url.password().is_some() {
            return Err(CoreError::PlayUrlHasCredentials);
        }

        let host = url
            .host_str()
            .filter(|host| !host.is_empty())
            .ok_or(CoreError::MissingHost)?
            .to_ascii_lowercase();
        if !url.path().to_ascii_lowercase().ends_with(".m3u8") {
            return Err(CoreError::NotHls);
        }

        Ok(Self {
            raw: raw.to_string(),
            host,
            https: scheme == "https",
        })
    }

    pub fn as_str(&self) -> &str {
        &self.raw
    }

    pub fn host(&self) -> &str {
        &self.host
    }

    pub fn is_https(&self) -> bool {
        self.https
    }
}

impl fmt::Debug for PlayUrl {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PlayUrl")
            .field("host", &self.host)
            .field("https", &self.https)
            .field("query", &"<redacted>")
            .finish()
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct StreamFormat {
    #[serde(default)]
    pub id: Option<i64>,
    #[serde(rename = "fn", default)]
    pub fn_name: Option<String>,
    #[serde(default)]
    pub fnname: Option<String>,
    #[serde(default)]
    pub defnname: Option<String>,
    #[serde(default)]
    pub defnrate: Option<String>,
}

impl StreamFormat {
    pub fn label(&self) -> String {
        self.fnname
            .clone()
            .or_else(|| self.defnname.clone())
            .unwrap_or_else(|| {
                self.fn_name
                    .clone()
                    .unwrap_or_else(|| "未知清晰度".to_string())
            })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LiveInfo {
    pub play_url: PlayUrl,
    pub definition: Option<String>,
    pub formats: Vec<StreamFormat>,
    pub live_pid: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BridgeEvent {
    LiveInfo(LiveInfo),
    Ready {
        url: Option<String>,
    },
    Error {
        code: Option<String>,
        message: String,
    },
}

impl BridgeEvent {
    pub fn from_ipc(raw: &str) -> Result<Self, CoreError> {
        let value: serde_json::Value = serde_json::from_str(raw)?;
        let kind = value
            .get("type")
            .and_then(serde_json::Value::as_str)
            .ok_or(CoreError::MissingField("type"))?;

        match kind {
            "live_info" => {
                let playurl = value
                    .get("playurl")
                    .and_then(serde_json::Value::as_str)
                    .ok_or(CoreError::MissingField("playurl"))?;
                let play_url = PlayUrl::parse(playurl)?;
                let formats = value
                    .get("formats")
                    .cloned()
                    .map(serde_json::from_value)
                    .transpose()?
                    .unwrap_or_default();

                Ok(Self::LiveInfo(LiveInfo {
                    play_url,
                    definition: value
                        .get("defn")
                        .and_then(serde_json::Value::as_str)
                        .map(str::to_owned),
                    formats,
                    live_pid: value
                        .get("livepid")
                        .and_then(serde_json::Value::as_str)
                        .map(str::to_owned),
                }))
            }
            "bridge_ready" => Ok(Self::Ready {
                url: value
                    .get("url")
                    .and_then(serde_json::Value::as_str)
                    .map(str::to_owned),
            }),
            "error" => Ok(Self::Error {
                code: value
                    .get("code")
                    .and_then(serde_json::Value::as_str)
                    .map(str::to_owned),
                message: value
                    .get("message")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or("鉴权桥未知错误")
                    .to_string(),
            }),
            other => Err(CoreError::UnknownBridgeEvent(other.to_string())),
        }
    }
}

#[derive(Debug, Clone)]
pub struct LiveStreamLease {
    pub info: LiveInfo,
    acquired_at: Instant,
}

impl LiveStreamLease {
    pub fn new(info: LiveInfo) -> Self {
        Self {
            info,
            acquired_at: Instant::now(),
        }
    }

    pub fn age(&self) -> Duration {
        self.acquired_at.elapsed()
    }

    pub fn is_stale(&self, max_age: Duration) -> bool {
        self.age() >= max_age
    }

    pub fn is_stale_default(&self) -> bool {
        self.is_stale(DEFAULT_STREAM_REFRESH_AFTER)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_realistic_bridge_message() {
        let raw = r#"{
            "type":"live_info",
            "playurl":"https://live.example.ysp.cctv.cn/opaque/2024078203_web.m3u8?from=player&svrtime=1&pid=600001859",
            "defn":"fhd",
            "livepid":"600001859",
            "formats":[{"id":2024078203,"fn":"fhd","fnname":"蓝光 1080P","defnrate":"1080P"}]
        }"#;
        let event = BridgeEvent::from_ipc(raw).unwrap();
        let BridgeEvent::LiveInfo(info) = event else {
            panic!("expected live_info");
        };
        assert_eq!(info.play_url.host(), "live.example.ysp.cctv.cn");
        assert_eq!(info.definition.as_deref(), Some("fhd"));
        assert_eq!(info.formats[0].label(), "蓝光 1080P");
    }

    #[test]
    fn rejects_non_hls_and_unsafe_urls() {
        assert!(matches!(
            PlayUrl::parse("javascript:alert(1)"),
            Err(CoreError::UnsupportedScheme(_))
        ));
        assert!(matches!(
            PlayUrl::parse("file:///etc/passwd.m3u8"),
            Err(CoreError::UnsupportedScheme(_))
        ));
        assert!(matches!(
            PlayUrl::parse("https://example.com/video.mp4"),
            Err(CoreError::NotHls)
        ));
    }

    #[test]
    fn debug_redacts_query_and_path_secrets() {
        let url =
            PlayUrl::parse("https://cdn.example.com/secret-token/channel.m3u8?ysign=super-secret")
                .unwrap();
        let debug = format!("{url:?}");
        assert!(debug.contains("cdn.example.com"));
        assert!(!debug.contains("super-secret"));
        assert!(!debug.contains("secret-token"));
    }

    #[test]
    fn lease_becomes_stale() {
        let info = LiveInfo {
            play_url: PlayUrl::parse("https://cdn.example.com/a.m3u8").unwrap(),
            definition: None,
            formats: Vec::new(),
            live_pid: None,
        };
        let lease = LiveStreamLease::new(info);
        assert!(!lease.is_stale(Duration::from_secs(60)));
        assert!(lease.is_stale(Duration::ZERO));
    }
}
