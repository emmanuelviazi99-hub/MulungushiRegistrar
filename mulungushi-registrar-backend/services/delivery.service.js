// services/delivery.service.js
//
// Sends a verification code to the person. The code is NEVER put in an HTTP
// response — the only way to get it is to read the mailbox (or phone) it was
// sent to, which is what proves the person owns that address.
//
// Channels, chosen with CODE_DELIVERY (comma separated, default "email"):
//
//   email     Nodemailer over SMTP — Gmail with an app password, Mailtrap,
//             the university's mail relay ... (see .env.example). This is the
//             normal setting. Check your SMTP settings with:
//                 npm run email:test -- you@example.com
//   sms       A text through Twilio, IN ADDITION to email (CODE_DELIVERY=email,sms).
//             Optional; a Twilio trial can only text numbers you have verified.
//   console   Prints the code in the server terminal. Only for working offline
//             without any mail account; the server refuses to start in
//             production with it, because every code would land in the logs.
//
// Under NODE_ENV=test codes go to the in-memory `outbox` instead (unless a test
// sets TEST_CODE_DELIVERY to exercise a real channel, as tests/email.test.js does).

const outbox = []; // test only: { to, phone, purpose, code, text }

const PURPOSE_TEXT = {
  STUDENT_SIGNUP: 'finish creating your Mulungushi Registrar account',
  STAFF_SIGNUP: 'finish creating your Mulungushi Registrar staff account',
  PASSWORD_RESET: 'reset your Mulungushi Registrar password',
};

function parseChannels(value) {
  return String(value || 'email')
    .split(',')
    .map((c) => c.trim().toLowerCase())
    .filter(Boolean);
}

function activeChannels(env = process.env) {
  if (env.NODE_ENV === 'test') return env.TEST_CODE_DELIVERY ? parseChannels(env.TEST_CODE_DELIVERY) : ['memory'];
  return parseChannels(env.CODE_DELIVERY);
}

const escapeHtml = (s) => String(s).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

function buildMessage({ code, purpose, ttlMinutes }) {
  const action = PURPOSE_TEXT[purpose] || 'continue';
  const subject = `${code} is your Mulungushi Registrar code`;
  const text =
    `Your Mulungushi Registrar code is ${code}. ` +
    `Use it to ${action}. ` +
    `It expires in ${ttlMinutes} minutes. ` +
    'If you did not ask for this, ignore this message — nobody can use your account without this code.';
  const html = `<!doctype html>
<html><body style="margin:0;padding:24px;background:#f4f6f8;font-family:Arial,Helvetica,sans-serif;color:#1f2933">
  <div style="max-width:440px;margin:0 auto;background:#ffffff;border-radius:8px;padding:28px">
    <p style="margin:0 0 4px;font-size:13px;color:#52606d">Mulungushi University</p>
    <h1 style="margin:0 0 20px;font-size:20px">Your verification code</h1>
    <p style="margin:0 0 16px;font-size:15px">Use this code to ${escapeHtml(action)}:</p>
    <p style="margin:0 0 16px;font-size:30px;font-weight:bold;letter-spacing:4px;font-family:'Courier New',monospace">${escapeHtml(code)}</p>
    <p style="margin:0 0 16px;font-size:14px;color:#52606d">It expires in ${Number(ttlMinutes)} minutes and can only be used once.</p>
    <p style="margin:0;font-size:13px;color:#7b8794">If you did not ask for this, ignore this email. Nobody can use your account without the code.</p>
  </div>
</body></html>`;
  return { subject, text, html };
}

// --- email (Nodemailer) ------------------------------------------------------

function smtpSettings(env = process.env) {
  return {
    host: env.SMTP_HOST,
    port: Number(env.SMTP_PORT) || 587,
    secure: String(env.SMTP_SECURE).toLowerCase() === 'true', // true = port 465 (TLS), false = 587 (STARTTLS)
    auth: env.SMTP_USER ? { user: env.SMTP_USER, pass: env.SMTP_PASS } : undefined,
    from: env.SMTP_FROM || env.SMTP_USER,
  };
}

/** What is missing for email delivery to work ([] when the settings are complete). */
function missingSmtpSettings(env = process.env) {
  const missing = [];
  if (!env.SMTP_HOST) missing.push('SMTP_HOST');
  if (!env.SMTP_FROM && !env.SMTP_USER) missing.push('SMTP_FROM (or SMTP_USER)');
  if (env.SMTP_USER && !env.SMTP_PASS) missing.push('SMTP_PASS');
  return missing;
}

