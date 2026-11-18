#!/usr/bin/env bash
set -euo pipefail
umask 077
# Usage: bash scripts/backup.sh /absolute/new-backup-directory /absolute/passphrase-file
destination=${1:?New absolute backup directory required}
passphrase=${2:?Passphrase file required}
[[ "$destination" = /* && "$passphrase" = /* && -s "$passphrase" ]] || { echo 'Absolute paths and a nonempty passphrase file required' >&2; exit 2; }
[[ -f docker-compose.prod.yml ]] || { echo 'Run from the deployment directory' >&2; exit 2; }
[[ ! -e "$destination" ]] || { echo 'Backup destination already exists; refusing overwrite' >&2; exit 2; }
mkdir -m 700 "$destination"
scratch=$(mktemp -d "${TMPDIR:-/tmp}/evils-backup.XXXXXXXX")
trap 'rm -rf -- "$scratch"' EXIT
docker compose -f docker-compose.prod.yml exec -T postgres pg_dump -U evils -d evils --format=custom > "$scratch/database.dump"
docker compose -f docker-compose.prod.yml exec -T postgres pg_restore --list < "$scratch/database.dump" > "$scratch/database.contents"
for config in .env docker-compose.prod.yml docker-compose.yml Dockerfile; do
  if [[ -f "$config" ]]; then cp "$config" "$scratch/$config"; fi
done
if [[ -f target/evils-ebics-server-0.6.0.jar ]]; then cp target/evils-ebics-server-0.6.0.jar "$scratch/application.jar"; fi
(cd "$scratch" && sha256sum database.dump > database.sha256)
tar -C "$scratch" -czf - . | openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 -pass "file:$passphrase" -out "$destination/backup.tar.gz.enc"
(cd "$destination" && sha256sum backup.tar.gz.enc > backup.sha256)
printf '%s\n' 'Backup created. Keep the passphrase separately; it is not included in the archive.'
printf '%s\n' "$destination/backup.tar.gz.enc"
