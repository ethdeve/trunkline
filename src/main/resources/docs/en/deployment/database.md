# Database Deployment

Trunkline uses an **embedded H2 database** by default, which is suitable for single-instance deployments and development. As soon as you need **multiple replicas (`replicas > 1`)**, you must switch to an **external shared database** — this page explains how to connect to PostgreSQL.

## Why multiple replicas require an external database

An H2 single-file database can be **opened by only one process at a time**. Measured (H2 2.3.232, URL parameters identical to the default configuration, two JVM processes opening the same file concurrently):

```
[B] 失败: org.h2.jdbc.JdbcSQLNonTransientConnectionException:
    Database may be already in use: ".../data/trunkline.mv.db".
    Possible solutions: close all other connection(s); use the server mode [90020-232]
```

As a result, both mounting strategies are broken with multiple replicas:

| Mounting strategy | Actual behavior |
|---|---|
| Multiple pods share one volume (`ReadWriteMany`) | The 2nd and 3rd pods **cannot open the database** ⇒ start-up fails, crash loop |
| Each pod has its own volume | Each pod holds its own copy ⇒ users / accounts / API keys / role permissions diverge ⇒ **random 401s as requests round-robin** |

> If the shared volume sits on NFS, the risk **may** escalate from a clean failure to concurrent-write corruption (NFS locking semantics are unreliable). This point was not measured.

**Conclusion**: without an external shared database, `replicas` must stay at `1`. See issue #160.

## Prerequisites

| Item | Requirement |
|---|---|
| PostgreSQL | 12 or newer (the automated tests run against `postgres:16-alpine`) |
| JDBC driver | `org.postgresql:postgresql` is bundled with the distribution — nothing extra to install |
| Database account privileges | `CONNECT`, plus `USAGE` and `CREATE` on the target schema (Flyway needs them to create tables and indexes and to maintain `flyway_schema_history`) |
| Character set | UTF-8 (default) |

## Configuration

The data source is externalized through three environment variables, and the **defaults still point at H2**, so behavior is unchanged when they are not set:

```yaml
# config/config-service/core.yml
spring:
  datasource:
    url: ${DATABASE_URL:jdbc:h2:file:./data/trunkline;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=FALSE}
    username: ${DATABASE_USERNAME:sa}
    password: ${DATABASE_PASSWORD:}
```

To connect to PostgreSQL:

```bash
export DATABASE_URL="jdbc:postgresql://pg.internal:5432/trunkline"
export DATABASE_USERNAME="trunkline"
export DATABASE_PASSWORD="<strong-password>"
```

Notes:

- **Do not set `spring.jpa.properties.hibernate.dialect`.** The dialect is auto-detected by Hibernate from the connection; hardcoding it is silently carried over when you switch databases, producing wrong DDL with hard-to-diagnose errors.
- The driver needs no explicit declaration (`driver-class-name` was removed); Spring infers it from the URL prefix.
- The database must be **created beforehand** (`CREATE DATABASE trunkline;`). The application only creates tables.

### Connection pool

No connection pool is configured explicitly today, so Spring Boot's `HikariCP` defaults apply (maximum 10 connections). Before deploying multiple replicas, budget the total connections against PostgreSQL's `max_connections`:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10      # example; keep replicas x this value < max_connections
      connection-timeout: 30000
