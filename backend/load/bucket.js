// TC-API-011: k-anonymous bucket lookup under load (docs/testing.md). 200 requests per second for 60 s against
// random 16-bit buckets of +880 after seeding 1M numbers (backend/load/seed.sh); every 4th request carries an
// access token if ACCESS_TOKEN is set (registered-only names are then included).
//
//   docker run --rm --network host -v "$PWD/backend/load:/load" grafana/k6:2.3.0 run /load/bucket.js
//
// Env: BASE_URL (default http://localhost:8080), RATE (200), DURATION (60s), ACCESS_TOKEN (optional).
// The pass/fail threshold is p95 < 50 ms. k6 measures from the client, which includes the local network hop;
// backend/load/server_p95.py reads the server's own histogram from /metrics for the server-time figure.
import http from "k6/http";
import { check } from "k6";

const base = __ENV.BASE_URL || "http://localhost:8080";
const token = __ENV.ACCESS_TOKEN || "";

export const options = {
  scenarios: {
    warmup: {
      executor: "constant-arrival-rate",
      rate: 50,
      timeUnit: "1s",
      duration: "10s",
      preAllocatedVUs: 20,
      exec: "lookup",
      tags: { phase: "warmup" },
    },
    lookups: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 200),
      timeUnit: "1s",
      duration: __ENV.DURATION || "60s",
      startTime: "10s",
      preAllocatedVUs: 50,
      maxVUs: 200,
      exec: "lookup",
      tags: { phase: "measure" },
    },
  },
  thresholds: {
    "http_req_duration{phase:measure}": ["p(95)<50"],
    "http_req_failed{phase:measure}": ["rate<0.01"],
    "checks{phase:measure}": ["rate>0.99"],
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function lookup() {
  const bucket = Math.floor(Math.random() * 65536);
  const headers = token && Math.random() < 0.25 ? { Authorization: `Bearer ${token}` } : {};
  const response = http.get(`${base}/v1/lookup/buckets/880/${bucket}`, { headers, tags: { name: "bucket" } });
  check(response, {
    "status 200": (r) => r.status === 200,
    // About 2.5 % of buckets are legitimately empty with the seed data (no reports, no visible name).
    "is a bucket": (r) => r.status === 200 && Array.isArray(r.json("entries")) && r.json("bucket") >= 0,
  });
}
