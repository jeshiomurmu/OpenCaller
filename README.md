# OpenCaller

> Working name. Check GitHub, Play Store and trademark search for conflicts before the first public release.

OpenCaller is an open-source, privacy-first caller ID, spam blocker, dialer and SMS app for **Android**, with a self-hostable backend. It aims for feature parity with Truecaller (free and Premium tiers) without harvesting anyone's address book.

- **License:** GPL-3.0-or-later (required because we reuse GPL-3.0 code from Fossify Phone and Fossify Messages)
- **Platform:** Android 10 (API 29) and newer. `targetSdk 36` (Google Play requirement since 31 Aug 2026)
- **Distribution:** GitHub Releases (APK), F-Droid (`foss` flavor), Google Play (`play` flavor)
- **Status:** Phase 0, planning. See [`docs/phase.md`](docs/phase.md)

## What makes it different

| Truecaller | OpenCaller |
| --- | --- |
| Names largely crowdsourced from other people's contact lists | Names only from people who register their own number, verified businesses, and official sender registries |
| Closed source, single company server | GPL-3.0 app, self-hostable server, federation-ready |
| Server sees every number you look up | On-device spam list + k-anonymous bucket lookups; the server never sees a single looked-up number during live caller ID |
| Ads in free tier | No ads. Server-cost features (AI assistant, recording line, voice-clone scanner) are paid on the hosted instance or free when you self-host with your own keys |

## Feature map

Every Truecaller feature, free and Premium, is in scope. Full list with acceptance criteria: [`docs/prd.md`](docs/prd.md).

- **Identify:** live caller ID, number search, verified business caller ID with logo and call reason, caller ID for WhatsApp/Telegram calls, video caller ID, SMS sender ID including OTP senders
- **Protect:** community spam list, auto-block top spammers, block hidden / foreign / non-contact numbers, regex and prefix rules, STIR/SHAKEN verdict, SMS spam and phishing filter, OTP safety guard, AI voice-clone scanner, family protection
- **Communicate:** full dialer, call log, contacts, in-call screen, SMS/MMS app with smart inbox, free VoIP calls, Flash messages
- **Premium-equivalent:** who viewed my profile, who searched for me, incognito mode, contact requests, supporter badge, Ghost Call, Announce Caller, call recording via recording line, AI call-screening assistant, family plan, backup and restore

## Repository layout

```
opencaller/
├── README.md                    ← you are here
├── CLAUDE.md                    ← entry point for AI coding agents (imports docs/rules.md)
├── .cursorrules                 ← same rules for Cursor
├── .gitignore
├── docs/
│   ├── prd.md                   ← what we build, for whom, MVP scope, non-goals
│   ├── architecture.md          ← app flow, stack, folder structure, schemas, APIs
│   ├── rules.md                 ← rules for AI agents and humans
│   ├── implementation_plan.md   ← all phases as checklists
│   ├── phase.md                 ← CURRENT phase, progress log, handoff notes
│   ├── design.md                ← colors, type, components, UX rules
│   ├── testing.md               ← test strategy, test cases, manual QA, bug log
│   ├── prompts.md               ← step-by-step build prompts, zero → GitHub release
│   └── decisions/               ← architecture decision records (ADRs)
├── .github/
│   ├── ISSUE_TEMPLATE/          ← bug and feature templates
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── workflows/               ← CI and release pipelines (created in Phase 0 / 15)
├── android/                     ← Gradle project for the app
│   ├── app/                     ← application module, flavors: foss, play
│   ├── build-logic/convention/  ← Gradle convention plugins
│   ├── core/
│   │   ├── common/              ← dispatchers, Result types, logging
│   │   ├── model/               ← pure Kotlin domain models
│   │   ├── phonenumber/         ← libphonenumber wrapper, E.164 normalization
│   │   ├── database/            ← Room + SQLCipher, DAOs, migrations
│   │   ├── datastore/           ← settings (Proto/Preferences DataStore)
│   │   ├── network/             ← Retrofit/OkHttp API client, auth
│   │   ├── data/                ← repositories, sync workers
│   │   ├── designsystem/        ← theme, tokens, base components
│   │   ├── ui/                  ← shared composables (caller card, OTP card…)
│   │   ├── telephony/           ← role management, screening engine, MMI helpers
│   │   ├── ml/                  ← on-device SMS classifier (LiteRT)
│   │   └── testing/             ← fakes, test rules, fixtures
│   └── feature/
│       ├── onboarding/   callerid/   blocking/   search/    profile/
│       ├── dialer/       calllog/    contacts/   incall/    messages/
│       ├── otp/          business/   premium/    social/    backup/
│       ├── voip/         family/     recording/  assistant/ scanner/
│       └── settings/
├── backend/
│   ├── server/                  ← Kotlin + Ktor API server
│   ├── agents/                  ← Python LiveKit Agents: AI assistant, voice-clone scanner
│   └── infra/migrations/        ← Flyway SQL migrations
├── ml/
│   ├── sms-classifier/          ← training + export to .tflite (LiteRT)
│   └── voice-spoof/             ← AASIST-family anti-spoofing service
└── infra/
    ├── docker/                  ← docker-compose for local + single-VPS deploy
    └── deploy/                  ← production deployment notes and scripts
```

## How to build it

This repo is designed to be built step by step by an AI coding agent (Claude Code, Cursor) with a human reviewing each phase.

1. Read [`docs/prd.md`](docs/prd.md) and [`docs/architecture.md`](docs/architecture.md).
2. Open [`docs/prompts.md`](docs/prompts.md) and run the prompts in order. Each prompt ends with tests, a commit, and an update to [`docs/phase.md`](docs/phase.md).
3. Never skip a phase's exit criteria.

## Requirements (developer machine)

- JDK 17 or newer (Android Gradle Plugin requirement)
- Android Studio (latest stable) with Android SDK Platform 36
- Docker + Docker Compose (backend)
- Python 3.11+ (ML and agents)
- A physical Android 10+ phone with a SIM for telephony QA (the emulator covers most, not all, cases; see [`docs/testing.md`](docs/testing.md))

## Paid third-party services (only for some features)

| Feature | Needs | Without it |
| --- | --- | --- |
| Phone-number sign-up | SMS/flash-call OTP provider | Self-hosted: OTP printed in server log (dev only) |
| AI assistant, recording line | Telephony provider with SIP trunk + phone numbers, STT/LLM/TTS keys | Feature hidden |
| Voice-clone scanner | Same telephony path + GPU/CPU for the model | Feature hidden |
| Push notifications | FCM (`play`) or UnifiedPush (`foss`) | Polling fallback |

All keys are configured on the server via environment variables. Nothing secret ships in the APK.
