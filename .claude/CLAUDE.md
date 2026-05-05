@../AGENTS.md

## Claude Code workflow

- Before creating a pull request, run `/techdebt` to check for technical debt, code duplication, and unnecessary complexity in the branch changes.
- Always write new unit tests using JUnit 5 and Java. If the target test file is `.groovy`, run the `/migrate-groovy-to-java` skill on it first.
