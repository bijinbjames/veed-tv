# Concept C: Friendly TV

A chunky retro TV set (rabbit-ear antenna with ball tips, stubby legs, a glass glint) shows a sunny green hill, sitting on the app's own amber, so the family can spot "the TV app" by shape and colour before reading a word; the banner stacks "Veed" over "TV" so the name is set at 65 px instead of the 40 px a single line would allow, which matters because the launcher actually draws the banner at 144 x 81 px.

Palette: amber `#FFB300` tile (the app's current accent, and the only yellow tile in the Favorite Apps row), navy `#0B1830` for the set and the wordmark (9.9:1 on the amber), screen sky `#3EB3F0`, sun `#FFEB91`, hill `#17904C` and glint `#F5FBFF`, with sun, sky and hill stepped down in brightness (2.0:1 and 1.7:1) so the picture survives greyscale and the loss of blue-green discrimination that comes with age.

The wordmark is Baloo Chettan 2 ExtraBold, a rounded face that Ek Type drew for Malayalam, licensed under the SIL Open Font License and converted to outlines; SF Rounded was offered but its licence limits it to Apple platforms, so it is not used.

The design skill set the order of work: state the design read first, refuse the defaults (no purple, glow, gradient or thin line), lock one accent and one corner-radius family, and check contrast in numbers rather than by eye; its ban on hand-drawn SVG was set aside only because this brief asks for vector art, which is kept to plain paths and circles with no clip paths, masks or filters so it converts straight to a VectorDrawable.

Risks at small sizes: at 32 px the glint disappears and the antenna and legs fall to 1 to 2 px, so recognition rests on the amber disc, the navy frame and the blue-green screen; a sun-and-hill screen can recall a generic "photo" glyph, which the antenna, legs and glint are there to counter; and the amber sits only 1.3:1 in brightness from VLC's orange, so the two are told apart by hue and by navy-on-amber against white-on-orange, worth rechecking if the row is ever reordered to put them side by side.
