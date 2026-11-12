/* Kursor site modülü: Khan Academy (videolar gömülü YouTube oynatıcısıyla; komutlar K.embed üzerinden) */
(function () {
  var K = window.Kursor; if (!K) return;
  function byLabel(re) {
    var els = document.querySelectorAll('a[href],button');
    for (var i = 0; i < els.length; i++) {
      var l = (els[i].getAttribute('aria-label') || els[i].innerText || '').trim();
      if (re.test(l) && els[i].offsetParent !== null) { els[i].click(); return true; }
    }
    return false;
  }
  K.register({
    id: 'khanacademy',
    match: /(^|\.)khanacademy\.org$/,
    next: function () { return byLabel(/^(next in course|up next|sonraki)/i); },
    prev: function () { history.back(); return true; }
  });
})();
