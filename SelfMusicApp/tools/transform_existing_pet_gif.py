from pathlib import Path
import os
import subprocess

import numpy as np
from PIL import Image, ImageFilter, ImageSequence


ROOT = Path(__file__).resolve().parents[1]
FFMPEG = ROOT / "tmp/video-tools/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe"
INPUT = ROOT / "design/selfmusic-pet-isolated-video-action.gif"
OUTPUT_GIF = ROOT / "design/selfmusic-pet-isolated-video-action-large-transparent.gif"
OUTPUT_WEBP = ROOT / "design/selfmusic-pet-isolated-video-action-large-transparent.webp"
PREVIEW = ROOT / "tmp/pet-existing-gif-transform-preview.png"

SIZE = 512
FPS = 24
ZOOM = 1.15
SOURCE_CENTER = (256.0, 255.0)


def transform_frame(frame: Image.Image) -> Image.Image:
    source = frame.convert("RGBA")
    inverse_scale = 1.0 / ZOOM
    transformed = source.transform(
        (SIZE, SIZE),
        Image.Transform.AFFINE,
        (
            inverse_scale,
            0.0,
            SOURCE_CENTER[0] - (SIZE / 2) * inverse_scale,
            0.0,
            inverse_scale,
            SOURCE_CENTER[1] - (SIZE / 2) * inverse_scale,
        ),
        resample=Image.Resampling.BICUBIC,
    )

    yy, xx = np.mgrid[:SIZE, :SIZE]
    distance = np.sqrt(((xx - 256) / 222.0) ** 2 + ((yy - 235) / 245.0) ** 2)
    blur_amount = np.clip((distance - 0.80) / 0.28, 0.0, 1.0)
    alpha = np.clip((1.24 - distance) / 0.42, 0.0, 1.0)

    blurred = transformed.filter(ImageFilter.GaussianBlur(radius=7))
    blur_mask = Image.fromarray((blur_amount * 255).astype(np.uint8), mode="L")
    softened = Image.composite(blurred, transformed, blur_mask)
    softened.putalpha(Image.fromarray((alpha * 255).astype(np.uint8), mode="L"))
    return softened


def load_frames() -> list[Image.Image]:
    source = Image.open(INPUT)
    return [transform_frame(frame) for frame in ImageSequence.Iterator(source)]


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
            "[animation][palette]paletteuse=dither=sierra2_4a:diff_mode=rectangle:alpha_threshold=58",
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
    frames = load_frames()
    frames[min(round(1.38 * FPS), len(frames) - 1)].save(PREVIEW)
    if os.environ.get("PET_PREVIEW_ONLY") != "1":
        encode(frames, OUTPUT_GIF, webp=False)
        encode(frames, OUTPUT_WEBP, webp=True)
    print(f"frames={len(frames)}")
    print(f"preview={PREVIEW}")
    print(f"gif={OUTPUT_GIF}")
    print(f"webp={OUTPUT_WEBP}")


if __name__ == "__main__":
    main()
