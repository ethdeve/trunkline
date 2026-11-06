# Store Configuration

<!-- 版本信息 -->
> **Doc Version**: 1.0.0
> **Last Updated**: 2026-06-09
> **Git Commit**: 227a92b7
> **Author**: Lincoln
<!-- /版本信息 -->

> **Version:** 2.9.5  
> **Last Updated:** 2026-06-09  
> **Default Storage:** H2 Database

Trunkline uses a storage manager to persist configuration data and version control. This page details the storage-related configuration options.

## Storage Types

Trunkline defaults to JPA persistence (embedded H2, switchable to PostgreSQL).

### JPA / H2 Storage (jpa)

JPA storage is the default method, keeping configuration data in a relational database with transaction support. The default datasource is embedded H2 (file database `./data/jairouter`), switchable to PostgreSQL via environment variables. Suitable for all environments (dev, test, prod).

### Redis Storage (redis)

Keeps configuration in Redis, suited for multi-instance shared-config deployments.

### Memory Storage (memory)

Keeps configuration in memory, for temporary/testing scenarios; lost on restart.

## Configuration Options

Storage configuration uses the `store` prefix (`config/config-service/core.yml`).

### Basic Configuration

```yaml
store:
  type: jpa              # Storage type, default jpa; alternatives redis / memory
  path: ./config         # Config file import/export directory
  fallback-storage: memory   # Fallback storage on degradation
  migration:
    enabled: true        # Migrate from legacy file store to JPA
  security-migration:
    enabled: true        # Migrate security data (API keys, JWT accounts)
```

### Configuration Description

| Configuration Item | Default Value | Description |
|--------------------|---------------|-------------|
| `store.type` | `jpa` | Storage type: `jpa` (default), `redis`, `memory` |
| `store.path` | `./config` | Config file import/export directory |
| `store.fallback-storage` | `memory` | Fallback storage on degradation |
| `store.migration.enabled` | `true` | Config data migration toggle |
| `store.security-migration.enabled` | `true` | Security data migration toggle |

> The earlier keys `store.h2.url`, `store.auto-merge`, `store.auto-backup`, `store.backup-interval` **do not exist**. The H2 connection is configured under `spring.datasource`.

### Datasource Configuration

```yaml
spring:
  datasource:
    url: ${DATABASE_URL:jdbc:h2:file:./data/jairouter;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=FALSE}
    username: ${DATABASE_USERNAME:sa}
    password: ${DATABASE_PASSWORD:}
  jpa:
    hibernate:
      ddl-auto: update     # update for H2; overridden to validate by Flyway for PostgreSQL
  h2:
    console:
      enabled: false       # enable in dev
```

> The earlier `spring.r2dbc` configuration does not apply to the current JPA implementation.

### H2 Database Storage Complete Configuration

```yaml
store:
  type: h2
  h2:
    url: file:./data/config
  migration:
    enabled: false
  security-migration:
    enabled: false

spring:
  r2dbc:
    url: r2dbc:h2:file:///./data/config
    username: sa
    password:
    pool:
      initial-size: 10
      max-size: 20
      max-idle-time: 30m
  h2:
    console:
      enabled: false
      path: /h2-console

jairouter:
  security:
    audit:
      storage: h2
      retentionDays: 30
```

## Environment-Specific Configuration

Different environments can have different storage configurations:

### Development Environment (application-dev.yml)

```yaml
store:
  type: h2
  h2:
    url: file:./data/dev-config
  migration:
    enabled: true  # Enable automatic migration in development environment
  security-migration:
    enabled: true  # Enable security data migration in development environment

spring:
  r2dbc:
    pool:
      initial-size: 10
      max-size: 20
  h2:
    console:
      enabled: true
      path: /h2-console

jairouter:
  security:
    audit:
      storage: h2
      retentionDays: 7
```

### Production Environment (application-prod.yml)

```yaml
store:
  type: h2
  h2:
    url: file:/var/lib/jairouter/data/config
  migration:
    enabled: false
  security-migration:
    enabled: false

spring:
  h2:
    console:
      enabled: false
  r2dbc:
    pool:
      initial-size: 20
      max-size: 50
      max-idle-time: 30m

jairouter:
  security:
    audit:
      storage: h2
      retentionDays: 90
```

## Auto-Merge Configuration

