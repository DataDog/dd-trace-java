# String / Pattern peephole census: running notebook

Part of the "help the JIT, don't replace it" direction for the Datadog JIT proposal: simple
call-for-call substitutions where a **constant** regex/format string flows straight into a
`String`/`Pattern` method. This notebook tracks what the static census finds, corpus by corpus.

- Running totals: `totals-central.md` (Central batches) and `totals-baseline.md` (Gradle cache); both generated, do not edit
- Exact library coordinates per batch: `batches.txt`
- Target-list coverage, with per-library numbers: `top100.md` (from `top100.txt` via `top100.py`)
- Legacy vs current comparison: `legacy.md` (via `legacy.py`)
- Branch `dougqh/jit-census` (research only, not for merge); Slack: #Datadog JIT analysis
- Per-run outputs: `out-*/report.txt`, `out-*/by-artifact.txt`, `out-*/sites.csv`

## How to run

From the repo root (needs bash, curl, python3 and a JDK 17+ `java`):

```sh
research/jit-census/run-batch.sh batchN group:artifact [group:artifact ...]
```

This fetches the latest `<release>` of each library from Maven Central into `/tmp/ddjit/central/batchN`
(override with `CENSUS_DL`), runs `Census.java`, writes `out-batchN/`, records exact coordinates in
`batches.txt`, and regenerates `totals-central.md`.

Other corpora: `java -cp asm.jar Census.java <gradle files-2.1 dir> out` (the baseline), or
`Census.java jdk:<java.home> out-jdkNN` for a JDK's class libraries (`-Dmodules=java.base` restricts it).

## Protocol for each batch (humans and Bits)

1. Run `run-batch.sh` with the next batch number and 10 libraries **not already in `batches.txt`**.
2. Read `out-batchN/by-artifact.txt`, plus `out-batchN/report.txt` for the top constants.
3. Append a **Batch log** entry: the libraries, then 3–6 bullets on what stood out. That means
   libraries with many sites, shapes that are likely hot (per-request paths) versus cold
   (startup, error messages), and constants the classifier puts in OTHER that look like a simple shape.
4. If a recurring OTHER constant has a simple specialized form, propose a new shape under
   **Changes to the rule catalog** (name, example constants, rewrite, trap). Don't change
   `classify()` without also re-running the earlier batches, so the totals stay comparable.
5. Update **Headline findings** only when a batch changes one.
6. Commit `research/jit-census/` with a message like `jit-census: batch N (<theme>)`. Workers (Bits,
   cloud agents) push to a **new** branch, `dd/jit-census/batchN` (Bits) or `claude/jit-census/batchN`
   (cloud), cut from the latest `dougqh/jit-census`. Worker sandboxes can't push to existing branches.
   The notebook owner merges those into `dougqh/jit-census` and reconciles FINDINGS.md.

## Method and caveats

- **Pre-scan of the constant pool:** one pass, no method parsing. A class passes if it references a
  target method; the S0 filter also needs a string constant with an S0 shape. The body scan runs on
  *every* class to check that the pre-scan never misses; so far it has 0 misses.
- **Body scan (ASM):** recognizes the constant via simple stack shapes (an `ldc` right before the
  call, or one simple push in between). It **undercounts**: constants that arrive through locals or
  fields aren't seen.
- **Classifier:** `classify()` in Census.java. Shapes: SPLIT_FASTPATH, S0_FOLD, LITERAL,
  CASEI_LITERAL, CHARCLASS, CHAR_RUN, WS_SEP, ANCHORED_LITERAL, LINES, DOTSTAR, ALT_LITERALS,
  OTHER, INVALID.
- **The counts are static.** They say nothing about hotness. `String.format` is dominated by
  error-message construction (cold). Migration tools (Liquibase, Flyway) mostly run once at startup.
- The Gradle-cache corpus is biased toward what dd-trace-java instruments, and shaded jars (e.g.
  glassfish-embedded-all, hadoop-client-runtime) count some code more than once.

## Corpora

