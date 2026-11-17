# Release signing

A tag `vX.Y.Z` makes `.github/workflows/release.yml` build, sign and publish the release. The signing key lives only in
four GitHub Actions secrets; nothing key-related is ever committed. The workflow writes the key to a temporary file on
the runner, Gradle reads it through environment variables (`android/app/build.gradle.kts`: `OPENCALLER_KEYSTORE_FILE`,
`OPENCALLER_KEYSTORE_PASSWORD`, `OPENCALLER_KEY_ALIAS`, `OPENCALLER_KEY_PASSWORD`), and the file is deleted at the end
of the job. Without the secrets a real tag stops before building.

One key signs the foss and play APKs and the play App Bundle. It is also the Play upload key.

## 1. Create the key (once, on your own computer)

You need a JDK (`keytool` comes with it). Pick a long password and keep it in your password manager.

```bash
keytool -genkeypair -v \
  -keystore opencaller-release.jks -storetype PKCS12 \
  -alias opencaller -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=OpenCaller, O=<your name or organisation>, C=<two-letter country>"
```

`keytool` asks for the keystore password. A PKCS12 keystore uses the same password for the key, so
`OPENCALLER_KEY_PASSWORD` is the same as `OPENCALLER_KEYSTORE_PASSWORD`.

Check it and note the certificate's SHA-256 (F-Droid and the Play Console show it too):

```bash
keytool -list -v -keystore opencaller-release.jks -alias opencaller | grep -E "Alias|SHA256"
```

**Back it up.** Losing this file or its password means you can never ship an update that installs over existing
OpenCaller installs from GitHub or F-Droid. Keep two copies in different places, for example an encrypted USB stick and
your password manager's file storage. Never commit it, never send it in chat or email, and never paste the password
anywhere but the commands below.

## 2. Add the four secrets

With the [GitHub CLI](https://cli.github.com/) signed in to an account that administers the repository (`gh auth login`):

```bash
repo=jeshiomurmu/OpenCaller
base64 -w 0 opencaller-release.jks | gh secret set OPENCALLER_KEYSTORE_BASE64 --repo "$repo"
gh secret set OPENCALLER_KEYSTORE_PASSWORD --repo "$repo"   # prompts for the value; paste the password
gh secret set OPENCALLER_KEY_ALIAS --repo "$repo" --body opencaller
gh secret set OPENCALLER_KEY_PASSWORD --repo "$repo"        # the same password again
gh secret list --repo "$repo"
```

On macOS use `base64 -i opencaller-release.jks` (it doesn't wrap lines). Without the CLI: repository → Settings →
Secrets and variables → Actions → New repository secret, once per name.

Optional, for builds that talk to your server (leave them unset for an offline build):

```bash
gh secret set OPENCALLER_API_BASE_URL --repo "$repo" --body https://api.example.org
gh secret set OPENCALLER_API_PINS --repo "$repo" --body "sha256/<current key>,sha256/<backup key>"
```

How to compute the pins is in `infra/deploy/README.md` → Certificate pins.

## 3. Release

1. Set `versionName` and `versionCode` in `android/app/build.gradle.kts` (the scheme is in the comment above them) and
   add a `## [X.Y.Z]` section to `CHANGELOG.md`; merge to `main`.
2. Tag and push:

   ```bash
   git tag -a vX.Y.Z -m "OpenCaller X.Y.Z" && git push origin vX.Y.Z
   ```

3. Actions → Release: the checks run, then the signed `-foss.apk`, `-play.apk`, `-play.aab`, both R8 mapping files and
   `SHA256SUMS` are published as a GitHub release (a pre-release when the version has a suffix such as `-beta`), with
   the notes from `CHANGELOG.md`. `.github/workflows/images.yml` pushes the backend images for the same tag.

## Play App Signing and update compatibility

Play re-signs what it delivers with its **app signing key**. If you let Play generate that key, the Play build and the
GitHub/F-Droid builds carry different signatures and can't update one another (users would have to uninstall first).
To keep them interchangeable, choose in Play Console → Setup → App signing to use **your own key** (upload
`opencaller-release.jks` through Play's encryption tool) before the first release; the upload key is then the same key.
If you prefer Play's key, say so in the README: GitHub/F-Droid and Play installs are separate.

## Testing the pipeline

A tag starting with `v0.0.0-ci-test`, pushed from any branch, runs the whole release workflow with a throwaway key
generated inside the job: checks, build, signature verification, `SHA256SUMS`, a pre-release, a fresh download of that
release checked against `SHA256SUMS` and `apksigner verify`, and finally deletion of its own release and tag. Nothing it
signs can update a real install.

```bash
git tag v0.0.0-ci-test-1 && git push origin v0.0.0-ci-test-1
```

The signed files stay available for 90 days as the run's workflow artifact.
