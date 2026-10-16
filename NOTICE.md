# NOTICE

OpenCaller
Copyright (C) 2026 OpenCaller contributors

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version (`GPL-3.0-or-later`, see `LICENSE` and ADR 0003).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

## Code derived from other projects

Files derived from other projects (for example Fossify Phone or Fossify Messages) keep their
upstream copyright headers and are listed here with their source and license.

_None yet._

## Bundled third-party libraries

Libraries shipped inside the app or the server. Every license below is GPL-3.0-compatible.
Keep this list in sync with `android/gradle/libs.versions.toml` and `backend/server/gradle/libs.versions.toml`.

| Component | Used in | License |
| --- | --- | --- |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | app, server | Apache-2.0 |
| AndroidX Activity, Jetpack Compose (UI, Material 3), Navigation, DataStore, Room, SQLite | app | Apache-2.0 |
| Dagger / Hilt | app | Apache-2.0 |
| libphonenumber (Google) | app | Apache-2.0 |
| Timber (Jake Wharton) | app | Apache-2.0 |
| Protocol Buffers (Google, Java/Kotlin lite runtime) | app | BSD-3-Clause |
| SQLCipher for Android (Zetetic LLC) | app | BSD-3-Clause, see below |
| Ktor (server core, Netty engine, content negotiation) | server | Apache-2.0 |
| Netty | server | Apache-2.0 |
| kotlinx.serialization | server | Apache-2.0 |
| Logback | server | EPL-1.0 / LGPL-2.1 (dual) |

## Bundled fonts and icons

| Asset | Location | License |
| --- | --- | --- |
| Noto Sans Regular & Medium 2.015, Noto Sans Mono SemiBold 2.014 (The Noto Project Authors, github.com/notofonts) | `android/core/designsystem/src/main/res/font/` | SIL Open Font License 1.1, full text in `android/core/designsystem/fonts/OFL.txt` |
| Material Symbols Outlined icons (Google, github.com/google/material-design-icons) | `android/core/designsystem/src/main/res/drawable/ic_*.xml` | Apache-2.0, full text in `android/core/designsystem/fonts/MATERIAL_SYMBOLS_LICENSE.txt` |

## SQLCipher for Android license

```
Copyright (c) 2008-2023, ZETETIC LLC
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the ZETETIC LLC nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ZETETIC LLC ''AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ZETETIC LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## ML models and datasets

Bundled models and datasets need licenses that permit redistribution (ADR 0003).

_None yet._
