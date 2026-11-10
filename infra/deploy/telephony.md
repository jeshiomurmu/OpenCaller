# Telephony: the recording line (LiveKit SIP + Egress)

How a server operator connects phone numbers to OpenCaller's recording line (docs/prd.md F-UT-03, ADR 0009).
The guide follows LiveKit's official documentation only (docs.livekit.io, read 2026-11-10); every LiveKit setting
below links to the page it comes from. Nothing here is specific to one SIP provider except the
[Twilio section](#twilio-elastic-sip-trunking), which follows LiveKit's own Twilio quickstart.

## How a recording works

```
 phone (the user's call with the other party, held)
   │  1. app: POST /v1/lines/recording → line number + one-time code
   │  2. app dials the line, sends the code as DTMF, then merges both calls (conference)
   ▼
 SIP provider (e.g. Twilio Elastic SIP Trunking) ──SIP──▶ LiveKit SIP (livekit/sip)
                                                           │ inbound trunk: accepts calls to the line's number
                                                           │ dispatch rule: a new room per call, dispatches "recorder"
                                                           ▼
                                                      LiveKit room ◀── recorder agent (backend/agents)
                                                           │            reads the code, claims the session,
                                                           │            plays the beep + announcement,
                                                           ▼            starts a track egress
                                                      LiveKit Egress (livekit/egress) ──S3──▶ MinIO recordings/
                                                                                              │
 server: push "recording_ready" (an opaque id) → app downloads → server deletes the file (≤ 1 h after the
 download is confirmed, 24 h at the latest)
```

The phone's conference is mixed by the network, so the SIP participant's single audio track carries both parties.
The agent records that track ([track egress](https://docs.livekit.io/transport/media/ingress-egress/egress/track.md):
audio is re-encoded as stereo Opus in an OGG file), which needs no browser and little CPU.

## Legal switch (read this first)

Recording is offered only to users whose phone number belongs to a country in the server's approved list:

- `RECORDING_COUNTRIES` (ISO 3166 codes, comma-separated) sets the list. OpenCaller's default is `BD,IN`
  (Bangladesh and India, the owner's decision of 2026-11-10).
- The admin web (`/recording-countries`) can switch a country on or off; every change is written to the audit log.
- Every other country stays **off**. `infra/docker/.env.example` lists placeholders for other countries, all
  commented out. Turning one on is strictly the operator's decision and must be approved after the operator's own
  legal review of that country's call-recording and consent law (all-party consent states and countries need
  more than a beep). OpenCaller is open source: a self-hoster changes the list to fit their own legal advice.
- Both parties always hear a beep and the announcement; the app reminds the user that they are responsible for the
  law where the other party is (ADR 0009).

## What you need

| Part | Image / package | Notes |
| --- | --- | --- |
| LiveKit server | `livekit/livekit-server:v1.13.11` (already in `infra/docker/compose.yml`) | Needs Redis once SIP or Egress run ([SIP server](https://docs.livekit.io/transport/self-hosting/sip-server.md)) |
| LiveKit SIP | `livekit/sip:v1.23.0` | The same Redis as the LiveKit server; host networking (RTP port range) |
| LiveKit Egress | `livekit/egress:v1.15.0` | The same Redis; LiveKit recommends at least 4 CPUs and 4 GB per instance ([Egress service](https://docs.livekit.io/transport/self-hosting/egress.md)) |
| Recorder agent | `backend/agents` (Python, `livekit-agents` 1.8.11) | Joins each recording room; talks to the server's agent API |
| Transcriber (optional) | `backend/agents` (`faster-whisper`, self-hosted) | Only for users who turned transcripts on |
| A phone number per country | from your SIP provider | Callers in that country dial it as a local call |
| `lk` (LiveKit CLI) | [livekit-cli](https://docs.livekit.io/reference/developer-tools/livekit-cli.md) | Creates the trunk and the dispatch rule |

## 1. Run SIP, Egress and the agent

The development stack has a telephony overlay that leaves the default stack unchanged:

```shell
cp infra/docker/.env.example infra/docker/.env      # fill in the recording section too
docker compose -f infra/docker/compose.yml -f infra/docker/compose.telephony.yml up -d --build
```

The overlay (`infra/docker/compose.telephony.yml`):

- points the LiveKit server at the stack's Redis (`REDIS_HOST`, `REDIS_PASSWORD`; flags of `livekit-server`
  v1.13.11, `cmd/server/main.go`);
- runs `livekit/sip` with **host networking** (LiveKit's own compose file does the same: Docker handles the
  10000–20000 RTP range badly) and `SIP_CONFIG_BODY`:
  ```yaml
  api_key: <LIVEKIT_API_KEY>
  api_secret: <LIVEKIT_API_SECRET>
  ws_url: ws://127.0.0.1:7880
  redis:
    address: 127.0.0.1:6379
    password: <REDIS_PASSWORD>
  sip_port: 5060
  rtp_port: 10000-20000
  use_external_ip: true
  ```
  (keys from the [SIP server page](https://docs.livekit.io/transport/self-hosting/sip-server.md) and the
  `livekit/sip` README);
- runs `livekit/egress` with host networking and `EGRESS_CONFIG_BODY` (`api_key`, `api_secret`, `ws_url`,
  `redis`, `health_port`); the S3 target (MinIO) is passed by the agent with each request
  ([`S3Upload`](https://docs.livekit.io/reference/other/egress/api.md#s3upload), `force_path_style: true` for MinIO);
- runs the recorder agent (`python -m opencaller_agents.recorder start`) with host networking.

Host networking works on Linux only. On a production host, run the same images with the same settings (or as
systemd services) and keep Redis and port 7880 off the internet.

## 2. Open the firewall

From LiveKit's [ports and firewall](https://docs.livekit.io/transport/self-hosting/ports-firewall.md) page:

| Port | Protocol | Open to | Purpose |
| --- | --- | --- | --- |
| 5060 | UDP and TCP | your SIP provider's signalling addresses only | SIP signalling (`sip_port`) |
| 5061 | TCP | your SIP provider only | SIP over TLS (`tls.port`), see [Security](#security) |
| 10000–20000 | UDP | your SIP provider's media addresses | RTP (`rtp_port`) |
| 7880 | TCP | nobody (internal; TLS proxy for apps) | LiveKit API and WebSocket |

Restrict 5060/5061 and the RTP range to the address ranges your provider publishes. Use only the provider's
official, current list (Twilio publishes its Elastic SIP Trunking signalling and media ranges in its own docs); do
not copy addresses from elsewhere, they change.

Your SIP URI is `<public-ip-address>:5060` (LiveKit: "Determine your SIP URI"); with a DNS name, `<host>:5060`.

## 3. Create one inbound trunk per line

An inbound trunk accepts calls to your numbers ([Inbound trunk](https://docs.livekit.io/telephony/accepting-calls/inbound-trunk.md)).
Trunks are long-lived: create them once (one per number is LiveKit's advice), never per call.

Register the CLI with your server ([project management](https://docs.livekit.io/reference/developer-tools/livekit-cli/projects.md)):

```shell
lk project add opencaller --url http://127.0.0.1:7880 --api-key "$LIVEKIT_API_KEY" --api-secret "$LIVEKIT_API_SECRET" --default
```

`inbound-trunk.json` (replace the number with your line's number in E.164; one file per country):

```json
{
  "trunk": {
    "name": "OpenCaller recording line BD",
    "numbers": ["+8800000000000"],
    "allowed_addresses": ["<your provider's signalling CIDR>", "<another CIDR>"],
    "krisp_enabled": false
  }
}
```

```shell
lk sip inbound create inbound-trunk.json
lk sip inbound list
```

- `numbers`: E.164 only, never a full SIP URI (it would never match).
- `allowed_addresses`: plain IP addresses or CIDR blocks of your provider. On LiveKit Cloud the field must be
  enabled by LiveKit support first; for a self-hosted server, also enforce the same ranges in the firewall (§2).
- Username/password (`auth_username`, `auth_password`) works only if your provider supports it; Twilio Elastic SIP
  Trunking does not.
- Leave `krisp_enabled` off: noise cancellation would alter what is recorded.
- Other fields (`ringing_timeout`, `max_call_duration`, `media`) are listed under
  [`CreateSIPInboundTrunk`](https://docs.livekit.io/reference/telephony/sip-api.md#createsipinboundtrunk).

## 4. Create the dispatch rule

A dispatch rule decides which room each caller joins and which agent is sent there
([Dispatch rule](https://docs.livekit.io/telephony/accepting-calls/dispatch-rule.md)). OpenCaller uses a **callee**
rule with `randomize: true`: the room is named after the line's number (ours, not the user's) plus a random
suffix, so every call gets its own room and the caller's number never appears in a room name (LiveKit warns that
an individual rule's room names carry the caller's number into logs and traces). `hide_phone_number: true` gives
the SIP participant a random identity and leaves the caller's number out of its attributes: the agent never needs
it, the one-time code identifies the session.

`dispatch-rule.json`:

```json
{
  "dispatch_rule": {
    "name": "OpenCaller recording line",
    "rule": {
      "dispatch_rule_callee": {
        "room_prefix": "rec",
        "randomize": true
      }
    },
    "hide_phone_number": true,
    "room_config": {
      "empty_timeout": 30,
      "departure_timeout": 5,
      "max_participants": 4,
      "agents": [{ "agent_name": "recorder" }]
    }
  }
}
```

```shell
lk sip dispatch create dispatch-rule.json --trunks "<trunk-id>[,<trunk-id>…]"
lk sip dispatch list
```

- `agent_name` must match the agent's name (`recorder`, `backend/agents/opencaller_agents/recorder.py`); the agent
  only joins rooms it is explicitly dispatched to.
- `max_participants`: the SIP caller, the agent and the egress recorder, with one spare.
- `room_config` fields: [`RoomConfiguration`](https://docs.livekit.io/reference/server/server-apis.md#roomconfiguration).

## 5. Tell the server about the lines

In the server's environment (see `infra/docker/.env.example`):

| Variable | Example | Meaning |
| --- | --- | --- |
| `RECORDING_LINES` | `+8800000000000,+910000000000` | The lines' numbers; the server picks the one in the user's country |
| `RECORDING_COUNTRIES` | `BD,IN` | Countries where recording is approved (§ Legal switch) |
| `RECORDING_AGENT_TOKEN` | 32+ random characters | Shared by the server and the agents (`/agent/…` endpoints) |
| `RECORDING_DOWNLOAD_GRACE_MINUTES` | `30` | How long a confirmed download stays before deletion (the job runs every 10 minutes, so ≤ 40 min) |

Without `RECORDING_LINES` and `RECORDING_AGENT_TOKEN` the feature is off: `POST /v1/lines/recording` answers
`recording_unavailable`.

## 6. Test it

1. `lk sip inbound list` and `lk sip dispatch list` show the trunk and the rule.
2. Call the line's number from any phone: you hear the beep and the code prompt; after 3 wrong codes or 20 seconds
   without one the agent says so and hangs up. The agent's log shows the room (`rec_<line>_<random>`), never the
   caller's number.
3. In the app (a supporter account, or a self-hosted server, in an approved country): during a call tap Record,
   accept the reminder; the app dials the line, sends the code and merges the calls. Both parties hear the beep and
   the announcement. Hang up: the recording appears in Recordings within a minute; download and play it.
4. `docker compose exec minio mc ls …` (or the MinIO console) shows `recordings/<id>.ogg` until the download has
   been confirmed for `RECORDING_DOWNLOAD_GRACE_MINUTES`, then the object is gone (`recordings.deleted_at` is set).

These are the steps of TC-REC-001 (docs/testing.md), which must pass on a real SIM in a launch country.

## Twilio Elastic SIP Trunking

From LiveKit's [Twilio quickstart](https://docs.livekit.io/telephony/start/providers/twilio.md) (inbound only; the
recording line never calls out). Install the Twilio CLI and sign in with a profile first.

1. Buy a phone number in each launch country (the Twilio console; local regulations for Bangladesh and India
   numbers apply, e.g. address and identity documents).
2. Create a trunk; the domain must end in `pstn.twilio.com`:
   ```shell
   twilio api trunking v1 trunks create \
     --friendly-name "OpenCaller recording" \
     --domain-name "opencaller-recording.pstn.twilio.com"
   ```
3. Point its origination at your LiveKit SIP server:
   ```shell
   twilio api trunking v1 trunks origination-urls create \
     --trunk-sid <twilio_trunk_sid> \
     --friendly-name "LiveKit SIP URI" \
     --sip-url "sip:<public-ip-or-host>:5060;transport=tcp" \
     --weight 1 --priority 1 --enabled
   ```
   For a fallback server add another origination URL with a higher `--priority` (Twilio tries the lowest first;
   `weight` only spreads load between equal priorities).
4. Attach each number to the trunk:
   ```shell
   twilio phone-numbers list
   twilio api trunking v1 trunks phone-numbers create \
     --trunk-sid <twilio_trunk_sid> \
     --phone-number-sid <twilio_phone_number_sid>
   ```
5. Create the LiveKit inbound trunk (§3) with the numbers and Twilio's signalling ranges in `allowed_addresses`,
   and the dispatch rule (§4).

Twilio Elastic SIP Trunking has no username/password on inbound calls; restrict by address. (LiveKit also documents
[inbound calls through TwiML](https://docs.livekit.io/telephony/accepting-calls/inbound-twilio.md), which supports
credentials; OpenCaller doesn't use it.)

## Security

- **Signalling and media encryption.** LiveKit documents [secure trunking](https://docs.livekit.io/telephony/features/secure-trunking.md):
  enable Secure Trunking on the Twilio trunk, use `;transport=tls` in the origination URL (port 5061) and set the
  trunk's media encryption (`media.encryption`: `SIP_MEDIA_ENCRYPT_ALLOW` or `SIP_MEDIA_ENCRYPT_REQUIRE`). The
  self-hosted SIP server then needs a certificate for its TLS listener; that part of the SIP server's configuration
  isn't covered by this guide yet (open issue in docs/phase.md). Until then, signalling and media between the
  provider and your server are unencrypted on the internet, like most PSTN trunks.
- **Who can reach the line.** Anyone can dial the number; a call without a valid one-time code gets the beep, a
  prompt and is hung up after 3 tries. Codes are 8 digits, single use, valid 10 minutes, stored as HMAC hashes, and
  the server limits failed claims per line.
- **Credentials.** The agent holds the LiveKit API key, the MinIO credentials for `recordings/` (give it a MinIO
  user that may only write and read that prefix in production) and `RECORDING_AGENT_TOKEN`. Nothing in this setup is
  committed to the repository.
- **What LiveKit sees.** Room names carry the line's number, never the caller's; the SIP participant's identity is
  random (`hide_phone_number`). The audio passes through LiveKit and Egress and is stored only in MinIO.
