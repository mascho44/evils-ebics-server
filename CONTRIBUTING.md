# Contributing

Thanks for your interest in EVILS. Bug reports, test cases from real EBICS clients, and pull requests
are welcome. For security problems please follow [SECURITY.md](SECURITY.md) instead of opening an issue.

## Getting started

Requirements: JDK 21, Maven 3.9+ and Docker (for the PostgreSQL development database).

```bash
mvn test          # H2 profile, no database needed
mvn clean package # always build with "clean": stale classes can end up in the jar
```

See the [README](README.md) for running the server locally.

## Making a change

1. Open an issue first for anything larger than a small fix, so we can agree on the approach.
2. Branch from `main`, keep the change focused, and add or update tests. The suite must stay green.
3. Update [CHANGELOG.md](CHANGELOG.md) (new version on top, newest first) and raise the version in
   `pom.xml`: a new feature is a minor version (`0.25.0`), a fix a patch version (`0.24.2`).
4. Update the README and `docs/ROADMAP.md` if behavior or scope changes.
5. Open a pull request that explains *why*, not only *what*.

## Conventions

- **Language:** new code comments, commit messages and pull requests in English. User-facing console
  texts and domain terms of the German banking profile (for example *Kontoauszug*) stay German.
- **Comments:** explain the reason behind non-obvious decisions (a protocol detail, a bank behavior), not
  what the next line does. Give every new class a short description of its role.
- **Packages:** `de.ostms.evils.ebics.*`; keep protocol handling, services, generators and admin
  controllers in their existing packages.
- **Protocol work:** cover new order types with a test that goes through the real request path and
  validates the response against the bundled schema where one exists. Prefer behavior that matches what
  German banks deliver over a literal reading of the specification, and say so in a comment.
- **Never commit secrets,** real keys, real bank data or `.env` files. Test credentials in tests are fine.

## License

By contributing you agree that your contribution is licensed under the
[GNU General Public License v3.0](LICENSE), like the rest of the project.
