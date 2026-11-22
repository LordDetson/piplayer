# Interface customization for the first release

Accepted on 20 November 2026. Tracks PIM-70 and informs PIM-71 through PIM-75. Built-in palettes and layouts are
tracked by PIM-91 and PIM-92.

## Rendering model

PiPlayer renders its own Compose components. The appearance model selects app-defined component identifiers, their
order and their presentation. Now Playing, its buttons and library rows use the same approach. Renderers keep the
existing playback and navigation callbacks. Playback, settings and file operations keep their existing permission
checks.

System, Light and Dark follow system dynamic colors where available. The fixed palettes have previews in Appearance.
An existing theme selection keeps its stored identifier. Each theme keeps its own Now Playing presentation choice.

| Layout | Now Playing |
| --- | --- |
| Standard | The existing cover, track information and action placement. |
| Minimal | Cover, track, progress, previous, play or pause, and next. One control reveals or hides the secondary actions on the same screen. |
| Expanded | Smaller artwork beside track information, with track actions, playback controls and secondary controls in separate groups. Action groups wrap when space is limited. The screen scrolls at large font sizes or short window heights. |

Every built-in layout retains labeled touch targets and access to the queue, identification, track actions,
favorite, shuffle, repeat, timer and supported audio effects. The player continues to honor available Media3
commands. Controls for later M5 features join the appropriate group when their feature is implemented.

### Complete built-in themes

| Theme | Colors | Library | Now Playing | Mini player | Bottom navigation |
| --- | --- | --- | --- | --- | --- |
| System | Follows the device | Songs, Albums and Artists first. Search leads the top bar. | Standard with the stock order. | Artwork first, progress at the top. | Library, Search and Fix. |
| Light | Light | Playlists beside Songs. Search leads the top bar. | Standard with Queue first. | Track information first, progress at the top. | Search, Library and Fix. |
| Dark | Dark | Playlists after Albums. Sort leads the top bar. | Expanded with track actions first. | Play/pause beside artwork, progress at the top. | Fix, Library and Search. |
| AMOLED | Black | Folders beside Songs. Settings and Search are visible. | Minimal with Favorite first and audio information hidden. | Artwork first, progress hidden. | Library and Fix. |
| Cool graphite | Cool slate | Artists beside Songs. Settings leads the top bar. | Expanded with Queue first and Equalizer leading the extra actions. | Play/pause first, progress at the top. | Fix, Search and Library. |
| Warm light | Warm cream | Albums and Songs first. Sort leads the top bar. | Standard with Favorite and Queue first. | Track information first, progress at the bottom. | Search, Fix and Library. |
| Cover Focus | Dark plum and peach | Albums first. Search and settings are visible. | Minimal presentation with artwork and basic playback. Audio information is hidden. | Artwork first, progress at the bottom. | Library and Search. |
| Control Deck | Light mint and teal | Songs, Folders and Playlists first. Sort is the first button. | Expanded presentation with Queue beside Next. Equalizer leads the extra actions when available. | Play/pause first, progress at the top. | Library, Fix and Search. |

Choosing a built-in theme applies its palette, Now Playing presentation and all four panel layouts. Local edits
are saved for that theme. On upgrade, the previous shared presentation and tab edits are copied to the last
built-in theme that used them (or System if a theme with its own presets is selected) and to existing saved themes
that still rely on them. Existing theme-specific edits take precedence. Export includes the resulting layouts.
Theme packages imported without a presentation inherit the current presentation. Undo reselects the previous theme
and keeps subsequent edits in the imported theme.

### Inline UI edit mode

Use **UI edit mode** in the long-press menu on a library tab or toolbar action, Now Playing, the mini player, or
bottom navigation. The app keeps the existing positions and sizes when the editor opens. A selection outline,
resize handle and floating editor actions appear over the screen. Drag the selected component to a freely chosen
position, resize it with the corner handle and set inner spacing in Properties. Alignment guides appear near
panel and component edges during the drag.
Components may overlap. Layers lets users select covered components, and the layer actions change drawing order.

Add component lists the panel's built-in controls and compatible controls from other panels, such as playback,
queue, settings and navigation. The library list, the way out of Now Playing and the route back to it remain
available when controls are hidden. Done saves intentional changes in the active theme. Untouched native controls
keep their original layout. Cancel discards the draft. Reset layout restores that panel's order and visibility
from the selected theme.

Properties supports foreground and background colors and stock or imported PNG, WebP and standalone static SVG
icons on icon controls. Vector icons keep their shape when resized. The editor supports SVG paths, ordinary
shapes and grouped transforms. An icon with unsupported effects or external resources is rejected. A button can
preview and use a press, bounce or tilt animation. Text components have their own alignment setting.

