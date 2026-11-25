import http from 'k6/http';
import encoding from 'k6/encoding';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const BASIC_USER = __ENV.BASIC_AUTH_USER || 'admin@meterflow.example';
const BASIC_PASS = __ENV.BASIC_AUTH_PASS || 'ChangeMeAdmin#2026';
const CUSTOMER_ID = __ENV.CUSTOMER_ID;
const METRIC_ID = __ENV.METRIC_ID;
const PERIOD_START_ISO = __ENV.PERIOD_START_ISO || new Date(Date.now() - 24 * 3600 * 1000).toISOString();
const TOTAL_EVENTS = parseInt(__ENV.TOTAL_EVENTS || '1000000', 10);
const BATCH_SIZE = parseInt(__ENV.BATCH_SIZE || '1000', 10);
const TOTAL_BATCHES = Math.ceil(TOTAL_EVENTS / BATCH_SIZE);

const eventsAccepted = new Counter('meterflow_qa1_accepted');
const eventsLate = new Counter('meterflow_qa1_late');
const eventsDuplicate = new Counter('meterflow_qa1_duplicate');
const batchDuration = new Trend('meterflow_qa1_batch_ms', true);

export const options = {
  scenarios: {
    one_million_dataset: {
      executor: 'shared-iterations',
      vus: parseInt(__ENV.VUS || '8', 10),
      iterations: TOTAL_BATCHES,
      maxDuration: '30m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.005'],
  },
};

function authHeaders() {
  const token = encoding.b64encode(`${BASIC_USER}:${BASIC_PASS}`);
  return {
    Authorization: `Basic ${token}`,
    'Content-Type': 'application/json',
  };
}

/**
 * Deterministic dataset:
 * - Every 100 events: indices 0..94 are unique on-time, 95..96 are unique late (within 72h),
 *   and 97..99 are exact duplicates of indices 0..2 in the same 100-event block (3% duplicates, 2% late).
 */
function buildDeterministicEvent(globalIndex, periodStartMs) {
  const blockBase = Math.floor(globalIndex / 100) * 100;
  const offset = globalIndex % 100;

  let canonicalIndex = globalIndex;
  let isLate = false;
  if (offset >= 97) {
    canonicalIndex = blockBase + (offset - 97);
  } else if (offset >= 95) {
    isLate = true;
  }

  const quantity = ((canonicalIndex % 10) + 1);
  const tsMs = isLate
    ? periodStartMs - (60 * 1000) - ((canonicalIndex % 3600) * 1000)
    : periodStartMs + (60 * 1000) + ((canonicalIndex % 3600) * 1000);

  return {
    customerId: CUSTOMER_ID,
    metricId: METRIC_ID,
    quantity: quantity,
    eventTimestamp: new Date(tsMs).toISOString(),
    idempotencyKey: `qa1-ev-${canonicalIndex}`,
    properties: { units: quantity },
  };
}

export default function () {
  if (!CUSTOMER_ID || !METRIC_ID) {
    throw new Error('CUSTOMER_ID and METRIC_ID environment variables are required');
  }
  const batchIdx = __ITER;
  const startIdx = batchIdx * BATCH_SIZE;
  const endIdx = Math.min(startIdx + BATCH_SIZE, TOTAL_EVENTS);
  const periodStartMs = Date.parse(PERIOD_START_ISO);

  const events = [];
  for (let i = startIdx; i < endIdx; i++) {
    events.push(buildDeterministicEvent(i, periodStartMs));
  }

  const res = http.post(
    `${BASE_URL}/api/v1/events/batch`,
    JSON.stringify({ events }),
    { headers: authHeaders(), timeout: '30s' }
  );

  batchDuration.add(res.timings.duration);
  const ok = check(res, {
    'QA1 batch HTTP 202 or 200': (r) => r.status === 202 || r.status === 200,
  });

  if (ok && res.body) {
    const body = res.json();
    eventsAccepted.add(body.acceptedCount || 0);
    eventsLate.add(body.lateAcceptedCount || 0);
    eventsDuplicate.add(body.duplicateCount || 0);
    check(body, {
      'QA1 zero conflicts and zero rejections': (b) => b.conflictCount === 0 && b.rejectedCount === 0,
    });
  }
}

export function teardown() {
  if (!CUSTOMER_ID || !METRIC_ID) {
    return;
  }
  const periodStartMs = Date.parse(PERIOD_START_ISO);
  const windowStart = new Date(periodStartMs - 7200 * 1000).toISOString();
  const windowEnd = new Date(periodStartMs + 7200 * 1000).toISOString();

  // Compute exact independent expected unique event count and sum
  let expectedCount = 0;
  let expectedSum = 0;
  for (let i = 0; i < TOTAL_EVENTS; i++) {
    if (i % 100 < 97) {
      expectedCount++;
      expectedSum += (i % 10) + 1;
    }
  }

  const queryRes = http.post(
    `${BASE_URL}/api/v1/metering/usage/query`,
    JSON.stringify({
      customerId: CUSTOMER_ID,
      metricId: METRIC_ID,
      windowStart,
      windowEnd,
      includeTraceableEventIds: false,
    }),
    { headers: authHeaders() }
  );

  const reconcileRes = http.post(
    `${BASE_URL}/api/v1/metering/rollups/reconcile`,
    JSON.stringify({
      customerId: CUSTOMER_ID,
      metricId: METRIC_ID,
      windowStart,
      windowEnd,
    }),
    { headers: authHeaders() }
  );

  const queryBody = queryRes.status === 200 ? queryRes.json() : {};
  const recBody = reconcileRes.status === 200 ? reconcileRes.json() : {};
  console.log(JSON.stringify({
    qa1Summary: {
      totalSubmitted: TOTAL_EVENTS,
      expectedUniqueCount: expectedCount,
      expectedSum: expectedSum,
      queriedCount: queryBody.matchedEventCount,
      queriedSum: queryBody.aggregatedQuantity,
      reconciledCount: recBody.reconciledEventCount,
      reconciledSum: recBody.reconciledSum,
      exactParity: Number(queryBody.matchedEventCount) === expectedCount
        && Number(queryBody.aggregatedQuantity) === expectedSum
        && Number(recBody.reconciledSum) === expectedSum,
    },
  }));
}
