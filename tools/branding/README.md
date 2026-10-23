# Branding

The launcher banner and app icon are "Friendly TV" (`concept-c/`, with its rationale): a chunky
navy TV set showing a sun over a green hill, on the app's amber. It was picked on 2026-10-20 from
three concepts designed in parallel, judged in a real Google TV favourites row at the 144x81 px a
launcher actually draws a banner at.

To change it, edit the SVGs in a concept folder (shapes only: the text is already converted to
outlines, so no font is needed) and run:

```sh
python3 tools/branding/apply_icon.py tools/branding/concept-c app/src/main/res
```

That renders `banner.png` for hdpi to xxxhdpi with Inkscape, converts the icon foreground path
for path into `drawable/ic_launcher_foreground.xml`, and sets `ic_launcher_background`.

The wordmark is Baloo Chettan 2 ExtraBold (SIL Open Font License), a rounded face made for
Malayalam.
