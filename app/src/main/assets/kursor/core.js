/*
 * Kursor — genel video katmanı / generic video layer
 * Her sayfada çalışır. Sayfadaki ana videoyu bulur ve kumanda komutlarını uygular.
 * Siteye özel davranışlar (sonraki ders, altyazı kaynağı) assets/kursor/sites/*.js
 * dosyalarındaki modüllerden gelir; modül yoksa genel yöntemler kullanılır.
 * Runs on every page. Finds the main video and applies remote-control commands.
 * Site-specific behaviour (next lesson, subtitle source) comes from the modules in
 * assets/kursor/sites/*.js; without a module the generic fallbacks are used.
 */
(function () {
  if (window.Kursor && window.Kursor.version) return;

  var sites = [];
  var K = { version: 1 };

  /** Site modülü kaydı / register a site module: {id, match: RegExp(host), next, prev, cues, title} */
  K.register = function (m) { sites.push(m); };

  K.site = function () {
    var h = location.hostname;
    for (var i = 0; i < sites.length; i++) if (sites[i].match.test(h)) return sites[i];
    return null;
  };

  // ---------------------------------------------------------------- video bulma / finding the video

  function collectVideos(root, out, depth) {
    if (!root || depth > 6) return;
    var vs = root.querySelectorAll ? root.querySelectorAll('video') : [];
    for (var i = 0; i < vs.length; i++) out.push(vs[i]);
    // açık shadow DOM'lar / open shadow roots (ör. bazı oynatıcı bileşenleri)
    var all = root.querySelectorAll ? root.querySelectorAll('*') : [];
    for (var j = 0; j < all.length; j++) if (all[j].shadowRoot) collectVideos(all[j].shadowRoot, out, depth + 1);
    // aynı kökenli iframe'ler / same-origin iframes
    var fs = root.querySelectorAll ? root.querySelectorAll('iframe') : [];
    for (var k = 0; k < fs.length; k++) {
      try { var d = fs[k].contentDocument; if (d) collectVideos(d, out, depth + 1); } catch (e) { /* başka köken / cross-origin */ }
    }
  }

  function area(v) {
    var r = v.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return 0;
    var st = v.ownerDocument.defaultView.getComputedStyle(v);
    if (st.visibility === 'hidden' || st.display === 'none') return 0;
    return r.width * r.height;
  }

  /** Ana video: oynayan en büyük video, yoksa en büyük görünür video. */
  K.video = function () {
    var vs = []; collectVideos(document, vs, 0);
    var best = null, score = 0;
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i], a = area(v);
      if (!a) continue;
      var s = a * (v.paused ? 1 : 4) * (v.duration > 0 ? 2 : 1);
      if (s > score) { score = s; best = v; }
    }
    return best;
  };

  /** Sayfanın "video modu" için uygun olup olmadığı: görünür alanın %25'inden büyük video var mı? */
  K.state = function () {
    var v = K.video(), site = K.site();
    if (!v) {
      var em = K.embed();
      if (!em) return JSON.stringify({ video: false, site: site ? site.id : null });
      var fr = em.frame.getBoundingClientRect();
      return JSON.stringify({
        video: true, embed: em.kind,
        big: (fr.width * fr.height) / Math.max(1, window.innerWidth * window.innerHeight) > 0.25,
        t: embedInfo.currentTime || 0, d: embedInfo.duration || 0,
        paused: embedInfo.playerState !== undefined ? embedInfo.playerState !== 1 : embedInfo.paused !== false,
        rate: embedInfo.playbackRate || 1, site: site ? site.id : null,
        hasNext: !!(site && site.next) || !!findNav(true), tracks: 0
      });
    }
    var r = v.getBoundingClientRect();
    var vw = Math.max(1, window.innerWidth * window.innerHeight);
    return JSON.stringify({
      video: true,
      big: (r.width * r.height) / vw > 0.25,
      t: v.currentTime || 0, d: isFinite(v.duration) ? v.duration : 0,
      paused: v.paused, rate: v.playbackRate,
      site: site ? site.id : null,
      hasNext: !!(site && site.next) || !!findNav(true),
      tracks: v.textTracks ? v.textTracks.length : 0
    });
  };

  // ---------------------------------------------------------------- komutlar / commands

  // ---------------------------------------------------------------- gömülü oynatıcılar / embedded players
  // Başka kökenden gömülü YouTube ve Vimeo oynatıcılarına, oynatıcıların kendi resmi
  // postMessage arayüzüyle komut gönderilir (ör. Khan Academy dersleri YouTube gömülüdür).
  // Cross-origin YouTube/Vimeo embeds are driven through their official postMessage APIs.
  var embedInfo = {};
  window.addEventListener('message', function (e) {
    try {
      var d = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
      if (d && d.event === 'infoDelivery' && d.info) for (var k in d.info) embedInfo[k] = d.info[k];
      if (d && d.event === 'playProgress' && d.data) { embedInfo.currentTime = d.data.seconds; embedInfo.duration = d.data.duration; }
    } catch (x) { /* başka mesaj / unrelated message */ }
  });

  K.embed = function () {
    var fs = document.querySelectorAll('iframe'), best = null, ba = 0;
    for (var i = 0; i < fs.length; i++) {
      var src = fs[i].src || '', kind = null;
      if (/youtube(-nocookie)?\.com\/embed\//.test(src)) kind = 'yt';
      else if (/player\.vimeo\.com\/video\//.test(src)) kind = 'vimeo';
      if (!kind) continue;
      var a = area(fs[i]);
      if (a > ba) { ba = a; best = { frame: fs[i], kind: kind }; }
    }
    if (best && best.kind === 'yt' && !best.frame.__kursorListen) {
      best.frame.__kursorListen = true;
      best.frame.contentWindow.postMessage(JSON.stringify({ event: 'listening', id: 'kursor' }), '*');
    }
    return best;
  };

  function embedCmd(em, a, val) {
    var w = em.frame.contentWindow;
    if (em.kind === 'yt') {
      var send = function (func, args) { w.postMessage(JSON.stringify({ event: 'command', func: func, args: args || [] }), '*'); };
      if (a === 'toggle') send(embedInfo.playerState === 1 ? 'pauseVideo' : 'playVideo');
      else if (a === 'play') send('playVideo');
      else if (a === 'pause') send('pauseVideo');
      else if (a === 'seek') send('seekTo', [Math.max(0, (embedInfo.currentTime || 0) + Number(val)), true]);
      else if (a === 'rate') send('setPlaybackRate', [Number(val)]);
    } else {
      var vm = function (method, value) { w.postMessage(JSON.stringify(value === undefined ? { method: method } : { method: method, value: value }), '*'); };
      if (a === 'toggle') vm(embedInfo.paused === false ? 'pause' : 'play'), embedInfo.paused = !(embedInfo.paused === false);
      else if (a === 'play') vm('play');
      else if (a === 'pause') vm('pause');
      else if (a === 'seek') vm('setCurrentTime', Math.max(0, (embedInfo.currentTime || 0) + Number(val)));
      else if (a === 'rate') vm('setPlaybackRate', Number(val));
      vm('addEventListener', 'playProgress');
    }
    return 'embed';
  }

  K.cmd = function (a, val) {
    var v = K.video();
    if (!v) { var em = K.embed(); return em ? embedCmd(em, a, val) : 'novideo'; }
    switch (a) {
      case 'toggle': if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function () {}); } else v.pause(); break;
      case 'play': v.play(); break;
      case 'pause': v.pause(); break;
      case 'seek': v.currentTime = Math.max(0, Math.min((v.duration || 1e9) - 0.5, v.currentTime + Number(val))); break;
      case 'rate': v.playbackRate = Number(val); K._rate = Number(val); break;
      case 'fit': K.fit(val); break;
    }
    return v.paused ? 'paused' : 'playing';
  };

  // Bazı oynatıcılar yeni videoda hızı 1'e döndürür; seçilen hız korunur.
  // Some players reset the rate on every new video; keep the chosen rate.
  K._rate = 0;
  setInterval(function () {
    if (!K._rate) return;
    var v = K.video();
    if (v && Math.abs(v.playbackRate - K._rate) > 0.01 && !v.paused) v.playbackRate = K._rate;
  }, 1500);

  /** Görüntü: contain (sığdır) / cover (doldur) / zoom oranı */
  K.fit = function (mode) {
    var v = K.video(); if (!v) return;
    if (mode === 'contain' || mode === 'cover') { v.style.objectFit = mode; v.style.transform = ''; }
    else { v.style.objectFit = 'contain'; v.style.transform = 'scale(' + Number(mode) + ')'; }
  };

  // ---------------------------------------------------------------- önceki / sonraki
  var NEXT = /^(next|sonraki|ileri|siguiente|suivant|weiter|próximo|go to next item|next lecture|next video|sonraki ders|sonraki video|bir sonraki)\b/i;
  var PREV = /^(previous|prev|önceki|geri|anterior|précédent|zurück|go to previous item|previous lecture|önceki ders)\b/i;

  function labelOf(el) {
    return ((el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (el.innerText || '')).trim();
  }

  function findNav(next) {
    var re = next ? NEXT : PREV;
    var els = document.querySelectorAll('a[href],button,[role=button],[role=link]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i], r = el.getBoundingClientRect();
      if (!r.width || !r.height || el.disabled) continue;
      var l = labelOf(el);
      if (l && l.length < 60 && re.test(l)) return el;
    }
    return null;
  }

  /** dir: 1 sonraki / -1 önceki. Önce site modülü, sonra genel arama. */
  K.go = function (dir) {
    var s = K.site();
    if (s && (dir > 0 ? s.next : s.prev)) { var r = (dir > 0 ? s.next : s.prev)(); if (r) return 'site'; }
    var el = findNav(dir > 0);
    if (el) { el.click(); return 'generic'; }
    return 'none';
  };

  // ---------------------------------------------------------------- altyazı / subtitles

  /**
   * Altyazı satırlarını verir: Promise<[{s,e,text}]>.
   * Önce site modülü (ör. YouTube), yoksa videonun kendi metin kanalları (<track>, HLS altyazıları).
   */
  K.cues = function (prefLang) {
    var s = K.site();
    if (s && s.cues) return Promise.resolve(s.cues(prefLang)).then(function (c) { return c && c.length ? c : trackCues(prefLang); });
    return trackCues(prefLang);
  };

  function trackCues(prefLang) {
    return new Promise(function (res) {
      var v = K.video(); if (!v || !v.textTracks || !v.textTracks.length) return res([]);
      var tt = v.textTracks, pick = null;
      for (var i = 0; i < tt.length; i++) {
        var t = tt[i]; if (t.kind !== 'subtitles' && t.kind !== 'captions') continue;
        if (!pick) pick = t;
        if (prefLang && (t.language || '').toLowerCase().indexOf(prefLang) === 0) { pick = t; break; }
      }
      if (!pick) return res([]);
      if (pick.mode === 'disabled') pick.mode = 'hidden';
      var tries = 0;
      (function wait() {
        var c = pick.cues;
        if (c && c.length) {
          var out = [];
          for (var j = 0; j < c.length; j++) out.push({ s: c[j].startTime, e: c[j].endTime, text: (c[j].text || '').replace(/<[^>]+>/g, '') });
          return res(out);
        }
        if (++tries > 20) return res([]);
        setTimeout(wait, 250);
      })();
    });
  }

  /**
   * Canlı altyazı: oynatıcının o an ekranda gösterdiği altyazı metni (site modülündeki
   * liveCaption seçicisiyle). Altyazı dosyası alınamayan sitelerde satır satır çeviri için.
   * Live caption: the caption text the player is showing right now (site module selector).
   */
  K.liveCue = function () {
    var s = K.site(), sel = (s && s.liveCaption) || '.vjs-text-track-cue, .jw-text-track-cue, .plyr__caption, .shaka-text-container';
    var els = document.querySelectorAll(sel), out = [];
    for (var i = 0; i < els.length; i++) { var t = (els[i].textContent || '').replace(/\s+/g, ' ').trim(); if (t) out.push(t); }
    return out.join('\n');
  };

  K.cueLanguages = function () {
    var v = K.video(), out = [];
    if (v && v.textTracks) for (var i = 0; i < v.textTracks.length; i++) out.push(v.textTracks[i].language || v.textTracks[i].label || '?');
    return out;
  };

  window.Kursor = K;
})();
