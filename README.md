# Kursor

**Eğitim videoları için kumandayla kullanılan Android TV tarayıcısı.**
*A remote-driven Android TV browser for learning videos.*

Kursor bağımsız bir tarayıcıdır; içinde açılan sitelerle bağlantısı yoktur. Siteleri kendi hesabınızla ve o sitelerin kurallarına göre kullanırsınız.
*Kursor is an independent browser and is not affiliated with the websites opened in it.*

## Özellikler / Features
- **İmleç modu:** yön tuşlarıyla ekranda imleç, OK ile tıklama, kenara gelince sayfa kaydırma
- **Video modu:** sayfada büyük bir video oynayınca kendiliğinden açılır
  - OK: alt menü (önceki/sonraki, ±10 sn, oynat/durdur, hız 0,75–2×, altyazı, görüntü, tam ekran)
  - ◀ ▶: 10 sn geri/ileri · ▼: tam ekran · ▲: imleç (tam ekranda: sığdır/doldur/yakınlaştır)
- **Altyazı çevirisi (10 dil):** videonun altyazı dosyası varsa tamamı önceden cümle cümle çevrilir; yoksa ekrandaki altyazı satırı anında çevrilir. Otomatik / Kapalı / Her zaman açık modları, yazı boyutu.
- **Her sitede çalışır:** düz HTML5 videolar, Video.js / JW Player / Plyr oynatıcıları, gömülü YouTube ve Vimeo oynatıcıları
- **Site modülleri:** popüler platformlar için ek davranışlar (sonraki ders düğmesi, altyazı kaynağı)
- **Reklam engelleme / Ad blocking:** dahili, bağımlılıksız Java süzgeci (`AdBlocker.java`) AdGuard filtre listelerini (Base, Mobile Ads, Turkish) kullanır; reklam isteklerini engeller, reklam kutularını gizler. Listeler uygulamaya gömülü gelir ve sonra arka planda sessizce güncellenir (`Filters.java`). Telefonda 🛡 düğmesi, TV'de Menü'yü basılı tutmak: bu sitede/her yerde kapat. — Built-in, dependency-free Java filter using AdGuard lists; bundled at build time, then silently auto-updated in the background. 🛡 button (phone) / hold Menu (TV): turn off per site or everywhere.
- **Ana sayfa düğmesi / Home button:** köşede, kenarlardan boşluklu yüzen ⌂; telefonda sürüklenip sağ/sol kenara konabilir, TV'de imleçle tıklanır. — Floating ⌂ in a corner with edge margins; draggable on phones, cursor-clickable on TV.

## Mimari / Architecture
```
app/src/main/assets/kursor/
  core.js        Genel video katmanı: ana videoyu bulur, komutları uygular (window.Kursor)
  sites/*.js     Site modülleri: Kursor.register({id, match, next, prev, cues, liveCaption})
  subtitles.js   Altyazı çeviri katmanı
  home.html      Ana sayfa
app/src/main/java/app/kursor/MainActivity.java   WebView, imleç, kumanda, alt menü
```

### Yeni site modülü eklemek / Adding a site module
`assets/kursor/sites/` altına bir dosya ekleyin:
```js
(function () {
  var K = window.Kursor; if (!K) return;
  K.register({
    id: 'ornek',
    match: /(^|\.)ornek\.com$/,          // site adresi
    next: function () { /* sonraki ders düğmesine tıkla, başarılıysa true */ },
    prev: function () { /* önceki */ },
    liveCaption: '.altyazi-satiri',      // isteğe bağlı: ekrandaki altyazı öğesi
    cues: function (lang) { return []; } // isteğe bağlı: [{s, e, text}] altyazı satırları
  });
})();
```
Modüller yalnızca sayfada görünen öğeleri ve oynatıcının kendi verdiği bilgileri kullanmalıdır.

| Modül | Durum |
|---|---|
| YouTube | Test edildi: oynat/durdur, hız, sarma, sonraki video, altyazı dosyası |
| Udemy | Test edildi: hız, sarma, önceki/sonraki ders |
| Khan Academy | Test edildi: gömülü YouTube oynatıcısında hız ve sarma, sonraki ders |
| Coursera | Giriş gerektirdiği için gerçek hesapla doğrulanmadı |
| BTK Akademi | Giriş gerektirdiği için gerçek hesapla doğrulanmadı |

## Kurulum / Install
[Releases](../../releases/latest) sayfasından `Kursor.apk` dosyasını indirip TV'ye kurun. GitHub sürümü yeni sürümleri kendisi bulur ve kurar.
`Kursor-GooglePlay.aab` yalnızca Google Play Console içindir, TV'ye kurulmaz.

## Derleme / Build
`main` dalına her gönderimde GitHub Actions imzalı APK ve Play paketi (AAB) derler. İmza anahtarı depoda değil, `SIGNING_KEYSTORE_B64` ve `SIGNING_PASSWORD` gizli ayarlarındadır (anahtar takma adı: `kursor`).

## Gizlilik / Privacy
Uygulama veri toplamaz; analiz, reklam veya izleme yazılımı içermez. Ayrıntılar: [PRIVACY.md](PRIVACY.md)

## Lisans / License
GPL-3.0

Reklam filtresi listeleri / ad filter lists: [AdGuard Filters](https://github.com/AdguardTeam/AdguardFilters) (GPL-3.0), EasyList dahil / including EasyList.