The auto-merge function is used to scan and process multi-version configuration files in the configuration directory.

### Function Description

When the auto-merge function is enabled, the system will periodically scan multi-version configuration files in the configuration directory and provide merge functionality.

### Usage Example

```yaml
# Enable auto-merge function and specify configuration directory
store:
  type: file
  path: "/var/lib/jairouter/config/"
  auto-merge: true
```

## H2 Database Management

### H2 Console Access

The H2 console can be enabled in development environments for database management and debugging:

```yaml
spring:
  h2:
    console:
      enabled: true
      path: /h2-console
```

Access `http://localhost:8080/h2-console` for management.

Connection information:
- JDBC URL: `jdbc:h2:file:./data/config`
- Username: `sa`
- Password: (empty)

### Database Table Structure

The system automatically creates the following tables to store different types of data:

| Table Name | Purpose | Estimated Records |
|------------|---------|------------------|
| config_data | Configuration data | 10-100 |
| security_audit | Security audit | 10,000+ |
| api_keys | API Keys | 10-50 |
| jwt_accounts | JWT Accounts | 5-20 |

#### config_data Table

| Field | Type | Description |
|-------|------|-------------|
| id | BIGINT | Primary key |
| config_key | VARCHAR(255) | Configuration key |
| config_value | TEXT | Configuration content (JSON format) |
| version | INT | Version number |
| created_at | TIMESTAMP | Creation time |
| updated_at | TIMESTAMP | Update time |
| is_latest | BOOLEAN | Whether it is the latest version |

#### security_audit Table

| Field | Type | Description |
|-------|------|-------------|
| id | BIGINT | Primary key |
| event_id | VARCHAR(255) | Unique event identifier |
| event_type | VARCHAR(100) | Event type |
| user_id | VARCHAR(255) | User ID |
| client_ip | VARCHAR(50) | Client IP |
| user_agent | VARCHAR(500) | User agent |
| timestamp | TIMESTAMP | Event time |
| resource | VARCHAR(500) | Accessed resource |
| action | VARCHAR(100) | Performed action |
| success | BOOLEAN | Whether successful |
| failure_reason | VARCHAR(1000) | Failure reason |
| additional_data | TEXT | Additional data (JSON) |
| request_id | VARCHAR(255) | Request ID |
| session_id | VARCHAR(255) | Session ID |

#### api_keys Table

| Field | Type | Description |
|-------|------|-------------|
| id | BIGINT | Primary key |
| key_id | VARCHAR(255) | Key ID |
| key_value | VARCHAR(500) | Key value |
| description | VARCHAR(1000) | Description |
| permissions | TEXT | Permission list (JSON) |
| expires_at | TIMESTAMP | Expiration time |
| created_at | TIMESTAMP | Creation time |
| enabled | BOOLEAN | Whether enabled |
| metadata | TEXT | Metadata (JSON) |
| usage_statistics | TEXT | Usage statistics (JSON) |

#### jwt_accounts Table

| Field | Type | Description |
|-------|------|-------------|
| id | BIGINT | Primary key |
| username | VARCHAR(255) | Username |
| password | VARCHAR(500) | Password (encrypted) |
| roles | TEXT | Role list (JSON) |
| enabled | BOOLEAN | Whether enabled |
| created_at | TIMESTAMP | Creation time |
| updated_at | TIMESTAMP | Update time |

## Data Migration

### Migrating from File Storage to H2 Database

Enable automatic migration function to automatically migrate file storage data to H2 during application startup:

```yaml
store:
  type: h2
  migration:
    enabled: true
```

### Security Data Migration

The system also supports migrating security-related data to H2 database:

```yaml
store:
  type: h2
  security-migration:
    enabled: true  # Enable security data migration (API Keys, JWT accounts, etc.)
```

### First-time Startup Configuration

If there is existing data in the system that needs to be migrated, you can enable the migration function during first-time startup:

**Development Environment:**

```yaml
# application-dev.yml
store:
  migration:
    enabled: true  # Enable configuration data migration
  security-migration:
    enabled: true  # Enable security data migration
```

After starting the application, the system will automatically:
1. Migrate configuration data from `./config/*.json`
2. Migrate API Keys from memory/configuration files
3. Migrate JWT accounts from configuration files

After migration is complete, it is recommended to disable automatic migration:

