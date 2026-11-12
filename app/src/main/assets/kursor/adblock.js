/*
 * Kursor reklam gizleme (kozmetik süzme): reklam kutularını CSS ile gizler.
 * Kursor ad hiding (cosmetic filtering): hides ad boxes with CSS.
 * Kurallar uygulamanın içindeki süzgeçten (KursorAd köprüsü) gelir; ağ isteği yapılmaz.
 * Rules come from the in-app filter (KursorAd bridge); no network requests are made.
 */
(function () {
  if (!window.KursorAd || window.__kursorAd) return;
  window.__kursorAd = true;
  var host = location.hostname;
  var sent = Object.create(null);
  var style = document.createElement('style');
  style.id = 'kursor-ab';

  function attach() {
    if (!style.parentNode) (document.head || document.documentElement).appendChild(style);
  }
  function add(css) {
    if (!css) return;
    style.appendChild(document.createTextNode(css));
    attach();
  }

  try { add(KursorAd.base(host)); } catch (e) { }

  var pending = [];
  function collect(el) {
    if (!el || el.nodeType !== 1) return;
    var id = el.id;
    if (id && typeof id === 'string' && !sent['#' + id]) { sent['#' + id] = 1; pending.push('#' + id); }
    var cl = el.classList;
    if (cl) for (var i = 0; i < cl.length; i++) {
      var k = '.' + cl[i];
      if (!sent[k]) { sent[k] = 1; pending.push(k); }
    }
  }
  function flush() {
    timer = 0;
    if (!pending.length) return;
    var keys = pending; pending = [];
    try { add(KursorAd.tokens(host, JSON.stringify(keys))); } catch (e) { }
  }
  var timer = 0;
  function later() { if (!timer) timer = setTimeout(flush, 250); }

  var all = document.querySelectorAll('[id],[class]');
  for (var i = 0; i < all.length; i++) collect(all[i]);
  flush();

  new MutationObserver(function (muts) {
    for (var m = 0; m < muts.length; m++) {
      var mu = muts[m];
      if (mu.type === 'attributes') { collect(mu.target); continue; }
      for (var n = 0; n < mu.addedNodes.length; n++) {
        var node = mu.addedNodes[n];
        if (node.nodeType !== 1) continue;
        collect(node);
        if (node.querySelectorAll) {
          var sub = node.querySelectorAll('[id],[class]');
          for (var s = 0; s < sub.length; s++) collect(sub[s]);
        }
      }
    }
    if (!style.parentNode) attach();   // site stili kaldırdıysa geri koy / re-attach if removed
    if (pending.length) later();
  }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
})();
