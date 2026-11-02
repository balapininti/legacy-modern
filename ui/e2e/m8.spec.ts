import { Page, expect, test } from '@playwright/test';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const stack = Object.fromEntries(
  readFileSync('e2e/.stack/stack.env', 'utf8').trim().split('\n').map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]),
);

const shot = (page: Page, name: string) => page.screenshot({ path: `e2e/screenshots/${name}.png`, animations: 'disabled' });

async function signInWithPassword(page: Page, username = 'admin', password = stack.ADMIN_PASSWORD) {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByTestId('who')).toContainText(username);
}

async function signInWithToken(page: Page, token: string, who: string) {
  await page.goto('/login');
  await page.getByText('Use an access token').click();
  await page.getByTestId('token-input').fill(token);
  await page.getByTestId('token-submit').click();
  await expect(page.getByTestId('who')).toContainText(who);
}

async function nav(page: Page, label: string) {
  await page.getByTestId('nav').getByRole('link', { name: label }).click();
}

async function navLabels(page: Page): Promise<string[]> {
  return (await page.getByTestId('nav').locator('[matListItemTitle]').allInnerTexts()).map((s) => s.trim());
}

async function shellSearch(page: Page, q: string) {
  await page.getByTestId('shell-search').fill(q);
  await page.getByTestId('shell-search').press('Enter');
  await expect(page.getByTestId('search-hits')).toBeVisible();
}

async function pick(page: Page, testId: string, option: string) {
  await page.getByTestId(testId).click();
  await page.getByRole('option', { name: option, exact: true }).click();
}

// Any uncaught error in the page fails the test that caused it.
const problems: string[] = [];
test.beforeEach(({ page }) => {
  problems.length = 0;
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && m.text().startsWith('ERROR')) problems.push(m.text());
  });
});
test.afterEach(() => {
  expect(problems).toEqual([]);
});

