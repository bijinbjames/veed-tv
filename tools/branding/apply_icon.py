"""Puts an icon concept into the app: banner PNGs at four densities (rendered from the outlined
SVG with Inkscape), the adaptive icon's foreground as a VectorDrawable converted path for path,
and its flat background colour. Refuses SVG features the plain conversion would get wrong."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

concept, res = sys.argv[1], sys.argv[2]
INKSCAPE = "/Applications/Inkscape.app/Contents/MacOS/inkscape"
SVG = "{http://www.w3.org/2000/svg}"

# Banner: 160x90 dp at every density the launcher may ask for.
for folder, (w, h) in {"drawable-hdpi": (240, 135), "drawable-xhdpi": (320, 180),
                       "drawable-xxhdpi": (480, 270), "drawable-xxxhdpi": (640, 360)}.items():
    out = f"{res}/{folder}/banner.png"
    subprocess.run(["mkdir", "-p", f"{res}/{folder}"], check=True)
    subprocess.run([INKSCAPE, f"{concept}/banner.svg", "--export-type=png", f"--export-filename={out}",
                    f"--export-width={w}", f"--export-height={h}"], check=True, capture_output=True)
    print("banner", out)

# Background: one flat rectangle -> a colour resource.
bg = ET.parse(f"{concept}/icon-background.svg").getroot()
shapes = [e for e in bg.iter() if e.tag in (SVG + "rect", SVG + "path", SVG + "circle", SVG + "linearGradient")]
assert len(shapes) == 1 and shapes[0].tag == SVG + "rect", "background is not one flat rect"
colour = shapes[0].get("fill")
assert re.fullmatch(r"#[0-9A-Fa-f]{6}", colour), colour
themes = f"{res}/values/themes.xml"
text = open(themes).read()
text, n = re.subn(r'(<color name="ic_launcher_background">)#[0-9A-Fa-f]{6}(</color>)', rf"\g<1>{colour}\g<2>", text)
assert n == 1
open(themes, "w").write(text)
print("background", colour)

# Foreground: plain filled paths, no transforms, strokes, clips or gradients.
fg = ET.parse(f"{concept}/icon-foreground.svg").getroot()
assert fg.get("viewBox") == "0 0 108 108"
paths = []
for e in fg.iter():
    tag = e.tag.replace(SVG, "")
    if tag in ("svg", "title", "g", "desc"):
        assert "transform" not in e.attrib, f"{tag} has a transform"
        continue
    assert tag == "path", f"unsupported element {tag}"
    assert set(e.attrib) <= {"fill", "d"}, e.attrib
    assert re.fullmatch(r"#[0-9A-Fa-f]{6}", e.get("fill")), e.get("fill")
    paths.append((e.get("fill").upper(), " ".join(e.get("d").split())))
body = "\n".join(f'    <path\n        android:fillColor="{f}"\n        android:pathData="{d}" />' for f, d in paths)
open(f"{res}/drawable/ic_launcher_foreground.xml", "w").write(f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Concept C, "Friendly TV": a chunky navy TV set with rabbit ears, showing a sun over a green
     hill, on the app's amber (the background colour). Converted path for path from
     tools/branding/concept-c/icon-foreground.svg; all of it inside the 66dp safe circle. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{body}
</vector>
""")
print("foreground", len(paths), "paths")
