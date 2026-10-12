// config/env.js
//
// Fails fast at startup on configuration that would make the server unsafe,
// instead of discovering it later (a missing JWT secret only shows up as a
// 500 on the first login; missing SMTP settings only show up when nobody
// receives their sign-up code; a console "code delivery" in production would
// write every verification code into the server logs).

function assertConfig(env = process.env) {
  const problems = [];

  const secret = env.JWT_SECRET || '';
  if (secret.length < 32 || /^replace_this/i.test(secret)) {
    problems.push('JWT_SECRET must be a random string of at least 32 characters (see .env.example)');
  }

  // Verification codes (services/delivery.service.js). Checked here so a missing
  // SMTP setting is found when the server starts, not by the first person who
  // tries to sign up and never receives their code.
  if (env.NODE_ENV !== 'test') {
    const { parseChannels, missingSmtpSettings } = require('../services/delivery.service');
    const channels = parseChannels(env.CODE_DELIVERY);
    const unknown = channels.filter((c) => !['email', 'sms', 'console'].includes(c));
    if (unknown.length) problems.push(`CODE_DELIVERY has unknown channel(s): ${unknown.join(', ')} (use email, sms or console)`);
    if (channels.includes('email')) {
      const missing = missingSmtpSettings(env);
      if (missing.length) {
        problems.push(
          `Email delivery of verification codes needs ${missing.join(', ')} (see the SMTP section of .env.example). ` +
            'Without a mail account you can set CODE_DELIVERY=console for local development.'
        );
      }
    }
    if (env.NODE_ENV === 'production' && channels.includes('console')) {
      problems.push('In production CODE_DELIVERY must not include "console": it prints every verification code into the server logs.');
    }
  }

  if (problems.length > 0) {
    throw new Error(`Configuration problems:\n - ${problems.join('\n - ')}`);
  }
}

/** bcrypt cost. Lowered only under test so the suite stays fast. */
function bcryptRounds() {
  return process.env.NODE_ENV === 'test' ? 4 : 12;
}

module.exports = { assertConfig, bcryptRounds };
