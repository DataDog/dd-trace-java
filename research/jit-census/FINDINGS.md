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
| Central batch 5 (latest releases of hot target-list libraries) | 10 | 8,560 | `out-batch5/`; re-scan of baseline libraries, netty resolves to 5.0.0.Alpha2 |

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
- **HEX_PAD** (proposed, from batch 5; `format`, not regex): a format string that is literal text
  plus one zero-padded hex conversion, e.g. `"\\u%04X"` (×10 in batch 5: Tomcat
  `StructuredField`, `JSONFilter.escape`; logback `JsonParser.printable`; Jackson), `"\\u%04x"`,
  and in the baseline `"%02x"` ×49, `"\\u%04x"` ×44, `"0x%08x"` ×34. Rewrite:
  `prefix + DDStrings.hexPad(value, width, upper)` (`Integer.toHexString` plus left zero padding).
  Trap: `%X` takes only `Byte`/`Short`/`Integer`/`Long`/`BigInteger`, so a boxed `Character` throws
  `IllegalFormatConversionException`, and a negative `Byte`/`Short` prints its unsigned 8/16-bit value (`ff`), not the sign-extended
  `ffffffff` that `Integer.toHexString` gives. The helper has to
  dispatch on the boxed type, or the rule fires only when the call site boxes an `int`.
  The win is per-call `Formatter` setup (and the regex parse on JDK 8/11), and the sites are on escape
  paths, which are warm at best.
- **CONTAINS_CLASS** (proposed, from batch 5, one site so far): `matches(".*[0-9A-Za-z].*")` (Guava
  `PercentEscaper.<init>`). Rewrite: a char loop looking for a member of the class. Trap: `.` does not
  match line terminators, so `matches` is false for any input that contains `\n`, `\r`, `\u0085`,
  ` ` or ` `. The helper has to return false when the input contains one of them.
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

### Batch 5: latest releases of hot target-list libraries (2026-11-20, Temurin 21.0.4)
jackson-databind 2.23.0, jackson-core 2.23.0, guava 33.8.0-jre, commons-lang3 3.22.0, spring-core
7.1.0, spring-web 7.1.0, spring-webmvc 7.1.0, tomcat-embed-core 11.0.29, netty-codec-http
5.0.0.Alpha2, logback-classic 1.6.8. All ten are already in the baseline, so this batch checks how
stale the "latest cached" version is, and which of the sites run per request.

| Library | Latest: sites (per 1k) | Baseline version: sites (per 1k) | Change |
|---|---:|---:|---|
| jackson-databind | 2.23.0: 183 (225) | 2.22.3: 181 (223) | +2 `format` |
| jackson-core | 2.23.0: 51 (239) | 2.22.3: 49 (230) | +2 `format` |
| guava | 33.8.0: 19 (9.6) | 33.6.0: 18 (9.1) | +1 `format` |
| commons-lang3 | 3.22.0: 48 (108) | 3.21.0: 48 (109) | none |
| spring-core | 7.1.0: 97 (78) | 7.1.0-RC1: 97 (78) | none |
| spring-web | 7.1.0: 24 (17.5) | 7.0.9: 24 (18.1) | none in count; the multipart split moved (below) |
| spring-webmvc | 7.1.0: 26 (48) | 7.0.9: 26 (48) | 2 `replaceAll` LITERAL became `replace(CS,CS)` |
| tomcat-embed-core | 11.0.29: 56 (37.6) | 11.0.26: 53 (36.0) | +1 `replaceAll` LITERAL, +1 `split` CHARCLASS, +1 `replace` |
| netty-codec-http | 5.0.0.Alpha2: 14 (48) | 4.2.19: 14 (37) | different code line; 4.2.21 (scanned off-batch) is identical to 4.2.19 |
| logback-classic | 1.6.8: 12 (65) | 1.6.5: 11 (62) | +1 `format` |

Sites exclude the split fast path. "Per 1k" is all addressable sites per 1k classes.

- **The baseline is not stale for these libraries.** Moving to the latest release changes each count
  by 0 to 3 sites, and density by at most 1.6 per 1k (Tomcat). The changes go both ways. Spring
  did our rewrite by hand: `ResourceHttpRequestHandler.locationToString` went from
  `replaceAll("class path resource", ...)` to `replace` (cold, it builds a log string). Tomcat
  went the other way. `SSLHostConfigPreSharedKey.setDigest` adds `replaceAll("-", "")`, and
  `RandomizedTextRewriteMap.<init>` changed `split(" ")` (fast path) to `split("\\s+")`. Both run at config time.
