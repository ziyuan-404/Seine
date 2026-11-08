# Third-party source acknowledgements

## REAREye

Seine is a fork of [REAREye](https://github.com/killerprojecte/REAREye),
which supplies the base hook framework, rear-screen component system and
configuration model. The project license is included in `LICENSE`.

## MiRearScreenSwitcher

The full-screen green liquid effect in `RearChargingLiquidView.kt` is adapted from
`LightningShapeView.java` by **AntiOblivionis**, part of
[MiRearScreenSwitcher](https://github.com/AntiOblivionis/MiRearScreenSwitcher),
revision `7e0ae1a35aea58c1f94019cf3cb54681adb54b9d`.

Original source:
`android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/LightningShapeView.java`.
The charging entry animation is described in `RearScreenChargingActivity.java`.

The referenced upstream version is licensed under **GNU GPL version 3**, compatible
with this project's GPL-3.0 license. The complete license is included in `LICENSE`.
Upstream credits 汐木泽 as chief tester and credits Cursor, Claude-4.5-Sonnet, GPT-5,
and Gemini-2.5-Pro as development assistants.

Seine adapts the full-screen drawing to its existing host process, with a bounded
frame rate, visible-window lifecycle, display-relative gravity, safe text placement,
and live battery updates. MRSS's Shizuku task switching, launcher termination,
continuous wake loop, and foreground services are not included.

## HyperIsland

The configuration back gesture adapts the motion tracking and settling curves from [HyperIsland](https://github.com/1812z/HyperIsland). Its backup and restore screen also informs the interaction design of Seine's configuration backup page.

```text
MIT License

Copyright (c) 2026 1812z

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Mochi Compose

The menu renderer is built from the [Mochi Compose](https://github.com/feice11/mochi-compose)
0.2.0 Maven source release. Seine applies the distance-based neck calculation from
the upstream MetaballPage demo while menus open or close, caps the menu's
fusion radius at 22 dp, and uses the metaball shader for connected shapes at
Seine's mid graphics level. The module
includes the upstream Apache 2.0 license and preserves its consumer rules,
baseline profile, and bundled notices.

## Interface design references

Folia informs the sponsorship entry and sponsorship-page interactions.
Interface animations reference **STRAY洋葱**’s animation designs, used with permission.
These are interface design references; adapted source code is attributed above.