```

> The pool settings have **not been validated under load**. Tune them to your own capacity plan.

## Cross-database caveats

The following two differences between H2 and PostgreSQL are real and were verified by automated tests against a real PostgreSQL instance.

### Index and constraint names are schema-scoped

In PostgreSQL, tables, indexes, sequences, and views **share one schema-wide namespace**, whereas index names are **table-scoped** in H2 / MySQL. Two different tables therefore **cannot use the same index name**:

```
ERROR: relation "idx_audit_timestamp" already exists
```

When this happens, Hibernate **only logs a WARN and continues**, so the **index is silently dropped** — functionality is unaffected, but the related queries lose their index and get slower. Three historical duplicates (`idx_audit_*`, `idx_instance_id`) have been fixed, and automated tests now guard both name uniqueness and the actual existence of indexes.

When adding `@Index` to a new entity, keep the name **globally unique** and prefer a table prefix (for example `idx_cb_metrics_instance_id`).

### Physical type convention for the JSON columns (`tags` / `headers`)

`tags` / `headers` on `service_instance` use JSON mapping (`@JdbcTypeCode(SqlTypes.JSON)`) and the entity **no longer hard-codes `columnDefinition`**: the physical type is left to the dialect — PostgreSQL creates **`jsonb`** (measured: `information_schema.columns.udt_name = jsonb`), H2 / MySQL use their own `json` type, and read/write works.

Physical-type consistency for those columns is now guaranteed by the **versioned baseline**: the merged `V1__baseline.sql` builds them as **`jsonb`** on PostgreSQL and as the dialect's own `json` on H2 / MySQL directly — the earlier `TEXT` / `CLOB` divergence (issue #190) was already converged and frozen into the V1 baseline, so both database origins land on one physical type. No separate convergence step is needed for new databases.

> Check a database's actual types:
> `SELECT udt_name FROM information_schema.columns WHERE table_name = 'service_instance' AND column_name IN ('tags', 'headers');`

## Versioned schema migrations (Flyway)

Table creation is owned by **Flyway** on **both** the PostgreSQL and the H2 path (issues #191 / #192). **No extra switch is needed**: the application picks the script set from the dialect of the data source URL, see `FlywayDialectEnvironmentPostProcessor`.

| Data source | Flyway | Script location | `ddl-auto` | Who creates tables |
|---|---|---|---|---|
| `jdbc:postgresql://…` | enabled | `classpath:db/migration/postgres` | `validate` | Flyway |
| H2 (default / `DATABASE_URL` unset) | enabled | `classpath:db/migration/h2` | `update` | Flyway |

The decision is keyed on the URL rather than on a profile because which database this project connects to is decided solely by `DATABASE_URL`: the same `prod` profile may point at H2 or at PostgreSQL. Both dialects get `spring.flyway.enabled=true`, `baseline-on-migrate=true` and `baseline-version=1`; **only PostgreSQL also overrides `ddl-auto` to `validate`** — why H2 does not is explained at the end of this section.

Each dialect has its own script set because the physical types differ (H2 uses `json` / `TEXT` / `enum`, PostgreSQL uses `jsonb` / `TEXT` / `varchar` + check): **an entity change needs a script on both sides**.

### The baseline (`V1__baseline.sql`)

Each dialect's `V1__baseline.sql` is the **complete schema of the current entity model**, produced by Hibernate's schema export (not hand-written DDL), so it matches the entity mappings by construction — which is the precondition for `validate` to pass on PostgreSQL.

> **V1 is a "merged baseline".** On 2026-11-09 the schema was re-baselined: the fully evolved schema (V1 plus the V2…V18 increments applied on top) was **consolidated back into a single V1**. It already contains the results of the former `V2__legacy_convergence`, the new `model_provider` / `model_price` tables, `jwt_accounts.department/name/tenant_id`, the pre-provisioned `tenant_id` on 7 tables, the `virtual_model` dimension, the `quota_ledger` unique constraint, the `security_audit.event_type` addition `DATA_SANITIZATION`, and the removal of `role_permissions`. The original `V2`…`V18` scripts were **archived** under `db-migration-archive/{h2,postgres}/` at the repository root (kept in git, no longer scanned by Flyway, not packaged into the jar) for audit only. A published V1 baseline **must never be edited** (Flyway checks checksums); future schema changes go into new **V2 / V3 …** scripts on both sides.

