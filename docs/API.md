# API reference

Base URL: `http://localhost:8080`. All listed APIs require `Authorization: Bearer <access-token>`. OFFICER or ADMIN is required unless otherwise noted. JSON validation errors use Spring ProblemDetail; field validation adds an `errors` object. Common responses are 400 for invalid input, 401 for absent/invalid token, 403 for insufficient role, 404 for missing resources, 409 for uniqueness/idempotency conflicts, and 503 for a required downstream service failure.

| Method | Gateway path | Purpose |
|---|---|---|
| GET | `/api/users/me` | Current token subject, username, realm roles |
| GET | `/api/customers?search=&page=0&size=20` | Search name or reference; maximum size 100 |
| POST | `/api/customers` | Create profile; 201 |
| GET | `/api/customers/{id}` | Read profile |
| PUT | `/api/customers/{id}` | Update profile fields |
| POST | `/api/data/customers/{id}` | Submit financial snapshot; UUID Idempotency-Key required; 202 |
| GET | `/api/data/customers/{id}?page=0` | Financial history, 20 per page |
| GET | `/api/scores/customers/{id}/latest` | Latest source-version score; 404 before first score |
| GET | `/api/scores/customers/{id}/history?page=0` | Scores ordered by source version, 20 per page |
| POST | `/api/scores/preview` | Calculate a score without persistence or events |
| GET | `/api/reports/customers/{id}?page=0` | Reports ordered by source version, 20 per page |
| GET | `/api/reports/{id}` | Read report snapshot |
| GET | `/api/reports/{id}/pdf` | Authenticated PDF attachment, no-store |
| POST | `/api/reports/{id}/email` | Send a textual report summary to SMTP |
| GET | `/api/admin/{service}/audit?page=0&size=20` | ADMIN only; service is users, data, scores, or reports |

Customer creation/update body:

```json
{
  "reference": "CUS-1004",
  "fullName": "Synthetic Customer",
  "email": "customer@example.test",
  "currency": "INR"
}
```

Reference is 3–40 letters, digits, or hyphens and must be unique. Full name is required, maximum 120 characters. Email is required, maximum 200. Supported currencies are INR, SEK, EUR, and USD. New customers are ACTIVE.

Financial submission / score preview body:

```json
{
  "monthlyIncome": 80000,
  "monthlyDebtPayments": 12000,
  "creditUtilizationPercent": 25,
  "missedPaymentsLast12Months": 0,
  "creditHistoryMonths": 72,
  "recentCreditInquiries": 1,
  "currency": "INR"
}
```

POST financial data with `Idempotency-Key: <new UUID>`. Retain that key for retries of the same request. The response includes `eventId`, `schemaVersion`, `customerId`, `sourceVersion`, `submittedAt`, `input`, and `officerId`. A 202 means the financial record and outgoing event are saved; it does not mean scoring or report generation has completed.

Customer, score, financial-history, and report lists use an `items` array plus `total` and `page`. The customer list also returns `size`. Audit uses `content`, `totalElements`, `page`, and `size`. All pages are zero-based.

Email body:

```json
{ "to": "customer@example.test" }
```

Email sends a summary, not a PDF attachment. In the supplied configuration every SMTP message is captured by Mailpit locally.

## Event contracts

`financial-data-collected` uses `FinancialDataEvent`; `credit-score-updates` uses `ScoreCalculatedEvent`. Both are JSON strings with customer ID as the Kafka key. Java record definitions live in `contracts/src/main/java/dev/credit/contracts` and are the authoritative field definitions. The broker also contains `financial-data-collected.DLT` and `credit-score-updates.DLT`.

Use `scripts/smoke-test.py` as an executable client example. It obtains an officer-scoped token through a local service account and never prints that token. Interactive users sign in through the browser; password-grant authentication is disabled.
