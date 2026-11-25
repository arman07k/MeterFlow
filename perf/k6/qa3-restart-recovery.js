import http from 'k6/http';
import encoding from 'k6/encoding';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const BASIC_USER = __ENV.BASIC_AUTH_USER || 'admin@meterflow.example';
const BASIC_PASS = __ENV.BASIC_AUTH_PASS || 'ChangeMeAdmin#2026';
const CUSTOMER_ID = __ENV.CUSTOMER_ID;
const METRIC_ID = __ENV.METRIC_ID;
const RUN_TAG = __ENV.RUN_TAG || 'qa3-run-1';
const BATCH_COUNT = parseInt(__ENV.BATCH_COUNT || '20', 10);
const BATCH_SIZE = parseInt(__ENV.BATCH_SIZE || '100', 10);

export const options = {
  scenarios: {
    replay_and_recover: {
      executor: 'shared-iterations',
      vus: 4,
      iterations: BATCH_COUNT * 2, // Submit every deterministic batch twice to simulate retry after restart
      maxDuration: '5m',
    },
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

  const deterministicBatch = __ITER % BATCH_COUNT;
  const baseTsMs = Date.parse(__ENV.BASE_TS_ISO || new Date(Date.now() - 600000).toISOString());
  const events = [];
  for (let i = 0; i < BATCH_SIZE; i++) {
    const seq = deterministicBatch * BATCH_SIZE + i;
    events.push({
      customerId: CUSTOMER_ID,
      metricId: METRIC_ID,
      quantity: 2,
      eventTimestamp: new Date(baseTsMs + (seq % 1000) * 100).toISOString(),
      idempotencyKey: `${RUN_TAG}-ev-${seq}`,
      properties: { units: 2 },
    });
  }

  const res = http.post(
    `${BASE_URL}/api/v1/events/batch`,
    JSON.stringify({ events }),
    { headers: authHeaders(), timeout: '15s' }
  );

  check(res, {
    'QA3 batch accepted or deduplicated safely': (r) => r.status === 202 || r.status === 200,
  });
}

export function teardown() {
  if (!CUSTOMER_ID || !METRIC_ID) {
    return;
  }
  const recoverRes = http.post(
    `${BASE_URL}/api/v1/metering/rollups/recover?maxEvents=5000`,
    null,
    { headers: authHeaders() }
  );
  check(recoverRes, { 'QA3 recovery endpoint HTTP 200': (r) => r.status === 200 });
}