```yaml
store:
  migration:
    enabled: false
  security-migration:
    enabled: false
```

**Production Environment:**

It is recommended to manually migrate in production environments:

```bash
# 1. Validate migration in staging environment
java -jar app.jar --spring.profiles.active=staging \
  --store.migration.enabled=true \
  --store.security-migration.enabled=true

# 2. Verify data integrity
# Access H2 console to check data

# 3. Backup database
cp ./data/config.mv.db ./backup/

# 4. Execute migration in production environment
java -jar app.jar --spring.profiles.active=prod \
  --store.migration.enabled=true \
  --store.security-migration.enabled=true

# 5. Disable migration switch after verification
```

## Performance Optimization

### Connection Pool Configuration

```yaml
spring:
  r2dbc:
    pool:
      initial-size: 10
      max-size: 20
      max-idle-time: 30m
      max-acquire-time: 3s
      max-create-connection-time: 5s
```

### Index Optimization

The system automatically creates the following indexes to improve query performance:

- `idx_config_key`: Configuration key index
- `idx_is_latest`: Latest version marker index
- `idx_config_key_latest`: Composite index

### Database Optimization

Regularly perform database optimization:

```sql
-- Analyze tables
ANALYZE TABLE config_data;
ANALYZE TABLE security_audit;
ANALYZE TABLE api_keys;
ANALYZE TABLE jwt_accounts;

-- View index usage
SELECT * FROM INFORMATION_SCHEMA.INDEXES;
```

## Backup and Recovery

### Backup

The H2 database file is located at `./data/config.mv.db` and can be backed up by directly copying the file:

```bash
cp ./data/config.mv.db ./backup/config-$(date +%Y%m%d).mv.db
```

### Recovery

Stop the application, replace the database file, and restart:

```bash
cp ./backup/config-20241120.mv.db ./data/config.mv.db
```

### Production Environment Backup Strategy

```bash
#!/bin/bash
# /usr/local/bin/backup-jairouter-db.sh

DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="/var/backups/jairouter"
DB_FILE="/var/lib/jairouter/data/config.mv.db"

mkdir -p $BACKUP_DIR
cp $DB_FILE $BACKUP_DIR/config_$DATE.mv.db

# Compress backup
gzip $BACKUP_DIR/config_$DATE.mv.db

# Keep backups for the last 30 days
find $BACKUP_DIR -name "config_*.mv.db.gz" -mtime +30 -delete

echo "Backup completed: config_$DATE.mv.db.gz"
```

Add to crontab:

```bash
# Backup daily at 3 AM
0 3 * * * /usr/local/bin/backup-jairouter-db.sh
```

## Notes

1. Ensure the storage path has appropriate read/write permissions
2. In production environments, it is recommended to use absolute paths to avoid path issues
3. Configuration directories should be backed up regularly to prevent data loss
4. Different environments should use different storage paths to avoid configuration conflicts
5. H2 database files cannot be shared between multiple processes
6. H2 database is now the default storage method, providing better performance and reliability

## Troubleshooting

### Storage Directory Does Not Exist

If the configured storage directory does not exist, the system will log warning messages but will not automatically create the directory. Please ensure the directory exists and has appropriate permissions.

### Permission Issues

If you encounter permission issues, please check:
- Read/write permissions of the application running user to the storage directory
- Execute permissions of the parent directory (permission to enter the directory)

### Insufficient Disk Space

Monitor the disk usage of the storage directory to ensure there is enough space to store configuration data and version history.

### Auto-Merge Function Not Working

If the auto-merge function is not working, please check:
- Whether the `store.auto-merge` configuration is set to true
- Whether the configuration directory exists and has read permissions
- Whether the configuration file naming conforms to the `model-router-config@<version>.json` format

### Database Connection Failure

If you encounter database connection issues, please check:
- Whether the `store.h2.url` configuration is correct
- Whether the database file path has write permissions
- Detailed error information in application logs

### Database File Corruption

```bash
# Try recovery
java -cp h2*.jar org.h2.tools.Recover -dir ./data -db config

# If recovery fails, use backup
cp ./backup/config-latest.mv.db ./data/config.mv.db
```

### Connection Pool Exhaustion

Check logs:

```
ERROR: Pool is exhausted
```

Solution:

```yaml
spring:
  r2dbc:
    pool:
      max-size: 100  # Increase connection pool size
```