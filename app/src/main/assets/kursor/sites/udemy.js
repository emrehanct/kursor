/* Kursor site modülü: Udemy — yalnızca sayfada görünen öğeleri kullanır */
(function () {
  var K = window.Kursor; if (!K) return;
  function clickPurpose(p) { var b = document.querySelector('[data-purpose="' + p + '"]'); if (b) { b.click(); return true; } return false; }
  K.register({
    id: 'udemy',
    match: /(^|\.)udemy\.com$/,
    next: function () { return clickPurpose('go-to-next'); },
    prev: function () { return clickPurpose('go-to-previous'); },
    liveCaption: '[data-purpose="captions-cue-text"]'
  });
})();
