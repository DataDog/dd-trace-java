@../AGENTS.md

## Claude Code workflow

- Before creating a pull request, run `/techdebt` to check for technical debt, code duplication, and unnecessary complexity in the branch changes.
- When adding tests: always write JUnit 5 Java. If the target test file is `.groovy`, run `/migrate-groovy-to-java` on it first.
