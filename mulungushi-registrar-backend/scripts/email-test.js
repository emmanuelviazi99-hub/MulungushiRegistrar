// scripts/email-test.js
//
// Checks your SMTP settings and sends one sample verification email, so you
// know codes will arrive BEFORE a student tries to sign up.
//
//   npm run email:test -- you@example.com

require('dotenv').config();
const delivery = require('../services/delivery.service');
const { generateCode } = require('../services/code.service');

async function main() {
  const to = process.argv[2];
  if (!to || !to.includes('@')) {
    console.error('Usage: npm run email:test -- you@example.com');
    process.exit(1);
  }
  const missing = delivery.missingSmtpSettings();
  if (missing.length) {
    console.error(`Missing in .env: ${missing.join(', ')}. See the SMTP section of .env.example.`);
    process.exit(1);
  }

  console.log(`Connecting to ${process.env.SMTP_HOST}:${process.env.SMTP_PORT || 587} as ${process.env.SMTP_USER || '(no login)'} ...`);
  await delivery.verifyEmailSettings();
  console.log('SMTP login OK.');

  const sample = generateCode();
  const { subject, text, html } = delivery.buildMessage({ code: sample, purpose: 'STUDENT_SIGNUP', ttlMinutes: 10 });
  // Sent through exactly the same Nodemailer transport the server uses.
  await delivery.sendEmail({
    to,
    subject: `[TEST] ${subject}`,
    text: `This is a test from npm run email:test — the code below does nothing.\n\n${text}`,
    html,
  });
  console.log(`Sent a test email to ${to}. If it is not in the inbox within a minute, check the spam folder.`);
  delivery.closeTransport();
}

main().catch((err) => {
  console.error(`Email test FAILED: ${err.message}`);
  if (/Invalid login|535|534/.test(err.message)) {
    console.error('Gmail: SMTP_PASS must be a 16-letter "app password" (Google Account > Security > 2-Step Verification > App passwords), not your normal password.');
  }
  delivery.closeTransport();
  process.exit(1);
});
