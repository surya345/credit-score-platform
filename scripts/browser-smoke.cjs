/* Optional browser test. Install Playwright separately, or set PLAYWRIGHT_MODULE.
 * The running stack must be available at localhost:4200 and localhost:8180.
 * Screenshots are optional, written only when TEST_ARTIFACTS is set.
 */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const browser = await chromium.launch({headless:true,args:['--no-sandbox'],
    ...(process.env.CHROMIUM_EXECUTABLE ? {executablePath:process.env.CHROMIUM_EXECUTABLE} : {})});
  const context = await browser.newContext({viewport:{width:1440,height:1050}});
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto('http://localhost:4200');
    await page.getByRole('button',{name:'Sign in to workspace'}).click();
    await page.locator('#username').fill('officer');
    await page.locator('#password').fill('Officer123!');
    await page.locator('#kc-login').click();
    await page.getByRole('heading',{name:'Credit analysis',exact:true}).waitFor({timeout:45000});
    await page.getByRole('button',{name:/Aarav Sharma/}).click();
    await page.getByRole('button',{name:'Financial data',exact:true}).click();
    await page.getByRole('button',{name:'Save & analyze',exact:true}).click();
    await page.getByRole('status').filter({hasText:'Analysis complete.'}).waitFor({timeout:90000});
    if(!(await page.locator('.score-number').innerText()).includes('763')) throw new Error('Expected score 763');
    if(process.env.TEST_ARTIFACTS) await page.screenshot({path:path.join(process.env.TEST_ARTIFACTS,'desktop.png'),fullPage:true});
    await page.getByRole('button',{name:/^Reports/}).click();
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button',{name:'Download PDF',exact:true}).first().click();
    const download = await downloadPromise;
    if(await download.failure()) throw new Error('PDF browser download failed');
    await page.setViewportSize({width:390,height:844});
    await page.getByRole('button',{name:'Overview',exact:true}).click();
    const overflow = await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth);
    if(overflow) throw new Error('Mobile layout overflows horizontally');
    if(process.env.TEST_ARTIFACTS) await page.screenshot({path:path.join(process.env.TEST_ARTIFACTS,'mobile.png'),fullPage:true});
    if(errors.length) throw new Error('Browser errors: '+errors.join('; '));
    console.log('PASS: PKCE sign-in, customer selection, submission, score 763, PDF download, mobile layout');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error.message);process.exitCode=1;});
