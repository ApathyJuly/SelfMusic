from collections import deque
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageOps, ImageStat


ROOT = Path(__file__).resolve().parents[1]
OPEN_PATH = ROOT / "app/src/main/res/drawable-nodpi/pet_icon_open.png"
CLOSED_PATH = ROOT / "app/src/main/res/drawable-nodpi/pet_icon_closed.png"
BACKGROUND_SOURCE = ROOT / "tmp/pet-icon-background.png"
BACKGROUND_PATH = ROOT / "app/src/main/res/drawable-nodpi/ic_launcher_background.png"
FOREGROUND_PATH = ROOT / "app/src/main/res/drawable-nodpi/ic_launcher_foreground.png"
DESIGN_PATH = ROOT / "design/selfmusic-pet-app-icon.png"
PREVIEW_PATH = ROOT / "design/selfmusic-pet-animation-preview.gif"
PREVIEW_WEBP_PATH = ROOT / "design/selfmusic-pet-animation-preview.webp"


def connected_components(mask: list[list[bool]]) -> list[tuple[int, int, int, int, int]]:
    height = len(mask)
    width = len(mask[0])
    seen = bytearray(width * height)
    components = []

    for y in range(height):
        for x in range(width):
            offset = y * width + x
            if seen[offset] or not mask[y][x]:
                continue
            queue = deque([(x, y)])
            seen[offset] = 1
            min_x = max_x = x
            min_y = max_y = y
            count = 0

            while queue:
                px, py = queue.popleft()
                count += 1
                min_x = min(min_x, px)
                max_x = max(max_x, px)
                min_y = min(min_y, py)
                max_y = max(max_y, py)
                for nx, ny in ((px - 1, py), (px + 1, py), (px, py - 1), (px, py + 1)):
                    if 0 <= nx < width and 0 <= ny < height:
                        next_offset = ny * width + nx
                        if not seen[next_offset] and mask[ny][nx]:
                            seen[next_offset] = 1
                            queue.append((nx, ny))

            components.append((min_x, min_y, max_x + 1, max_y + 1, count))

    return components


def find_eye_boxes(image: Image.Image) -> list[tuple[int, int, int, int]]:
    rgba = image.convert("RGBA")
    width, height = rgba.size
    pixels = rgba.load()
    mask = []

    for y in range(height):
        row = []
        for x in range(width):
            red, green, blue, alpha = pixels[x, y]
            row.append(
                alpha > 180
                and red > 155
                and green > 85
                and blue < 105
                and red > green * 1.12
                and green > blue * 1.35
            )
        mask.append(row)

    candidates = []
    for x0, y0, x1, y1, count in connected_components(mask):
        box_width = x1 - x0
        box_height = y1 - y0
        if (
            count > 700
            and 50 < box_width < 260
            and 40 < box_height < 240
            and height * 0.46 < y0 < height * 0.82
        ):
            candidates.append((x0, y0, x1, y1, count))

    candidates.sort(key=lambda item: item[4], reverse=True)
    eyes = sorted(candidates[:2], key=lambda item: item[0])
    if len(eyes) != 2:
        raise RuntimeError(f"Expected two eye regions, found: {candidates}")
    return [component[:4] for component in eyes]