| Corpus | Libraries | Classes | Notes |
|---|---:|---:|---|
| Gradle cache (dd-trace-java deps, latest of each) | 2,286 | 854,773 | baseline; `out/`, totals in `totals-baseline.md` |
| JDK 8 / 11 / 17 / 21 / 25 class libraries | modules | ~25–31k each | `out-jdkNN/`; not a target, see below |
| Central batch 1 (data / DB / docs) | 10 | 7,332 | `out-batch1/` |
| Central batch 2 (expressions / templates / parsers) | 10 | 3,886 | `out-batch2/` |

## Headline findings so far

1. **`replaceAll` with a literal regex is the top target.** About 51% of constant `replaceAll` sites
   in the baseline (1,325 / 2,611), and it shows up in 13 of the 20 Central libraries. Rewrite to
   `replace(CharSequence, CharSequence)`. Top constants: `"\\."`, `"\n"`, `"\""`, `"'"`, `"\\\\"`.
2. **Per-call compiles hide in the `String` API.** About 4,300 implicit per-call compiles (`split`
   off the fast path, `matches`, `replaceAll`, `replaceFirst`) versus 328 explicit
   `Pattern.compile` calls inside method bodies.
3. **The whitespace family dominates `split` once it misses the JDK fast path:** `"\\s+"`,
   `"\\s*,\\s*"`, `" *, *"`, `",\\s*"`, `" +"`.
4. **S0 (`"\\t"`, `"\\n"`, `"[.]"`) is rare in libraries.** 43 baseline sites; **`"\\t"` = 0
   everywhere so far**, including the JDK. Still worth an upstream JDK patch; application code
   is unmeasured.
5. **Class-file versions:** only about 22% of sites are in class files ≥55 (condy-eligible). About
   60% are 51–54 and 15–20% are older than 51. **Indy is the baseline mechanism**; classes older
   than 51 need side-class holders or get skipped.
6. **The pre-scan is sound and selective:** 2.6% of classes pass (1.66% only for `format`), 84%
   precision. The cost is about 780 ns/class (rough, not JMH); a JMH run is still needed (Bits worker).
7. **The JDK isn't a target**, since its sites are cold and the classes are bootstrap/CDS. It did confirm
   the X2 gate: `Formatter`'s regex-based format parser is present in JDK 8 and 11 and gone from 17.

## Legacy vs current (2026-11-20)

Hypothesis (Doug): legacy libraries are the big winners. Test: for the 383 libraries in the Gradle
cache whose oldest and newest cached versions differ by at least one major version, run the census
on both ends (`legacy.py`, outputs in `out-legacy-{oldest,newest}/`, summary in `legacy.md`). The
oldest cached versions are mostly dd-trace-java's minimum supported versions, so they're a fair
stand-in for "what customers on old stacks run".

- **Per class, legacy code isn't denser.** Implicit per-call compiles: 4.1 vs 4.2 per 1k classes.
  Across 216 libraries with ≥50 classes at both ends, density went down in 57, up in 53 and stayed
  the same in 106. Explicit `Pattern.compile` in methods and constructors is about 2x denser in old
  code, but small in absolute terms. `format` is 2x *denser* in new code (more error-message formatting).
- Some individual frameworks did clean up a lot: spring-messaging 4.0 → 6.2 (48 → 3 per 1k), spring-web 3.1 → 7.0
  (10 → 0.8), spring-expression (9 → 0), okhttp 3 → 4 (11 → 0; the Kotlin rewrite), jetty-client 9.1 → 12 (6.5 → 0).
  Others got worse (HikariCP, metrics-core, gson 1.6 → 2.15).
- **Where legacy does win is the runtime, not the library code.** Legacy stacks run on JDK 8, where
  `replace(CS,CS)` compiles a `Pattern` on every call (about +2.4 per 1k classes, the same at both ends)
  and `String.format` parses its format string with a regex on every call (20.7 per 1k in old code).
  So the JDK-8-only rules (X1, X2) are the legacy story. On JDK 8, the implicit-compile density is
  about 6.5 per 1k classes, versus 4.1 on JDK 9+.
