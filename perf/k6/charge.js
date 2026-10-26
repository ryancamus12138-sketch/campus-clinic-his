// =====================================================================
// 场景 M7：日常收费按 10 倍峰值压测。
//
// 按单个校医院日均约 150 人次（假设值）、8 小时、高峰时段集中 3 倍估算，峰值约 1 笔/秒收费；10 倍即 10 笔/秒。
//   steady  10 笔/秒恒定到达，持续 10 分钟
//   burst   第 4 分钟起叠加 50 笔/秒，持续 1 分钟（模拟开学体检、流感季）
// 每笔收费 = 检索患者 + 划价生成收费单 + 现金结算（带幂等键），共 3 个请求；
// 后台同时运行发件箱投递（生成待开具票据记录）、退费重试、超时关单等定时任务。
// 要求：所有请求 P99 < 200ms，错误率 < 0.1%。
//
// 运行：由 .github/workflows/perf.yml 在 k6 容器中执行（结果写到容器内的 /perf/results）
// =====================================================================
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js'

const BASE = __ENV.BASE_URL || 'http://localhost:8080'
const STEADY_RATE = Number(__ENV.STEADY_RATE || 10)
const STEADY_DURATION = __ENV.STEADY_DURATION || '10m'
const BURST_RATE = Number(__ENV.BURST_RATE || 50)
const PATIENTS = 3000
const JSON_HEADERS = { 'Content-Type': 'application/json' }
const ITEMS = ['JC-XCG', 'JC-NCG', 'ZL-JRZS', 'ZL-JMSY', 'JC-XD']

const settled = new Counter('charge_settled')
const failed = new Counter('charge_failed')

export const options = {
  setupTimeout: '2m',
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: STEADY_RATE,
      timeUnit: '1s',
      duration: STEADY_DURATION,
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
    burst: {
      executor: 'constant-arrival-rate',
      rate: BURST_RATE,
      timeUnit: '1s',
      duration: '1m',
      startTime: __ENV.BURST_START || '4m',
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  thresholds: {
    http_req_duration: ['p(99)<200'],
    'http_req_duration{scenario:steady}': ['p(99)<200'],
    'http_req_duration{scenario:burst}': ['p(99)<200'],
    http_req_failed: ['rate<0.001'],
    charge_failed: ['count<10'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

export function setup() {
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({ username: 'cashier01', password: 'Clinic@2026' }), {
    headers: JSON_HEADERS,
  })
  return { token: res.json('data.accessToken') }
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}

export default function (data) {
  const headers = { ...JSON_HEADERS, Authorization: `Bearer ${data.token}` }
  const n = 1 + Math.floor(Math.random() * PATIENTS)
  const schoolNo = `P${String(n).padStart(5, '0')}`

  const found = http.get(`${BASE}/api/patients?keyword=${schoolNo}`, { headers, tags: { name: 'search' } })
  const patientId = found.json('data.0.id')
  if (!check(found, { 'patient found': (r) => r.status === 200 && !!patientId })) {
    failed.add(1)
    return
  }

  const first = ITEMS[n % ITEMS.length]
  const lines = [{ itemCode: first, qty: 1 + (n % 3) }]
  // 一半的收费单再加一项检查（同一项目不能重复，后端会拒绝）
  if (n % 2 === 0) lines.push({ itemCode: first === 'JC-XCG' ? 'JC-NCG' : 'JC-XCG', qty: 1 })
  const created = http.post(`${BASE}/api/charge/orders`, JSON.stringify({ patientId, items: lines }), {
    headers,
    tags: { name: 'create' },
  })
  if (!check(created, { 'order created': (r) => r.status === 200 })) {
    console.error(`create failed status=${created.status} body=${created.body}`)
    failed.add(1)
    return
  }
  const order = created.json('data')

  // 医保结算平台未接入：收费员在医保终端结算后登记结算单号与统筹金额，再收自付现金
  const body = { tenders: [{ method: 'CASH', amount: order.payableAmount, received: order.payableAmount }] }
  if (Number(order.insuranceAmount) > 0) {
    body.insurance = { setlId: `PERF${order.chargeNo}`, fundPay: order.insuranceAmount }
  }
  const paid = http.post(`${BASE}/api/charge/orders/${order.id}/checkout`, JSON.stringify(body), {
    headers: { ...headers, 'Idempotency-Key': uuid() },
    tags: { name: 'cash' },
  })
  if (check(paid, { 'cash settled': (r) => r.status === 200 && r.json('data.tenders.0.payNo') })) {
    settled.add(1)
  } else {
    failed.add(1)
  }
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: false }),
    '/perf/results/charge-summary.json': JSON.stringify(data),
  }
}
