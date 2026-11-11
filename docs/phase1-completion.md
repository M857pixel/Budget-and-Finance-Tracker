# Phase 1 completion and email setup

## Verified against shared Supabase

The local backend is running at `http://localhost:8080` and React at `http://localhost:5173`. The backend uses the existing private IntelliJ credentials with `sslmode=verify-full` and `/Users/michaelshelton/Downloads/supabase-ca.crt`, `ddl-auto=validate`, and `AUTH_MAIL_MODE=dev`. No migration was rerun.

Independent PostgreSQL checks confirmed:

- `account_tokens` and both new user columns exist with the expected types/defaults/not-null constraints.
- Token primary key, user FK, allowed-purpose check, per-user/purpose uniqueness, normalized-email unique index, and RLS are present.
- anon/authenticated SELECT/INSERT privileges on tokens are false.
- Migration history contains exactly one Phase 1 entry.
- All 13 original accounts remain unverified at session version zero. Their original row fingerprints, including password hashes/balances, match the pre-migration snapshot exactly.
- All 77 transactions and 6 budgets match their original fingerprints exactly.
- There are now **14 users**: the original 13 plus dedicated test account ID **22**. No teammate account was modified. The test account is retained; its credentials are private, outside Git, in `~/.config/pocketledger-preflight/shared-smoke-account.json`.

The pool remains near its 15-session limit. No teammate sessions were terminated. To verify SQL independently, only the backend started by this task was briefly stopped; its released slot was used for read-only checks, then it was restarted with maximum pool size 1/minimum idle 0. The result is retained privately in `~/.config/pocketledger-preflight/phase1-independent-verification.json`.

## End-to-end results

A real no-send account on shared Supabase passed registration (201), pending-account login denial (403), verification, used-link rejection, login, logout, forgot-password, password reset, used-reset-link rejection, old-password rejection, old-session rejection, and new-password login.

Real Chromium also passed React login, credentialed transaction history, logout, forgot-password submission (200 and generic response), reset-link navigation in the same tab, password reset, and new-password login. No browser JavaScript errors occurred. Browser tests reused the dedicated account and a previously issued no-send reset link to avoid repeated mail requests. No transactions were created.

The browser test found that changing a URL hash in an already-open tab did not update the recovery screen. A small hashchange listener in `frontend/src/App.tsx` fixes this for verification/reset links. No other application code was changed in this follow-up.

Final backend tests: **25 passed, zero failures/errors** on Java 21. Final frontend `npm run build`: passed after the frontend fix. `git diff --check`: passed.

## Existing users: verification without losing access

All 13 original stored passwords use the supported PBKDF2 encoding. Their passwords and accounts were preserved. Unverified status is recoverable, not an account deletion or password change:

1. Open PocketLedger and choose **Resend verification**.
2. Enter the existing account's email address. Wait at least one minute before another request.
3. In real email mode, open the emailed link and press **Verify email**; then log in with the original email/password. Verification links expire after 24 hours; request another if needed. Resend supersedes the previous link.
4. In development/no-send mode, no message is delivered. The backend writes a private `mail-*.txt` file under Java's temp directory in `pocketledger-mail` (or `AUTH_DEV_OUTBOX`). The first line is the recipient and second line the link. For a coordinated local demo, the account owner can use the matching link privately. Never publish these bearer links or batch-update existing verification flags.
5. If a password is forgotten, verify first, then use **Forgot password**. The current implementation issues reset links only to verified accounts. Reset links expire after 30 minutes; successful reset invalidates old sessions.

Actual access depends on owning the stored email mailbox, or a coordinated no-send demonstration. If an old address is a typo or no longer accessible, stop and arrange an explicitly approved ownership/recovery process; do not silently mark it verified. Older teammate backend versions can still ignore verification, so coordinate which version is used.

## Exact EmailJS/Gmail setup remaining (manual)

No external accounts were configured, and no real emails were sent.

