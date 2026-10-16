import http from 'k6/http';
import { check } from 'k6';

const BASE = 'http://localhost:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    storm: { executor: 'shared-iterations', vus: 50, iterations: 50, maxDuration: '30s' },
  },
};

export function setup() {
  const created = http.post(`${BASE}/wallets`, JSON.stringify({ ownerName: 'k6' }),
    { headers: { ...JSON_HEADERS, 'Idempotency-Key': `k6-wallet-${Date.now()}` } });
  return { wallet: created.json('walletId'), key: `k6-topup-${Date.now()}` };
}

export default function (data) {
  const res = http.post(`${BASE}/wallets/${data.wallet}/topups`, JSON.stringify({ amountMinor: 1000 }),
    { headers: { ...JSON_HEADERS, 'Idempotency-Key': data.key } });
  check(res, { 'status is 201': (r) => r.status === 201 });
}

export function teardown(data) {
  const balance = http.get(`${BASE}/wallets/${data.wallet}/balance`).json('balanceMinor');
  console.log(`balance = ${balance}  (must be exactly 1000)`);
}