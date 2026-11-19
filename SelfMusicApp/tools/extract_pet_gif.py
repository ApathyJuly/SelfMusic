from pathlib import Path
import os
import subprocess

import numpy as np
from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
FFMPEG = ROOT / "tmp/video-tools/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe"
VIDEO = Path(r"F:\OBS\workspace\2026-08-18 22-45-55.mp4")
BACKGROUND = ROOT / "tmp/pet-icon-background.png"
OUTPUT = ROOT / "design/selfmusic-pet-transparent-action.gif"
OUTPUT_WEBP = ROOT / "design/selfmusic-pet-transparent-action.webp"
PREVIEW = ROOT / "tmp/pet-isolated-preview.png"
MASK_PREVIEW = ROOT / "tmp/pet-isolated-mask-preview.png"

SIZE = 512
FPS = 24
FOCUS_CROP = (115, -5, 435, 315)


def convex_hull(points: list[tuple[int, int]]) -> list[tuple[int, int]]:
    points = sorted(set(points))
    if len(points) <= 1:
        return points

    def cross(origin, first, second):
        return (first[0] - origin[0]) * (second[1] - origin[1]) - (first[1] - origin[1]) * (second[0] - origin[0])

    lower = []
    for point in points:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], point) <= 0:
            lower.pop()
        lower.append(point)
    upper = []
    for point in reversed(points):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], point) <= 0:
            upper.pop()
        upper.append(point)
    return lower[:-1] + upper[:-1]


def decode_frames() -> list[np.ndarray]:
    command = [
        str(FFMPEG),
        "-v", "error",
        "-i", str(VIDEO),
        "-vf", f"crop=480:480:720:0,fps={FPS},scale={SIZE}:{SIZE}:flags=lanczos",
        "-f", "rawvideo",
        "-pix_fmt", "rgb24",
        "-",
    ]
    process = subprocess.Popen(command, stdout=subprocess.PIPE)
    frame_bytes = SIZE * SIZE * 3
    frames = []
    assert process.stdout is not None
    while True:
        data = process.stdout.read(frame_bytes)
        if len(data) != frame_bytes:
            break
        frames.append(np.frombuffer(data, dtype=np.uint8).reshape(SIZE, SIZE, 3).copy())
    if process.wait() != 0 or not frames:
        raise RuntimeError("Could not decode source video")
    return frames


def make_mask(frame: np.ndarray, temporal_median: np.ndarray) -> Image.Image:
    frame_int = frame.astype(np.int16)
    difference = np.max(np.abs(frame_int - temporal_median), axis=2)
    maximum = frame.max(axis=2)
    minimum = frame.min(axis=2)
    saturation = maximum.astype(np.int16) - minimum.astype(np.int16)
    red = frame[:, :, 0].astype(np.int16)
    green = frame[:, :, 1].astype(np.int16)
    blue = frame[:, :, 2].astype(np.int16)

    yy, xx = np.mgrid[:SIZE, :SIZE]
    effect_roi = ((xx - 256) / 205) ** 2 + ((yy - 112) / 145) ** 2 <= 1.0
    body_roi = ((xx - 265) / 105) ** 2 + ((yy - 140) / 78) ** 2 <= 1.0
    antenna_roi = (xx > 228) & (xx < 300) & (yy > 15) & (yy < 130)
    cyan = (blue > 115) & (green > 95) & (blue + green > red * 1.55)
    magenta = (red > 118) & (blue > 88) & (red + blue > green * 1.65)
    gold = (red > 130) & (green > 78) & (blue < 118) & (red > green * 1.16)
    neon_or_eyes = (cyan | magenta | gold) & (saturation > 45) & effect_roi
    moving_antenna = (difference > 24) & antenna_roi
    color_mask = Image.fromarray((neon_or_eyes.astype(np.uint8) * 255), mode="L")
    color_mask = color_mask.filter(ImageFilter.MaxFilter(7)).filter(ImageFilter.GaussianBlur(radius=3))
    body_mask = Image.new("L", (SIZE, SIZE), 0)
    face_marker = (gold | magenta) & (xx > 185) & (xx < 350) & (yy > 95) & (yy < 190)
    marker_y, marker_x = np.nonzero(face_marker)
    if len(marker_x) >= 8:
        center_x = int(np.median(marker_x))
        center_y = int(np.median(marker_y)) - 4
    else:
        center_x, center_y = 265, 140
    body_draw = ImageDraw.Draw(body_mask)
    body_draw.ellipse(
        (center_x - 100, center_y - 58, center_x + 100, center_y + 68),
        fill=255,
    )
    body_draw.polygon(
        [(center_x - 88, center_y - 30), (center_x - 70, center_y - 92), (center_x - 25, center_y - 55)],
        fill=255,
    )
    body_draw.polygon(
        [(center_x + 28, center_y - 55), (center_x + 70, center_y - 92), (center_x + 90, center_y - 28)],
        fill=255,
    )
    body_mask = body_mask.filter(ImageFilter.GaussianBlur(radius=4))
    antenna_mask = Image.fromarray((moving_antenna.astype(np.uint8) * 255), mode="L")
    antenna_mask = antenna_mask.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.GaussianBlur(radius=3))
    mask_array = np.maximum.reduce([
        np.asarray(color_mask, dtype=np.float32),
        np.asarray(body_mask, dtype=np.float32),
        np.asarray(antenna_mask, dtype=np.float32),
    ])
    mask_array = np.clip((mask_array - 30) * 1.9, 0, 255)

    # Prevent static hair below the pet from surviving broad dilation.
    vertical_fade = np.ones((SIZE, 1), dtype=np.float32)
    vertical_fade[174:, 0] = np.clip(1.0 - (np.arange(174, SIZE) - 174) / 48.0, 0.0, 1.0)
    mask_array *= vertical_fade
    return Image.fromarray(mask_array.astype(np.uint8), mode="L")


