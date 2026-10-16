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
| Kotlin standard library | app, server | Apache-2.0 |
| AndroidX Activity, Jetpack Compose (UI, Material 3) | app | Apache-2.0 |
| AndroidX Room | app | Apache-2.0 |
| Dagger / Hilt | app | Apache-2.0 |
| Ktor (server core, Netty engine, content negotiation) | server | Apache-2.0 |
| Netty | server | Apache-2.0 |
| kotlinx.serialization | server | Apache-2.0 |
| Logback | server | EPL-1.0 / LGPL-2.1 (dual) |

## ML models and datasets

Bundled models and datasets need licenses that permit redistribution (ADR 0003).

_None yet._
