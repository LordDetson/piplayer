# Third-party notices

PiPlayer includes the following third-party software. Their sources are part of this repository as git submodules
under `third_party/`, pinned to the release tags named below.

## TagLib 2.3.2

- Source: https://github.com/taglib/taglib, tag `v2.3.2` (submodule `third_party/taglib`).
- Authors: Scott Wheeler, Lukas Lalinsky, Tsuda Kageyu, Stephen F. Booth, Urs Fleisch and the other contributors
  listed in its `AUTHORS` file.
- License: TagLib is offered under the GNU Lesser General Public License version 2.1 or the Mozilla Public License
  version 1.1. PiPlayer uses it under the **GNU LGPL version 2.1**; the full text is in
  `third_party/taglib/COPYING.LGPL`.
- PiPlayer ships TagLib unmodified, as the separate shared library `libtag.so`. Its complete source is in this
  repository at the tag above, and PiPlayer's own build files show how it is built.

## utfcpp

- Source: https://github.com/nemtrif/utfcpp, the commit pinned by TagLib 2.3.2 (`third_party/taglib/3rdparty/utfcpp`).
- Copyright 2006 Nemanja Trifunovic.
- License: Boost Software License 1.0 (`third_party/taglib/3rdparty/utfcpp/LICENSE`).

## Chromaprint 1.6.2

- Source: https://github.com/acoustid/chromaprint, tag `v1.6.2` (submodule `third_party/chromaprint`).
- Copyright 2010-2016 Lukas Lalinsky.
- License: MIT (`third_party/chromaprint/LICENSE.md`). PiPlayer builds Chromaprint without its FFmpeg-derived
  resampler (LGPL), so only the MIT-licensed code and KissFFT are included.

## KissFFT (bundled with Chromaprint)

- Copyright 2003-2010 Mark Borgerding. All rights reserved.
- License: BSD 3-Clause (`third_party/chromaprint/src/3rdparty/kissfft/COPYING`).

## LLVM libc++ and libc++abi

- Shipped as `libc++_shared.so` from the Android NDK 30.0.16248370, because several native libraries share the C++
  runtime.
- License: Apache License 2.0 with LLVM Exceptions; older parts are dual licensed under the University of Illinois
  "BSD-Like" license and the MIT license. The full text is in `nativelib/licenses/libc++-LICENSE.txt`.

The app carries all of these texts in its assets under `licenses/`.

## Test recordings (not distributed)

The fingerprint tests download freely licensed recordings at test time; they are not stored in this repository.
`nativelib/fingerprint-samples.json` lists each one with its source and license:

- J. S. Bach, Goldberg Variations: Aria da Capo e Fine, and The Art of Fugue: Contrapunctus 1, performed by
  Kimiko Ishizaka, released as CC0 1.0.
- E. Grieg, Peer Gynt Suite No. 1: Morning Mood, and W. A. Mozart, Le nozze di Figaro: Overture, performed by the
  Musopen Symphony Orchestra, public domain.
- J. Strauss I, Radetzky March, performed by "The President's Own" United States Marine Band, public domain as a
  work of the U.S. federal government.