// One pooled SMTP connection for the whole process, created on first use, so
// every code does not pay for a fresh TCP + TLS + login handshake.
let transport = null;
let transportKey = null;

function getTransport() {
  const settings = smtpSettings();
  const key = JSON.stringify(settings);
  if (!transport || transportKey !== key) {
    const missing = missingSmtpSettings();
    if (missing.length) throw new Error(`Email delivery needs ${missing.join(', ')} in .env`);
    const nodemailer = require('nodemailer'); // only loaded when email delivery is used
    if (transport) transport.close();
    transport = nodemailer.createTransport({
      pool: true,
      maxConnections: 3,
      host: settings.host,
      port: settings.port,
      secure: settings.secure,
      auth: settings.auth,
      connectionTimeout: 10000,
      greetingTimeout: 10000,
      socketTimeout: 15000,
      // Local test servers use self-signed certificates; real SMTP servers must not.
      tls: process.env.NODE_ENV === 'test' ? { rejectUnauthorized: false } : undefined,
    });
    transportKey = key;
  }
  return { transport, from: settings.from };
}

async function sendViaEmail({ to, subject, text, html }) {
  const { transport: t, from } = getTransport();
  await t.sendMail({ from, to, subject, text, html });
}

/** Opens a connection and logs in, without sending anything. Used by `npm run email:test`. */
async function verifyEmailSettings() {
  const { transport: t } = getTransport();
  await t.verify();
}

function closeTransport() {
  if (transport) transport.close();
  transport = null;
  transportKey = null;
}

// --- sms (Twilio, optional) ----------------------------------------------------

async function sendViaTwilio({ to, body }) {
  const { TWILIO_ACCOUNT_SID: sid, TWILIO_AUTH_TOKEN: token, TWILIO_FROM: from } = process.env;
  if (!sid || !token || !from) {
    throw new Error('TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and TWILIO_FROM must be set for sms delivery');
  }
  const params = new URLSearchParams({ To: to, Body: body });
  // TWILIO_FROM is either a Twilio phone number (+1...) or a Messaging Service SID (MG...).
  if (from.startsWith('MG')) params.set('MessagingServiceSid', from);
  else params.set('From', from);

  const res = await fetch(`https://api.twilio.com/2010-04-01/Accounts/${encodeURIComponent(sid)}/Messages.json`, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${Buffer.from(`${sid}:${token}`).toString('base64')}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: params.toString(),
    signal: AbortSignal.timeout(8000),
  });
  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    throw new Error(`Twilio responded ${res.status}: ${detail.slice(0, 200)}`);
  }
}

// --- entry point -------------------------------------------------------------------

/**
 * Sends `code` to the person. Resolves with the list of channels that worked;
 * throws only if NONE worked (so a broken SMS gateway does not block sign-up
 * when the email went out).
 */
async function sendCode({ email, phone, code, purpose, ttlMinutes = 10 }) {
  const message = buildMessage({ code, purpose, ttlMinutes });
  const delivered = [];
  let lastError = null;

  for (const channel of activeChannels()) {
    try {
      if (channel === 'memory') {
        outbox.push({ to: email, phone: phone || null, purpose, code, text: message.text });
      } else if (channel === 'console') {
        console.log(`[DEV ONLY] ${purpose} code for ${email}${phone ? ` / ${phone}` : ''}: ${code}`); // eslint-disable-line no-console
      } else if (channel === 'email') {
        await sendViaEmail({ to: email, ...message });
      } else if (channel === 'sms') {
        if (!phone) continue; // no phone number on file: skip, the email still goes out
        await sendViaTwilio({ to: phone, body: message.text });
      } else {
        throw new Error(`Unknown CODE_DELIVERY channel "${channel}" (use email, sms or console)`);
      }
      delivered.push(channel);
    } catch (err) {
      lastError = err;
      console.error(`Code delivery via ${channel} failed: ${err.message}`); // eslint-disable-line no-console
    }
  }

  if (delivered.length === 0) throw lastError || new Error('No code delivery channel is configured');
  return delivered;
}

module.exports = {
  sendCode,
  buildMessage,
  activeChannels,
  parseChannels,
  missingSmtpSettings,
  verifyEmailSettings,
  sendEmail: sendViaEmail,
  closeTransport,
  outbox,
};
