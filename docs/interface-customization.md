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

Both release builds use the same data package format for sharing themes and supported layouts (PIM-72). Export
through the Android share sheet and import through the system file picker, including files downloaded from the
forum. The package contains a versioned JSON model and supported, size-limited assets. It refers to app-defined
components and bundled assets by known identifiers. Validate all entries and the model before preview or import,
preserve the current appearance on rejection, and keep the previous appearance for undo after applying a package.
A saved theme that can no longer be rendered falls back to the standard presentation.

A shared data package carries declarative settings and assets. The forum build's executable plugins are tracked
by PIM-101. Their format and permissions are decided independently.

### Theme package version 1

Open a UTF-8 JSON file of at most 32 KiB from the Appearance settings. Preview shows the named palette and
its layout selection. Confirm to save and select the theme or cancel to keep the active appearance. Undo is
available during the current visit to Settings and preserves later independent changes. The system save dialog
exports the current palette and layout as `PiPlayer-theme.json`.

```json
{
  "version": 1,
  "name": "Forest",
  "base": "graphite",
  "palette": { "accentRgb": 7847843, "backgroundRgb": 1055274 },
  "layout": "minimal"
}
```

- `name` is nonblank, at most 80 UTF-16 code units, and contains no control characters.
- `base` is one of `system`, `light`, `dark`, `amoled`, `graphite` or `warm_light`.
- `palette` is optional. The `accentRgb`, `backgroundRgb`, `surfaceRgb` and `textRgb` values are integers from
  0 to 16777215. Missing colors inherit from the base palette. `automaticText` defaults to `true`. Set it to
  `false` to use a supplied `textRgb`.
- `layout` is `standard`, `minimal` or `expanded`. Omit it to preserve the current layout.
- Version 1 accepts the fields above. Font files, images and other assets require a separately specified version.

### Theme package version 2

Version 2 adds a `panel` object. It stores the library toolbar and tab strip for the chosen theme. Each field is a
comma-separated list of stable component IDs. Hidden items keep their places in the saved order. All toolbar
buttons may be hidden. At least one tab must stay visible. A package with unknown or duplicated IDs is invalid.

```json
{
  "version": 2,
  "name": "Library focus",
  "base": "graphite",
  "panel": {
    "tabOrder": "songs,artists,albums,playlists,genres,folders",
    "hiddenTabs": "genres",
    "buttonOrder": "sort,search,settings",
    "hiddenButtons": "settings"
  }
}
```

Export includes the current library panel. Import and activate a named theme in one write. Version 1 packages
remain supported and carry over the current panel on import. Each saved theme can be reselected in Appearance.

The app shares its validated model with the local integration from PIM-73 and PIM-74. Applying a model requires the
customization permission and uses the same validator as the manual editor. The catalog distributes the same format.
