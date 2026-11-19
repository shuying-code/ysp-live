(function () {
  if (window.__yspBridgeInstalled) return;
  window.__yspBridgeInstalled = true;

  var lastUrl = "";
  var captured = false;

  function allowedHost() {
    var host = String(location.hostname || "").toLowerCase();
    return host === "yangshipin.cn" || host.endsWith(".yangshipin.cn");
  }

  function post(payload) {
    if (!allowedHost()) return;
    var text;
    try {
      text = JSON.stringify(payload);
    } catch (_) {
      return;
    }

    try {
      if (window.ipc && typeof window.ipc.postMessage === "function") {
        window.ipc.postMessage(text);
      } else if (
        window.YspAndroidBridge &&
        typeof window.YspAndroidBridge.postMessage === "function"
      ) {
        window.YspAndroidBridge.postMessage(text);
      } else if (
        window.chrome &&
        window.chrome.webview &&
        typeof window.chrome.webview.postMessage === "function"
      ) {
        window.chrome.webview.postMessage(text);
      }
    } catch (_) {
      // The host may have destroyed the bridge while the page was unloading.
    }
  }

  function simplifyFormats(formats) {
    if (!Array.isArray(formats)) return [];
    return formats.map(function (item) {
      return {
        id: item && item.id,
        fn: item && item.fn,
        fnname: item && item.fnname,
        defnname: item && item.defnname,
        defnrate: item && item.defnrate,
      };
    });
  }

  function handlePlayerResponse(data) {
    if (!data || typeof data !== "object") return;
    var body = data.data && typeof data.data === "object" ? data.data : data;
    var playurl = body && body.playurl;
    if (typeof playurl !== "string" || !playurl) return;
    if (playurl === lastUrl) return;
    lastUrl = playurl;
    captured = true;

    post({
      type: "live_info",
      playurl: playurl,
      defn: body.defn || null,
      livepid:
        body.livepid == null ? null : String(body.livepid),
      formats: simplifyFormats(body.formats),
    });

    // The native player now owns playback. Keep the page alive for refresh,
    // but stop the official <video> so the stream is not downloaded twice.
    window.setTimeout(function () {
      try {
        document.querySelectorAll("video").forEach(function (video) {
          video.muted = true;
          video.pause();
        });
      } catch (_) {}
    }, 1200);
  }

  function parseMaybeJson(text) {
    if (typeof text !== "string") return null;
    try {
      return JSON.parse(text);
    } catch (_) {
      return null;
    }
  }

  function isLiveInfoUrl(url) {
    try {
      var parsed = new URL(String(url), location.href);
      return /\/v1\/player\/get_live_info\/?$/.test(parsed.pathname);
    } catch (_) {
      return String(url || "").indexOf("/v1/player/get_live_info") >= 0;
    }
  }

  var originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__yspRequestUrl = String(url || "");
    return originalOpen.apply(this, arguments);
  };

  var originalSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function () {
    var xhr = this;
    if (isLiveInfoUrl(xhr.__yspRequestUrl)) {
      xhr.addEventListener("load", function () {
        try {
          if (typeof xhr.responseText !== "string") return;
          var data = parseMaybeJson(xhr.responseText);
          if (data) handlePlayerResponse(data);
        } catch (_) {}
      });
    }
    return originalSend.apply(this, arguments);
  };

  if (typeof window.fetch === "function") {
    var originalFetch = window.fetch;
    window.fetch = function (input, init) {
      var url =
        typeof input === "string"
          ? input
          : input && input.url
            ? input.url
            : "";
      var promise = originalFetch.apply(this, arguments);
      if (!isLiveInfoUrl(url)) return promise;

      return promise.then(function (response) {
        try {
          response
            .clone()
            .json()
            .then(function (data) {
              handlePlayerResponse(data);
            })
            .catch(function () {});
        } catch (_) {}
        return response;
      });
    };
  }

  function nudgePlayback() {
    if (captured) return;
    try {
      var videos = document.querySelectorAll("video");
      for (var i = 0; i < videos.length; i += 1) {
        videos[i].muted = true;
        var result = videos[i].play();
        if (result && typeof result.catch === "function") result.catch(function () {});
      }
    } catch (_) {}
  }

  window.setInterval(nudgePlayback, 1500);
  window.setTimeout(nudgePlayback, 800);

  window.addEventListener("error", function (event) {
    post({
      type: "error",
      code: "page_error",
      message: String((event && event.message) || "page error").slice(0, 300),
    });
  });

  window.addEventListener("unhandledrejection", function (event) {
    post({
      type: "error",
      code: "unhandled_rejection",
      message: String((event && event.reason) || "unhandled rejection").slice(0, 300),
    });
  });

  function announceReady() {
    post({ type: "bridge_ready", url: location.href });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", announceReady, { once: true });
  } else {
    announceReady();
  }
})();

