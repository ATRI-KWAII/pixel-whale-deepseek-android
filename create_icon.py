"""Generate the launcher icon from the same pixel art as the welcome screen."""
from pathlib import Path
from PIL import Image, ImageDraw

ART = [
    "................................",
    ".....................Y..........",
    "....................YYY.........",
    ".....................Y..........",
    ".................L...D..........",
    "................LLL.DDD.........",
    ".................L...D..........",
    "...................L............",
    ".................LLLL...........",
    ".................L..L...........",
    "......DD........DDDDDDDDDDD.....",
    ".....DCCD.....DDCCCCCCCCCCDD....",
    "....DCCCCD...DCCCCCCCCCCCCCDD...",
    "...DCCCCCCD.DCCCCCCCCCCCCCCCCD..",
    "....DCCCCCCDDCCCCCCCCCCCCCCCCD..",
    ".....DDCCCCCCCCCCCCCCCCCCCCCCCD..",
    ".......DDCCCCCCCCCCCCCCCDCCCCCD..",
    "........DCCCCCCCCCCCCCCCCCCCCCD..",
    "........DCCCCCCCCCCCCCCCCCCPCCD..",
    "........DCCCCCCCCCCBBBBBBBBBBDD..",
    ".........DCCCCCBBBBBBBBBBBBBD...",
    "..........DDDDDBBBBBBBBBBBBD....",
    "...............DDDDDDDDDDDDD.....",
    "................................",
]
palette = {
    "D": "#183e59", "C": "#4fa8c8", "B": "#e4f8f6",
    "P": "#f497a0", "L": "#89d3e2", "Y": "#ffd068",
}
icon = Image.new("RGB", (192, 192), "#153c5b")
draw = ImageDraw.Draw(icon)
draw.rounded_rectangle((8, 8, 183, 183), radius=33, fill="#dff6f7")
draw.rectangle((20, 139, 171, 149), fill="#a1dce6")
draw.rectangle((31, 151, 160, 156), fill="#a1dce6")
for y, row in enumerate(ART):
    for x, char in enumerate(row):
        if char in palette:
            sx, sy = 16 + x * 5, 25 + y * 5
            draw.rectangle((sx, sy, sx + 4, sy + 4), fill=palette[char])
draw.rectangle((16 + 23*5, 25 + 14*5, 16 + 24*5 - 1, 25 + 15*5 - 1), fill="#183e59")
draw.rectangle((26, 56, 35, 65), fill="#89d3e2")
draw.rectangle((47, 41, 51, 45), fill="#89d3e2")
destination = Path(__file__).parent / "res" / "drawable" / "ic_launcher.png"
destination.parent.mkdir(parents=True, exist_ok=True)
icon.save(destination)
print(destination)