def make_closed_frame(open_image: Image.Image) -> Image.Image:
    result = open_image.convert("RGBA").copy()
    eye_boxes = find_eye_boxes(result)

    for x0, y0, x1, y1 in eye_boxes:
        width = x1 - x0
        height = y1 - y0
        center_x = (x0 + x1) // 2
        center_y = (y0 + y1) // 2

        sample_box = (
            max(0, x0 + width // 4),
            max(0, y0 - height // 3),
            min(result.width, x1 - width // 4),
            max(1, y0 - 4),
        )
        sample = result.crop(sample_box).convert("RGB")
        red, green, blue = (round(value) for value in ImageStat.Stat(sample).median)
        face_color = (max(18, red - 6), max(16, green - 6), max(22, blue - 4), 255)

        cover_box = (
            x0 - round(width * 0.10),
            y0 - round(height * 0.10),
            x1 + round(width * 0.10),
            y1 + round(height * 0.10),
        )
        cover_mask = Image.new("L", result.size, 0)
        ImageDraw.Draw(cover_mask).ellipse(cover_box, fill=255)
        cover_mask = cover_mask.filter(ImageFilter.GaussianBlur(radius=max(4, width // 28)))
        face_layer = Image.new("RGBA", result.size, face_color)
        result = Image.composite(face_layer, result, cover_mask)

        arc_width = round(width * 0.78)
        arc_height = round(height * 0.50)
        arc_box = (
            center_x - arc_width // 2,
            center_y - arc_height // 3,
            center_x + arc_width // 2,
            center_y + arc_height * 2 // 3,
        )
        glow = Image.new("RGBA", result.size, (0, 0, 0, 0))
        glow_draw = ImageDraw.Draw(glow)
        stroke = max(9, round(width * 0.075))
        glow_draw.arc(arc_box, start=202, end=338, fill=(255, 180, 36, 210), width=stroke + 8)
        blurred_glow = glow.filter(ImageFilter.GaussianBlur(radius=max(5, width // 24)))
        result = Image.alpha_composite(result, blurred_glow)

        eyelid = Image.new("RGBA", result.size, (0, 0, 0, 0))
        ImageDraw.Draw(eyelid).arc(
            arc_box,
            start=202,
            end=338,
            fill=(255, 196, 44, 255),
            width=stroke,
        )
        result = Image.alpha_composite(result, eyelid)

    return result


def fit_layer(image: Image.Image, canvas_size: int, content_size: int) -> Image.Image:
    content = ImageOps.contain(image.convert("RGBA"), (content_size, content_size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    x = (canvas_size - content.width) // 2
    y = (canvas_size - content.height) // 2
    canvas.alpha_composite(content, (x, y))
    return canvas


def make_launcher_assets(open_image: Image.Image) -> None:
    background_source = Image.open(BACKGROUND_SOURCE).convert("RGB")
    background = ImageOps.fit(background_source, (1080, 1080), Image.Resampling.LANCZOS)
    background = background.filter(ImageFilter.GaussianBlur(radius=2))
    background.save(BACKGROUND_PATH, optimize=True)

    foreground = fit_layer(open_image, 1080, 660)
    foreground.save(FOREGROUND_PATH, optimize=True)

    design_background = ImageOps.fit(background_source, (1024, 1024), Image.Resampling.LANCZOS)
    design_background = design_background.filter(ImageFilter.GaussianBlur(radius=2)).convert("RGBA")
    vignette = Image.new("L", (1024, 1024), 0)
    vignette_draw = ImageDraw.Draw(vignette)
    vignette_draw.ellipse((-120, -80, 1144, 1184), fill=215)
    vignette = ImageOps.invert(vignette.filter(ImageFilter.GaussianBlur(radius=110)))
    shade = Image.new("RGBA", (1024, 1024), (5, 7, 18, 155))
    design_background = Image.composite(shade, design_background, vignette)
    pet = fit_layer(open_image, 1024, 820)
    design_background.alpha_composite(pet)
    design_background.save(DESIGN_PATH, optimize=True)

    density_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in density_sizes.items():
        output_dir = ROOT / "app/src/main/res" / folder
        output_dir.mkdir(parents=True, exist_ok=True)
        icon = design_background.resize((size, size), Image.Resampling.LANCZOS)
        icon.save(output_dir / "ic_launcher.png", optimize=True)


def make_animation_preview(open_image: Image.Image, closed_image: Image.Image) -> None:
    background_source = Image.open(BACKGROUND_SOURCE).convert("RGB")
    background = ImageOps.fit(background_source, (512, 512), Image.Resampling.LANCZOS)
    background = background.filter(ImageFilter.GaussianBlur(radius=1)).convert("RGBA")
    rgba_frames = []
    fps = 24
    frame_count = round(3.2 * fps)

    for frame_index in range(frame_count):
        seconds = frame_index / fps
        blink_phase = seconds % 1.85
        source = closed_image if 0.78 <= blink_phase <= 0.93 else open_image
        bob = round(math.sin(seconds * math.tau / 1.56) * 7)
        tilt = math.sin(seconds * math.tau / 2.24) * 4.5
        pulse = 1.0 + math.sin(seconds * math.tau / 1.64) * 0.022
        content_size = round(400 * pulse)
        pet = ImageOps.contain(source, (content_size, content_size), Image.Resampling.LANCZOS)
        pet = pet.rotate(tilt, resample=Image.Resampling.BICUBIC, expand=True)
        frame = background.copy()
        x = (512 - pet.width) // 2
        y = (512 - pet.height) // 2 + bob
        frame.alpha_composite(pet, (x, y))
        rgba_frames.append(frame)

    frames = [frame.convert("P", palette=Image.Palette.ADAPTIVE, colors=256) for frame in rgba_frames]

    frames[0].save(
        PREVIEW_PATH,
        save_all=True,
        append_images=frames[1:],
        duration=round(1000 / fps),
        loop=0,
        optimize=False,
        disposal=2,
    )
    rgba_frames[0].save(
        PREVIEW_WEBP_PATH,
        save_all=True,
        append_images=rgba_frames[1:],
        duration=round(1000 / fps),
        loop=0,
        lossless=True,
        method=6,
    )


def main() -> None:
    open_image = Image.open(OPEN_PATH).convert("RGBA")
    closed_image = make_closed_frame(open_image)
    closed_image.save(CLOSED_PATH, optimize=True)
    make_launcher_assets(open_image)
    make_animation_preview(open_image, closed_image)
    print(f"open={OPEN_PATH}")
    print(f"closed={CLOSED_PATH}")
    print(f"launcher={DESIGN_PATH}")
    print(f"preview={PREVIEW_PATH}")
    print(f"preview_webp={PREVIEW_WEBP_PATH}")
    print(f"closed_corner_alpha={closed_image.getpixel((0, 0))[3]}")


if __name__ == "__main__":
    main()
