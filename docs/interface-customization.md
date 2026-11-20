# Interface customization for the first release

Accepted on 20 November 2026. Tracks PIM-70 and informs PIM-71 through PIM-75. Built-in palettes and layouts are
tracked by PIM-91 and PIM-92.

## Rendering model

PiPlayer renders its own Compose components. The appearance model selects app-defined component identifiers, their
order and their presentation. Now Playing, its buttons and library rows use the same approach. Renderers keep the
existing playback and navigation callbacks. Playback, settings and file operations keep their existing permission
checks.

The theme and the Now Playing layout are independent settings. The initial choices are System, Light, Dark,
AMOLED, Cool graphite and Warm light. The first three follow system dynamic colors where available. The other
three carry fixed Material color roles and previews in Appearance. An existing theme selection keeps its stored
identifier. The layout defaults to Standard when absent or unrecognized.

| Layout | Now Playing |
| --- | --- |
| Standard | The existing cover, track information and action placement. |
| Minimal | Cover, track, progress, previous, play or pause, and next. One control reveals or hides the secondary actions on the same screen. |
| Expanded | Smaller artwork beside track information, with track actions, playback controls and secondary controls in separate groups. Action groups wrap when space is limited. The screen scrolls at large font sizes or short window heights. |

Every built-in layout retains labeled touch targets and access to the queue, identification, track actions,
favorite, shuffle, repeat, timer and supported audio effects. The player continues to honor available Media3
commands. Controls for later M5 features join the appropriate group when their feature is implemented.

## Imported themes

The Appearance screen opens the theme editor at the current palette. Its quick controls set the accent,
background and panel colors. The editor previews each change and offers automatic text colors or a manual
text color. Automatic mode derives a readable foreground for each surface. The editor shows low contrast
in manual mode. Saving makes a named theme, and canceling restores the appearance from before editing.

The editor also previews the selected font in English and Russian with the system's font scale. It offers the
system font, bundled fonts with Cyrillic coverage, and local TrueType or OpenType files (TTF or OTF). Import uses
the system file picker and keeps a validated copy in app storage. A failed import leaves the previous font selected.
The theme saves its font and palette together. The Now Playing layout remains a separate preference.
These controls are tracked by PIM-97 and PIM-98.

The editor and importer use a versioned JSON format with bounded values and known identifiers. Validate and resolve
the model before applying it, keep the previous appearance for undo, and use the standard presentation if a saved
theme can no longer be rendered. A package contains only bounded images and declarative data. Its references resolve
to assets bundled in that package or supplied by PiPlayer.

The app shares its validated model with the local integration from PIM-73 and PIM-74. Applying a model requires the
customization permission and uses the same validator as the manual editor. The catalog distributes the same format.
