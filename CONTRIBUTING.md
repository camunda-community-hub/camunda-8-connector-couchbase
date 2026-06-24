# Contributing to Couchbase DB Connector for Camunda 8

Thank you for your interest in contributing! This project follows the [Camunda Community Hub](https://github.com/camunda-community-hub/community) guidelines.

## Code of Conduct

By participating in this project you agree to abide by the [Camunda Community Code of Conduct](https://camunda.com/events/code-conduct/).

## How to contribute

### Reporting bugs

Open a [GitHub issue](../../issues/new) with:
- A clear description of the problem
- Steps to reproduce
- Expected vs actual behaviour
- Connector version, Camunda version, and Couchbase version

### Suggesting features

Open a [GitHub issue](../../issues/new) describing the use case and the proposed behaviour. Discussion is welcome before any code is written.

### Submitting a pull request

1. Fork the repository and create a branch from `main`.
2. Make your changes. All five operations, error paths, and guardrails have unit tests — please add tests for any new behaviour.
3. Run the full test suite before opening a PR:
   ```bash
   mvn test
   ```
4. Ensure the element template regenerates cleanly:
   ```bash
   mvn package -DskipTests
   ```
5. Open a pull request against `main`. Describe what changed and why.

PRs are reviewed within 30 days. Please be patient and responsive to feedback.

## Development setup

**Requirements:** Java 21, Maven 3.8+

```bash
# Clone and build
git clone https://github.com/camunda-community-hub/camunda-8-connector-couchbase.git
cd camunda-8-connector-couchbase
mvn test
```

No running Couchbase instance is required — all 27 unit tests use mocks.

## Coding guidelines

- Java 21 — use records, sealed types, and other modern language features where appropriate.
- Follow the existing package structure under `io.camunda.connector.couchbase`.
- Error codes must be documented in the README error reference table.
- Keep the element template in sync with any new connector properties.

## License

By contributing you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