1. Create a dedicated free Gmail account for PocketLedger; protect it with recovery options and MFA. Do not use a teammate's personal mailbox/password in code.
2. Create an EmailJS account. In **Email Services**, add Gmail and authorize the dedicated account using its supported connection flow. A purchased domain is not required for this Gmail service. Do not click provider test-send buttons until ready to authorize a real message.
3. Create exactly two templates. Both must set **To Email** to `{{to_email}}` and include a link/button whose href is `{{action_url}}`:
   - Verification: subject `Verify your PocketLedger email`; explain the link expires in 24 hours and must be confirmed on the app page.
   - Reset: subject `Reset your PocketLedger password`; explain the link expires in 30 minutes and can be ignored if not requested.
4. In EmailJS Account/Security enable API requests from non-browser/server applications and private-key authorization as supported by your account. Obtain its public and private keys. Confirm those controls are available for the chosen plan; do not weaken backend authentication if the account cannot support them.
5. Set the following **backend-only** environment variables in the private IntelliJ Spring Boot run configuration (never React/Vite or tracked files):

```text
EMAILJS_SERVICE_ID=<connected Gmail service ID>
EMAILJS_VERIFY_TEMPLATE_ID=<verification template ID>
EMAILJS_RESET_TEMPLATE_ID=<reset template ID>
EMAILJS_PUBLIC_KEY=<EmailJS public key>
EMAILJS_PRIVATE_KEY=<EmailJS private key>
AUTH_FRONTEND_URL=http://localhost:5173
AUTH_MAIL_MODE=dev
```

6. Keep no-send mode until approval to send real messages. After approval, set `AUTH_MAIL_MODE=emailjs`, restart the backend, and test both templates with a controlled recipient. Check delivery/spam, provider permissions, and link behavior. Real recipients need an AUTH_FRONTEND_URL they can reach; localhost links work only on the machine running the frontend. HTTPS/nonlocal hosting needs separate approval/configuration.

The backend POSTs to `https://api.emailjs.com/api/v1.0/email/send`, mapping service/template/public/private keys to `service_id`, `template_id`, `user_id`, and `accessToken`. Template parameters are exactly `to_email` and `action_url`.

Official sources checked:

- REST API, payload, and one-request-per-second rate limit: https://www.emailjs.com/docs/rest-api/send/
- Private key authorization: https://www.emailjs.com/docs/sdk/options/
- Gmail/personal service connection and low-volume suitability: https://www.emailjs.com/docs/user-guide/connecting-email-services/
- Current free tier: 200 monthly requests, two templates: https://www.emailjs.com/pricing/

The adapter enforces 1.1 seconds between sends and a one-minute cooldown on errors. Requests during cooldown are not queued; users can retry later. Password-recovery responses remain generic even when delivery fails. Free quota and Gmail limits still apply; suitability is for a small school demo, not guaranteed production delivery.

## Running and Phase 2 handoff

The current local servers are left running. The private helper below reuses the existing IntelliJ database credentials without printing them; it does not contain passwords or change the run configuration:

```bash
python3 ~/.config/pocketledger-preflight/run-shared-backend.py
```

It launches the existing packaged JAR, uses verified TLS, limits itself to one database connection, and forces no-send mode. Rebuild/package the backend before using this helper after future Java changes. Startup logs are private at `~/.config/pocketledger-preflight/phase1-shared-startup.log`. Its Mistral key is intentionally empty for these auth tests; use the normal private AI configuration when beginning authorized Phase 2 work. Start Vite separately with `npm run dev` from frontend if it is not already running.

Before using the ordinary IntelliJ run configuration, add equivalent verified-TLS JDBC properties privately (sslmode=verify-full and sslrootcert pointing to the downloaded CA), keep ddl-auto=validate, and set AUTH_MAIL_MODE explicitly. Do not duplicate an already running backend on port 8080.

Phase 1's local/shared-database authentication paths are functioning. Remaining real-email blockers are the dedicated Gmail/EmailJS setup, private environment variables, reachable link URL, and approved live delivery tests. Shared pool capacity remains operationally constrained. No pushes, merges, deployments, existing-user status changes, migration reruns, or real sends occurred.
