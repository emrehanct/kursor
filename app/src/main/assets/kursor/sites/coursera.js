/* Kursor site modülü: Coursera (Video.js oynatıcı; altyazılar <track> olarak gelir) */
(function () {
  var K = window.Kursor; if (!K) return;
  function byLabel(re) {
    var els = document.querySelectorAll('button,a[href]');
    for (var i = 0; i < els.length; i++) {
      var l = (els[i].getAttribute('aria-label') || els[i].innerText || '').trim();
      if (re.test(l) && els[i].offsetParent !== null) { els[i].click(); return true; }
    }
    return false;
  }
  K.register({
    id: 'coursera',
    match: /(^|\.)coursera\.org$/,
    next: function () { return byLabel(/^(go to next item|next item|sonraki öğe|sonraki)/i); },
    prev: function () { return byLabel(/^(go to previous item|previous item|önceki öğe|önceki)/i); },
    liveCaption: '.vjs-text-track-cue'
  });
})();
