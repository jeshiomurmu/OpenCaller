# Release texts

Texts the owner submits by hand for v1.0.0 (they were first written for v0.5.0-beta). Each is written from the code as it stands (permissions from the
merged manifests, data from `docs/architecture.md` §5 and §10 and the server's tables). Re-check them when a release
adds a permission, a server table or an SDK.

| File | Where it goes |
| --- | --- |
| `fdroid-rfp.md` | A new issue at https://gitlab.com/fdroid/rfp/-/issues |
| `fdroiddata-io.github.jeshiomurmu.opencaller.yml` | Draft for `metadata/` in fdroiddata, if F-Droid asks for a merge request |
| `play-data-safety.md` | Play Console → App content → Data safety |
| `play-permissions-declaration.md` | Play Console → App content → Sensitive app permissions (SMS and Call Log) |
| `play-release-checklist.md` | The order of everything before a Play submission (build, app content, listing, tracks) |
| `signing.md` | Creating the release key, the four GitHub secrets, tagging a release, testing the pipeline |
| `fdroid.md` | F-Droid metadata, the foss dependency check, reproducible builds, submitting |
| `device-test-plan.md` | Every device step CI can't run, in one order, with a sign-off table |
| `play-staged-rollout.md` | Play Console → Production: release notes and the 10 % → 50 % → 100 % rollout with its halt checks |

The store listing itself (title, descriptions, changelog) is in `fastlane/metadata/android/en-US/`, which both F-Droid
and Play's upload tools read.