- **Central's `<release>` for netty-codec-http is 5.0.0.Alpha2**, the abandoned 2015 alpha, not
  4.2.21.Final. This is the same trap as OGNL's beta in batch 2. Its row is a different code base,
  so it shouldn't be compared with the 4.2 baseline. A follow-up for `fetch.sh` is to skip
  pre-release `<release>` values (proposed here, not changed).
- **Almost every addressable site here is cold.** On their request paths, these frameworks either
  tokenize by hand or use a cached static `Pattern` (for example, 14 of spring-web's patterns are
  compiled in `<clinit>`, including `UriComponentsBuilder`'s query pattern `"([^&=]+)(=?)([^&]+)?"`).
  There are 11 implicit compiles in the batch, and 10 of them are on config or startup paths:
  Tomcat `SSLHostConfig.setProtocols` (`split("(?=[-+,])")`), `JAASRealm.parseClassNames`
  (`"[ ]*,[ ]*"`), `OpenSSLCipherConfigurationParser.parseInternal` (`":|,| "`),
  `UriUtil.warToJar` / `JarFactory.getJarEntryURL` (`replaceFirst`, jar scanning), Jackson
  `VersionUtil.mavenVersionFor` (`replaceAll("\\.", "/")`), Guava `PercentEscaper.<init>` (`matches`),
  and Spring `SimpleTypeReference.of` (AOT). The eleventh is the multipart split below. The `Pattern.compile` sites with constant regexes are all in
  `<clinit>` or a constructor (Tomcat `CGIServlet.<init>`). The ~325 `plain-%s` `format` sites are
  nearly all exception messages (Jackson's top constants are "Invalid ... definition for %s" text).
- **Hot or warm sites, by name:**
  - spring-web 7.1 `http.converter.multipart.MultipartParser$HeadersState.parseHeaders()`:
    `split("\\r\\n")` once per multipart part. It's an implicit compile on every part of every
    multipart request, and it's **new on the servlet stack in 7.1**. In 7.0.9 the same code was the
    WebFlux `http.codec.multipart` parser. In 7.1 WebFlux switched to a hand scanner that rejects bare CR/LF,
    and the split-based copy moved to the new converter. This is the best site in the batch.
    It is classified LINES, but `"\\r\\n"` is a fixed two-character literal, so a literal-split helper covers it.
  - Tomcat 11.0.29 `RequestUtil.stripPathParams` (`split(";")`) and netty 4.2
    `HttpContentCompressor.determineWrapper` (`split(",")`, Accept-Encoding) run per request, but they're
    on the JDK fast path already. Nothing to gain.
  - Netty 4.2 `HttpPostRequestEncoder.encodeAttribute` (`replace` ×3: `*`, `+`, `~`) and netty 5
    `QueryStringEncoder.encodeComponent` (`replace("+", "%20")`) run per form attribute on the client. Jackson
    `ISO8601Utils.parse` (`replace(":", "")`) runs per date when the offset is written without a colon.
    Spring-web `HttpRequestValues$Builder.appendQueryParams` (`replace(":", "%3A")`) and
    spring-webmvc `ExtendedServletRequestDataBinder.normalizeHeaderName` (`replace("-", "")`) are also
    per request. All of these are `replace(CS,CS)`, so they only gain on **JDK 8** (X1). Most of these
    library versions need JDK 17+ (Spring 7, Tomcat 11), so in practice only Netty 4.2 and Jackson 2.x on JDK 8 benefit.
  - Escape paths in `format`: Tomcat `JSONFilter.escape(char)` and `juli.LogUtil.escape`, logback
    `JsonParser.printable` (`"\\u%04X"`), and logback `SyslogStartConverter.computeTimeStampString` (cached per second).
    These are per control character or per log event, so they're warm at most. See HEX_PAD above.
- **Proposed shapes:** HEX_PAD (`format`) and CONTAINS_CLASS (`matches(".*[cls].*")`), under
  Changes to the rule catalog.

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
