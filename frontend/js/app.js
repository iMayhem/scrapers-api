/* Moovie frontend — TMDB browse/search + scraper backend + player */
(function () {
  "use strict";

  const CFG = window.CONFIG;
  const $ = (id) => document.getElementById(id);

  const state = {
    listType: "movie", // home tab
    searchType: "movie",
    media: null, // current TMDB media
    tmdbId: null,
    isTv: false,
    season: 1,
    episode: 1,
    scraping: false,
    abort: null,
  };

  // ── TMDB helpers ────────────────────────────────────────────────────────
  async function tmdb(path, params = {}) {
    const qs = new URLSearchParams({ api_key: CFG.tmdbKey, ...params });
    const res = await fetch(`${CFG.tmdbBase}${path}?${qs}`);
    if (!res.ok) throw new Error("TMDB " + res.status);
    return res.json();
  }

  const img = (p, size = "w342") => (p ? `${CFG.tmdbImg}/${size}${p}` : "");

  // ── Render ──────────────────────────────────────────────────────────────
  function cardFor(item) {
    const title = item.title || item.name;
    const year = (item.release_date || item.first_air_date || "").slice(0, 4);
    const div = document.createElement("div");
    div.className = "card";
    div.innerHTML = `
      <img src="${img(item.poster_path)}" loading="lazy" alt="" />
      <div class="c-title">${title}</div>
      <div class="c-year">${year || ""}</div>`;
    div.onclick = () => openMedia(item);
    return div;
  }

  function renderResults(list) {
    const grid = $("results");
    grid.innerHTML = "";
    if (!list.length) {
      grid.innerHTML = '<div class="spinner">No results</div>';
      return;
    }
    list.forEach((it) => grid.appendChild(cardFor(it)));
  }

  // ── Home / search ───────────────────────────────────────────────────────
  async function loadHome() {
    $("homeTitle").textContent = "Trending";
    const data = await tmdb(`/trending/${state.listType}/week`);
    renderResults(data.results || []);
  }

  async function doSearch() {
    const q = $("searchInput").value.trim();
    if (!q) return;
    $("homeTitle").textContent = `Search: "${q}"`;
    const data = await tmdb(`/search/${state.searchType}`, { query: q, include_adult: false });
    renderResults(data.results || []);
  }

  // ── Media detail + episode picker ───────────────────────────────────────
  async function openMedia(item) {
    const isTv = !!item.first_air_date || item.media_type === "tv" || item.name;
    state.media = item;
    state.isTv = isTv;
    state.tmdbId = item.id;
    state.season = 1;
    state.episode = 1;

    const title = item.title || item.name;
    $("mPoster").src = img(item.poster_path, "w342");
    $("mTitle").textContent = title;
    const year = (item.release_date || item.first_air_date || "").slice(0, 4);
    const rating = item.vote_average ? `⭐ ${item.vote_average.toFixed(1)}` : "";
    $("mMeta").textContent = `${isTv ? "TV Show" : "Movie"} • ${year} ${rating}`;
    $("mOverview").textContent = item.overview || "";

    $("episodePicker").classList.toggle("hidden", !isTv);
    if (isTv) await loadSeasons();

    clearLog();
    clearStreams();
    $("streamCount").textContent = "";
    $("modal").classList.remove("hidden");
  }

  async function loadSeasons() {
    const d = await tmdb(`/tv/${state.tmdbId}`);
    const seasons = (d.seasons || []).filter((s) => s.season_number > 0);
    const sSel = $("seasonSelect");
    sSel.innerHTML = "";
    seasons.forEach((s) => {
      const opt = document.createElement("option");
      opt.value = s.season_number;
      opt.textContent = `Season ${s.season_number} (${s.episode_count || "?"} eps)`;
      sSel.appendChild(opt);
    });
    await loadEpisodes();
  }

  async function loadEpisodes() {
    const season = +$("seasonSelect").value;
    state.season = season;
    const d = await tmdb(`/tv/${state.tmdbId}/season/${season}`);
    const eps = d.episodes || [];
    const eSel = $("episodeSelect");
    eSel.innerHTML = "";
    eps.forEach((e) => {
      const opt = document.createElement("option");
      opt.value = e.episode_number;
      opt.textContent = `E${e.episode_number} — ${e.name || ""}`;
      eSel.appendChild(opt);
    });
  }

  // ── Scrape (live NDJSON stream from backend) ───────────────────────────
  function logLine(text, cls = "info") {
    const div = document.createElement("div");
    div.className = "log-line " + cls;
    div.textContent = text;
    $("logLines").appendChild(div);
    $("logLines").scrollTop = $("logLines").scrollHeight;
  }

  function clearLog() { $("logLines").innerHTML = ""; }
  function clearStreams() { $("streamList").innerHTML = ""; }

  async function scrape() {
    if (state.scraping) return;
    const media = state.media;
    const title = media.title || media.name;
    const year = (media.release_date || media.first_air_date || "").slice(0, 4);
    const type = state.isTv ? "show" : "movie";

    state.scraping = true;
    $("scrapeBtn").disabled = true;
    $("scrapeBtn").textContent = "⏳ Scraping…";
    clearLog();
    clearStreams();
    $("streamCount").textContent = "";
    logLine(`🔍 ${title} (${year}) [${type}] — streaming live from ${CFG.backendUrl}…`);
    logLine(`Requesting: /api/scrape?title=${encodeURIComponent(title)}&year=${year}&type=${type}${state.isTv ? `&season=${state.season}&episode=${state.episode}` : ""}`);

    const params = new URLSearchParams({ title, year, type });
    if (state.isTv) {
      params.set("season", state.season);
      params.set("episode", state.episode);
    }
    params.set("stream", "true");

    const abort = new AbortController();
    state.abort = abort;
    let count = 0;

    try {
      const res = await fetch(`${CFG.backendUrl}/api/scrape?${params}`, { signal: abort.signal });
      if (!res.ok || !res.body) throw new Error("HTTP " + res.status);
      logLine("Connected — receiving live stream events…");

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, idx).trim();
          buf = buf.slice(idx + 1);
          if (!line) continue;
          try {
            const evt = JSON.parse(line);
            handleStreamEvent(evt);
            count++;
          } catch (e) { /* partial */ }
        }
      }
      logLine(`✅ Scrape finished. ${count} link(s) found.`, "link");
    } catch (e) {
      if (e.name !== "AbortError") logLine(`❌ Scrape failed: ${e.message}`, "err");
    } finally {
      state.scraping = false;
      state.abort = null;
      $("scrapeBtn").disabled = false;
      $("scrapeBtn").textContent = "🔍 Find Streams";
    }
  }

  function handleStreamEvent(evt) {
    if (evt.msgType === "stream") {
      const s = evt;
      const url = s.url || "";
      logLine(`▶ [${s.type || "?"}] ${s.quality || "Auto"} — ${s.server || "?"}`, "link");
      addStreamCard(s);
    }
  }

  function addStreamCard(s) {
    const div = document.createElement("div");
    div.className = "stream-item";
    const badge = s.type === "hls" ? "badge-hls" : s.type === "dash" ? "badge-dash" : "badge-mp4";
    div.innerHTML = `
      <span class="stream-badge ${badge}">${(s.type || "mp4").toUpperCase()}</span>
      <div class="stream-info">
        <div class="stream-server">${s.quality || "Auto"} — ${s.server || "unknown"}</div>
        <div class="stream-meta">${s.latencyMs ? `${s.latencyMs} ms` : ""} • ${shortUrl(s.url || "")}</div>
      </div>
      <button class="stream-play">▶ Play</button>`;
    div.querySelector(".stream-play").onclick = () => play(s);
    $("streamList").appendChild(div);
    $("streamCount").textContent = `(${$("streamList").children.length})`;
  }

  function shortUrl(u) {
    try { return new URL(u).host + new URL(u).pathname.slice(0, 40); } catch { return u.slice(0, 50); }
  }

  // ── Playback (JW Player or HTML5, via backend proxy token) ─────────────
  async function play(stream) {
    $("playerTitle").textContent = `${stream.server} — ${stream.quality || "Auto"}`;
    $("playerModal").classList.remove("hidden");
    $("playerStatus").textContent = "Minting proxy token…";

    let proxyUrl = stream.url;
    try {
      const res = await fetch(`${CFG.backendUrl}/api/token`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url: stream.url, headers: stream.headers || {} }),
      });
      const data = await res.json();
      if (data.token) {
        proxyUrl = `${CFG.backendUrl}/api/proxy?token=${data.token}`;
        $("playerStatus").textContent = "Proxy token ready. Starting playback…";
      }
    } catch (e) {
      $("playerStatus").textContent = "Token failed, playing direct URL.";
    }

    initPlayer(proxyUrl);
  }

  function initPlayer(url) {
    const wrap = $("playerWrap");
    wrap.innerHTML = "";

    // JW Player if loaded (config key + script), else HTML5 fallback
    if (typeof window.jwplayer === "function") {
      const jw = window.jwplayer(wrap.id || "playerWrap");
      jw.setup({
        file: url,
        width: "100%",
        aspectratio: "16:9",
        primary: "html5",
      });
    } else {
      const video = document.createElement("video");
      video.controls = true;
      video.autoplay = true;
      video.playsInline = true;
      video.src = url;
      wrap.appendChild(video);
    }
  }

  // ── Boot JW Player if key configured ───────────────────────────────────
  function bootJW() {
    if (!CFG.jwKey) {
      $("playerStatus").textContent = "JW Player key not set — using HTML5 player.";
      return;
    }
    const s = document.createElement("script");
    s.src = `https://cdn.jwplayer.com/libraries/${CFG.jwKey}.js`;
    s.async = true;
    document.head.appendChild(s);
  }

  // ── Backend status ─────────────────────────────────────────────────────
  async function checkBackend() {
    try {
      const res = await fetch(`${CFG.backendUrl}/api/providers`);
      const d = await res.json();
      $("backendStatus").textContent = `✅ backend: ${d.total} providers online`;
      $("backendStatus").className = "status ok";
    } catch (e) {
      $("backendStatus").textContent = "❌ backend offline";
      $("backendStatus").className = "status err";
    }
  }

  // ── Wire up events ─────────────────────────────────────────────────────
  function init() {
    $("searchBtn").onclick = doSearch;
    $("searchInput").addEventListener("keydown", (e) => { if (e.key === "Enter") doSearch(); });
    $("searchType").onchange = (e) => { state.searchType = e.target.value; };
    document.querySelectorAll(".tab").forEach((t) => {
      t.onclick = () => {
        document.querySelectorAll(".tab").forEach((x) => x.classList.remove("active"));
        t.classList.add("active");
        state.listType = t.dataset.list;
        loadHome();
      };
    });

    $("scrapeBtn").onclick = scrape;
    $("closeModal").onclick = () => $("modal").classList.add("hidden");
    $("closePlayer").onclick = () => { $("playerModal").classList.add("hidden"); $("playerWrap").innerHTML = ""; };
    $("seasonSelect").onchange = loadEpisodes;
    $("episodeSelect").onchange = (e) => { state.episode = +e.target.value; };
    $("episodeGo").onclick = () => {
      state.episode = +$("episodeSelect").value;
      scrape();
    };

    // Close modals on backdrop click
    [$("modal"), $("playerModal")].forEach((m) => {
      m.addEventListener("click", (e) => { if (e.target === m) m.classList.add("hidden"); });
    });

    bootJW();
    checkBackend();
    loadHome();
  }

  document.addEventListener("DOMContentLoaded", init);
})();