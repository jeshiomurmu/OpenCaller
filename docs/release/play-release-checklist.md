# Play release checklist

For the owner, before each Play submission of the `play` flavor. The detailed answers are in the other files of this
folder; this list is the order to do them in. Tick in a copy (or the release's issue), not here.

## 1. The build

- [ ] **Target API 36.** `AndroidSdk.TARGET_SDK = 36` (`android/build-logic/.../AndroidSdk.kt`). Play requires API 36
      for new apps and updates from 31 August 2026 (developer.android.com → Google Play's target API level
      requirement, read 2026-11-17); re-check that page before each release, the requirement moves every year.
- [ ] **Signed by the release workflow** from a `vX.Y.Z` tag with the four `OPENCALLER_*` secrets
      (`signing.md`); upload `OpenCaller-vX.Y.Z-play.aab` from the GitHub release, never a local build.
- [ ] **Server and pins** set for the tag: `OPENCALLER_API_BASE_URL` and `OPENCALLER_API_PINS` (the play release
      build refuses to build without two pins; `infra/deploy/pins.sh`).
- [ ] **R8 mapping** uploaded with the bundle (Play Console → the release → App bundle explorer → Downloads →
      ReTrace mapping file: `OpenCaller-vX.Y.Z-play-mapping.txt` from the release), so Android vitals shows readable
      stacks.
- [ ] **Version.** `versionCode` higher than every earlier upload; `fastlane/.../changelogs/<versionCode>.txt` and the
      `CHANGELOG.md` section exist.
- [ ] **Minimum app version.** If this release needs a server change that breaks older apps, raise the server's
      `MIN_APP_VERSION_CODE` only after the rollout reaches 100 % (`infra/deploy/README.md` §10).

## 2. App content (Play Console → Policy → App content)

- [ ] **Privacy policy URL.** Publish `PRIVACY.md` at a stable public URL (GitHub Pages, or the repository's
      rendered file at a tag) once the lawyer has reviewed it (issue 110), and enter it here and in the store
      listing. Required for SMS and Call Log permissions, also for closed testing.
- [ ] **Data safety** form: answers in `play-data-safety.md` (re-check when a release adds a permission, an SDK or a
      server table).
- [ ] **Sensitive permissions declaration** (SMS and Call Log, default handler): `play-permissions-declaration.md`,
      with the demo video it asks for (default SMS app and default phone app on a phone). Also: full-screen intent
      (incoming calls and the OTP warning, issue 95), `phoneCall` foreground service for app calls, and
      `USE_FULL_SCREEN_INTENT` as listed there.
- [ ] **Content rating** questionnaire (IARC): a communication app with user-to-user contact (app calls, Flash
      messages, profile names and pictures), no violence, no gambling, no purchases of digital goods except the
      Supporter subscription. Answer "Yes" to users interacting/communicating and sharing content; the result is
      usually rated for everyone with a "Users interact" notice.
- [ ] **Target audience**: 18+ only (sign-in with a phone number, call and SMS handling); "Not designed for
      children".
- [ ] **Ads**: "No ads". **News app**: No. **Government app**: No. **Financial features**: none.
- [ ] **Account deletion.** In the app: Me → Delete account (`DELETE /v1/me`). Play also asks for a web URL where
      someone without the app can request deletion; the server has no such page yet (`docs/phase.md` open issues).
      Until one exists, give the privacy policy's section on deletion with a contact address that handles requests.

## 3. Store listing

- [ ] Title, short and full description from `fastlane/metadata/android/en-US/`; screenshots (issue 111); the
      512 × 512 icon `fastlane/metadata/android/en-US/images/icon.png`; a 1024 × 500 feature graphic (Play requires
      one).
- [ ] Contact e-mail and website; the privacy policy URL.
- [ ] Bengali and Hindi listings only after the native speakers' review (issue 171).

## 4. Testing tracks

- [ ] **Internal test** with the owner's phones: the device steps of `device-test-plan.md` that need a Play build
      (billing with license testers, FCM push, Drive backup).
- [ ] **Closed test**: a new personal developer account needs a closed test with enough opted-in testers for a
      continuous period (12 testers for 14 days when last checked; Play Console shows the current numbers on the
      production access page) before it can apply for production. Collect crash-free sessions in Android vitals meanwhile (issue 173).

## 5. Production: staged rollout

Follow `play-staged-rollout.md`: 10 % → 50 % → 100 %, at least 3 days per stage, halt on a new crash cluster, crash
rate ≥ 1.09 % or ANR rate ≥ 0.47 %, or a rise in wrongly blocked or wrongly rung calls; a halted release is fixed
with a new `versionCode`, never by resuming it.

## 6. After the rollout

- [ ] F-Droid gets the same tag automatically (`fdroid.md`); check its build log a few days later.
- [ ] Raise `MIN_APP_VERSION_CODE` on the server if the release planned it.
- [ ] Note the release in `docs/phase.md` (log) and close the release's issue.
