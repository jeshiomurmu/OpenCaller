# Design system — OpenCaller

Material 3 base with our own tokens. Implemented in `core:designsystem`. **Never hard-code a color, size or font in feature code; use the tokens.**

## 1. Design principles

1. **Decide in one glance.** The caller card must answer "answer or not?" in under a second: label, risk color, source.
2. **Color carries meaning, so it's reserved.** Red only for danger, amber only for suspicious, green only for verified/safe, blue only for business. Brand teal never signals risk.
3. **Always show the source.** A name without "where it came from" is not shown.
4. **Calm by default, loud when it matters.** Normal screens are quiet; the OTP-during-call warning is the loudest thing in the app.
5. **Works for everyone:** 200% font scale, TalkBack, any script (Bangla, Devanagari, Arabic, Latin), one-handed use.

## 2. Color tokens

Dynamic color (Material You) is **off** for semantic tokens and **optional** for neutral surfaces (Settings → Appearance). All pairs below were checked with the WCAG 2.x formula; ratios are listed.

### Light

| Token | Hex | Used for | Contrast |
| --- | --- | --- | --- |
| `primary` | `#0B6E69` | Brand, primary buttons, links | 6.09 vs `onPrimary` |
| `onPrimary` | `#FFFFFF` | Text on primary | |
| `surface` / `background` | `#FAFAF9` | Screens | |
| `onSurface` | `#1B1C1C` | Body text | 16.35 |
| `onSurfaceVariant` | `#4A4F55` | Secondary text | 7.91 |
| `outline` | `#747A80` | Borders, dividers (UI 3:1 rule) | 4.16 |
| `danger` | `#B3261E` | Spam/scam label, block actions | 6.26 vs surface |
| `onDanger` | `#FFFFFF` | Text on danger fill | 6.54 |
| `dangerContainer` / `onDangerContainer` | `#F9DEDC` / `#410E0B` | Spam card background | 12.77 |
| `warning` text / `warningContainer` | `#7A4600` / `#FFF1DC` | Suspicious label | 6.98 |
| `safe` | `#146C2E` | Verified, contact, "not spam" | 6.26 |
| `business` | `#1F5FBF` | Verified business badge, business name | 5.83 |
| `gold` (supporter) | `#8A6A00` | Gold badge text/icon | 4.85 vs surface |

### Dark

| Token | Hex | Contrast vs `surface` `#1A1C1E` |
| --- | --- | --- |
| `surface` / `background` | `#1A1C1E` / `#111314` | |
| `onSurface` | `#E3E3E3` | 13.32 |
| `onSurfaceVariant` | `#C2C7CE` | 10.05 |
| `primary` / `onPrimary` | `#5FD4CB` / `#00201E` | 9.58 / 9.60 |
| `danger` | `#FFB4AB` | 10.07 |
| `warning` | `#FFB95C` | 10.05 |
| `safe` | `#7DDB8F` | 10.09 |
| `business` | `#9EC2FF` | 9.45 |
| `outline` | `#8C9199` | 5.39 |
| `gold` | `#E9C46A` | 10.23 |

Rule: risk is **never** communicated by color alone. Every risk color is paired with an icon and a word ("Likely scam").

## 3. Typography

- **Font family:** Noto Sans (SIL Open Font License) bundled for Latin; Noto Sans Bengali, Noto Sans Devanagari, Noto Sans Arabic loaded as downloadable fonts or bundled per locale; system fallback for everything else. Reason: one consistent family across the scripts of our launch countries.
- **Codes and numbers:** Noto Sans Mono for OTP codes and phone numbers in the dialer (tabular digits, no 0/O confusion).
- **Scale** (Material 3 roles, `sp`):

| Role | Size / line height | Weight | Use |
| --- | --- | --- | --- |
| `displaySmall` | 36 / 44 | 400 | Dialer number entry |
| `headlineMedium` | 28 / 36 | 500 | Caller name on in-call/caller card |
| `titleLarge` | 22 / 28 | 500 | Screen titles |
| `titleMedium` | 16 / 24 | 500 | List item titles, card titles |
| `bodyLarge` | 16 / 24 | 400 | Body text, messages |
| `bodyMedium` | 14 / 20 | 400 | Secondary text |
| `labelLarge` | 14 / 20 | 500 | Buttons |
| `labelMedium` | 12 / 16 | 500 | Chips, badges |
| `otpCode` (custom) | 32 / 40 mono | 600 | OTP card code |

