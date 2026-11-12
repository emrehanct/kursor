# Uygulama simgesini ve TV ana ekran afişini üretir (GitHub Actions derlemesinde çalışır).
# Generates the launcher icon and the Android TV home-screen banner (runs in CI).
#   tools/icon.b64   -> kare simge / square icon (JPEG, base64)
#   tools/banner.b64 -> 16:9 afiş / banner (JPEG, base64)
from PIL import Image
import base64, io, os

res = "app/src/main/res"


def load(path):
    return Image.open(io.BytesIO(base64.b64decode(open(path).read()))).convert("RGB")


icon = load("tools/icon.b64")
banner = load("tools/banner.b64")

for sub, (w, h) in [("drawable", (320, 180)), ("drawable-xhdpi", (640, 360))]:
    os.makedirs(os.path.join(res, sub), exist_ok=True)
    banner.resize((w, h), Image.LANCZOS).save(os.path.join(res, sub, "banner.png"))

for sub, size in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144)]:
    os.makedirs(os.path.join(res, sub), exist_ok=True)
    icon.resize((size, size), Image.LANCZOS).save(os.path.join(res, sub, "ic_launcher.png"))

print("icons ready / simgeler hazir")
