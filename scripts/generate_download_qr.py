from pathlib import Path

import qrcode
from qrcode.image.pure import PyPNGImage


DOWNLOAD_URL = "https://github.com/JaafarTanoukhi/EtherDrop-Mobile/releases/latest/download/EtherDrop.apk"


if __name__ == "__main__":
    output = Path(__file__).resolve().parents[1] / "assets" / "download-qr.png"
    image = qrcode.make(DOWNLOAD_URL, image_factory=PyPNGImage, box_size=8, border=4)
    image.save(str(output))
    print(f"Generated {output.name} for {DOWNLOAD_URL}")