Never set text below 12 sp. All text must survive 200% font scale without clipping (test with previews at `fontScale = 2f`).

## 4. Spacing, shape, elevation

- Spacing scale (dp): 4, 8, 12, 16, 24, 32, 48. Screen side padding 16 dp.
- Touch targets ≥ 48 × 48 dp.
- Corner radius: chips 8, cards 16, sheets 28 (top), buttons full-pill.
- Elevation: use tonal surfaces, not shadows, except the caller-card overlay (level 3 so it reads above other apps).

## 5. Core components (in `core:ui`)

| Component | Anatomy | States |
| --- | --- | --- |
| `CallerCard` | Avatar/logo · name (`headlineMedium`) · `SourceChip` · risk banner · meta row (country, line type, report count) · actions | Contact, Self-registered, Verified business, Spam, Suspicious, Unknown, Hidden |
| `SourceChip` | Icon + label: Contact / Self-registered / Verified business / Sender registry / Community label | |
| `RiskBanner` | Icon + "Likely scam · Loan · 214 reports" on `dangerContainer`; amber variant for Suspicious | |
| `VerifiedBadge` | Blue check + "Verified business"; tooltip explains how verified | |
| `OtpCard` | Brand + source · code (`otpCode`) · copy · expiry countdown · "Never share this code" line | Normal, Expired, Risky (during call) |
| `OtpGuardScreen` | Full-screen danger: big icon, "Someone on a call is asking for your code?", buttons "Hang up", "I requested this" | |
| `RuleRow` | Pattern · type · action · test button | Enabled/disabled |
| `PermissionExplainer` | Illustration · why · what we never do · Continue / Not now | |
| `EmptyState` | Icon · one sentence · one action | |

## 6. Screens and navigation

Bottom navigation (5): **Calls** (log + dialer FAB) · **Messages** · **Contacts** · **Protection** (blocking, family, scam feed) · **Me** (profile, supporter, settings).

Key screens: Onboarding (5 steps max), Caller card overlay, In-call, After-call, Search, Blocked log, Rules editor, Smart inbox, Thread, OTP guard, Profile, Viewers, Business profile, Claim business, Supporter plans, Settings.

## 7. UX rules

- **Permissions:** explainer screen first, one permission at a time, never at app start without context. "Not now" always available; app degrades gracefully with a banner.
- **Blocking is reversible:** every blocked call/SMS can be unblocked from the log in one tap; show a snackbar "Blocked +880 ••• 1234 · Undo".
- **Labels explain themselves:** tapping any label opens "Why am I seeing this?" (source, report count, date).
- **No dark patterns:** no fake urgency, no pre-ticked upsells, cancel subscription in ≤ 2 taps, supporter features never nag during calls.
- **Calls are sacred:** nothing (ads, upsells, rating prompts) appears on the in-call or caller-card UI.
- **Offline first:** every screen works offline; network-only features show a clear offline state.
- **Numbers display** in the user's national format when same country, international format otherwise (libphonenumber formatting).

## 8. Error and empty states (copy)

| Situation | Message | Action |
| --- | --- | --- |
| Offline | "You're offline. Blocking still works with your saved spam list." | — |
| Rate limited (search) | "You've used today's 50 searches. More tomorrow, or become a supporter." | Plans |
| Role lost | "OpenCaller can't screen calls right now. Turn call screening back on." | Fix |
| Server error | "Something went wrong on our side. Your protection still works offline." | Retry |
| No data for number | "No reports. Not a registered business." | Report / Save |

Tone: plain, calm, short sentences, no blame, no jargon ("E.164", "bucket", "PIR" never appear in UI).

## 9. Motion and haptics

- Standard Material motion durations (short 100–200 ms, medium 250–400 ms). Respect "Remove animations" system setting.
- Haptic on block action and on OTP guard appearance. No haptics during an active call except for keypad.

## 10. Iconography and illustration

Material Symbols (Rounded) only. Business logos are user-uploaded, displayed in a 40 dp circle with a neutral fallback monogram. No stock photos.

## 11. Accessibility checklist (every screen)

- [ ] All interactive elements have `contentDescription` / semantics labels
- [ ] Focus order logical; caller card actions reachable by TalkBack within 3 swipes
- [ ] Works at 200% font and display size "Largest"
- [ ] Contrast ≥ 4.5:1 text, ≥ 3:1 UI (tokens above already comply)
- [ ] No information by color only
- [ ] Announce Caller and OTP guard tested with TalkBack on
