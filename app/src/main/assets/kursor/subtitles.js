/*
 * Kursor — altyazı çeviri katmanı / subtitle translation overlay
 * Kaynak sırası / source order:
 *   1) Altyazı dosyası: site modülünün cues() işlevi ya da videonun kendi metin kanalları
 *      (Kursor.cues). Tamamı önceden cümle cümle çevrilir.
 *   2) Canlı altyazı: dosya yoksa oynatıcının ekranda gösterdiği satır anında çevrilir.
 * Modlar: 'auto' (video zaten hedef dildeyse gösterme), 'on', 'off'.
 * Çeviri uygulama üzerinden yapılır (KursorSub köprüsü); sayfa çeviri sunucusuna doğrudan gitmez.
 */
(function () {
  if (window.__kSub) { window.__kSub.kick(); return; }
  var K = window.Kursor; if (!K) return;
  var S = window.__kSub = { mode: 'auto', lang: 'tr' };
  var cbs = {}, seq = 0, cur = null, box = null, span = null, lastText = null;
  var liveCache = {}, livePending = {};

  window.__kSubCb = function (id, ok, data) { var f = cbs[id]; delete cbs[id]; if (f) f(ok, data); };
  function bridge(method, arg) {
    return new Promise(function (res, rej) {
      var id = 'k' + (++seq);
      cbs[id] = function (ok, data) { if (ok) res(data); else rej(new Error(data)); };
      try { KursorSub[method](id, arg); } catch (e) { delete cbs[id]; rej(e); }
    });
  }
  function status(m) { if (S.mode !== 'off') { try { KursorSub.status(m); } catch (e) {} } }

  // ---------------------------------------------------------------- görünüm / view
  function ui() {
    if (!document.getElementById('k-sub-style')) {
      var st = document.createElement('style');
      st.id = 'k-sub-style';
      var site = K.site(), own = (site && site.liveCaption) || '.vjs-text-track-display,.jw-captions,.plyr__captions';
      st.textContent =
        '#k-sub{position:fixed!important;left:0;right:0;bottom:6vh;z-index:2147483647!important;' +
        'text-align:center;pointer-events:none}' +
        '#k-sub span{display:inline-block;max-width:88vw;background:rgba(0,0,0,.75);color:#fff;' +
        'font-family:sans-serif;font-weight:600;font-size:calc(2.7vw * var(--k-subscale, 1));line-height:1.35;' +
        'padding:.25em .7em;border-radius:.3em;white-space:pre-line}' +
        // Sitenin kendi altyazısı: metni okunabilsin diye yalnız görünmez yapılır (opacity).
        // The site's own captions stay readable for live mode, just invisible.
        'html.k-sub-on ' + own.split(',').join(',html.k-sub-on ') + '{opacity:0!important}';
      (document.head || document.documentElement).appendChild(st);
    }
    if (!box || !box.isConnected) {
      box = document.createElement('div'); box.id = 'k-sub';
      span = document.createElement('span'); box.appendChild(span);
      document.documentElement.appendChild(box);
    }
  }
  function show(t) {
    if (!t && !box) return;
    if (t === lastText && box && box.isConnected) return;
    lastText = t; ui();
    span.textContent = t;
    box.style.display = t ? 'block' : 'none';
  }

  // ---------------------------------------------------------------- gruplama / grouping
  function group(cues) {
    var g = [], c = null;
    cues.forEach(function (q) {
      if (c && q[0] - c[1] > 1.5) { g.push(c); c = null; }
      if (!c) c = [q[0], q[1], q[2]];
      else { c[1] = Math.max(c[1], q[1]); c[2] += ' ' + q[2]; }
      if (/[.?!…]["')\]]?$/.test(c[2]) || c[1] - c[0] > 6.5 || c[2].length > 150) { g.push(c); c = null; }
    });
    if (c) g.push(c);
    return g;
  }
  function similar(a, b) { return String(a).toLowerCase().replace(/\W/g, '') === String(b).toLowerCase().replace(/\W/g, ''); }

  function translateAll(st) {
    var chunks = [], i = 0;
    while (i < st.groups.length) {
      var start = i, len = 0;
      while (i < st.groups.length && (i === start || len + st.groups[i][2].length < 1400)) { len += st.groups[i][2].length + 1; i++; }
      chunks.push([start, i]);
    }
    var v = K.video(), t = v ? v.currentTime : 0;
    function dist(ch) { var s = st.groups[ch[0]][0], e = st.groups[ch[1] - 1][1]; return t < s ? s - t : (t > e ? 100000 + (t - e) : 0); }
    chunks.sort(function (a, b) { return dist(a) - dist(b); });
    var k = 0, fails = 0;
    function next() {
      if (st !== cur || k >= chunks.length) return Promise.resolve();
      var ch = chunks[k];
      var texts = st.groups.slice(ch[0], ch[1]).map(function (g) { return g[2]; });
      return bridge('translate', JSON.stringify(texts)).then(function (out) {
        var arr = JSON.parse(out), same = 0;
        for (var j = 0; j < arr.length && ch[0] + j < ch[1]; j++) { st.tr[ch[0] + j] = arr[j]; if (similar(arr[j], texts[j])) same++; }
        // İlk parçada satırların çoğu değişmediyse video zaten hedef dilde.
        // If most lines of the first chunk come back unchanged, the video is already in the target language.
        if (k === 0 && arr.length && same / arr.length > 0.7) st.same = true;
        k++; fails = 0;
        if (st.same && S.mode === 'auto') return;
        return next();
      }, function (e) {
        if (++fails > 3) throw e;
        return new Promise(function (r) { setTimeout(r, 4000 * fails); }).then(next);
      });
    }
    return next();
  }

  function videoKey() {
    var v = K.video();
    return location.host + location.pathname + '|' + (v ? Math.round(v.duration || 0) : 0);
  }

  function load(key) {
    var st = cur = { key: key, mode: S.mode, groups: null, tr: [], dead: false, same: false, live: false };
    if (S.mode === 'off') return;
    var ck = 'ksub:' + S.lang + ':' + key;
    try {
      var c = JSON.parse(localStorage.getItem(ck) || 'null');
      if (c && c.g) { st.groups = c.g; st.tr = c.t; st.same = !!c.s; return; }
    } catch (e) {}
    K.cues(S.lang).then(function (cues) {
      if (st !== cur) return;
      if (!cues || !cues.length) { st.live = true; return; }      // dosya yok → canlı altyazı
      st.groups = group(cues.map(function (q) { return [q.s, q.e, q.text]; }));
      st.tr = new Array(st.groups.length);
      status('preparing');
      return translateAll(st).then(function () {
        if (st !== cur) return;
        try { localStorage.setItem(ck, JSON.stringify({ g: st.groups, t: st.tr, s: st.same })); } catch (e) {}
        status('ready');
      });
    }).catch(function () { if (st === cur) { st.dead = true; status('fail'); } });
  }

  // Canlı satır çevirisi / live line translation
  function liveText() {
    var src = K.liveCue();
    if (!src) return '';
    if (liveCache[src] !== undefined) return liveCache[src];
    if (!livePending[src]) {
      livePending[src] = true;
      bridge('translate', JSON.stringify([src])).then(function (out) {
        var tr = JSON.parse(out)[0] || '';
        liveCache[src] = similar(tr, src) && S.mode === 'auto' ? '' : tr;
        delete livePending[src];
      }, function () { delete livePending[src]; });
    }
    return '';
  }

  function find(gs, t) {
    var lo = 0, hi = gs.length - 1, r = -1;
    while (lo <= hi) { var m = (lo + hi) >> 1; if (gs[m][0] <= t) { r = m; lo = m + 1; } else hi = m - 1; }
    return r;
  }

  function tick() {
    var v = K.video();
    if (!v || S.mode === 'off') { show(''); document.documentElement.classList.toggle('k-sub-on', S.mode === 'off' && !!v); return; }
    var key = videoKey();
    if (!cur || cur.key !== key || cur.mode !== S.mode) { show(''); load(key); }
    var txt = '';
    if (cur.live) txt = liveText();
    else if (cur.groups && !(S.mode === 'auto' && cur.same)) {
      var t = v.currentTime, i = find(cur.groups, t);
      if (i >= 0 && t <= cur.groups[i][1] + 0.4 && cur.tr[i]) txt = cur.tr[i];
    }
    document.documentElement.classList.toggle('k-sub-on', !!txt || (cur.live && !!K.liveCue() && S.mode !== 'auto'));
    show(txt);
  }

  S.setMode = function (m) { S.mode = (m === 'on' || m === 'off') ? m : 'auto'; lastText = null; liveCache = {}; tick(); };
  S.setSize = function (k) { var n = parseFloat(k); if (n > 0) document.documentElement.style.setProperty('--k-subscale', String(n)); };
  S.setLang = function (l) { if (l && l !== S.lang) { S.lang = l; cur = null; liveCache = {}; } };
  S.kick = function () { lastText = null; tick(); };
  setInterval(tick, 250);
  tick();
})();
