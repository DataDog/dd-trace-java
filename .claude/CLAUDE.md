@../AGENTS.md

## Claude Code workflow

- Before creating a pull request, run `./review.sh` (see [Review Guidelines](../AGENTS.md#review-guidelines)) over the branch changes.
- After running `/migrate-groovy-to-java`, run `/review-groovy-migration` on the migrated files before opening a PR.
- Always write new unit tests using JUnit 5 and Java. If the target test file is `.groovy`, run the `/migrate-groovy-to-java` skill on it first.
