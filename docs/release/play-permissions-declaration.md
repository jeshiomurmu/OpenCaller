# Play Console: SMS and Call Log permissions declaration (v0.5.0-beta)

Play Console → App content → Sensitive app permissions → **SMS and Call Log permissions**. The play build declares
these restricted permissions (merged manifest):

| Group | Permissions | Used only while OpenCaller holds |
| --- | --- | --- |
| SMS | `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` | the default SMS app role (`ROLE_SMS`) |
| Call Log | `READ_CALL_LOG`, `WRITE_CALL_LOG` | the default phone app role (`ROLE_DIALER`) |

Caller ID and blocking alone use the call screening role (`ROLE_CALL_SCREENING`) and need neither group.

## Answers

**Does your app request SMS or Call Log permissions?** Yes.

**Is the app the default handler?** Yes, for both: *Default SMS handler* and *Default Phone handler*. The user chooses
this in onboarding or later in Settings → Permissions & roles. OpenCaller asks Android for the role first
(`RoleManager.createRequestRoleIntent`, after an explainer screen); the SMS permissions come with the role and are
never requested on their own. The call log is read only while the app is the default phone app.

**Core functionality** (choose): *Default SMS handler*; *Default Phone handler*.

**Describe how the app uses the permissions** (paste):

> OpenCaller is a caller ID, spam blocker, phone app and SMS app. As the default phone app it shows the user's call
> history (READ_CALL_LOG) and lets them delete entries (WRITE_CALL_LOG). As the default SMS app it shows, sends and
> receives the user's text and picture messages (READ_SMS, SEND_SMS, RECEIVE_SMS, RECEIVE_MMS, RECEIVE_WAP_PUSH), sorts
> them into a smart inbox and warns about one-time codes that arrive during a call with a stranger. All of this
> happens on the device. Call log entries and message content are never uploaded, sold or shared; they never leave
> the phone. Users who don't make OpenCaller their default phone or SMS app still get caller ID and blocking through
> the call screening role, without these permissions.

**Demo video** (Play asks for a link): record on a phone with the play build:

1. Fresh install → onboarding → *Make OpenCaller your phone app* → the system role dialog → Allow → Calls tab shows
   the call log.
2. Me → Settings → Permissions & roles → *Default SMS app* → the system dialog → Allow → Messages tab shows the
   conversations; receive an SMS and reply.
3. Show that without the roles the Calls tab offers the role and doesn't show the call log, and the Messages tab
   offers the SMS role.

Upload it unlisted (for example YouTube) and paste the link.

## Other declarations the same release triggers

- **Full-screen intent** (`USE_FULL_SCREEN_INTENT`, Android 14+): core use is *calling* (incoming calls over the
  lock screen as the default phone app); the OTP warning during a call belongs to the call experience
  (`docs/phase.md` issue 95).
- **Phone permission** (`READ_PHONE_STATE`, `READ_PHONE_NUMBERS`, `CALL_PHONE`): not restricted, but the store
  listing should say why (call status for the caller card; SIM choice on dual-SIM phones; placing calls from the dial
  pad).
- **Display over other apps** (`SYSTEM_ALERT_WINDOW`): optional caller card over other apps in screening-only mode;
  the user turns it on.
- **Contacts** (`READ_CONTACTS`, `WRITE_CONTACTS`): read on the phone to let contacts ring and show their names; the
  contacts editor writes them. Never uploaded.