- **Empty database**: Flyway runs `V1`, creating a schema identical to the entity model (history row: `version = 1`, `type = SQL`).
- **Existing database** (tables present, no `flyway_schema_history`): `baseline-on-migrate=true` records it **as baseline 1 without executing** `V1` (history row: `type = BASELINE`); the database contents are left untouched.

### Re-baselining a running instance

After the merge, if a running instance's `flyway_schema_history` is still on the old version chain (V1, or V1+V2…V18) while the jar now contains only V1, starting it directly fails on a version mismatch. The recommended re-baseline procedure (exactly what this repo did for the 8080 instance on 2026-11-09):

1. **Stop the running instance** (make sure no connection holds the database file — critical for H2).
2. **Drop the Flyway metadata table** so the database looks "non-empty but without a history table", which triggers `baseline-on-migrate`:
   - H2: via the H2 Shell or `DROP TABLE IF EXISTS flyway_schema_history;` — note this is a **whole-table DROP**, not `DELETE FROM flyway_schema_history`; deleting only rows leaves the table present and Flyway will think "never migrated" and re-run V1, failing on "table already exists".
   - PostgreSQL: `DROP TABLE IF EXISTS flyway_schema_history;`
3. **Restart with a clean jar** (`mvnw clean package` so target/classes does not retain the removed old `V2`…`V18`) — Flyway creates the table and records baseline 1 (log: `Successfully baselined schema with version: 1`); existing tables are untouched, no `V2`…`V18` is executed, then `validate` passes and the app starts normally.

> ⚠️ Key trap: `mvnw package` **incrementally overwrites** resources and does not delete old migration files that were removed from target/classes, so the jar can still contain the old `V2`…`V18` and start-up reports errors like `Duplicate column`. After renaming/moving migration scripts, **always** `mvnw clean package`.

### Why H2 still uses `update` (not `validate`)

Measured on H2 2.3.232 + Hibernate 6.6: the application's default URL carries `DATABASE_TO_UPPER=FALSE`, so objects are created in **lower case** (`PUBLIC.api_call_history`), while Hibernate's schema validation looks the schema name up in the JDBC metadata with the other casing and therefore always reports `Schema-validation: missing table …`. A JDBC-metadata probe confirms it: `getTables(cat, 'PUBLIC', …)` hits, `'public'` misses, and neither `default_schema=PUBLIC` nor `jdbc_metadata_extraction_strategy=grouped` fixes it. The same scripts do work under H2's **default** case mode (measured: migration + validation + start-up, exit code 0), but switching to it would invalidate every existing lower-case H2 database and force a rebuild — a breaking change this step does not take. H2 therefore runs "Flyway versioning + Hibernate `update`": **Flyway owns table creation and legacy repair, while `update` merely keeps a drifted dev database able to start**.

> ⚠️ Known limitation (unchanged from before): if a pre-v2.9.7 H2 database had its `tags` column added as `CLOB`, H2 cannot convert it to `json` in place (the V1 baseline only builds the correct `json` type for an **empty** database and will not alter pre-existing legacy columns). Delete the H2 database file and let it be recreated.

### Writing a new migration

- Published scripts must **never be edited** (Flyway verifies checksums); add `V2__…` on **both** sides for schema changes (`V1` is the merged baseline and is published, so `V2` onwards are the new feature migrations).
- Scripts target their own dialect (PostgreSQL uses `jsonb` / `generated by default as identity`; H2 uses `json` / `TEXT` / `enum`).
- Concurrency: Flyway takes its own lock on `flyway_schema_history`, so when several instances start at once only one performs the migration and the others wait — already safer than every pod running DDL. Even so, having a **Kubernetes Job run the migration first** and letting application pods only `validate` is preferable (see `kubernetes.md`).

## Migrating data from H2 to PostgreSQL

> ⚠️ **These steps have not been verified end-to-end against real production data.** Rehearse against a copy and verify row counts per table.

1. **Create the schema on PostgreSQL first**: start the application once with a `DATABASE_URL` pointing at PostgreSQL — Flyway runs `V1__baseline.sql` and creates the tables — then stop it.

