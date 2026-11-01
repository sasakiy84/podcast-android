#!/usr/bin/env python3
"""Compile the canonical SVG into Android vector resources (standard library only)."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
SVG = "{http://www.w3.org/2000/svg}"
source = ET.parse(ROOT / "docs/branding/logo.svg").getroot()
paths = list(source.find(f"{SVG}g[@id='mark']"))
background = source.find(f"{SVG}rect[@id='background']").get("fill")
body = next(p.get("fill") for p in paths if p.get("id") == "body")
facet = next(p.get("fill") for p in paths if p.get("id") == "body-facet")


def write(relative, content):
    path = RES / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def vector(size, adaptive=False, monochrome=False):
    viewport = 108 if adaptive else 1000
    lines = [
        '<!-- Generated from docs/branding/logo.svg; run scripts/generate-brand-assets.py. -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{size}dp" android:height="{size}dp"',
        f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">',
    ]
    if adaptive:
        # Android's adaptive layer has 18dp of overscan around the visible 72dp mask.
        # Keep all important contours inside its central 66dp safe circle.
        lines += ['    <group android:scaleX="0.075" android:scaleY="0.075"',
                  '        android:translateX="16.5" android:translateY="16.5">']
    indent = "        " if adaptive else "    "
    for path in paths:
        if monochrome and path.get("id").endswith("-facet"):
            continue
        color = "#FFFFFFFF" if monochrome else (
            "@color/galena_copper" if path.get("fill") == body else "@color/galena_facet"
        )
        lines += [f'{indent}<path android:fillColor="{color}"',
                  f'{indent}    android:fillType="evenOdd" android:pathData="{path.get("d")}" />']
    if adaptive:
        lines += ["    </group>"]
    return "\n".join(lines + ["</vector>", ""])


write("drawable/ic_launcher_foreground.xml", vector(108, adaptive=True))
write("drawable/ic_launcher_monochrome.xml", vector(108, adaptive=True, monochrome=True))
write("drawable/ic_podcast.xml", vector(24, monochrome=True))
write("values/brand_colors.xml", f'''<!-- Generated from docs/branding/logo.svg; run scripts/generate-brand-assets.py. -->
<resources>
    <color name="galena_background">{background}</color>
    <color name="galena_copper">{body}</color>
    <color name="galena_facet">{facet}</color>
</resources>
''')
write("mipmap-anydpi/ic_launcher.xml", '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/galena_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
''')
