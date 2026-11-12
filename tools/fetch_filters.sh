#!/bin/sh
# Reklam filtresi listelerinin en güncelini uygulamaya gömer (ilk açılışta internetsiz de çalışsın diye).
# Uygulama sonrasında listeleri kendisi, sessizce günceller. Listeler: AdGuard (GPL-3.0).
# Bundles the latest ad filter lists into the app (so blocking works offline on first launch).
# The app then keeps them up to date by itself, silently. Lists: AdGuard (GPL-3.0).
set -e
dir="$(dirname "$0")/../app/src/main/assets/adblock"
mkdir -p "$dir"
for id in 2 11 13; do
  url="https://filters.adtidy.org/extension/ublock/filters/$id.txt"
  if curl -fsSL --retry 3 --max-time 120 -o "$dir/$id.tmp" "$url" && head -c 512 "$dir/$id.tmp" | grep -q "! Title"; then
    mv "$dir/$id.tmp" "$dir/$id.txt"
    echo "ok $id $(wc -c < "$dir/$id.txt") bytes"
  else
    rm -f "$dir/$id.tmp"
    echo "skip $id (download failed; the app will fetch it on first launch)"
  fi
done