def composite_frames(frames: list[np.ndarray], preview_only: bool = False) -> list[Image.Image]:
    sampled = np.stack(frames[::4]).astype(np.int16)
    temporal_median = np.median(sampled, axis=0).astype(np.int16)
    background = Image.open(BACKGROUND).convert("RGB").resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    composites = []

    preview_index = min(round(1.38 * FPS), len(frames) - 1)
    selected_frames = [(preview_index, frames[preview_index])] if preview_only else enumerate(frames)
    for index, frame in selected_frames:
        foreground = Image.fromarray(frame, mode="RGB").convert("RGBA")
        mask = make_mask(frame, temporal_median)
        foreground.putalpha(mask)
        focused_pet = foreground.crop(FOCUS_CROP).resize((SIZE, SIZE), Image.Resampling.LANCZOS)
        composites.append(focused_pet)
        if index == preview_index:
            focused_pet.save(PREVIEW)
            mask.save(MASK_PREVIEW)
    return composites


def encode_gif(frames: list[Image.Image]) -> None:
    command = [
        str(FFMPEG),
        "-y",
        "-v", "error",
        "-f", "rawvideo",
        "-pix_fmt", "rgba",
        "-s", f"{SIZE}x{SIZE}",
        "-r", str(FPS),
        "-i", "-",
        "-filter_complex",
        "split[animation][palette_source];"
        "[palette_source]palettegen=max_colors=255:reserve_transparent=1:stats_mode=diff[palette];"
        "[animation][palette]paletteuse=dither=sierra2_4a:diff_mode=rectangle:alpha_threshold=72",
        "-loop", "0",
        str(OUTPUT),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    assert process.stdin is not None
    for frame in frames:
        process.stdin.write(np.asarray(frame.convert("RGBA"), dtype=np.uint8).tobytes())
    process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError("Could not encode GIF")


def encode_webp(frames: list[Image.Image]) -> None:
    command = [
        str(FFMPEG),
        "-y",
        "-v", "error",
        "-f", "rawvideo",
        "-pix_fmt", "rgba",
        "-s", f"{SIZE}x{SIZE}",
        "-r", str(FPS),
        "-i", "-",
        "-c:v", "libwebp_anim",
        "-lossless", "0",
        "-quality", "92",
        "-loop", "0",
        str(OUTPUT_WEBP),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    assert process.stdin is not None
    for frame in frames:
        process.stdin.write(np.asarray(frame.convert("RGBA"), dtype=np.uint8).tobytes())
    process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError("Could not encode animated WebP")


def main() -> None:
    frames = decode_frames()
    preview_only = os.environ.get("PET_PREVIEW_ONLY") == "1"
    composites = composite_frames(frames, preview_only=preview_only)
    if not preview_only:
        encode_gif(composites)
        encode_webp(composites)
    print(f"frames={len(frames)}")
    print(f"preview={PREVIEW}")
    print(f"mask_preview={MASK_PREVIEW}")
    print(f"output={OUTPUT}")
    print(f"output_webp={OUTPUT_WEBP}")


if __name__ == "__main__":
    main()
