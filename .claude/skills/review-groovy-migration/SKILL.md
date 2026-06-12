---
name: review-groovy-migration
description: >
  Post-migration quality review. Checks Java test files produced by migrate-groovy-to-java
  against the shared quality rules. Use after migration, or on any branch with recently
  migrated .java test files. Produces structured FINDING blocks grouped by severity,
  then offers to auto-fix BLOCKERs and WARNINGs.
---

Review migrated Java test files against the quality rules.

## Step 1 — Load rules

Read `.claude/skills/migrate-groovy-to-java/QUALITY_RULES.md` in full before proceeding.

## Step 2 — Identify target files

If the user specified files or a module path, use those. Otherwise, find files added on the current branch:

```bash
MERGE_BASE=$(git merge-base HEAD origin/master 2>/dev/null || git merge-base HEAD master)
git diff "$MERGE_BASE" --name-only --diff-filter=A | grep 'src/test/java.*\.java$'
```

If no files are found, fall back to modified test files:

```bash
git diff "$MERGE_BASE" --name-only | grep 'src/test/java.*\.java$'
```

## Step 3 — Run grep-based detection

For each rule with a grep `Detection` pattern, run it over the target files. Use the patterns from the rules file. Sample commands:

```bash
# RULE-C01
grep -rn "assertTrue(.*instanceof" <files>

# RULE-C02
grep -rn "assertTrue(.*== null.*instanceof\|assertTrue(.*instanceof.*== null" <files>

# RULE-F01
grep -rn "int.*[Ss]ampling[Pp]riority\|int.*\bpriority\b\|int.*\bmechanism\b" <files>

# RULE-A02
grep -rn '@WithConfig(key = "' <files>

# RULE-B01
grep -rn "new LinkedHashMap<>()" <files>

# RULE-C04
grep -rn "\.getTags()\.get(" <files>

# RULE-D01
grep -rn "mock(.*Map.*\.class)" <files>

# RULE-G01
grep -rn "/\* [a-z]" <files>

# RULE-G02
grep -rn "() -> [a-zA-Z]*\.[a-zA-Z]*())" <files>

# RULE-J01
grep -rn "\.equals(.*[Hh]eader\|[Hh]eader.*\.equals(" <files>

# RULE-J02
grep -rn "CarrierVisitor\|forEachKeyValue" <files>
```

Read the full content of any file that has at least one hit, to understand the context.

## Step 4 — Structural detection (LLM-based)

For rules without grep patterns (RULE-B02, RULE-B03, RULE-C03, RULE-E01, RULE-E02, RULE-E03, RULE-G03, RULE-G04, RULE-H01, RULE-H02, RULE-I01, RULE-I02), read all target files and identify violations based on the Before/After examples in the rules.

## Step 5 — Emit structured findings

For each issue found, emit one finding block:

```
FINDING
  file:     <absolute path>
  line:     <line number, or range start-end>
  rule:     <RULE-XNN>
  severity: <BLOCKER|WARNING|STYLE>
  excerpt:  <the offending code, single line>
  fix:      <the corrected code, single line or brief description>
```

Group all findings by severity: BLOCKERs first, then WARNINGs, then STYLEs.

At the end, print a one-line summary:

```
Summary: N blocker(s), M warning(s), K style issue(s) across F file(s).
```

If no issues are found:

```
No findings. All rules pass.
```

## Step 6 — Offer to fix

After the summary, ask: "Fix all BLOCKERs and WARNINGs automatically? (yes / no / select rules)"

If the user agrees:
1. Apply each fix. For each file changed, run `./gradlew spotlessApply` on its module after editing.
2. Re-run the grep checks to confirm the findings are resolved.
3. Report which findings were fixed and which (if any) require manual attention.
