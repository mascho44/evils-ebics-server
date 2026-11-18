# Project context

Maintained via Claude Code going forward (previously synced from a ChatGPT project - that sync is
retired as of 2026-09-25, since it silently overwrote local commits on every new ChatGPT task run).
Normal git workflow applies: branch -> PR -> merge -> deploy, same as any other project here.

## Deployment

The server runs as Docker Compose services on a single host (`docker-compose.prod.yml`, with its own
`postgres` service and data volume). The Dockerfile is multi-stage: the jar is built from source inside
the image, so no local Maven build or pre-built jar is needed.

Per release: transfer the sources to the host (e.g. `git archive HEAD | ssh <host> tar -x -C <dir>`),
dump the database (`pg_dump -Fc`), then rebuild and recreate only the app container
(`docker compose -f docker-compose.prod.yml up -d --build app`). The database container and its
volume (participants, keys, ledger, accounts) are untouched by an app-only update. If files were
removed in the repository, delete the old target directory before extracting, since `tar` does not
remove them. Server-specific names, paths and credentials are deliberately not kept in this repository.

## Project instructions

This project has no custom instructions.
