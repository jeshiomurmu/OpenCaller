# F-Droid request for packaging (text for gitlab.com/fdroid/rfp)

Title: **OpenCaller: caller ID, spam blocker, dialer and SMS app**

---

* **Name:** OpenCaller
* **Source code:** https://github.com/jeshiomurmu/OpenCaller
* **License:** GPL-3.0-only (`LICENSE`)
* **Issue tracker:** https://github.com/jeshiomurmu/OpenCaller/issues
* **Changelog:** https://github.com/jeshiomurmu/OpenCaller/blob/main/CHANGELOG.md
* **Category:** Phone & SMS
* **Package:** `io.github.jeshiomurmu.opencaller`
* **First version to build:** tag `v0.5.0-beta` (versionCode 50040), `android/` Gradle project, flavor `foss`
  (`./gradlew assembleFossRelease`)
* **Description and screenshots:** fastlane layout in `fastlane/metadata/android/en-US/`

**Summary:** Caller ID, spam blocker, dialer and SMS app that never uploads your contacts.

**Description:** OpenCaller screens calls on the phone (rules, hidden numbers, non-contacts, foreign numbers, a
community spam list), can be the default phone app (in-call screen, dial pad, call log, contacts) and the default SMS
app (smart inbox, one-time-code warnings). Names on the caller card always say where they come from; they never come
from other people's address books.

**Why it fits F-Droid**

* No proprietary libraries in the `foss` flavor: no Google Play Services, Firebase, ads, analytics or crash
  reporting. Dependencies are AndroidX, Kotlin, Hilt, Room with SQLCipher, Retrofit/OkHttp, libphonenumber and
  protobuf-lite (all open source; versions in `android/gradle/libs.versions.toml`).
* The server is open source in the same repository (`backend/server/`, Ktor) and can be self-hosted with
  `infra/docker/compose.yml`.
* The F-Droid build has no server address set (none is compiled in without `-Popencaller.apiBaseUrl`): blocking,
  the dialer and messages work fully offline, and the online features (sign-in, reports, search, spam-list updates)
  say "Not connected to a server". We'd like to discuss how a self-hoster or a later official server address should
  be set for F-Droid builds.
* Privacy: contacts, call log and SMS bodies never leave the phone; live lookups send only the country calling code
  and a 16-bit hash bucket of the number (k-anonymity), never the number.

**Anti-features:** none that we know of. The online features talk to an OpenCaller server, whose code is free;
there is no non-free network service.

**Build notes**

* JDK 17, Android Gradle Plugin 9.4.0, Gradle 9.8.1 (the distribution checksum is pinned in
  `android/gradle/wrapper/gradle-wrapper.properties`), compileSdk 36.
* `minSdk` 29, `targetSdk` 36.
* The release APK on GitHub is signed with our key; F-Droid signs its own build, so users can't switch between the
  two without reinstalling.
* Known Android requirements: the app asks for the call screening role and, optionally, the default phone and SMS
  app roles; the SMS and call log permissions come with those roles.

I am the developer and will maintain the metadata (fastlane) in the repository.
