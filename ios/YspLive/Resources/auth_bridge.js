(function () {
  if (window.__yspBridgeInstalled) return;
  window.__yspBridgeInstalled = true;

  var lastUrl = "";
  var lastPid = "";
  var lastDefn = "";
  var captured = false;
  var nativeOwnsStream = false;
  var nudgeTimer = null;
  var nudgeAttempts = 0;
  var focusedVideo = null;
  var requestedPid = "";
  var lastVideoWidth = 0;
  var lastVideoHeight = 0;

  function requestedPidFromLocation() {
    try {
      var match = /[?&]pid=([^&#]+)/.exec(location.href);
      return match ? decodeURIComponent(match[1]) : "";
    } catch (_) {
      return "";
    }
  }

  requestedPid = requestedPidFromLocation();

  function syncNativeMode() {
    try {
      if (
        window.YspAndroidBridge &&
        typeof window.YspAndroidBridge.isOfficialPlayerMode === "function"
      ) {
        nativeOwnsStream = !window.YspAndroidBridge.isOfficialPlayerMode();
        if (nativeOwnsStream) silenceOfficialPlayback();
      }
    } catch (_) {}
  }

  function allowedHost() {
    var host = String(location.hostname || "").toLowerCase();
    return (
      host === "yangshipin.cn" ||
      host.endsWith(".yangshipin.cn") ||
      host === "cctv.com" ||
      host.endsWith(".cctv.com") ||
      host === "cctv.cn" ||
      host.endsWith(".cctv.cn") ||
      host === "cntv.cn" ||
      host.endsWith(".cntv.cn")
    );
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

  function findOfficialVideo() {
    var videos = document.querySelectorAll("video");
    if (!videos.length) return null;
    var best = null;
    var bestArea = 0;
    for (var i = 0; i < videos.length; i += 1) {
      var candidate = videos[i];
      if (!candidate || !candidate.isConnected) continue;
      var rect = candidate.getBoundingClientRect();
      var area = Math.max(0, rect.width) * Math.max(0, rect.height);
      if (!best || area > bestArea) {
        best = candidate;
        bestArea = area;
      }
    }
    return best || videos[0];
  }

  function reportVideoSize(video) {
    if (nativeOwnsStream || !video) return;
    var width = Number(video.videoWidth || 0);
    var height = Number(video.videoHeight || 0);
    if (!isFinite(width) || !isFinite(height) || width <= 0 || height <= 0) {
      return;
    }
    width = Math.round(width);
    height = Math.round(height);
    if (width === lastVideoWidth && height === lastVideoHeight) return;
    lastVideoWidth = width;
    lastVideoHeight = height;
    post({ type: "video_size", width: width, height: height });
  }

  function focusOfficialVideo(force) {
    if (nativeOwnsStream) return false;
    var video = findOfficialVideo();
    if (!video) return false;
    if (!force && video === focusedVideo && video.isConnected) return true;
    focusedVideo = video;
    var node = video;

    while (node && node !== document.body && node.parentElement) {
      var parent = node.parentElement;
      var children = parent.children;
      for (var i = 0; i < children.length; i += 1) {
        var child = children[i];
        if (child === node) continue;
        if (/^(SCRIPT|STYLE|LINK|NOSCRIPT)$/.test(child.tagName)) continue;
        child.style.setProperty("display", "none", "important");
      }

      node.style.setProperty("position", "fixed", "important");
      node.style.setProperty("inset", "0", "important");
      node.style.setProperty("width", "100vw", "important");
      node.style.setProperty("height", "100vh", "important");
      node.style.setProperty("transform", "none", "important");
      node.style.setProperty("overflow", "hidden", "important");
      node.style.setProperty("background", "#000", "important");
      node.style.setProperty("z-index", "2147483647", "important");
      node = parent;
    }

    document.documentElement.style.setProperty("background", "#000", "important");
    document.body.style.setProperty("margin", "0", "important");
    document.body.style.setProperty("overflow", "hidden", "important");
    document.body.style.setProperty("background", "#000", "important");
    video.style.setProperty("width", "100vw", "important");
    video.style.setProperty("height", "100vh", "important");
    video.style.setProperty("object-fit", "contain", "important");
    video.style.setProperty("background", "#000", "important");
    video.controls = false;
    reportVideoSize(video);
    return true;
  }

  window.__yspFocusOfficialVideo = function () {
    return focusOfficialVideo(true);
  };

  function handlePlayerResponse(data) {
    if (!data || typeof data !== "object") return;
    var body = data.data && typeof data.data === "object" ? data.data : data;
    var playurl = body && body.playurl;
    if (typeof playurl !== "string" || !playurl) return;
    var definition = body.defn == null ? "" : String(body.defn);
    var livePid =
      body.livepid == null ? "" : String(body.livepid);
    if (requestedPid && livePid && livePid !== requestedPid) return;
    if (
      playurl === lastUrl &&
      livePid === lastPid &&
      definition === lastDefn
    ) {
      return;
    }
    lastUrl = playurl;
    lastPid = livePid;
    lastDefn = definition;
    captured = true;
    stopNudge();

    post({
      type: "live_info",
      playurl: playurl,
      defn: body.defn || null,
      livepid: livePid || null,
      formats: simplifyFormats(body.formats),
    });

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
    if (nativeOwnsStream) {
      silenceOfficialPlayback();
      return;
    }
    if (captured) {
      stopNudge();
      return;
    }
    nudgeAttempts += 1;
    if (nudgeAttempts > 8) {
      stopNudge();
      return;
    }
    focusOfficialVideo(false);
    var video = findOfficialVideo();
    if (!video) return;
    try {
      video.muted = true;
      if (video.paused) {
        var result = video.play();
        if (result && typeof result.catch === "function") result.catch(function () {});
      }
    } catch (_) {}
  }

  function stopNudge() {
    if (nudgeTimer) {
      clearInterval(nudgeTimer);
      nudgeTimer = null;
    }
  }

  function startNudge() {
    stopNudge();
    nudgeAttempts = 0;
    nudgeTimer = window.setInterval(nudgePlayback, 2000);
    window.setTimeout(nudgePlayback, 600);
  }

  startNudge();
  syncNativeMode();

  document.addEventListener(
    "loadedmetadata",
    function (event) {
      var target = event && event.target;
      if (target && String(target.tagName || "").toUpperCase() === "VIDEO") {
        reportVideoSize(target);
      }
    },
    true,
  );

  document.addEventListener(
    "playing",
    function (event) {
      var target = event && event.target;
      if (target && String(target.tagName || "").toUpperCase() === "VIDEO") {
        reportVideoSize(target);
      }
    },
    true,
  );

  window.addEventListener("resize", function () {
    reportVideoSize(findOfficialVideo());
  });

  document.addEventListener(
    "DOMContentLoaded",
    function () {
      if (!requestedPid) requestedPid = requestedPidFromLocation();
      syncNativeMode();
    },
    { once: true },
  );

  function silenceOfficialPlayback() {
    try {
      document.querySelectorAll("video,audio").forEach(function (video) {
        video.muted = true;
        if (!video.paused) video.pause();
      });
    } catch (_) {}
  }

  window.__yspPauseOfficialVideo = function () {
    silenceOfficialPlayback();
    return true;
  };

  window.__yspResumeOfficialVideo = function () {
    if (nativeOwnsStream) return false;
    var video = findOfficialVideo();
    if (!video) return false;
    try {
      document.querySelectorAll("video,audio").forEach(function (candidate) {
        if (candidate !== video) {
          candidate.muted = true;
          candidate.volume = 0;
        }
      });
      video.muted = false;
      video.volume = 1;
      if (video.paused) {
        var result = video.play();
        if (result && typeof result.catch === "function") result.catch(function () {});
      }
    } catch (_) {}
    focusOfficialVideo(true);
    return true;
  };

  document.addEventListener(
    "play",
    function (event) {
      if (!nativeOwnsStream) return;
      var target = event.target;
      if (!target || !/^(VIDEO|AUDIO)$/.test(target.tagName)) return;
      try {
        target.muted = true;
        target.pause();
      } catch (_) {}
    },
    true,
  );

  document.addEventListener(
    "playing",
    function (event) {
      if (nativeOwnsStream) return;
      var target = event.target;
      if (!target || !/^(VIDEO|AUDIO)$/.test(target.tagName)) return;
      focusOfficialVideo(false);
      post({ type: "playing" });
    },
    true,
  );

  document.addEventListener(
    "waiting",
    function (event) {
      if (nativeOwnsStream) return;
      var target = event.target;
      if (!target || target.tagName !== "VIDEO") return;
      post({ type: "buffering" });
    },
    true,
  );

  document.addEventListener(
    "error",
    function (event) {
      if (nativeOwnsStream) return;
      var target = event && event.target;
      if (!target || target.tagName !== "VIDEO") return;
      var code = target.error ? target.error.code : 0;
      post({
        type: "playback_error",
        message: "video error " + String(code),
      });
    },
    true,
  );

  window.__yspSetNativeMode = function (enabled) {
    nativeOwnsStream = Boolean(enabled);
    if (nativeOwnsStream) silenceOfficialPlayback();
  };

  function prepareChannelSwitch(pid) {
    requestedPid = String(pid || "");
    captured = false;
    lastUrl = "";
    lastPid = "";
    lastDefn = "";
    focusedVideo = null;
    silenceOfficialPlayback();
    startNudge();
  }

  function findTvComponent() {
    var app =
      document.getElementById("app") ||
      document.querySelector("#app") ||
      document.body;
    var root = app && app.__vue__;
    if (!root) return null;
    var stack = [root];
    var visited = [];
    while (stack.length) {
      var vm = stack.pop();
      if (!vm || visited.indexOf(vm) >= 0) continue;
      visited.push(vm);
      if (typeof vm.changeTV === "function") return vm;
      if (vm.$children && vm.$children.length) {
        for (var i = 0; i < vm.$children.length; i += 1) {
          stack.push(vm.$children[i]);
        }
      }
    }
    return null;
  }

  function findTvChannel(vm, pid) {
    var groups = [vm.tabA, vm.tabB];
    for (var i = 0; i < groups.length; i += 1) {
      var group = groups[i];
      if (!Array.isArray(group)) continue;
      for (var j = 0; j < group.length; j += 1) {
        if (group[j] && String(group[j].pid) === pid) return group[j];
      }
    }
    return null;
  }

  function eachVueComponent(visitor) {
    var app =
      document.getElementById("app") ||
      document.querySelector("#app") ||
      document.body;
    var root = app && app.__vue__;
    if (!root) return null;
    var stack = [root];
    var visited = [];
    while (stack.length) {
      var vm = stack.pop();
      if (!vm || visited.indexOf(vm) >= 0) continue;
      visited.push(vm);
      if (visitor(vm)) return vm;
      if (vm.$children && vm.$children.length) {
        for (var i = 0; i < vm.$children.length; i += 1) {
          stack.push(vm.$children[i]);
        }
      }
    }
    return null;
  }

  function findLiveQualityPlayer(pid) {
    return eachVueComponent(function (vm) {
      var config = vm && vm.videoConfig;
      if (!config || typeof vm.getLiveUrlsByVid !== "function") return false;
      return !pid || String(config.pid) === pid;
    });
  }

  /**
   * Ask the official player to fetch a specific stream definition. The host
   * calls this to select the highest available quality and to step down when
   * native playback fails.
   */
  window.__yspApplyLiveQuality = function (definition) {
    var target = String(definition || "");
    if (!target) return false;
    var pid = requestedPid || requestedPidFromLocation();
    var vm = findLiveQualityPlayer(pid);
    if (!vm) return false;
    // A lower rung may resolve to the same URL as the initial stream. Clear
    // deduplication here so the host still receives that stream and can play it.
    captured = true;
    lastUrl = "";
    lastPid = "";
    lastDefn = "";
    stopNudge();
    try {
      var result = vm.getLiveUrlsByVid(
        String(vm.videoConfig.pid),
        vm.videoConfig.vid,
        target,
        0,
      );
      if (result && typeof result.catch === "function") {
        result.catch(function () {});
      }
      return true;
    } catch (_) {
      try {
        if (typeof vm.onChangeQuality === "function") {
          vm.onChangeQuality({ fn: target });
          return true;
        }
      } catch (_) {}
      return false;
    }
  };

  window.__yspSwitchOfficialChannel = function (pid) {
    var targetPid = String(pid || "");
    if (!targetPid) return false;
    var vm = findTvComponent();
    if (!vm) return false;
    var channel = findTvChannel(vm, targetPid);
    if (!channel) return false;
    if (vm.tvIndex && String(vm.tvIndex.pid) === targetPid) {
      requestedPid = targetPid;
      return true;
    }
    try {
      prepareChannelSwitch(targetPid);
      vm.changeTV(channel);
      return true;
    } catch (_) {
      return false;
    }
  };

  window.addEventListener("error", function (event) {
    if (captured) return;
    var message = String((event && event.message) || "page error");
    if (/AbortError/.test(message) && /play\(\) request was interrupted/.test(message)) {
      return;
    }
    post({
      type: "error",
      code: "page_error",
      message: message.slice(0, 300),
    });
  });

  window.addEventListener("unhandledrejection", function (event) {
    if (captured) return;
    var message = String((event && event.reason) || "unhandled rejection");
    if (/AbortError/.test(message) && /play\(\) request was interrupted/.test(message)) {
      return;
    }
    post({
      type: "error",
      code: "unhandled_rejection",
      message: message.slice(0, 300),
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

