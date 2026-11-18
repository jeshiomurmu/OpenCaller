# F-Droid

How OpenCaller reaches F-Droid: the request for packaging (`fdroid-rfp.md`), the build recipe
(`fdroiddata-io.github.jeshiomurmu.opencaller.yml`), the store texts in `fastlane/`, and the checks that keep the
`foss` flavor free of proprietary code.

## Store metadata (`fastlane/metadata/android/en-US/`)

F-Droid reads the fastlane layout from the tagged source:

| File | Status |
| --- | --- |
| `title.txt`, `short_description.txt` (≤ 80 characters), `full_description.txt` (≤ 4,000) | done |
| `changelogs/<versionCode>.txt` (≤ 500 characters each) | one per release: 10010, 50040, 1000099, 1010099 |
| `images/icon.png` (512 × 512) | done, drawn from the launcher icon's vector (the placeholder ring; replace both when the final artwork exists) |
| `images/phoneScreenshots/1.png` … | **owner**: 4–6 screenshots from a phone or from the emulator jobs' artifacts (`emulator-api-36`, the light-mode tab walk; issue 111) |
| `images/featureGraphic.png` (1024 × 500) | optional, not made |

Every release adds `changelogs/<versionCode>.txt` (the release workflow's notes come from `CHANGELOG.md`; keep the two
in step).

## No proprietary dependencies in `foss`

CI (`android` job) and the release workflow run `scripts/ci/foss-dependencies.sh`, which resolves
`./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath` twice (as GitHub releases build it, and
with `-Popencaller.voip=false` as F-Droid builds it), prints the artifacts per group and fails if any group of Google
Play services, Firebase, Play Billing, Play Core, ML Kit, Crashlytics/Fabric or Google's data transport appears. The
play-only libraries are declared as `playImplementation` (`android/app/build.gradle.kts`), so they never reach the
foss classpath.

Output summary (CI, recorded at the end of Phase 15):

From CI run 44231936660 (`android` job, commit `5cbb159`, 2026-11-18):

| Configuration | External artifacts | Groups | Proprietary groups found |
| --- | --- | --- | --- |
| GitHub release (`foss`, app calls included) | 207 | 79 | none |
| F-Droid (`foss`, `-Popencaller.voip=false`) | 197 | 72 | none |

The largest groups are AndroidX (Lifecycle, Compose, DataStore, Navigation, CameraX, Room, Hilt), kotlinx, Dagger,
Square (Retrofit, OkHttp, Okio), protobuf, SQLCipher (`net.zetetic`), libphonenumber, Timber, Tink and the
UnifiedPush connector. The F-Droid configuration drops exactly LiveKit's groups: `io.livekit`, `io.github.webrtc-sdk`,
`net.java.dev.jna`, `javax.sip`, `com.auth0.android`, `com.vdurmont`, `com.github.davidliu` (JitPack), plus one
`androidx.core` and one `org.jetbrains.kotlin` artifact that only the app-call module pulls in (10 artifacts in all). `com.google.*` artifacts on the classpath are
open-source libraries (Guava, Gson, Tink, protobuf, Dagger, error-prone annotations, AutoValue annotations, findbugs
JSR-305), none of the forbidden groups. The script prints the full per-group counts in every CI run.

What else F-Droid looks for, and where the build stands:

- **Signing block with dependency metadata.** AGP writes Google's encrypted "Dependency metadata" block into APKs by
  default; F-Droid's scanner rejects it. `dependenciesInfo { includeInApk = false }` turns it off for every APK; the
  Play App Bundle keeps it (`includeInBundle = true`).
- **Prebuilt binaries.** The only native code in the foss build with app calls is LiveKit's WebRTC and Rust libraries
  (Apache-2.0, but prebuilt). F-Droid builds with `opencaller.voip=false`, which leaves `feature:voip`, LiveKit and the
  JitPack repository out (ADR 0007); SQLCipher's native library comes from Maven Central as an AAR, which F-Droid
  accepts from Maven Central (its scanner may still ask; answer with the source link
  https://github.com/sqlcipher/sqlcipher-android).
- **Non-free network services.** The foss build talks only to the OpenCaller server it was built for (none in an
  F-Droid build, issue 115) and, for push, to the user's UnifiedPush distributor. The anti-feature list in
  `fdroid-rfp.md` stays accurate: none, unless F-Droid counts the community spam list as `NonFreeNet` (the server is
  open source and self-hostable).

## Reproducible builds

F-Droid can publish the developer's own signed APK when its own build from the same tag matches it byte for byte
(apart from the signature). Then users can move between the GitHub APK and F-Droid's without reinstalling.

What the build already does for that:

- The Gradle wrapper is pinned with its distribution checksum (`gradle-wrapper.properties`, `distributionSha256Sum`),
  every dependency version is fixed in `libs.versions.toml`, and the toolchain is JDK 17 (Temurin in CI).
- No timestamps, build numbers or machine names in `BuildConfig`; `versionCode` and `versionName` are in the source.
- R8 is deterministic for the same inputs; the mapping file is published with each release for crash stacks.
- The Baseline Profile is committed (`src/main/generated/baselineProfiles`) and never regenerated during a build
  (`automaticGenerationDuringBuild = false`).
- The dependency-metadata signing block is off (above).

What is still needed (owner's choice, not done):

1. **One configuration on both sides.** F-Droid builds with `-Popencaller.voip=false` and without a server address;
   the GitHub foss APK has app calls and, for real tags, `OPENCALLER_API_BASE_URL`. They can't match. Either the
   release workflow publishes a third APK built exactly like F-Droid's recipe (foss, `voip=false`, no server address)
   as the reference, or F-Droid keeps signing with its own key (the default today: no reproducibility needed, but
   F-Droid and GitHub installs can't update each other).
2. **Check locally before claiming it.** Build the tag twice in two clean clones (different paths, different users)
   and compare: `apksigcopier compare OpenCaller-vX.Y.Z-foss.apk --unsigned app-foss-release-unsigned.apk` (or
   `diffoscope` on the two unsigned APKs). Differences usually come from the build path in native libraries, file
   ordering in resources, or a different `build-tools` version; pin `buildToolsVersion` if needed.
3. **The recipe.** Add `Binaries: https://github.com/jeshiomurmu/OpenCaller/releases/download/v%v/OpenCaller-v%v-fdroid.apk`
   and `AllowedAPKSigningKeys: <SHA-256 of the release certificate>` (`apksigner verify --print-certs` on a signed
   APK, lowercase without colons) to `fdroiddata-io.github.jeshiomurmu.opencaller.yml`, then
   `fdroid build -v -l io.github.jeshiomurmu.opencaller` in fdroidserver's Docker image to see F-Droid verify it.

## Submitting

1. Tag a release (`docs/release/signing.md`); check that `fastlane/` has the screenshots and the new changelog.
2. Open the request at https://gitlab.com/fdroid/rfp/-/issues with the text of `fdroid-rfp.md`, or a merge request to
   fdroiddata with the recipe (run `fdroid lint` and `fdroid build` on it first).
3. Updates come by themselves afterwards (`UpdateCheckMode: Tags`, `AutoUpdateMode: Version`): each new `vX.Y.Z` tag
   with a higher `versionCode` is built by F-Droid within a few days.
