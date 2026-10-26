// =====================================================================
// 界面验证与截图：在 dev 环境（docker compose 拉起的完整系统 + 演示数据）上，
// 先通过真实接口走一遍“放号 → 窗口挂号 → 开方 → 收费 → 退费 → 交班 → 复核”，
// 再以各角色登录管理端逐页截图。截图里的数据都是这次真实操作产生的演示数据。
//
// 收款异常需要一次真实的收款失败：FAULT_INJECTION=1 时，脚本在开发库上临时加一个触发器，
// 让资金流水写入失败（模拟数据库故障），收费员随后的一次“现金 + 银行卡”收费因此中途失败，
// 由应用自身的异常处理转为收款异常；触发器随即删除。只用于开发编排，需要能执行 docker compose。
//
// 运行：BASE_URL=http://localhost FAULT_INJECTION=1 node tools/screenshots/capture.mjs <输出目录>
// 输出全部页面截图和问题清单（problems.txt）。带编号的 8 张即 README 使用的截图，文件名与 docs/images 中相同，
// 核对后直接复制过去；不带编号的是其余页面，只用于检查界面。
// =====================================================================
import { chromium } from 'playwright'
import { mkdirSync, writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'

const BASE = process.env.BASE_URL || 'http://localhost'
const OUT = process.argv[2] || 'shots'
mkdirSync(OUT, { recursive: true })

const ACCOUNTS = {
  admin: 'Admin@2026',
  cashier01: 'Clinic@2026',
  doctor01: 'Clinic@2026',
  pharm01: 'Clinic@2026',
  finance01: 'Clinic@2026',
  auditor01: 'Clinic@2026',
}
const tokens = {}
const problems = []

async function call(user, method, path, body, headers = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(tokens[user] ? { Authorization: `Bearer ${tokens[user].accessToken}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok || json.code !== 0) {
    throw new Error(`${user} ${method} ${path} -> ${res.status} ${JSON.stringify(json).slice(0, 300)}`)
  }
  return json.data
}

async function step(name, fn) {
  try {
    const r = await fn()
    console.log(`ok   ${name}`)
    return r
  } catch (e) {
    problems.push(`${name}: ${e.message}`)
    console.log(`FAIL ${name}: ${e.message}`)
    return undefined
  }
}

const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())

function payBody(order, tenders) {
  const body = { tenders }
  if (Number(order.insuranceAmount) > 0) {
    // 医保结算平台未接入：登记收费员在医保结算终端上得到的结算单号与统筹金额
    body.insurance = { setlId: `YB${order.chargeNo}`, fundPay: order.insuranceAmount }
  }
  return body
}

async function checkout(order, tenders) {
  return call('cashier01', 'POST', `/api/charge/orders/${order.id}/checkout`, payBody(order, tenders), {
    'Idempotency-Key': randomUUID(),
  })
}

/** 以 root 在开发库上执行 SQL（开发编排的默认 root 口令） */
function devSql(sql) {
  execFileSync(
    'docker',
    ['compose', '-f', process.env.COMPOSE_FILE_PATH || 'deploy/docker-compose.yml', 'exec', '-T', 'mysql',
      'mysql', '-uroot', `-p${process.env.DB_ROOT_PASSWORD || 'root'}`, 'clinic', '-e', sql],
    { stdio: ['ignore', 'pipe', 'pipe'] },
  )
}

// ------------------------------------------------------------------ 1 登录
for (const [u, p] of Object.entries(ACCOUNTS)) {
  tokens[u] = await step(`login ${u}`, () => call(u, 'POST', '/api/auth/login', { username: u, password: p }))
}

// ------------------------------------------------------------------ 2 业务数据
const student = await step('find student', async () => (await call('cashier01', 'GET', '/api/patients?keyword=D20210001'))[0])
const staff = await step('find staff', async () => (await call('cashier01', 'GET', '/api/patients?keyword=T19990001'))[0])

const doctorId = await step('find doctor', async () => {
  const me = await call('doctor01', 'GET', '/api/auth/me')
  return me.userId
})
let deptId
await step('find doctor dept', async () => {
  const depts = await call('admin', 'GET', '/api/system/depts?campusId=1')
  for (const d of depts) {
    const docs = await call('admin', 'GET', `/api/system/doctors?deptId=${d.id}`)
    if (docs.some((x) => String(x.id) === String(doctorId))) {
      deptId = d.id
      return
    }
  }
  deptId = depts[0].id
})

await step('publish slots', () =>
  call('admin', 'POST', '/api/schedule/slots/batch', {
    campusId: 1,
    deptId,
    doctorId,
    slotType: 1,
    dates: [today],
    periods: [
      { startTime: '22:00:00', endTime: '22:30:00', total: 20 },
      { startTime: '22:30:00', endTime: '23:00:00', total: 20 },
      { startTime: '23:00:00', endTime: '23:30:00', total: 20 },
    ],
    priceItemCode: 'GH-PT',
  }),
)

// 两位患者窗口挂号并缴挂号费
const registrations = []
for (const p of [student, staff].filter(Boolean)) {
  await step(`register ${p.name}`, async () => {
    const slots = await call('cashier01', 'GET', `/api/schedule/registrations/slots?campusId=1&slotType=1&date=${today}`)
    const slot = slots.find((s) => s.remaining > 0)
    // 挂号同时生成挂号费收费单
    const result = await call('cashier01', 'POST', '/api/schedule/registrations', { patientId: p.id, slotId: slot.id })
    registrations.push(result.registration)
    const order = result.chargeOrder
    if (order) await checkout(order, [{ method: 'CASH', amount: order.payableAmount, received: order.payableAmount }])
  })
}

// 医生为演示学生开方：一盒药 + 一项检查
await step('prescribe', async () => {
  const visits = await call('doctor01', 'GET', '/api/clinic/visits')
  const visit = visits.find((v) => String(v.patientId) === String(student.id)) ?? visits[0]
  return call('doctor01', 'POST', '/api/clinic/prescriptions', {
    registrationId: visit.registrationId ?? visit.id,
    items: [
      { kind: 1, drugId: 4001, qty: 1, usageNote: '每日 2 次，每次 1 粒' },
      { kind: 2, itemCode: 'JC-XCG', qty: 1 },
    ],
  })
})

// 演示教工：收费处直接划价，现金 + 银行卡组合收款
const staffOrder = await step('staff order', async () => {
  const order = await call('cashier01', 'POST', '/api/charge/orders', {
    patientId: staff.id,
    items: [
      { itemCode: 'JC-XD', qty: 1 },
      { itemCode: 'ZL-JRZS', qty: 2 },
    ],
  })
  const payable = Number(order.payableAmount)
  const card = Math.min(10, payable)
  const tenders = [{ method: 'BANK_CARD', amount: card.toFixed(2), reference: '000123456789' }]
  if (payable - card > 0) {
    tenders.unshift({
      method: 'CASH',
      amount: (payable - card).toFixed(2),
      received: Math.ceil(payable - card + 1).toFixed(2),
    })
  }
  await checkout(order, tenders)
  return call('cashier01', 'GET', `/api/charge/orders/${order.id}`)
})

// 演示教工：退一项注射（金额小，收费员申请即生效），现金、银行卡退款待收费员确认
await step('refund', async () => {
  const line = staffOrder.items.find((i) => i.itemCode === 'ZL-JRZS') ?? staffOrder.items[0]
  return call(
    'cashier01',
    'POST',
    `/api/charge/orders/${staffOrder.id}/refunds`,
    { items: [{ chargeItemId: line.id, qty: 1 }], reason: '患者未做第二次注射' },
    { 'Idempotency-Key': randomUUID() },
  )
})

// 演示教工：心电图 ×10（250 元）现金收费后整单申请退费，超过 200 元，等待财务审批
await step('refund awaiting approval', async () => {
  const order = await call('cashier01', 'POST', '/api/charge/orders', {
    patientId: staff.id,
    items: [{ itemCode: 'JC-XD', qty: 10 }],
  })
  await checkout(order, [{ method: 'CASH', amount: order.payableAmount, received: order.payableAmount }])
  const paid = await call('cashier01', 'GET', `/api/charge/orders/${order.id}`)
  const refund = await call(
    'cashier01',
    'POST',
    `/api/charge/orders/${order.id}/refunds`,
    { items: [{ chargeItemId: paid.items[0].id, qty: 10 }], reason: '检查预约取消，整单退费' },
    { 'Idempotency-Key': randomUUID() },
  )
  if (refund.status !== 0) throw new Error('退费没有进入待审批: ' + JSON.stringify(refund).slice(0, 200))
})

// 收款异常：现金 15 元 + 银行卡 10 元收费时资金流水写入失败（临时触发器模拟数据库故障）。
// 应用撤销收款登记后，现金是否已退还、POS 交易是否已撤销系统都无法确认，转为收款异常交给财务；
// 财务先核实了 POS 已撤销，现金一笔留待核实
await step('checkout exception', async () => {
  if (process.env.FAULT_INJECTION !== '1') throw new Error('未设置 FAULT_INJECTION=1，跳过')
  const order = await call('cashier01', 'POST', '/api/charge/orders', {
    patientId: staff.id,
    items: [{ itemCode: 'JC-XD', qty: 1 }],
  })
  devSql(
    "CREATE TRIGGER screenshot_fault BEFORE INSERT ON fund_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected fault'",
  )
  let failed = false
  try {
    await checkout(order, [
      { method: 'CASH', amount: '15.00', received: '20.00' },
      { method: 'BANK_CARD', amount: '10.00', reference: '000123456790' },
    ])
  } catch (e) {
    failed = e.message.includes('收款异常')
    if (!failed) throw e
  } finally {
    devSql('DROP TRIGGER IF EXISTS screenshot_fault')
  }
  if (!failed) throw new Error('收费没有按预期转为收款异常')
  const exception = (await call('finance01', 'GET', '/api/charge/checkouts/exceptions?resolved=false')).find(
    (x) => x.chargeNo === order.chargeNo,
  )
  const pos = exception.tenders.find((t) => t.channel === 6)
  await call('finance01', 'POST', `/api/charge/checkouts/${exception.id}/tenders/${pos.id}/verify`, {
    result: 'RETURNED',
    reference: 'POSCX0001207',
    note: '收费员已在 POS 机撤销当笔交易',
  })
})

// ------------------------------------------------------------------ 3 截图
// 使用系统已安装的 Google Chrome（CHROME_CHANNEL=chromium 时用 Playwright 自带的浏览器）
const channel = process.env.CHROME_CHANNEL || 'chrome'
const browser = await chromium.launch(channel === 'chromium' ? {} : { channel })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, locale: 'zh-CN' })
const page = await ctx.newPage()
page.on('pageerror', (e) => problems.push(`pageerror ${page.url()}: ${e.message}`))

async function as(user) {
  await page.goto(`${BASE}/login`)
  await page.evaluate(
    ([a, r]) => {
      localStorage.setItem('clinic.accessToken', a)
      localStorage.setItem('clinic.refreshToken', r)
    },
    [tokens[user].accessToken, tokens[user].refreshToken],
  )
}

async function shot(name, path, prepare) {
  await step(`shot ${name}`, async () => {
    if (path) await page.goto(BASE + path)
    await page.waitForLoadState('networkidle')
    if (prepare) await prepare()
    // 管理端在内容区内部滚动，按内容高度放大窗口，截下整页
    await page.mouse.move(0, 0)
    const height = await page.evaluate(() => {
      let h = document.documentElement.scrollHeight
      for (const el of document.querySelectorAll('*')) {
        const oy = getComputedStyle(el).overflowY
        if ((oy === 'auto' || oy === 'scroll') && el.scrollHeight > el.clientHeight) {
          h = Math.max(h, el.getBoundingClientRect().top + el.scrollHeight + 24)
        }
      }
      return Math.ceil(h)
    })
    await page.setViewportSize({ width: 1440, height: Math.min(Math.max(height, 900), 3000) })
    await page.waitForTimeout(500)
    await page.screenshot({ path: `${OUT}/${name}.png` })
    await page.setViewportSize({ width: 1440, height: 900 })
  })
}

// 登录流程：未登录访问业务页 → 选择身份 → 身份与账号不符被拒 → 正确身份登录
await step('login flow', async () => {
  await page.goto(`${BASE}/charge/settle`)
  await page.waitForURL(/\/login/)
  await page.getByText('请选择本人岗位对应的身份').waitFor()
  await shot('01-login', null)

  // 收费员账号选择“财务”身份：后端拒绝，不进入工作台
  await page.locator('.role-option', { hasText: '财务' }).click()
  await page.getByRole('button', { name: '继续' }).click()
  await page.getByRole('heading', { name: '财务登录' }).waitFor()
  await page.fill('input[autocomplete="username"]', 'cashier01')
  await page.fill('input[autocomplete="current-password"]', ACCOUNTS.cashier01)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByText('该账号不具备所选身份').waitFor()
  if (!page.url().includes('/login')) throw new Error('身份不符仍进入了系统: ' + page.url())
  await shot('login-mismatch', null)

  // 切换为收费员身份后登录，回到原先要访问的页面
  await page.getByRole('button', { name: '切换身份' }).first().click()
  await page.locator('.role-option', { hasText: '收费员' }).click()
  await page.getByRole('button', { name: '继续' }).click()
  await page.getByRole('heading', { name: '收费员登录' }).waitFor()
  await page.fill('input[autocomplete="username"]', 'cashier01')
  await page.fill('input[autocomplete="current-password"]', ACCOUNTS.cashier01)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL(/\/charge\/settle/)
  await page.evaluate(() => localStorage.clear())
})

// 窄屏
await step('login narrow', async () => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(`${BASE}/login?role=CASHIER`)
  await page.getByRole('heading', { name: '收费员登录' }).waitFor()
  await page.screenshot({ path: `${OUT}/login-narrow.png` })
  await page.setViewportSize({ width: 1440, height: 900 })
})

// 收费员
await as('cashier01')
await shot('dashboard', '/')
await shot('03-settle', '/charge/settle', async () => {
  await page.fill('input[placeholder="就诊卡号 / 学号 / 工号"]', 'D20210001')
  await page.keyboard.press('Enter')
  await page.waitForTimeout(800)
  await page.click('.el-table button:has-text("收费")')
  await page.waitForTimeout(800)
  const setl = page.locator('input[placeholder="医保终端显示的结算单号"]')
  if (await setl.count()) await setl.fill('YB20261026000123')
})
// 划价：检索学生，添加两项检查，显示按计费策略估算的医保统筹与个人自付
await shot('02-pricing', '/charge/settle', async () => {
  await page.fill('input[placeholder="就诊卡号 / 学号 / 工号"]', 'D20210001')
  await page.keyboard.press('Enter')
  const card = page.locator('.page-card', { has: page.locator('.section-title', { hasText: '划价' }) })
  await card.waitFor()
  for (const name of ['血常规', '常规心电图']) {
    await card.locator('.el-select').first().click()
    await page.keyboard.type(name)
    await page.locator('.el-select-dropdown__item:visible', { hasText: name }).first().click()
    await page.waitForTimeout(500)
  }
  await card.locator('.summary').waitFor()
})
await shot('orders', '/charge/orders')
await shot('05-refunds', '/charge/refunds', async () => {
  await page.locator('.el-table__expand-icon').first().click()
})

// 交班：实点现金比应有少 2 元，写明原因；财务复核
await step('hand over', async () => {
  const shift = await call('cashier01', 'GET', '/api/finance/shifts/current')
  const counted = (Number(shift.expectedCash) - 2).toFixed(2)
  // POS 机结算单与系统一致；现金短款 2 元写明原因
  await call('cashier01', 'POST', `/api/finance/shifts/${shift.id}/hand-over`, {
    countedCash: counted,
    posCounted: shift.posExpected,
    diffNote: '找零差错，短款 2 元',
  })
  await call('finance01', 'POST', `/api/finance/shifts/${shift.id}/review`, { note: '短款 2 元已由收费员当场补齐' })
})

// 医生、药师
await as('doctor01')
await shot('prescribe', '/clinic/prescribe', async () => {
  const row = page.locator('.el-table__row').first()
  if (await row.count()) await row.click()
})
await as('pharm01')
await shot('stock', '/pharmacy/stock')

// 财务
await as('finance01')
await step('rebuild report', () => call('finance01', 'POST', `/api/finance/reports/rebuild?date=${today}`))
await shot('08-shifts', '/finance/shifts')
await shot('09-reports', '/finance/reports')
await shot('invoices', '/finance/invoices')
await shot('04-exceptions', '/charge/checkout-exceptions', async () => {
  await page.getByText('现金待确认退还').first().waitFor()
})
// 退费审批：超过审批金额的退费，财务填写审批意见
await shot('06-refund-approval', '/charge/refunds', async () => {
  await page.locator('.el-table__row', { hasText: '待审核' }).first().getByRole('button', { name: '审批' }).click()
  await page.locator('.review-dialog textarea').waitFor()
  await page.locator('.review-dialog textarea').fill('核对检查未执行，同意退费')
})

// 管理员、审计员
await as('admin')
await shot('charge-points', '/system/charge-points')
await shot('users', '/system/users')
await as('auditor01')
await shot('audit', '/audit/logs')

await browser.close()
writeFileSync(`${OUT}/problems.txt`, problems.join('\n') + '\n')
console.log('\nproblems:', problems.length)
for (const p of problems) console.log(' -', p)
