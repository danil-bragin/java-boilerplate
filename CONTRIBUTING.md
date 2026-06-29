# Contributing

Thanks for your interest in this project. It is a reference Spring Boot boilerplate; contributions that keep it small, opinionated, and production-honest are welcome.

## Ground rules

- **Build before you push:** `./gradlew build` must pass (compile, Spotless format check, unit + integration tests). Integration tests use Testcontainers, so a running Docker is required.
- **Format:** code is formatted with Palantir Java Format via Spotless. Run `./gradlew spotlessApply` before committing.
- **Java 21 / Gradle wrapper:** use the bundled `./gradlew` (Gradle 8.14, JDK 21 toolchain auto-provisioned). Don't add a system-Gradle requirement.
- **One concern per starter:** a starter brings exactly one cross-cutting concern onto the classpath via auto-configuration, with zero business logic.
- **Document decisions:** non-trivial design choices are captured as an [ADR](docs/decisions/) in MADR format. Add one when the choice isn't obvious from the code.

## Pull requests

1. Branch off `main`.
2. Keep the change focused; include tests for new behavior.
3. Ensure `./gradlew build` is green and Spotless is clean.
4. Write a clear PR description: what changed and why. Reference an issue or ADR where relevant.
5. CI (build + format + tests) must pass before merge.

## Commit messages

Conventional-style prefixes are used (`feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `chore`). Keep the subject imperative and under ~72 chars; explain the *why* in the body.

## Reporting bugs / requesting features

Open an issue using the templates. For anything security-sensitive, see [SECURITY.md](SECURITY.md) instead of filing a public issue.