// In order, on one seeded database. Each test still reports on its own, so one failure does not hide the others.
test.describe('M8 acceptance on the seeded data', () => {
  test('the saved OpenAPI copy matches what the server publishes', async ({ request }) => {
    const live = await (await request.get(`${stack.API_URL}/v3/api-docs`)).json();
    const saved = JSON.parse(readFileSync('openapi.json', 'utf8'));
    const ops = (d: any) => Object.entries(d.paths).flatMap(([p, v]: any) => Object.keys(v).map((m) => `${m} ${p}`)).sort();
    expect(ops(saved)).toEqual(ops(live));
  });

  test('(1) an SME approves a rule in the review queue and search ranks it higher', async ({ page }) => {
    await signInWithToken(page, stack.SME_TOKEN, 'sme1');
    const labels = await navLabels(page);
    expect(labels).toContain('Review queue');
    expect(labels).not.toContain('Admin');
    expect(labels).not.toContain('Setup wizard');
    expect(labels).not.toContain('Captures');

    // A word many chunks share: for "interest" this rule is already first, which would prove nothing about the boost.
    await shellSearch(page, 'transaction');
    const hit = page.locator('tr[data-unit="CBACT04C-R1"]');
    await expect(hit).toHaveAttribute('data-status', 'draft');
    const rankBefore = Number(await hit.getAttribute('data-rank'));
    expect(rankBefore).toBeGreaterThan(1);
    await shot(page, 'search-before-approval');

    await nav(page, 'Review queue');
    const artifact = page.getByTestId('artifact').filter({ hasText: 'CBACT04C-R1' });
    await expect(artifact).toBeVisible();
    await shot(page, 'review-queue');
    const id = await artifact.getAttribute('data-artifact');
    await artifact.getByRole('button', { name: 'Approve' }).click();
    // It leaves the draft queue and shows as reviewed, by this SME, under its new status.
    await expect(page.locator(`[data-artifact="${id}"]`)).toHaveCount(0);
    await pick(page, 'review-status', 'reviewed');
    const reviewed = page.locator(`[data-artifact="${id}"]`);
    await expect(reviewed.getByTestId('artifact-status')).toHaveText('reviewed');
    await expect(reviewed).toContainText('by sme1');

    await shellSearch(page, 'transaction');
    const after = page.locator('tr[data-unit="CBACT04C-R1"]');
    await expect(after).toHaveAttribute('data-status', 'reviewed');
    const rankAfter = Number(await after.getAttribute('data-rank'));
    console.log(`CBACT04C-R1 for "transaction": rank ${rankBefore} before approval, ${rankAfter} after`);
    expect(rankAfter).toBeLessThan(rankBefore);
    await shot(page, 'search-after-approval');
  });

  test('(2, 3) a lead confirms a link and approves a deviation; the gap report and tracker follow', async ({ page, request }) => {
    await page.goto('/login');
    await shot(page, 'login');
    await page.getByLabel('Username').fill('admin');
    await page.getByLabel('Password').fill('not-the-password');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByTestId('login-error')).toHaveText('wrong username or password');
    await signInWithPassword(page);

    // Before: nothing is confirmed.
    await nav(page, 'Gap report');
    const gap = page.locator('tr[data-gap="CBACT04C"]');
    await expect(gap.getByTestId('gap-status')).toHaveText('missing');
    const countsBefore = await page.getByTestId('gap-counts').innerText();
    await shot(page, 'gap-report-before');

    await nav(page, 'Migration tracker');
    await expect(page.locator('[data-stage="analyzed"] [data-program="CBACT04C"]')).toBeVisible();
    await expect(page.locator('[data-stage="translated"] [data-program="CBACT04C"]')).toHaveCount(0);
    await shot(page, 'tracker-before');

    await nav(page, 'Link confirmation');
    const rows = page.locator('tr[data-modern="com.acme.card.batch.InterestCalculator"]');
    await expect(rows.first()).toBeVisible();
    await shot(page, 'links-before');
    await rows.first().getByRole('button', { name: 'Confirm' }).click();
    // The same pair from the other signal is the same decision, so every row of the pair shows it.
    await expect(rows.first().getByTestId('link-state')).toHaveText('confirmed');
    for (const r of await rows.all()) {
      await expect(r.getByTestId('link-state')).toHaveText('confirmed');
      await expect(r).toContainText('admin');
    }
    await shot(page, 'links-after');

    await nav(page, 'Deviations');
    const rowsD = page.getByTestId('deviations').locator('tbody tr');
    await expect(rowsD).toHaveCount(2);
    await expect(rowsD.nth(0).getByTestId('deviation-state')).toHaveText('candidate');
    await rowsD.nth(0).getByRole('button', { name: 'Approve' }).click();
    await expect(rowsD.nth(0).getByTestId('deviation-state')).toHaveText('approved');
    await expect(rowsD.nth(0).getByTestId('deviation-by')).toHaveText('admin');
    await rowsD.nth(1).getByRole('button', { name: 'Reject' }).click();
    await expect(rowsD.nth(1).getByTestId('deviation-state')).toHaveText('rejected');
    await shot(page, 'deviations-after');

    await nav(page, 'Gap report');
    const gapAfter = page.locator('tr[data-gap="CBACT04C"]');
    await expect(gapAfter.getByTestId('gap-status')).toHaveText('partial');
    await expect(gapAfter.getByTestId('gap-locations')).toContainText('InterestCalculator.java');
    expect(await page.getByTestId('gap-counts').innerText()).not.toEqual(countsBefore);
    await gapAfter.getByRole('button', { name: 'Rules of CBACT04C' }).click();
    await expect(page.getByTestId('gaps')).toContainText('CBACT04C-R1');
    await shot(page, 'gap-report-after');

    await nav(page, 'Migration tracker');
    const card = page.locator('[data-stage="translated"] [data-program="CBACT04C"]');
    await expect(card).toBeVisible();
    await expect(card).toContainText('InterestCalculator.java');
    await expect(card.getByTestId('tests')).toContainText('tests');
    await expect(page.locator('[data-stage="analyzed"] [data-program="CBACT04C"]')).toHaveCount(0);
    await shot(page, 'tracker-after');

    // The board is what migration_status says, not a local guess.
    const token = await page.evaluate(() => localStorage.getItem('toolkit.session'));
    const tracker = await (await request.get(`${stack.API_URL}/api/tracker`, { headers: { Authorization: `Bearer ${token}` } })).json();
    expect(tracker.programs.find((p: any) => p.program_id === 'CBACT04C').stage).toBe('translated');

    // An owner set on the board comes back from the API.
    await card.getByRole('button', { name: 'Edit CBACT04C' }).click();
    await card.getByLabel('Owner').fill('lead1');
    await card.getByRole('button', { name: 'Save' }).click();
    await expect(page.locator('[data-stage="translated"] [data-program="CBACT04C"]')).toContainText('lead1');
  });

  test('a developer sees neither Admin nor the lead actions, and does see captures', async ({ page }) => {
    await signInWithToken(page, stack.DEV_TOKEN, 'dev1');
    const labels = await navLabels(page);
    expect(labels).not.toContain('Admin');
    expect(labels).not.toContain('Setup wizard');
    expect(labels).not.toContain('Review queue');
    expect(labels).toContain('Captures');
    await shot(page, 'inventory-as-developer');

    await page.goto('/admin');
    await expect(page.getByTestId('not-allowed')).toBeVisible();
    await shot(page, 'not-allowed');
    await page.goto('/setup');
    await expect(page.getByTestId('not-allowed')).toBeVisible();

    await nav(page, 'Link confirmation');
    await expect(page.getByTestId('read-only')).toBeVisible();
    await expect(page.getByTestId('links')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Confirm' })).toHaveCount(0);
    await nav(page, 'Deviations');
    await expect(page.getByTestId('deviations')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve' })).toHaveCount(0);

    await nav(page, 'Captures');
    const run = page.locator('tr[data-capture]').filter({ hasText: 'CBACT04C' });
    await expect(run).toContainText('3');
    await run.getByRole('button', { name: 'Show fixtures' }).click();
    const pair = page.locator('[data-fixture="run1"]');
    await expect(pair.locator('pre').first()).toContainText('DIS-INT-RATE');
    await expect(pair.locator('pre').nth(1)).not.toBeEmpty();
    await shot(page, 'captures');
  });

  test('the program explorer shows real source lines with correct numbers, and clicking a caller navigates', async ({ page }) => {
    await signInWithToken(page, stack.DEV_TOKEN, 'dev1');
    await nav(page, 'Program explorer');
    await expect(page.getByTestId('pick-program')).toBeVisible();
    await page.getByTestId('program-search').fill('CBSTM03');
    await expect(page.getByTestId('program-list').locator('tbody tr')).toHaveCount(2);
    await page.getByTestId('program-list').getByRole('link', { name: 'CBSTM03A' }).click();
    await expect(page.getByTestId('program-title')).toHaveText('CBSTM03A');
    await expect(page.locator('[data-line="1"]')).toBeVisible();

    await page.getByTestId('goto-line').fill('351');
    await page.getByTestId('goto-line').press('Enter');
    const line = page.locator('[data-line="351"]');
    await expect(line).toBeInViewport();
    expect((await page.getByTestId('source').boundingBox())!.height).toBeGreaterThan(400);
    await expect(line.locator('.ln')).toHaveText('351');
    await expect(line.locator('.tx')).toContainText("CALL 'CBSTM03B'");
    await expect(page.locator('[data-line="350"] .tx')).not.toContainText("CALL 'CBSTM03B'");
    await shot(page, 'program-explorer');

    // The side panel is real data too.
    await page.getByRole('tab', { name: /^Calls/ }).click();
    await expect(page.getByTestId('callees')).toContainText('CBSTM03B');
    await page.getByRole('tab', { name: /^Paragraphs/ }).click();
    await expect(page.getByTestId('side-panel')).toContainText('0000-START');
    await page.getByRole('tab', { name: /^Data/ }).click();
    await page.getByLabel('Filter data items').fill('WS-M03B');
    await expect(page.getByTestId('panel-data')).toContainText('WS-M03B-AREA');
    await shot(page, 'program-explorer-data');
    await page.getByTestId('panel-data').getByRole('link', { name: 'WS-M03B-AREA' }).click();
    await expect(page.getByTestId('graph-edges')).toContainText('LK-M03B-AREA');
    await page.goBack();

    await page.getByTestId('program-list').getByRole('link', { name: 'CBSTM03B' }).click();
    await expect(page.getByTestId('program-title')).toHaveText('CBSTM03B');
    await page.getByRole('tab', { name: /^Calls/ }).click();
    const caller = page.getByTestId('callers').getByRole('row', { name: 'CBSTM03A CALLS 351' });
    await caller.getByRole('button', { name: 'CBSTM03A' }).click();
    await expect(page).toHaveURL(/\/programs\/CBSTM03A\?line=351$/);
    await expect(page.getByTestId('program-title')).toHaveText('CBSTM03A');
    await expect(page.locator('[data-line="351"] .tx')).toContainText("CALL 'CBSTM03B'");
    await expect(page.locator('[data-line="351"]')).toBeInViewport();

    // A rule's summary and rules for a program the knowledge pass covered.
    await page.goto('/programs/CBACT04C');
    await expect(page.getByTestId('panel-summary')).toContainText('transaction');
    await page.getByRole('tab', { name: /^Rules/ }).click();
    await expect(page.getByTestId('panel-rules')).toContainText('CBACT04C-R1');
    await shot(page, 'program-explorer-rules');
  });

  test('a long source stays virtual: few DOM rows and a fast jump to the end', async ({ page }) => {
    await signInWithToken(page, stack.DEV_TOKEN, 'dev1');
    const started = Date.now();
    await page.goto('/programs/COACTUPC');
    await expect(page.locator('[data-line="1"]')).toBeVisible();
    const title = await page.getByTestId('program-title').locator('xpath=..').innerText();
    const total = Number(/(\d+) lines/.exec(title)?.[1]);
    console.log(`COACTUPC: ${total} lines, first paint after ${Date.now() - started} ms`);
    expect(total).toBeGreaterThan(4000);
    expect(await page.locator('.src-line').count()).toBeLessThan(150);
    await page.getByTestId('goto-line').fill(String(total));
    await page.getByTestId('goto-line').press('Enter');
    await expect(page.locator(`[data-line="${total}"]`)).toBeVisible();
    expect(await page.locator('.src-line').count()).toBeLessThan(150);
    await page.getByTestId('find').fill('CDEMO-TO-PROGRAM');
    await page.getByTestId('find').press('Enter');
    await expect(page.locator('.src-line.target .tx')).toContainText('CDEMO-TO-PROGRAM');
  });

  test('graphs: CBSTM03B draws a node for CBSTM03A; depth, filter, job flow and lineage work', async ({ page }) => {
    await signInWithToken(page, stack.DEV_TOKEN, 'dev1');
    await nav(page, 'Graph view');
    await page.getByTestId('graph-program').fill('CBSTM03B');
    await page.getByTestId('graph-draw').click();
    const canvas = page.getByTestId('cy');
    await expect(canvas.locator('canvas').first()).toBeVisible();

    const drawn = () =>
      canvas.evaluate((el: any) => {
        const box = el.getBoundingClientRect();
        return el.__cy.nodes().map((n: any) => {
          const b = n.renderedBoundingBox();
          return { id: n.id(), visible: n.visible(), inside: b.x1 >= -1 && b.y1 >= -1 && b.x2 <= box.width + 1 && b.y2 <= box.height + 1 };
        });
      });
    await expect.poll(async () => (await drawn()).map((n: any) => n.id)).toContain('CBSTM03A');
    await expect.poll(async () => (await drawn()).find((n: any) => n.id === 'CBSTM03A')).toEqual({ id: 'CBSTM03A', visible: true, inside: true });
    await expect(page.getByTestId('graph-edges')).toContainText('351');
    await shot(page, 'graph-calls');

    // Depth and filter controls change what is drawn.
    await page.getByTestId('graph-depth').fill('1');
    await page.getByTestId('graph-draw').click();
    await expect.poll(async () => (await drawn()).length).toBeGreaterThan(1);
    const atDepth1 = (await drawn()).length;
    await page.getByTestId('graph-filter').fill('CBSTM03A');
    await expect.poll(async () => (await drawn()).map((n: any) => n.id).sort()).toEqual(['CBSTM03A', 'CBSTM03B']);
    expect(atDepth1).toBeGreaterThanOrEqual(2);
    await page.getByTestId('graph-filter').fill('');

    await page.getByTestId('view-job').click();
    await page.getByTestId('graph-job').fill('POSTTRAN');
    await page.getByTestId('graph-draw').click();
    await expect.poll(async () => (await drawn()).map((n: any) => n.id)).toContain('CBTRN02C');
    expect((await drawn()).some((n: any) => n.id.includes('DALYTRAN'))).toBe(true);
    await shot(page, 'graph-job-flow');

    await page.getByTestId('view-lineage').click();
    await page.getByTestId('graph-item').fill('WS-M03B-AREA');
    await page.getByTestId('graph-scope').fill('CBSTM03A');
    await page.getByTestId('graph-draw').click();
    await expect.poll(async () => (await drawn()).map((n: any) => n.id).join()).toContain('LK-M03B-AREA');
    await shot(page, 'graph-lineage');

    await page.getByTestId('view-calls').click();
    await page.getByTestId('graph-program').fill('NOSUCHPROGRAM');
    await page.getByTestId('graph-draw').click();
    await expect(page.getByTestId('state-empty')).toContainText('NOSUCHPROGRAM has no call edges');
  });

  test('inventory, setup wizard and admin as the lead', async ({ page, request }) => {
    await signInWithPassword(page);
    await nav(page, 'Inventory');
    await expect(page.getByTestId('stat-programs')).toHaveText('44');
    await expect(page.getByTestId('by-type')).toContainText('cobol');
    await expect(page.getByTestId('dialect-features')).toContainText('EXEC CICS');
    await expect(page.getByTestId('dead-code')).toContainText('CBTRN01C');
    await expect(page.getByTestId('unresolved')).toContainText('CDEMO-ADMIN-OPT-PGMNAME');
    await expect(page.getByTestId('no-parse-failures')).toBeVisible();
    await shot(page, 'inventory');

    // Setup: doctor tests the text inline and a bad config is refused, in the API's words.
    await nav(page, 'Setup wizard');
    const yaml = page.getByTestId('yaml');
    await expect(yaml).toHaveValue(/client: x/);
    const good = await yaml.inputValue();
    await shot(page, 'setup-edit');
    await page.getByTestId('run-doctor').click();
    await expect(page.locator('tr[data-check="database"]')).toContainText('PASS');
    await expect(page.locator('tr[data-check="source"]')).toContainText('PASS');
    await shot(page, 'setup-doctor');

    await page.getByRole('tab', { name: 'Edit toolkit.yaml' }).click();
    await yaml.fill('client: x\nsource:\n  root: /no/such/dir\n');
    await page.getByTestId('run-doctor').click();
    await expect(page.locator('tr[data-check="source"]')).toContainText('FAIL');
    await expect(page.locator('tr[data-check="source"]')).toContainText('not a directory');
    await shot(page, 'setup-doctor-failing');

    await page.getByRole('tab', { name: 'Edit toolkit.yaml' }).click();
    await yaml.fill('client: x\nsource:\n  encoding: klingon\n');
    await page.getByRole('tab', { name: 'Save' }).click();
    await expect(page.getByTestId('save-warning')).toContainText('has not checked');
    await page.getByTestId('save').click();
    await expect(page.getByTestId('save-error')).not.toBeEmpty();
    await shot(page, 'setup-save-refused');
    await page.getByRole('tab', { name: 'Edit toolkit.yaml' }).click();
    await yaml.fill(good);
    await page.getByRole('tab', { name: 'Save' }).click();
    await page.getByTestId('save').click();
    await expect(page.getByTestId('saved')).toContainText('toolkit.yaml');

    // Admin: users.
    await nav(page, 'Admin');
    await expect(page.locator('tr[data-user="sme1"]')).toBeVisible();
    await page.getByTestId('new-user').fill('qa1');
    await page.getByTestId('new-user-roles').click();
    await page.getByRole('option', { name: 'developer' }).click();
    await page.keyboard.press('Escape');
    await page.getByTestId('add-user').click();
    await expect(page.locator('tr[data-user="qa1"]').getByRole('checkbox', { name: 'developer for qa1' })).toBeChecked();
    await page.locator('tr[data-user="qa1"]').getByRole('checkbox', { name: 'sme-reviewer for qa1' }).click();
    await expect(page.locator('tr[data-user="qa1"]').getByRole('checkbox', { name: 'sme-reviewer for qa1' })).toBeChecked();
    await shot(page, 'admin-users');

    // Admin: a new token is shown once, works, and can be revoked at once.
    await page.getByRole('tab', { name: 'MCP tokens' }).click();
    await pick(page, 'token-user', 'qa1');
    await page.getByTestId('token-name').fill('laptop');
    await page.getByTestId('issue-token').click();
    const value = await page.getByTestId('new-token-value').innerText();
    expect(value).toMatch(/^tk_/);
    await page.screenshot({ path: 'e2e/screenshots/admin-tokens.png', animations: 'disabled', mask: [page.getByTestId('new-token-value')] });
    const me = async () => (await request.get(`${stack.API_URL}/api/auth/me`, { headers: { Authorization: `Bearer ${value}` } })).status();
    expect(await me()).toBe(200);
    await page.getByRole('button', { name: 'I have copied it' }).click();
    await expect(page.getByTestId('new-token')).toHaveCount(0);
    await expect(page.locator('body')).not.toContainText(value);
    const row = page.locator('tr[data-token="laptop"]');
    await row.getByRole('button', { name: 'Revoke' }).click();
    await row.getByRole('button', { name: 'Confirm revoke' }).click();
    await expect(row.getByRole('button', { name: 'Revoke' })).toHaveCount(0);
    expect(await me()).toBe(401);

    await page.getByRole('tab', { name: 'Audit log' }).click();
    await expect(page.getByTestId('state-empty').or(page.getByTestId('audit'))).toBeVisible();
    await shot(page, 'admin-audit');

    await page.getByRole('tab', { name: 'LLM spend' }).click();
    await expect(page.getByTestId('llm')).toContainText('gpt-oss-32k:latest');
    await expect(page.getByTestId('llm')).toContainText('99979');
    await shot(page, 'admin-llm');

    // Re-index through the page: the job runs on the server and the page follows it to the end.
    await page.getByRole('tab', { name: 'Jobs' }).click();
    await expect(page.getByTestId('jobs')).toContainText('discover');
    await page.getByTestId('run-index').click();
    await expect(page.getByTestId('job-running')).toHaveText('Idle', { timeout: 60_000 });
    await shot(page, 'admin-jobs');
  });

  test('every list shows loading, empty and the API\'s error message', async ({ page }) => {
    await signInWithPassword(page);
    await page.goto('/links');
    await expect(page.getByTestId('links')).toBeVisible();

    // Empty, for real: nothing has been rejected.
    await pick(page, 'link-status', 'rejected');
    await expect(page.getByTestId('state-empty')).toHaveText('No links with this status.');

    // Loading.
    await page.route('**/api/gap-report*', async (r) => {
      await new Promise((f) => setTimeout(f, 800));
      await r.continue();
    });
    await nav(page, 'Gap report');
    await expect(page.getByTestId('state-loading')).toHaveText('Loading…');
    await expect(page.getByTestId('gaps')).toBeVisible();

    // The API's own error, and an API that does not answer.
    await page.route('**/api/deviations*', (r) => r.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ error: { code: 'CONFLICT', message: 'someone else decided this first', hint: 'reload and try again' } }) }));
    await nav(page, 'Deviations');
    await expect(page.getByTestId('state-error')).toContainText('someone else decided this first');
    await expect(page.getByTestId('state-error')).toContainText('CONFLICT');
    await shot(page, 'error-state');
    await page.route('**/api/tracker', (r) => r.abort());
    await nav(page, 'Migration tracker');
    await expect(page.getByTestId('state-error')).toContainText('the API is not answering');

    // A real refusal from the real API: an action the role does not allow.
    await page.unrouteAll();
    await page.goto('/programs/NOSUCH');
    await expect(page.getByTestId('state-error')).toContainText('no program named NOSUCH');
  });

  test('artifact text is bound as text, never rendered as HTML', async ({ page }) => {
    await signInWithPassword(page);
    await nav(page, 'Review queue');
    const note = page.getByTestId('artifact').filter({ hasText: 'COSGN00C' }).filter({ has: page.locator('strong', { hasText: 'summary' }) });
    await note.getByRole('button', { name: 'Edit' }).click();
    const box = page.getByTestId('edit-box');
    await box.fill('{ not json');
    await page.getByTestId('edit-save').click();
    await expect(page.getByRole('alert')).toContainText('Not valid JSON');
    const evil = '<img src=x onerror="window.__xss=1"><script>window.__xss=2</script><b id="evil">bold</b>';
    await box.fill(JSON.stringify({ purpose: evil, role: 'online' }));
    await page.getByTestId('edit-save').click();
    await pick(page, 'review-status', 'reviewed');
    await expect(page.locator('body')).toContainText(evil);
    await expect(page.locator('#evil')).toHaveCount(0);
    await page.goto('/programs/COSGN00C');
    await expect(page.getByTestId('panel-summary')).toContainText(evil);
    await expect(page.locator('#evil')).toHaveCount(0);
    expect(await page.evaluate(() => (window as any).__xss)).toBeUndefined();
  });

  test('the session: only the token in localStorage, a dead token ends it, sign-out revokes a login but not a personal token', async ({ page, request }) => {
    const status = async (t: string | null) => (await request.get(`${stack.API_URL}/api/auth/me`, { headers: { Authorization: `Bearer ${t}` } })).status();

    await signInWithPassword(page);
    expect(await page.evaluate(() => Object.keys(localStorage))).toEqual(['toolkit.session']);
    expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual([]);
    const session = await page.evaluate(() => localStorage.getItem('toolkit.session'));
    expect(await status(session)).toBe(200);
    // Still signed in after a reload.
    await page.reload();
    await expect(page.getByTestId('who')).toContainText('admin');
    await page.getByTestId('sign-out').click();
    await expect(page).toHaveURL(/\/login/);
    expect(await status(session)).toBe(401);
    expect(await page.evaluate(() => localStorage.getItem('toolkit.session'))).toBeNull();

    await signInWithToken(page, stack.DEV_TOKEN, 'dev1');
    await page.getByTestId('sign-out').click();
    await expect(page).toHaveURL(/\/login/);
    expect(await status(stack.DEV_TOKEN)).toBe(200);

    // A bad pasted token is refused with the API's words.
    await page.getByText('Use an access token').click();
    await page.getByTestId('token-input').fill('tk_nonsense');
    await page.getByTestId('token-submit').click();
    await expect(page.getByTestId('login-error')).toContainText('token is unknown or revoked');

    // A stored token that has died takes you back to sign-in, then returns you to where you were.
    await page.evaluate(() => localStorage.setItem('toolkit.session', 'tk_dead'));
    await page.goto('/tracker');
    await expect(page).toHaveURL(/\/login\?return=%2Ftracker/);
  });

  // The API trusts a real issuer's signature, which is covered by ApiIT; this is only the browser half, against a stand-in.
  test('SSO: authorization code with PKCE against a stand-in identity provider', async ({ page }) => {
    const issuer = 'https://idp.example/realm';
    const seen: { challenge?: string; verifier?: string; method?: string | null; redirect?: string | null; clientId?: string | null } = {};
    const cors = { 'access-control-allow-origin': '*' };
    await page.route('**/api/auth/config', (r) => r.fulfill({ json: { oidc_issuer: issuer, oidc_client_id: 'toolkit-ui' } }));
    await page.route('https://idp.example/**', async (r) => {
      const url = new URL(r.request().url());
      if (url.pathname.endsWith('/.well-known/openid-configuration')) {
        return r.fulfill({ headers: cors, json: { issuer, authorization_endpoint: `${issuer}/auth`, token_endpoint: `${issuer}/token` } });
      }
      if (url.pathname.endsWith('/auth')) {
        seen.challenge = url.searchParams.get('code_challenge') ?? undefined;
        seen.method = url.searchParams.get('code_challenge_method');
        seen.redirect = url.searchParams.get('redirect_uri');
        seen.clientId = url.searchParams.get('client_id');
        const back = new URL(seen.redirect!);
        back.searchParams.set('code', 'the-code');
        back.searchParams.set('state', url.searchParams.get('state')!);
        return r.fulfill({ status: 302, headers: { location: back.toString() } });
      }
      if (r.request().method() === 'OPTIONS') return r.fulfill({ status: 204, headers: { ...cors, 'access-control-allow-headers': '*' } });
      const form = new URLSearchParams(r.request().postData() ?? '');
      seen.verifier = form.get('code_verifier') ?? undefined;
      expect(form.get('grant_type')).toBe('authorization_code');
      expect(form.get('code')).toBe('the-code');
      return r.fulfill({ headers: cors, json: { access_token: 'stand-in.jwt.token' } });
    });
    await page.route('**/api/auth/me', (r) =>
      r.request().headers()['authorization'] === 'Bearer stand-in.jwt.token'
        ? r.fulfill({ json: { username: 'oidc.user', roles: ['developer'] } })
        : r.fulfill({ status: 401, json: { error: { code: 'UNAUTHENTICATED', message: 'a bearer token is required', hint: '' } } }),
    );
    await page.route('**/api/inventory', (r) => r.fulfill({ status: 503, json: { error: { code: 'STAND_IN', message: 'not part of this test', hint: '' } } }));

    await page.goto('/login');
    await page.getByTestId('sso').click();
    await expect(page.getByTestId('who')).toContainText('oidc.user');
    expect(seen.method).toBe('S256');
    expect(seen.clientId).toBe('toolkit-ui');
    expect(new URL(seen.redirect!).pathname).toBe('/auth/callback');
    const expected = createHash('sha256').update(seen.verifier!).digest('base64url');
    expect(seen.challenge).toBe(expected);
    expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual([]);

    // A response that does not match the request is refused.
    await page.evaluate(() => {
      localStorage.clear();
      sessionStorage.setItem('toolkit.oidc.pending', JSON.stringify({ state: 'expected', verifier: 'v', returnUrl: '/', tokenEndpoint: 'https://idp.example/realm/token', clientId: 'c', redirectUri: 'r' }));
    });
    await page.goto('/auth/callback?code=x&state=forged');
    await expect(page.getByTestId('login-error')).toContainText('does not match');
  });
});
