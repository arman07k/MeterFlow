import http from 'k6/http';
import encoding from 'k6/encoding';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const BASIC_USER = __ENV.BASIC_AUTH_USER || 'admin@meterflow.example';
const BASIC_PASS = __ENV.BASIC_AUTH_PASS || 'ChangeMeAdmin#2026';
const CUSTOMER_ID = __ENV.CUSTOMER_ID;
const METRIC_ID = __ENV.METRIC_ID;
const BATCH_SIZE = parseInt(__ENV.BATCH_SIZE || '250', 10);
const TARGET_BATCHES_PER_SEC = parseInt(__ENV.BATCHES_PER_SEC || '10', 10); // 10 * 250 = 2,500 events/sec
const DURATION = __ENV.DURATION || '30s';

const eventsIngested = new Counter('meterflow_qa2_events_ingested');
const batchLatencyMs = new Trend('meterflow_qa2_batch_latency_ms', true);

export const options = {
  scenarios: {
    sustained_throughput: {
      executor: 'constant-arrival-rate',
      rate: TARGET_BATCHES_PER_SEC,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: parseInt(__ENV.PRE_ALLOCATED_VUS || '16', 10),
      maxVUs: parseInt(__ENV.MAX_VUS || '64', 10),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.005'],
    http_req_duration: ['p(95)<=100'],
  },
};

function authHeaders() {
  const token = encoding.b64encode(`${BASIC_USER}:${BASIC_PASS}`);
  return {
    Authorization: `Basic ${token}`,
    'Content-Type': 'application/json',
  };
}

export default function () {
  if (!CUSTOMER_ID || !METRIC_ID) {
    throw new Error('CUSTOMER_ID and METRIC_ID environment variables are required');
  }

  const nowMs = Date.now() - 5000;
  const events = new Array(BATCH_SIZE);
  for (let i = 0; i < BATCH_SIZE; i++) {
    events[i] = {
      customerId: CUSTOMER_ID,
      metricId: METRIC_ID,
      quantity: 1,
      eventTimestamp: new Date(nowMs - (i % 1000)).toISOString(),
      idempotencyKey: `qa2-${__VU}-${__ITER}-${i}`,
      properties: { units: 1 },
    };
  }

  const res = http.post(
    `${BASE_URL}/api/v1/events/batch`,
    JSON.stringify({ events }),
    { headers: authHeaders(), timeout: '10s' }
  );

  batchLatencyMs.add(res.timings.duration);
  const ok = check(res, {
    'QA2 HTTP 202 Accepted': (r) => r.status === 202,
  });
  if (ok && res.body) {
    const body = res.json();
    eventsIngested.add((body.acceptedCount || 0) + (body.lateAcceptedCount || 0));
  }
}
