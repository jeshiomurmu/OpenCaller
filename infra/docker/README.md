# Local stack (infra/docker)

`compose.yml` runs PostgreSQL, Redis, MinIO, the API server, the video transcoder and LiveKit for development and
CI (`scripts/smoke.sh`). It is not a production deployment; that is Phase 15 (`infra/deploy`).

```bash
cp infra/docker/.env.example infra/docker/.env   # replace every change-me value
scripts/vapid-keys.sh >> infra/docker/.env        # Web Push / UnifiedPush keys (optional; app calls need a push provider)
docker compose -f infra/docker/compose.yml up -d --build --wait
scripts/smoke.sh
```

## Services and ports

Every published port is bound to `127.0.0.1` unless noted.

| Service | Port | Protocol | What for |
| --- | --- | --- | --- |
| server | 8080 | TCP (HTTP) | The API. 9090 (metrics, admin jobs) and the admin web stay inside the container. |
| postgres | 5432 | TCP | Database |
| redis | 6379 | TCP | Rate limits, leader locks |
| minio | 9000, 9001 | TCP (HTTP) | Object store (S3 API, console); apps upload and download through pre-signed URLs |
| transcoder | none | | Turns uploaded video caller IDs into small MP4s with ffmpeg. Read-only file system, no capabilities, no published port |
| livekit | 7880 | TCP (HTTP, WebSocket) | LiveKit signalling and API (`LIVEKIT_URL`) |
| livekit | 7881 | TCP | ICE over TCP, for networks that block UDP |
| livekit | 3478 | UDP | Embedded TURN |
| livekit | 50000–50100 | UDP | Media (RTP/RTCP) |
| livekit | 50101–50200 | UDP | TURN relay to the SFU |

LiveKit's ports are bound to `LIVEKIT_BIND` (default `127.0.0.1`). The ranges are kept small for a development
machine (each call uses a few ports). `livekit/livekit.yaml` holds the LiveKit settings; the API key and secret come
from `.env` (`LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`) and are shared with the server, which signs room tokens.

## Calling between two phones (TC-VOIP-001)

Phones have to reach LiveKit's signalling port and its media ports:

1. Set `LIVEKIT_BIND=0.0.0.0`, `LIVEKIT_NODE_IP=<this machine's address as phones see it>` and
   `LIVEKIT_URL=ws://<that address>:7880` in `.env`, then `docker compose up -d`.
2. Open 7880/tcp, 7881/tcp, 3478/udp and 50000–50200/udp in the machine's firewall (and forward them on the router
   when one phone is on mobile data and the machine is behind NAT).
3. The apps must reach the API too (the phone's server address setting, or `opencaller.apiBaseUrl`).

`NODE_IP` is the address LiveKit advertises in its ICE candidates. Inside Docker, LiveKit can't discover it (it would
find the container's own address), so compose sets it from `LIVEKIT_NODE_IP`. On a VPS with a public address, use
that address.

## TLS

Plain `ws://` is acceptable on a development machine only. The server refuses `ws://` for `LIVEKIT_URL` when
`ENV=production`. In production:

- **Signalling (7880):** put LiveKit behind the same TLS reverse proxy as the API (for example a `livekit.` host
  name proxied to 7880 with WebSocket upgrade) and set `LIVEKIT_URL=wss://livekit.example.org`.
- **Media (UDP and 7881/tcp):** no TLS needed. WebRTC encrypts media itself (DTLS-SRTP). These ports can't sit behind
  an HTTP proxy or load balancer; expose them on the host.
- **TURN over TLS:** networks that allow only HTTPS need TURN on 443/TLS. LiveKit's embedded TURN then needs a domain
  and its certificate: set `turn.tls_port: 5349` (or 443 on a host with no other service on it), `turn.domain`, and
  `turn.cert_file`/`turn.key_file` (or `LIVEKIT_TURN_CERT`/`LIVEKIT_TURN_KEY`), or terminate TLS at a layer-4 load
  balancer with `turn.external_tls: true`. The local stack runs UDP TURN only (`tls_port: 0`), which needs no
  certificate.

## Push providers

App calls and Flash messages need a push provider; without one, `GET /v1/server` reports `appCalls: false` and
`flash: false`. Each push carries only a random id (ADR 0007).

- **Web Push / UnifiedPush (foss builds):** `scripts/vapid-keys.sh` prints a key pair; add `VAPID_SUBJECT`
  (`mailto:` or `https:` contact). Changing the key pair makes every existing registration invalid until the apps
  register again. In production the server only sends to `https://` endpoints on public addresses.
- **FCM (play builds):** a Firebase service-account key file mounted into the server container
  (`FCM_SERVICE_ACCOUNT_FILE`) and `FCM_PROJECT_ID`.