Drag the pencil handle to move the editor's command ring over the screen. Tap the handle to collapse or expand
the ring. Swipe along its arc to rotate commands into the foreground. Every ring button has the same visual size.
They float above the layout and occupy no space within the panel. Use Group to select several
components and confirm the selection. Drag any member to move the group, or drag a corner or edge of the group
outline to scale its components and spacing together. Editor options can dissolve the selected group.

The snap button toggles alignment with panel guides and neighboring components. In Editor options, choose whether
components can overlap or should move neighbors aside. In the second mode, a drag or resize that runs out of room
keeps the last position without overlaps. The active theme stores these choices separately for each panel.

A version 8 theme package transfers groups, snap and placement options, and text alignment with existing layout
overrides. Version 7 packages retain native overrides. Version 6 packages retain their saved positions. Earlier
versions keep their existing fields.

Long press a library tab, toolbar action, mini player control or navigation button and choose Edit the library
screen. The toolbar, tab strip, tab content, mini player and navigation bar share one movable, resizable canvas.
The active theme stores this canvas for the library screen. Set the tab strip to horizontal or vertical in its
properties. A tab or navigation button can show its icon and label, its icon only, or its label only. Icon-only
controls keep their accessible names. Theme package version 9 transfers the library canvas and label choices.

The title, album and artist can be positioned and styled independently in Now Playing and the mini player.
Now Playing also offers individual blocks for the codec, bitrate, bit depth, sample rate and channel count.
Saved themes with the earlier combined blocks retain those blocks until they are changed or reset.

## Imported themes

The Appearance screen opens the theme editor at the current palette. Its quick controls set the accent,
background and panel colors. The editor previews each change and offers automatic text colors or a manual
text color. Automatic mode derives a readable foreground for each surface. The editor shows low contrast
in manual mode. Saving makes a named theme, and canceling restores the appearance from before editing.

The editor also previews the selected font in English and Russian with the system's font scale. It offers the
system font, bundled fonts with Cyrillic coverage, and local TrueType or OpenType files (TTF or OTF). Import uses
the system file picker and keeps a validated copy in app storage. A failed import leaves the previous font selected.
The theme saves its font and palette together and keeps its own Now Playing layout.
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
- `base` is one of `system`, `light`, `dark`, `amoled`, `graphite`, `warm_light`, `cover_focus` or `control_deck`.
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

### Now Playing component layout

Long press a component on Now Playing to move it, hide it or restore another component. Move artwork, track
information, audio details and progress within the content section. Move buttons within or between the top,
playback and extra control rows. Keep the back control and the restore action available even if the panel is empty.
Short taps and progress drags keep their playback behavior. A layout edit writes display preferences for the active
theme, and a theme switch restores that theme's component layout. The basic Standard, Minimal and Expanded
layout choices continue to control the size and presentation of components.

Theme package version 3 adds a `player` object with five comma-separated lists of stable component IDs. `top`,
`content`, `controls` and `extras` place each component exactly once. `hidden` lists components that are currently
hidden, in their saved positions. Button IDs may be placed in any button row. Content IDs may appear only in
`content`. Unknown or repeated IDs invalidate the package. Versions 1 and 2 preserve the active component layout
when they are imported. Version 3 can include the library `panel` object from version 2.

### Mini player component layout

Long press the artwork, track information or play/pause button to move, hide or restore components. Long press
the progress strip to move it to the top or bottom edge, hide it or restore another component. A plus button
returns hidden components, including when the whole panel is hidden. A tap on the bar opens Now Playing and a
tap on the play/pause button controls playback. Each theme stores the order, hidden components and progress edge.

Theme package version 4 adds a `miniPlayer` object with `order`, `hidden` and `progress`. `order` contains
`cover`, `track_info` and `play_pause` exactly once. `hidden` may also contain `progress`. Both lists use comma
separators. `progress` is `top` or `bottom`. Versions 1 through 3 import with the current mini player layout.

### Bottom navigation layout

Long press Library, Search or Fix to reorder the bottom bar, hide a button or restore a hidden button. A plus
button also opens the restore menu when any button is hidden. Keep at least one destination visible. Editing the
bar keeps the current page open and changes only the menu. A notification can still open Fix while its button is hidden.

Theme package version 5 adds a `navigation` object with `order` and `hidden`. Both use comma-separated IDs.
`order` lists `library`, `search` and `fix` exactly once. `hidden` may list up to two of them. Versions 1 through 4
import with the current bottom navigation layout.
