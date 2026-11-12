/* Kursor site modülü: YouTube (youtube.com, youtube-nocookie.com, m.youtube.com) */
(function () {
  var K = window.Kursor; if (!K) return;
  function player() { return document.querySelector('#movie_player'); }
  K.register({
    id: 'youtube',
    match: /(^|\.)youtube(-nocookie)?\.com$/,
    next: function () { var b = document.querySelector('.ytp-next-button'); if (b && b.offsetParent !== null) { b.click(); return true; } return false; },
    prev: function () { var b = document.querySelector('.ytp-prev-button'); if (b && b.offsetParent !== null) { b.click(); return true; } history.back(); return true; },
    liveCaption: '.ytp-caption-segment',
    /** Oynatıcının altyazı kanallarından metin (sayfanın kendi verdiği adresler) */
    cues: function (lang) {
      var p = player(), pr = p && p.getPlayerResponse && p.getPlayerResponse();
      var tr = (pr && pr.captions && pr.captions.playerCaptionsTracklistRenderer && pr.captions.playerCaptionsTracklistRenderer.captionTracks) || [];
      if (!tr.length) return [];
      var pick = tr[0];
      for (var i = 0; i < tr.length; i++) if (lang && tr[i].languageCode.indexOf(lang) === 0 && tr[i].kind !== 'asr') { pick = tr[i]; break; }
      return fetch(pick.baseUrl + '&fmt=json3', { credentials: 'include' }).then(function (r) { return r.json(); }).then(function (j) {
        var out = [];
        (j.events || []).forEach(function (e) {
          if (!e.segs) return;
          var t = e.segs.map(function (s) { return s.utf8 || ''; }).join('').replace(/\n/g, ' ').trim();
          if (t) out.push({ s: e.tStartMs / 1000, e: (e.tStartMs + (e.dDurationMs || 2000)) / 1000, text: t });
        });
        return out;
      }).catch(function () { return []; });
    }
  });
})();
