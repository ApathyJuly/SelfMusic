from pathlib import Path
import math
import subprocess

import numpy as np
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
FFMPEG = ROOT / "tmp/video-tools/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe"
OPEN_PATH = ROOT / "app/src/main/res/drawable-nodpi/pet_icon_open.png"
CLOSED_PATH = ROOT / "app/src/main/res/drawable-nodpi/pet_icon_closed.png"
GIF_PATH = ROOT / "design/selfmusic-pet-transparent-action.gif"
WEBP_PATH = ROOT / "design/selfmusic-pet-transparent-action.webp"
PREVIEW_PATH = ROOT / "tmp/pet-transparent-animation-preview.png"

SIZE = 512
FPS = 24
FRAME_COUNT = 184
BASE_CONTENT_SIZE = 465


def trimmed(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    alpha_box = rgba.getchannel("A").getbbox()
    if alpha_box is None:
        raise RuntimeError("Pet asset is fully transparent")
    return rgba.crop(alpha_box)


def make_frames() -> list[Image.Image]:
    open_image = trimmed(Image.open(OPEN_PATH))
    closed_image = trimmed(Image.open(CLOSED_PATH))
    frames = []

    for frame_index in range(FRAME_COUNT):
        seconds = frame_index / FPS
        blink_phase = seconds % 1.85
        source = closed_image if 0.78 <= blink_phase <= 0.93 else open_image
        bob = round(math.sin(seconds * math.tau / 1.56) * 9)
        tilt = math.sin(seconds * math.tau / 2.24) * 5.0
        pulse = 1.0 + math.sin(seconds * math.tau / 1.64) * 0.022
        content_size = round(BASE_CONTENT_SIZE * pulse)

        pet = source.copy()
        pet.thumbnail((content_size, content_size), Image.Resampling.LANCZOS)
        pet = pet.rotate(tilt, resample=Image.Resampling.BICUBIC, expand=True)
        frame = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        x = (SIZE - pet.width) // 2
        y = (SIZE - pet.height) // 2 + bob - 10
        frame.alpha_composite(pet, (x, y))
        frames.append(frame)

    frames[round(1.38 * FPS)].save(PREVIEW_PATH)
    return frames


def encode(frames: list[Image.Image], output: Path, webp: bool) -> None:
    command = [
        str(FFMPEG), "-y", "-v", "error",
        "-f", "rawvideo", "-pix_fmt", "rgba",
        "-s", f"{SIZE}x{SIZE}", "-r", str(FPS), "-i", "-",
    ]
    if webp:
        command += ["-c:v", "libwebp_anim", "-lossless", "0", "-quality", "94", "-loop", "0"]
    else:
        command += [
            "-filter_complex",
            "split[animation][palette_source];"
            "[palette_source]palettegen=max_colors=255:reserve_transparent=1:stats_mode=diff[palette];"
            "[animation][palette]paletteuse=dither=sierra2_4a:diff_mode=rectangle:alpha_threshold=56",
            "-loop", "0",
        ]
    command.append(str(output))
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    assert process.stdin is not None
    for frame in frames:
        process.stdin.write(np.asarray(frame, dtype=np.uint8).tobytes())
    process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError(f"Could not encode {output}")


def main() -> None:
    frames = make_frames()
    encode(frames, GIF_PATH, webp=False)
    encode(frames, WEBP_PATH, webp=True)
    print(f"frames={len(frames)}")
    print(f"gif={GIF_PATH}")
    print(f"webp={WEBP_PATH}")
    print(f"preview={PREVIEW_PATH}")


if __name__ == "__main__":
    main()