2. **Export each table as CSV from H2** (run on the H2 side):

   ```sql
   CALL CSVWRITE('/tmp/export/api_call_history.csv', 'SELECT * FROM api_call_history');
   ```

3. **Import into PostgreSQL**:

   ```bash
   psql -h pg.internal -U trunkline -d trunkline \
     -c "\copy api_call_history FROM '/tmp/export/api_call_history.csv' CSV HEADER"
   ```

4. **Fix the identity sequences** (critical and easy to miss). The ID columns use `IDENTITY`, and bulk loading does **not** advance the underlying sequence, so later inserts collide with primary keys:

   ```sql
   SELECT setval(pg_get_serial_sequence('api_call_history', 'id'),
                 COALESCE((SELECT MAX(id) FROM api_call_history), 1));
   ```

   Run this for **every** table with an auto-incrementing primary key.

5. **Verify**: compare row counts between H2 and PostgreSQL for each table, and spot-check that key entities (accounts, API keys, role permissions) read back correctly.

## Verifying the connection took effect

1. **Check the start-up log**: the data source URL should be `jdbc:postgresql://…`. If it is still `jdbc:h2:file:…`, `DATABASE_URL` was not picked up.
2. **List the tables**:

   ```sql
   SELECT table_name FROM information_schema.tables
   WHERE table_schema = 'public' ORDER BY table_name;
   ```

   You should see 21 application tables (plus Flyway's own `flyway_schema_history`).
3. **Confirm the migration ran and recorded no failure**:

   ```sql
   SELECT version, description, type, success FROM flyway_schema_history ORDER BY installed_rank;
   ```

   A fresh database should show a single row `1 | baseline | SQL | t`; an existing database a single row `1 | << Flyway Baseline >> | BASELINE | t`.
4. **Check the indexes are complete** (related to the namespace trap above):

   ```sql
   SELECT indexname FROM pg_indexes WHERE schemaname = 'public' ORDER BY indexname;
   ```

## Automated tests

`PostgresCompatibilityIntegrationTest` verifies cross-database compatibility against a **real PostgreSQL** instance (table creation for every entity, `EXTRACT(HOUR …)`, large TEXT values, JSON columns, IDENTITY keys, `@Modifying` atomic increments, HQL `LIMIT`, index-name uniqueness, and actual index existence).

It is gated by environment variables and the whole class is skipped when they are absent:

```bash
export PG_TEST=true
export PG_TEST_URL="jdbc:postgresql://127.0.0.1:5432/postgres"
export PG_TEST_USERNAME=postgres
export PG_TEST_PASSWORD=postgres
./mvnw test -Dtest=PostgresCompatibilityIntegrationTest
```

CI (`.github/workflows/java-tests.yml`) provides a `postgres:16-alpine` service container, sets the variables above, and includes a "gated tests were not skipped" check — CI fails if that test gets skipped.

> Why not H2's `MODE=PostgreSQL`: the compatibility mode only covers a subset of the syntax and **cannot detect** real differences in dialect functions, column types, or generated DDL — which is exactly where cross-database issues live (for example `FUNCTION('HOUR', …)` works on H2 but there is no such function on PostgreSQL).

## Not covered yet

The following are **out of scope** for the current support and need separate work before scaling horizontally:

- **H2 does not use `ddl-auto: validate`**: see "Why H2 still uses `update`" above (with the measured evidence), so schema drift on H2 is not caught at start-up. Enabling it would require accepting a breaking change that invalidates every existing lower-case H2 database.
- **A pre-v2.9.7 H2 database whose `tags` column was added as `CLOB`**: H2 cannot convert it to `json` in place, so the database file must be deleted and recreated (unchanged from before).
- Multi-replica Kubernetes artifacts for production (manifests / Helm, PodDisruptionBudget, migration jobs): see issue #165.
