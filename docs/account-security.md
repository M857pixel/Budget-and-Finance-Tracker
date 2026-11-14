# Account security handoff

Branch: `feature/account-security`. No push, commit, shared database migration, live email, or external account changes were performed.

## Local setup and migration

Use Java 21 and Node with the existing Maven/npm wrappers. Backend schema management now uses `validate`, not `update`: startup cannot silently modify Supabase.

1. Create an isolated local PostgreSQL database. Set `SUPABASE_DB_URL` to its JDBC URL (including local credentials as needed). Do not use the shared Supabase URL for local migration testing.
2. Review existing migrations before applying them to that local database, then review/apply `supabase/migrations/202611091200_account_security.sql` locally. No migration runner was added.
3. The migration includes a duplicate normalized email audit query. Resolve any duplicates manually with the owners before migration; do not delete or merge records automatically. The unique index aborts the transaction if collisions remain.
4. All existing accounts become unverified; names, passwords, balances, and transactions remain intact. Users use Resend verification, confirm ownership, then log in with their existing password. Existing sessions without a version are rejected.
5. Start backend with `FRONTEND_AUTO_START=false` if running Vite separately. Default email mode is safe development mode.

## Environment variables

| Variable | Purpose/default |
| --- | --- |
| `SUPABASE_DB_URL` | Existing JDBC database connection; use isolated local database for tests |
| `FRONTEND_AUTO_START` | Existing launcher setting |
| `AUTH_MAIL_MODE` | `dev` by default; `emailjs` enables real sends only after approval |
| `AUTH_DEV_OUTBOX` | Default OS temp directory `/pocketledger-mail`; private local email files |
| `AUTH_FRONTEND_URL` | Trusted link base, default `http://localhost:5173`; HTTPS in deployment |
| `AUTH_ALLOWED_ORIGINS` | Comma-separated exact browser origins; defaults localhost/127.0.0.1 ports 5173 and 8080 |
| `EMAILJS_SERVICE_ID` | Gmail service ID |
| `EMAILJS_VERIFY_TEMPLATE_ID` | Verification template ID |
| `EMAILJS_RESET_TEMPLATE_ID` | Reset template ID |
| `EMAILJS_PUBLIC_KEY` | Backend-only public key |
| `EMAILJS_PRIVATE_KEY` | Backend-only private key |
| `SESSION_COOKIE_SECURE` | `true` with HTTPS; local HTTP default false |

Never put credentials in React/Vite environment files or commit them. Dev outbox files contain bearer links: keep private and delete after testing. This implementation uses POSIX permissions (macOS/Linux).

## EmailJS compatibility and manual setup

Official REST docs support backend JSON requests and private-key `accessToken`, at one request per second: https://www.emailjs.com/docs/rest-api/send/ . Gmail/personal services support small-volume sends: https://www.emailjs.com/docs/user-guide/connecting-email-services/ . Free tier currently lists 200 requests/month and two templates, sufficient for a university demo but unsuitable for large-volume use: https://www.emailjs.com/pricing/ . No purchased domain is required with a connected Gmail service.

After explicit approval for external setup:

1. Create a dedicated free Gmail account and EmailJS account; connect Gmail through EmailJS Email Services with the provider authorization flow. Do not share Gmail passwords in the repository.
2. Create two templates. Set recipient to `{{to_email}}` and include a button/link with `{{action_url}}`. Verification subject: “Verify your PocketLedger email”; reset subject: “Reset your PocketLedger password”. Verification expires in 24 hours, reset in 30 minutes. Do not enable tracking that rewrites or exposes token links.
3. In Account/Security enable server/non-browser API requests and private-key authorization as available. Confirm these controls are available on the actual account/free tier before enabling delivery. Do not weaken authentication if the account cannot support backend requests.
4. Set backend environment variables above. Keep `AUTH_MAIL_MODE=dev` until approval for a real recipient test.
5. After approval, validate delivery, spam placement, template escaping, quota behavior, expired links, and Gmail reconnect behavior. No credentials or live delivery were available for this work.

The adapter permits at most one send every 1.1 seconds, with a 60-second cooldown on provider failure and bounded network timeouts. It does not queue or retry automatically: users can resend after a minute. Registration remains pending on failed delivery; recovery responses are generic even on failure to avoid exposing account existence. A failed transactional issuance rolls back and retains any previous valid token.

## Security behavior and limits

Tokens contain 32 secure random bytes; only SHA-256 hashes are persisted. Issuance/consumption serialize on an account database lock, so resend supersedes earlier tokens and consumption is single-use. Reset increments session version; the global filter rejects old sessions on their next request. Login rotates the session; idle timeout is 30 minutes and absolute authentication lifetime is 12 hours. Requests already in flight at reset time may finish.

Legacy POST `/api/users` and DELETE `/api/users/{id}` return 410. Existing transaction and budget ownership checks remain. All unsafe API requests require an exact allowed Origin, including scripts/curl. Browser scripts cannot forge Origin; this strict Origin policy plus SameSite cookies provides CSRF protection without adding a second authentication framework. Controller tests isolate controller behavior with filters disabled; separate filter tests cover Origin rejection and revoked sessions.

Rate limits are bounded in-memory counters for a **single backend instance**: login 30/IP and 10/email per 15 minutes, registration 5/IP/hour, recovery 10/IP/hour and one/email/minute. Restarts reset counters; multiple replicas require a shared limiter before deployment. Use trusted proxy configuration before changing client address handling. Existing PBKDF2-HMAC-SHA256 hashes (600,000 iterations and random salt) stay compatible. No plaintext legacy passwords are accepted.

## Validation and remaining work

Executed Java 21 Maven tests: **25 passed**. Includes existing transaction/budget isolation tests, registration without session, duplicate email, unverified login, generic resend/recovery responses, hashed token issuance, expiry, verification, reset, token rejection, Origin validation, and session revocation. `npm run build` passed.

Follow-up: migration execution, data preservation, security constraints, duplicate rollback, and backend startup with validation have now passed against disposable PostgreSQL 18.6; see [migration runbook](account-migration-runbook.md). Not yet validated: PostgreSQL concurrent token-consumption integration, full browser flows against a local migrated database, and live EmailJS/Gmail delivery. These are required before shared rollout. Current tests use mocked persistence; they do not prove PostgreSQL locking behavior. Shared migration and deployment remain approval-gated.

Manual local browser checklist: register → pending screen → open private dev outbox link → confirm verification → log in → create transaction → logout → forgot password → open reset link → change password → verify old link fails, old password fails, old session fails, new password works. Check resend supersedes the earlier verification link, and a second account cannot access the first account's URL records.

## Changed files

- Backend controllers: `AuthController`, `LedgerController`.
- Model/repositories: `User`, `UserRepository`, new `AccountToken`, `AccountTokenRepository`.
- New services/filter: `AccountRecovery`, `AccountMail`, `AuthRateLimiter`, `SessionGuard`.
- Config: `application.properties` disables automatic schema updates.
- Frontend: `App.tsx`, new `AccountRecovery.tsx`.
- Tests: four existing controller test classes, new `AccountRecoveryTest`, `SessionGuardTest`.
- Migration: `202611091200_account_security.sql`; this handoff document.
