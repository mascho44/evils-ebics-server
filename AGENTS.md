# Project context

Maintained via Claude Code going forward (previously synced from a ChatGPT project - that sync is
retired as of 2026-09-25, since it silently overwrote local commits on every new ChatGPT task run).
Normal git workflow applies: branch -> PR -> merge -> deploy, same as any other project here.

## Deployment

The server runs as Docker Compose services on a single host, one stack for production and one for
staging (each with its own `postgres` service and data volume). The Dockerfile copies a pre-built jar
(`target/evils-ebics-server-*.jar`) instead of building from source inside the image.

Per release: build the jar locally (`mvn package`), copy the Dockerfile and **exactly one** jar into a
fresh build directory named after the version on the host, dump both databases (`pg_dump -Fc`), tag
the running images as `before-<version>`, build the new image from the build directory, then recreate
the app container - staging first, production after - with an explicit Compose project name
(`docker compose -p <project> -f <compose file> up -d --no-build app`). The database containers and
their volumes (participants, keys, ledger, accounts) are untouched by an app-only update.

Why exactly one jar: the wildcard `COPY` silently picks whichever match Docker's glob returns when
more than one `target/evils-ebics-server-*.jar` exists (lexical order, not version order or mtime -
e.g. `0.10.0` sorts before `0.9.0`), so a leftover jar can get built into the image instead of the new
one, with no error and no obvious symptom beyond "the feature I just deployed isn't there".
Server-specific names, paths and credentials are deliberately not kept in this repository.

## Project instructions

This project has no custom instructions.