- **Legacy code mostly can't use indy.** In the oldest versions, 49% of sites are in class files
  <51 (no indy), 46% in 51–54 and 5% in ≥55. Newest: 5% / 69% / 26%.
  **Mechanism consequence:** the default must be *classify at transform time + plain `ldc` /
  `invokestatic`* (for example `replaceAll(LIT, LIT)` → `replace(unescaped, unescaped)` on JDK 9+,
  or → `DDStrings.replaceLiteral(...)` on JDK 8). That works for every class-file version and
  needs no indy and no holder class. Indy is reserved for runtime-classified and self-promoting
  sites. Class files older than version 50 have no StackMapTable at all, which makes substitution
  there even simpler.

## Changes to the rule catalog from the census

- **ANCHORED_LITERAL** (new, from Liquibase): `replaceFirst(",$", "")`, `"^/"`, `"'$"`, `"^\\("`.
  The rewrite is a `startsWith`/`endsWith` check plus `substring`. Trap: a trailing `$` also matches
  before a final line terminator, so the helper falls back when the input ends in a line terminator.
- **CHAR_RUN** (new): `" +"`, `"/+"`, `":+"`. Trap: the `*` variant (`"\\.*"`, seen ×4 in
  `replaceAll`) **matches zero-width at every position**, so `replaceAll("\\.*", x)` puts `x` between
  every character. That's probably a bug in the library, but the helper has to reproduce it, or the
  rule excludes `*`.
- **Effect on the baseline of the three new shapes:** constant `replaceAll` sites classified OTHER
  dropped from 712 to 427 (ANCHORED_LITERAL 185, CHAR_RUN 95). `split` OTHER dropped from 177 to 101
  (WS_SEP 122 → 171, CHAR_RUN 26).
- **WS_SEP widened:** literal padded on either side by `\\s*`, `\\s+`, `" *"` or `" +"`
  (`",\\s*"`, `" *, *"`, `"\\s+or\\s+"`).
- **Frame-safety rule:** substitutions must keep the stack the same shape, so no stack map frame
  recomputation and no `getCommonSuperClass`. Every branch lives in the callee (helper or
  `guardWithTest`). Log guards and for-each presizing are the exceptions; they need frame synthesis.

## Batch log

### Batch 1: data / DB / documents (2026-11-20)
commons-csv, velocity-engine-core, mybatis, flyway-core, liquibase-core, poi, pdfbox, tika-core,
opencsv, mysql-connector-j.
- Liquibase dominates (814 sites): 131 `replaceFirst` (mostly anchored literals), 137
  `replace(CS,CS)`, 38 `Pattern.compile` in constructors.
- **MyBatis (17) and Connector/J (17) `replaceAll` with a literal** are few, but on the per-query path,
  so they're likely hot. Connector/J also has 8 × `split("\\s*,\\s*")`.
- POI and Tika mostly cache their patterns properly. PDFBox is mostly hex `format`.

### Batch 2: expressions / templates / parsers (2026-11-20)
commons-beanutils, commons-configuration2, ognl (3.5.0-BETA10: Central's `<release>` is a beta),
mvel2, commons-jexl3, handlebars, mustache.java compiler, univocity-parsers, jsqlparser,
javaparser-core.
- Quiet batch: these libraries tokenize by hand or cache their patterns. univocity and beanutils have 0 sites.
- **OGNL: 16 `replaceAll` with a literal**, the same pattern again.
- javaparser: 7 `split` with LINES (`\\r?\\n`, `\\R`).

## Open questions / next steps

- **Target list:** `top100.txt` (124 curated runtime libraries, including a legacy track). 110 are
  covered by the baseline, batch 3 covers 4 more, and batch 4 takes the remaining 10.
  Caveat: the baseline covers the *latest cached* version, not necessarily the latest release.
- Per-1k densities are inflated for small jars (commons-codec, HikariCP, org.json), so read them alongside absolute counts.

- Join with hotness data (profiler) before ranking. Static counts overstate `format` and migration tools.
- JMH for the pre-scan (Bits worker) once the scanner is pushed to a branch.
- First prototype: `replaceAll(LITERAL, LITERAL)` → `replace`; next, the WS_SEP / CHAR_RUN split helpers.
- Upstream: JDK `split` fast path for `\\t \\n \\r \\f \\a \\e` (check JBS first).
- Next batches (candidates): web frameworks and servlet stack not in the cache, Kotlin/Scala
  ecosystem libraries, security (Shiro, Spring Security, Keycloak), messaging, cloud SDK v1.
