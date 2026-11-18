#!/usr/bin/env bash
set -euo pipefail
umask 077
# Restores only into a NEW, isolated disposable PostgreSQL container, never the live DB.
archive=${1:?Absolute encrypted backup path required}
passphrase=${2:?Absolute passphrase file required}
[[ "$archive" = /* && -f "$archive" && "$passphrase" = /* && -s "$passphrase" ]] || exit 2
scratch=$(mktemp -d "${TMPDIR:-/tmp}/evils-restore.XXXXXXXX")
container="evils-restore-check-$(date +%s)-$$"
cleanup(){ docker rm -f -v "$container" >/dev/null 2>&1 || true; rm -rf -- "$scratch"; }
trap cleanup EXIT
(cd "$(dirname "$archive")" && sha256sum -c backup.sha256)
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$passphrase" -in "$archive" -out "$scratch/backup.tar.gz"
# The archive must come from backup.sh. Reject absolute paths, traversal and symlinks.
tar -tzf "$scratch/backup.tar.gz" | awk '/^\// || /(^|\/)\.\.($|\/)/ {bad=1} END {exit bad}'
if tar -tvzf "$scratch/backup.tar.gz" | awk 'substr($0,1,1)=="l" || substr($0,1,1)=="h" {bad=1} END {exit !bad}'; then echo 'Unsafe archive links' >&2; exit 2; fi
tar -xzf "$scratch/backup.tar.gz" -C "$scratch"
(cd "$scratch" && sha256sum -c database.sha256)
docker run -d --name "$container" --network none --tmpfs /var/lib/postgresql/data -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=evils postgres:16-alpine >/dev/null
ready=false
for attempt in $(seq 1 30); do
  if docker exec "$container" pg_isready -U postgres -d evils >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
[[ "$ready" = true ]] || { echo 'Restore database failed to start' >&2; exit 1; }
docker exec -i "$container" pg_restore -U postgres -d evils --no-owner --no-privileges --exit-on-error < "$scratch/database.dump"
docker exec "$container" psql -U postgres -d evils -v ON_ERROR_STOP=1 -c "SELECT 'accounts' AS entity,count(*) FROM accounts UNION ALL SELECT 'payment_orders',count(*) FROM payment_orders UNION ALL SELECT 'bank_keys',count(*) FROM bank_keys;"
printf '%s\n' 'Restore check passed in isolated PostgreSQL. Live database unchanged. Temporary restore container is removed.'
