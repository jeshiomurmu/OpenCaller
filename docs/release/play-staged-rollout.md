# Play production: staged rollout of v1.0.0

For the owner, who submits it in Play Console → Production → Create new release. Upload the App Bundle from the
`v1.0.0` release workflow run (artifact `OpenCaller-v1.0.0-play-aab`), not a debug or foss build. Before the first
production release: the privacy policy URL, the Data safety form (`play-data-safety.md`), the SMS and Call Log
declaration (`play-permissions-declaration.md`) and the content rating must be complete, and the closed test must
have run (Play requires it for new personal developer accounts).

## Release name

`1.0.0 (1000099)`

## Release notes (en-US, at most 500 characters)

Copy `fastlane/metadata/android/en-US/changelogs/1000099.txt`.

## Rollout plan

| Stage | Share of users | Move on when, after at least | Halt when |
| --- | --- | --- | --- |
| 1 | 10 % | 3 days | see "Halt" below |
| 2 | 50 % | 3 more days | see "Halt" below |
| 3 | 100 % | — | — |

At each stage check, in Play Console → Android vitals and the server's metrics:

- user-perceived crash rate below 1.09 % and ANR rate below 0.47 % (Play's bad-behaviour thresholds), and no crash
  cluster new in 1.0.0;
- no rise in calls ringing that should have been blocked, or blocked calls that should have rung (reviews, issues,
  "Not spam" reports per day);
- the server: 5xx rate under 0.1 %, lookup p95 under 50 ms, push delivery (family alerts, app calls) without errors in
  the log.

## Halt

Play Console → Production → Manage rollout → Halt rollout, when any check above fails, when a privacy problem is
reported (anything leaving the phone that the Data safety form doesn't list), or when emergency or outgoing calls are
affected in any way. Fix, raise the version (1.0.1, versionCode 1000199), and start again at 10 %. A halted release
keeps users who already have it; Play can't roll back an installed version.

## F-Droid

F-Droid builds from the `v1.0.0` tag with `docs/release/fdroiddata-io.github.jeshiomurmu.opencaller.yml`
(`UpdateCheckMode: Tags` picks the tag up). F-Droid has no staged rollout; its build usually appears a few days after
the tag.
