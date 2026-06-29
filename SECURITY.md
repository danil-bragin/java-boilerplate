# Security Policy

This is a reference/boilerplate project, not a deployed service. Still, if you find a vulnerability in the code or a dependency, please report it responsibly.

## Reporting a vulnerability

- **Preferred:** open a [private security advisory](https://github.com/danil-bragin/java-boilerplate/security/advisories/new) via GitHub.
- Do **not** open a public issue for a security-sensitive report.

Please include: affected module, a description, and reproduction steps or a proof of concept. You'll get an acknowledgement and, where applicable, a fix or guidance.

## Scope notes

- Credentials in `compose.yaml`, `application.yaml`, and Keycloak config are **development placeholders** (`demo`/`admin`, `${VAR:default}` overrides). They are not secrets and are not used in any real deployment.
- The `examples/acme-bank` stack is for local demonstration only; it is not hardened for production exposure.

## Supported versions

The `main` branch is the only supported line. Dependency updates are tracked via Dependabot.
