/* Kursor site modülü: BTK Akademi — genel oynatıcı desteğine ek olarak ders gezinme düğmeleri.
   Not: Ders sayfaları giriş gerektirdiği için seçiciler genel tutuldu; gerçek hesapla doğrulanmalı. */
(function () {
  var K = window.Kursor; if (!K) return;
  function byLabel(re) {
    var els = document.querySelectorAll('a[href],button,[role=button]');
    for (var i = 0; i < els.length; i++) {
      var l = (els[i].getAttribute('aria-label') || els[i].getAttribute('title') || els[i].innerText || '').trim();
      if (l.length < 40 && re.test(l) && els[i].offsetParent !== null) { els[i].click(); return true; }
    }
    return false;
  }
  K.register({
    id: 'btkakademi',
    match: /(^|\.)btkakademi\.gov\.tr$/,
    next: function () { return byLabel(/^(sonraki|ileri|sonraki ders|sonraki içerik)/i); },
    prev: function () { return byLabel(/^(önceki|geri|önceki ders|önceki içerik)/i); }
  });
})();
