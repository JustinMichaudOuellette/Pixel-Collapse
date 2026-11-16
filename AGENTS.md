# Agent notes

## Package layout

Sources under `ca.justinmo.wfcbitmap` are grouped by responsibility:

| Package | Contents |
|---|---|
| `ca.justinmo.wfcbitmap` | `PixelCollapseApplication` (process entry point) |
| `...generation` | `WfcGenerator`, native bridge (`WfcNative`, solver pool/adapters), `WindowTally` |
| `...output` | `EditorOutput` (preview sizes, save sizes, kernels), pixel-art upscalers, `ImageSaver` |
| `...video` | `VideoPlan`, GIF/MP4 encoders and gallery savers |
| `...storage` | SharedPreferences, editor snapshot, `DrawingStore`, `PatternStore`, `PatternPng` |
| `...palette` | Palette model and repository |
| `...editor` | `PixelGridState`, latency meter, debug test pattern |
| `...ui` | Compose editor, preview, menus, dialogs, export screens |
| `...ui.theme` | Compose theme/typography |
| `net.akachaki...` | Upstream WFC engine pieces used by `generation` |

Files import sibling packages with wildcard imports (`generation.*`, `output.*`, `video.*`,
`storage.*`, `palette.*`, `editor.*`). Keep new code in the package that matches its responsibility
rather than adding another root-level file.

## Device installs

Install app updates with `adb install -r <apk>` so the existing app data is kept. Do not uninstall or
clear app data unless the user explicitly asks for a clean install. Connected instrumentation runs can
uninstall the app; install the current build afterwards with `-r`.

## Testing

Delegate testing to the user unless the user explicitly asks you to test. Do not run builds, unit
tests, instrumentation tests, device installs, or adb checks on your own initiative just to validate a
change; make the change, explain what should be verified, and stop. Run a check only when the request
asks for it (or when a check is required to complete an explicitly requested task).

## Test pattern

Use this **9×9** drawing as the canonical test pattern for generation, kernel grouping, and
video/screen-chunk debugging. `*` is a filled pixel; `_` is empty (background).

```
_________
__**_**__
_*__*__*_
_*_____*_
__*___*__
_*_____*_
_*__*__*_
__**_**__
_________
```
