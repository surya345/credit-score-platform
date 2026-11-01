# Setup and operation

## Windows with Docker Desktop

1. Enable Docker Desktop's Linux container backend (WSL2 is suitable) and start Docker Desktop.
2. Extract the ZIP. Open PowerShell in the `credit-score-platform` folder containing `compose.yaml`.
3. Run `docker compose version`, then `docker compose up --build -d`.
4. Use `docker compose ps` and `docker compose logs -f` while the first build finishes.
5. Open http://localhost:4200. Use the application credentials from the README.

Alternatively run `powershell -ExecutionPolicy Bypass -File scripts/start.ps1` for the supplied local start script. On Linux/macOS run `sh scripts/start.sh`. These scripts only invoke Compose from the correct directory.

## Local endpoints

| URL / port | Purpose |
|---|---|
| http://localhost:4200 | Angular UI through Nginx |
| http://localhost:8080 | API gateway |
| http://localhost:8180 | Keycloak identity service |
| http://localhost:8025 | Mailpit inbox |
| localhost:1025 | Local SMTP receiver |
| localhost:3306 | MySQL |
| localhost:9092 | Kafka external listener |
| localhost:6379 | Redis |
| 8081 / 8082 / 8083 / 8084 | Users / data / scoring / reports |

The Keycloak **master-realm console** uses `admin / KeycloakAdmin123!`. The CreditLens **application** administrator uses `admin / Admin123!`. They are separate accounts in separate realms.

All APIs except health endpoints require a bearer token. A browser directly visiting `/api/customers` without a token should receive 401. All business services validate tokens as well as the gateway.

## Developing outside containers

Start only the infrastructure:

```sh
docker compose up -d mysql kafka redis keycloak mailpit
mvn -B -ntp package
```

Run each command in its own terminal from the project root:

```sh
java -jar user-service/target/user-service-1.0.0.jar
java -jar data-service/target/data-service-1.0.0.jar
java -jar scoring-service/target/scoring-service-1.0.0.jar
java -jar report-service/target/report-service-1.0.0.jar
java -jar gateway/target/gateway-1.0.0.jar
```

In another terminal:

```sh
cd frontend
npm ci
npm start
```

Open http://localhost:4200. The development proxy sends `/api` to localhost:8080. Do not run a container and its local counterpart on the same port.

The services use local MySQL defaults. Each accepts `DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `OIDC_ISSUER`, `OIDC_JWKS`, and `SERVER_PORT`; applicable services also use `USERS_URL`, `REDIS_HOST`, `REDIS_PORT`, `MAIL_HOST`, and `MAIL_PORT`. The gateway uses `USERS_URL`, `DATA_URL`, `SCORES_URL`, and `REPORTS_URL`. Spring properties can also be supplied as environment variables or command-line arguments.

H2 is a **test dependency only** and is not bundled in the production JAR. Normal startup uses MySQL. Test profiles disable event publishing, Kafka listener startup, demo seeding, and Redis caching.

## Useful checks

```sh
curl http://localhost:8080/actuator/health/readiness
python scripts/smoke-test.py --pdf-output sample-report.pdf --email
docker compose logs --tail=100 scoring-service report-service
```

The aggregate scoring-service health may be DOWN while Redis is unavailable, even though score reads fall back to the database. The readiness endpoint reports the application's readiness state; it does not prove the complete Kafka pipeline works. Use the smoke test for that.

### Optional browser test

The browser test uses Playwright's Chromium driver. Install it in a temporary tools directory, install Chromium using that package's CLI, and set `PLAYWRIGHT_MODULE` to that directory's absolute `node_modules/playwright` path. Then run:

```sh
node scripts/browser-smoke.cjs
```

It signs in through Keycloak, selects Aarav Sharma, submits the sample snapshot, waits for score 763 and its report, downloads a PDF, and checks mobile horizontal overflow. It uses synthetic demo data and creates another historical analysis each run. Set `TEST_ARTIFACTS` to an existing directory only if you want screenshots. If using a separately installed compatible Chromium build, set `CHROMIUM_EXECUTABLE` to its executable path.

## Troubleshooting

| Symptom | Check |
|---|---|
| Sign-in unavailable | Wait for Keycloak startup; inspect `docker compose logs keycloak`; use `localhost`, not a different hostname. |
| Port already allocated | Stop the other process or consistently change Compose ports, frontend config, identity redirects, issuer, and gateway configuration. |
| Analysis remains pending | Check Kafka, data-service outbox warnings, scoring/report logs, and dead-letter topics. Refresh before resubmitting. |
| Currency rejected | Financial snapshot currency must match the customer profile. |
| 409 on submit | A reused idempotency key has different data, or a database uniqueness conflict occurred. Retry the original request with its original key after a simultaneous conflict. |
| No email in a real inbox | Mail is intentionally captured locally at http://localhost:8025. |
| Schema validation fails | Check the database and Flyway history. Do not silently switch to Hibernate auto-update. |
| Login credentials changed in realm JSON but did not update | Realm import does not overwrite an existing realm. Update it deliberately through Keycloak, or reset the disposable local volumes. |

## Clean reset — deletes all local demo data

Only when you intend to discard all customers, scores, reports, identity changes, and Kafka messages:

```sh
docker compose down -v
docker compose up --build -d
```

The normal `down` command without `-v` preserves volumes. Reset the entire disposable demo together to avoid replaying old Kafka messages against unrelated empty service databases.

## Jenkins

The supplied `Jenkinsfile` expects a Linux agent with configured tools named `jdk-25` and `maven-3.9`, Node 24 and Python 3.10+ on PATH, and Docker Compose access. It runs backend tests and the MySQL/Kafka integration profile, builds Angular with the lockfile, then builds and verifies a disposable complete Compose stack. It publishes JUnit results and archives integration logs. Missing Docker fails these checks. It does not publish images or deploy to a remote cluster. See [INTEGRATION](INTEGRATION.md) for the same commands outside Jenkins.
