/*
A script for converting html into pdf, using headless Chrome.
This is to replace wkhtmltopdf, since that's not actively maintained anymore.
Requires Chrome 59 or newer, and Puppeteer, which requires Node 6.4.0 or newer.
*/

const puppeteer = require('puppeteer');

const html_name = process.argv[2];
const pdf_name = process.argv[3];

puppeteer.launch({headless: true})
  .then((browser) =>
    browser.newPage().then((page) =>
      page.goto(html_name, {waitUntil: 'networkidle'})
        .then(
          () => page.pdf({path: pdf_name, format: 'A4', margin: {top: '20mm', bottom: '20mm'}, displayHeaderFooter: false})
        )
        .then(() => browser.close())));

