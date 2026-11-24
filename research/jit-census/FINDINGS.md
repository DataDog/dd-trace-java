# String / Pattern peephole census: running notebook

Part of the "help the JIT, don't replace it" direction for the Datadog JIT proposal: simple
call-for-call substitutions where a **constant** regex/format string flows straight into a
`String`/`Pattern` method. This notebook tracks what the static census finds, corpus by corpus.

- Running totals: `totals-central.md` (Central batches) and `totals-baseline.md` (Gradle cache); both generated, do not edit
- Exact library coordinates per batch: `batches.txt`
- Target-list coverage, with per-library numbers: `top100.md` (from `top100.txt` via `top100.py`)
- Legacy vs current comparison: `legacy.md` (via `legacy.py`)
- **Discarded and superseded ideas, with reasons and revisit conditions: `DISCARDED.md`.** Add to it whenever an idea is dropped.
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
| Central batch 3 (security / web) | 10 | 6,847 | `out-batch3/`; analysis by Bits, outputs regenerated locally |
| Central batch 4 (rest of the target list, incl. legacy track) | 10 | 21,538 | `out-batch4/`; analysis by Bits, outputs regenerated locally |
| Central batch 5 (latest releases of hot target-list libraries) | 10 | 8,560 | `out-batch5/`; re-scan of baseline libraries at their latest stable release |

## Headline findings so far

1. **`replaceAll` with a literal regex is the top target.** About 51% of constant `replaceAll` sites
   in the baseline (1,325 / 2,611), and it shows up in 23 of the 50 Central libraries. Rewrite to
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
8. **The biggest frameworks are nearly clean on their hot paths** (batch 5: Jackson, Spring, Tomcat,
   Netty, Guava, Logback at latest). Their request paths tokenize by hand or use a `Pattern` compiled
   once in `<clinit>`. Of 11 implicit compiles, 10 run at config or startup. The one per-request
   site is **new in Spring 7.1**: `MultipartParser$HeadersState.parseHeaders()` calls
   `split("\\r\\n")` for every multipart part. The value is in the long tail (MyBatis,
   Connector/J, OGNL, Struts, Wicket, Vaadin), in JDK 8 deployments (X1/X2), and probably in
   application code, which no corpus here covers.

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
- **ANCHORED_CHAR_RUN** (proposed, batch 3): `replaceAll("/*$", "")` (Vaadin
  `SharedUtil.trimTrailingSlashes`), `replaceFirst("^_*", "")`. The rewrite strips a leading or
  trailing run of one character. Trap: the same `$`-before-final-line-terminator rule as
  ANCHORED_LITERAL. The `*` form also matches empty, which is harmless here because the replacement is `""`.
- **TRUNCATE_AT_CLASS** (proposed, batch 3): `replaceAll("[^0-9].*", "")`, `replaceAll("[-_+].*", "")`
  (Vaadin version parsing). The rewrite is `substring(0, indexOfFirstCharInClass)`. Trap: `.` doesn't match
  line terminators, so the helper falls back when the tail contains one. Version strings only, so low priority.
- **ANCHORED_ALT_PREFIX** (proposed, batch 4, jOOQ): `matches("^(insert|update|delete|merge|…)\\b.*?")`.
  The rewrite is `regionMatches` against each alternative, plus a word-boundary check on the next character.
  Trap: in `matches`, `.*?` can't cross a line terminator, so multi-line SQL needs the fallback.
- **Trap for every LITERAL `replaceAll` → `replace` rewrite (batch 4):** the *replacement* string has its
  own syntax. `replaceAll("\n", "\\\\n")` inserts a backslash followed by `n`. The rewrite has to
  un-escape `\\` and `\$` in the replacement and must not fire when it contains a `$n` group reference.
- **Trap (batch 4):** `split("|")` (RESTEasy) is an empty alternation, so it splits between every character.
  It is not a literal pipe. The classifier must never treat a bare `|` as a literal.
- Noted, not proposed: `">\\s+<"` (Struts `Compress`), `"\\s{2,}"` (Vaadin). One site each.
- **Subsumption test for candidate rewrites (Doug, 2026-11-20):** if the safety proof a rewrite
  needs implies the proof the JIT already does, drop the rewrite. It only helps where C2 doesn't run
  (interpreter and C1 code, methods over 8,000 bytes of bytecode, failed inlining), and those are cold or rare. A rewrite earns its keep only when it
  uses knowledge the JIT lacks: caching across calls, library semantics (what a regex or format string means, CHM forbidding null values),
  or the judgment that some value is unobservable. Dropped on this basis: swapping a local `StringBuffer`/`Vector`
  for `StringBuilder`/`ArrayList`. The non-escape proof it needs is what C2's escape analysis plus lock elision already does.
  Borderline: `substring(…).equals` → `regionMatches`; measure first.
- **Rule for any type substitution:** never when the object is stored to a field, static or array,
  passed as an argument, returned, thrown, captured by a lambda or inner class, observed via
  `instanceof`/`checkcast`/`getClass()`/`==`, or merged with another value at a join. Fields are visible to
  reflection, serialization and heap-walking frameworks. It also needs targeted StackMapTable edits.
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
commons-beanutils, commons-configuration2, ognl (first run used 3.5.0-BETA10, Central's `<release>`; re-run with 3.4.17),
mvel2, commons-jexl3, handlebars, mustache.java compiler, univocity-parsers, jsqlparser,
javaparser-core.
- Quiet batch: these libraries tokenize by hand or cache their patterns. univocity and beanutils have 0 sites.
- **OGNL: 15 `replaceAll` with a literal** (16 in the 3.5.0 beta), the same pattern again.
- javaparser: 7 `split` with LINES (`\\r?\\n`, `\\R`).

### Batch 3: security / web (2026-11-20, analysis by Bits)
shiro-core 3.0.2, keycloak-core 26.8.2, owasp-java-html-sanitizer 20261110.1, struts2-core 7.5.0,
wicket-core 11.0.0, flow-server, javalin 7.2.4, quarkus-vertx-http 4.0.0, pac4j-core 6.6.1,
jakarta.faces 4.1.21. Bits ran it on Temurin 17.0.20.1 but couldn't push (its sandbox can't push to
any branch), so the outputs were regenerated locally with the stable-version `fetch.sh`. Vaadin is
now 25.3.7: 401 sites, versus 412 in Bits' 25.4.0-beta1 run. Bits' hot and cold calls below
refer to the beta, but they're the same code paths.
- The security libraries are nearly clean. Shiro has 0 sites and the OWASP sanitizer 1 (both tokenize
  by hand). Keycloak has 26 (24 of them `format`), pac4j 12 and Javalin 2.
- **Wicket: all 11 in-method `Pattern.compile` sites are in `UrlValidator`** (`isValid`,
  `isValidAuthority`, `isValidPath`, …), so the regexes are recompiled on every validation. That's hot on
  form submits, and these are class files ≥55, so a condy constant would fix it. `AutoLabelMarker` formats
  `"Wicket.DOM.toggleClass('%s', '%s', %s);"` on every AJAX update.
- **Struts: `replaceAll` LITERAL ×8, some of it per request.** `UIBean.ensureAttributeSafelyNotEscaped`
  (`"\""` → `"&#34;"`) runs on every tag render, and `ServletRedirectResult.isPathUrl` (`" "` → `"%20"`) on
  every redirect. `XWorkConverter.removeAllIndexesInPropertyName` (OTHER) runs per request parameter.
  `DebuggingInterceptor` is dev-only.
- **Vaadin flow-server: mostly `format`** (error messages, PWA `device-width` ×26 at startup). Per
  request: `StaticFileServer` `replaceFirst("^/", "")` ×4 (ANCHORED_LITERAL), and
  `HandlerHelper.isUploadRequest`, an OTHER-shaped `matches` that compiles on every request.
  `replaceAll("\\\\", "/")` ×7 is dev-mode or bundling, so cold.
- pac4j `replaceAll("&", "&amp;")` is on the unauthenticated AJAX path. jakarta.faces
  `RestoreViewPhase.originatesInWebapp` calls `matches("^[a-z]+://.*")` per postback.
- **Class files: 100% of the regex sites are ≥55**, versus 22% in the baseline. Modern web stacks are condy-eligible.

### Batch 4: rest of the target list, including the legacy track (2026-11-20, analysis by Bits)
ehcache 3.12.0, net.sf.ehcache 2.10.9.2 (legacy), retrofit 3.0.0, feign-core 13.18, resteasy-core
7.0.6.Final, axis 1.4 (legacy; its only version, and there's no `<release>`), hibernate-core
7.4.17.Final, jooq 3.21.10, groovy 6.0.0, velocity 1.7 (legacy). Bits ran it on Temurin 17.0.20.1
and got pre-releases for hibernate (8.0.0.CR1), RESTEasy (8.0.0.Alpha1) and Feign (14.0-alpha1). It
couldn't push. The outputs were regenerated locally at the stable versions above. Feign changes the
most: 13.18 has **33 sites** versus 5 in the 14.0 alpha (9 `Pattern.compile`, 3 `replaceAll` LITERAL). Its hot/cold
split hasn't been traced. Hibernate is 563 sites at 7.4.17, versus 617 in Bits' 8.0 CR1 run.
**The target list is complete: 124 of 124** (`top100.md`).
- **jOOQ `DefaultExecuteContext.type`: 5 `matches("^(insert|update|…)\\b.*?")`-style calls on every
  query, which is 5 regex compiles per query.** It's the best find of the batch, and it motivates ANCHORED_ALT_PREFIX.
- RESTEasy: `CookieParser` `split("[;,]")` per request, and `ResteasyUriBuilderImpl` compiles 2 regexes per call.
- Hibernate is mostly `format` (messages, and SQL templates rendered once per query plan, so cold). The one
  per-value site is `OsonDocumentReader.getValue` `replaceAll("-", "")` (Oracle OSON only).
- Groovy: shaded ANTLR `toString` escapes and `isLoggingMethod` run at compile time, so cold. ehcache 3 is config/JMX.
- **The legacy track contradicts the legacy comparison on class-file versions:** ehcache 2.10.9.2 is
  all major 52 (rebuilt for Java 8), velocity 1.7 is major 48 with 1 cached `<clinit>` site, and
  axis 1.4 is major 47 with **0 sites** (no regex, no `format`). Only 1 of 33 legacy-track sites is
  in a class file <51, versus 49% in `legacy.md`. Legacy libraries that are still maintained get rebuilt,
  so <51 means *unmaintained* old versions.
- **Shading carries old class files into modern jars:** Groovy 6's shaded picocli and ASM are major 49,
  and they account for almost all of this batch's <51 sites.

### Batch 5: latest releases of hot target-list libraries (2026-11-20, Temurin 21.0.4)
jackson-databind 2.23.0, jackson-core 2.23.0, guava 33.8.0-jre, commons-lang3 3.22.0, spring-core
7.1.0, spring-web 7.1.0, spring-webmvc 7.1.0, tomcat-embed-core 11.0.29, netty-codec-http
4.2.21.Final, logback-classic 1.6.8. All ten are already in the baseline, so this batch checks how
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
| netty-codec-http | 4.2.21.Final: 14 (37) | 4.2.19: 14 (37) | none |
| logback-classic | 1.6.8: 12 (65) | 1.6.5: 11 (62) | +1 `format` |

Sites exclude the split fast path. "Per 1k" is all addressable sites per 1k classes.

- **The baseline is not stale for these libraries.** Moving to the latest release changes each count
  by 0 to 3 sites, and density by at most 1.6 per 1k (Tomcat). The changes go both ways. Spring
  did our rewrite by hand: `ResourceHttpRequestHandler.locationToString` went from
  `replaceAll("class path resource", ...)` to `replace` (cold, it builds a log string). Tomcat
  went the other way. `SSLHostConfigPreSharedKey.setDigest` adds `replaceAll("-", "")`, and
  `RandomizedTextRewriteMap.<init>` changed `split(" ")` (fast path) to `split("\\s+")`. Both run at config time.
- **Pre-release trap (fixed):** Central's `<release>` for netty-codec-http is 5.0.0.Alpha2, the
  abandoned 2015 alpha. The cloud worker caught it, the same trap as OGNL's beta in batch 2.
  `fetch.sh` now picks the highest *stable* version: it skips alpha/beta/RC/milestone/snapshot
  versions and prefers versions that start with a digit (guava's 2011-era `r09` was the next trap).
  Batches 2 and 5 were re-run with it: netty is now 4.2.21.Final and ognl 3.4.17. Batch 3's Vaadin
  is still 25.4.0-beta1; a re-run would pick 25.3.7.
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
  - Netty 4.2 `HttpPostRequestEncoder.encodeAttribute` (`replace` ×3: `*`, `+`, `~`) runs per form
    attribute on the client (the original run's netty 5 alpha also had `QueryStringEncoder.encodeComponent`). Jackson
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

## Roadmap: crawl, walk, run (Doug, 2026-11-23)

The tracer-side build-time work is the set of building blocks for the runtime replacements: prove each piece on our own code
(reviewable output, our tests, failures show up in CI) before touching customer code.

| Stage | Where it runs | What it proves for the next stage |
|---|---|---|
| **Crawl:** build time, our code | Gradle step: `setTag` rewrites, contributor masks, intercept classification | the ASM rewrite engine (call-for-call, frames untouched), classifying at transform time, registry as source of truth, golden files, differential tests, JFR inlining checks, `@PerfContract` gates |
| **Walk:** load time, customer code, report only | constant-pool pre-scan plus rule matching in real apps, sending telemetry and recommendations | the pre-scan's cost and soundness on real classpaths, rule hit rates, the hotness and cost model, the census confirmed in production |
| **Walk+:** load time, free substitutions from a hot list | profiler hot list (remote config, keyed by deploy version), rewritten at load | the rewrite engine on customer bytes, with exposure limited to known-hot sites |
| **Run:** runtime | self-switching call sites, rewrites that keep something alive, retransform plus directives with bailout checks | needs everything above: proven rules, a cost model, rollback, capability checks |

Built once at the crawl stage and reused: the rewrite engine and constant-pool scanner; the classifiers (tag keys, regex shapes: decide once, at the earliest point);
the verification harness (differential tests, fuzzing against the reference implementation, JFR inlining and compilation checks, the JMH Δcost suite); and the governance (golden files,
declared-then-verified contracts, `DISCARDED.md`). It also answers the proposal's "is this even possible?" on our own code first,
where the POC found the bigger lever: the tracer's overhead.

## Lessons from the crawl stage (experimenting on ourselves first)

The setTag experiments are a testbed for bytecode optimization in general. What carries over to customer-code rewrites:
1. **Inlining decisions depend on compile order** ("already compiled into a big method" only looks at the callee's existing C2 code), so a
   rewrite whose payoff depends on a call being inlined varies run to run, unless the target's own C2 code stays under `InlineSmallCode`.
2. **Rule for every substituted helper** (regex helpers, `computeIfAbsent` get-first, …): a small fast path, heavy paths out of line.
   Otherwise the helper becomes a new big callee and spreads the same refusals to every site that calls it.
3. **Measure compiled size before throughput:** the standalone C2 code size (JFR, `research/settag/inlining-summary.py` on the
   prototype branch) is deterministic and cheap, and predicts whether a rewrite can work before any benchmark hours are spent.
4. **Find out where the size goes before choosing the rewrite:** we targeted interception (~5%); the store dominated.
5. **Peepholes across statements** (fusing adjacent calls, with knowledge of what commutes) are a different class from call-for-call
   substitution. If fusion pays off, the machinery (detecting runs, delaying stores safely, emitting helpers) is reusable beyond tags.
6. **The approach in one line (Doug):** C2 could do much of this itself (inline then fold, coarsen adjacent locks, keep cold paths out),
   but only when compile order, profiles and the JDK line up. We do the same things at build time so the outcome is **deterministic**:
   help the JIT, don't replace it.
   It's easy because we have **prior knowledge of each piece**: these are tracer intrinsics, like C2's `OptimizeStringConcat`
   or its method intrinsics. Each has a recognizable shape, knowledge of what it means (the tag registry and what interceptors and setters read; for customer code, the
   specs of `String`/`Pattern`/library methods), and a fallback when the shape doesn't fit. The tracer-side fusion and the customer-side regex rules are the
   same kind of thing, and like intrinsics each needs differential testing against the reference behavior.

## Tracer-side track: setTag shaping (2026-11-23)

The problem: `setTag`/`setTagsFrom` are built to fold with constant keys, but they still hit "already
compiled into a big method". `InlineSmallCode` checks the callee's *existing* compiled size. So one call site with a
dynamic key (a `setTagsFrom` loop, a megamorphic call) gets the full `setTag` compiled on its own, and that
blocks inlining at every constant-key call site.

In priority order:
1. **FrozenTags is the general solution** (Doug): freeze a source's tags once and apply them to many spans, so most
   repeated per-span `setTag` work never happens.
2. **Classify keys as intercepted ahead of time, the shared foundation.** Every key is never / always /
   config-dependent, from the tag registry as the single source of truth (golden test). It's used by FrozenTags at freeze time
   (split entries into plain and intercepted) and by item 3. The config-dependent class can change via remote
   config, so check it against a versioned or volatile bitset at runtime; never bake it in. **First step: audit the interceptors
   to confirm they decide by key alone.** Any that look at the value or span state put their keys in "always".
3. **Call-site rewrite at build time (our code only):** `setTag(CONST, v)` → `setTagNoIntercept` /
   `setTagIntercepted` / guarded. Each target stays small even when compiled on its own, so constant call sites no longer
   depend on the generic `setTag`'s compiled size. Optional typed-setter dimension (no boxing). Advice is woven from the
   already-rewritten bytes, so the specialized calls land inside customer methods. It complements the `INTERCEPTED` id bit
   (#12817), which still pays off at dynamic call sites.
4. **Contributor/extractor masks, a peer of FrozenTags (Doug, 2026-11-23: contributors and extractors are the
   *preferred* API, so the complexity is worth it).** Make the analysis trivial by designing the API for it:
   - the sink accepts only `TagId` constants (no strings), so every key is a `getstatic`/`ldc` and the may-set mask is just
     the collected operands;
   - the sink must not escape (only sink calls; no storing, passing or capturing). This is a simple build check within one method;
   - declared, then verified: each contributor declares its key set, the build checks that the inferred keys fit within it,
     and a golden file indexes them for review (a `@PerfContract`-style contract);
   - an explicit `@DynamicTags`-style opt-out for genuinely dynamic keys (header tags, baggage, user or OTel attributes).
     Defeating the analysis without opting out fails the build, so the fast path is the default.
   - What the mask drives: (a) layout, as the union of the masks registered per instrumentation or span type, computed once at registration
     (an exact presize, or a rank-indexed packed layout, with an overflow structure for keys outside it); (b) skipping interceptors per contributor,
     with one AND against the current intercepted bitset per call; (c) a frozen/varying split in the extractor API, with
     FrozenTags covering the frozen part; (d) possibly pre-encoded keys for serialization.
   - Presizing tolerates an approximate mask (the store must still grow). Skipping interceptors needs the verified, sound mask.
     Validate with a sink assertion mode across the instrumentation tests.
   - Migration: direct `setTag` call sites keep the item-3 rewrite; new instrumentation uses contributors.
- **Prototype results so far (2026-11-23, branch `dougqh/settag-intercept-specialize`):** "already compiled into a
  big method" on `setTag` call sites depends on **compile order** (the check only sees the callee's existing C2 code), so it varies
  run to run and JDK to JDK. Skipping interception saves only about 2% (dense) to 8% (hash) of the standalone setter's C2 size.
  **The store dominates:** `DenseIdStore.storeLocal` alone is about 3.2 KB of C2 code (limit 2.5 KB); `HashIdStore` is bigger
  (collision chains, entry construction). The deterministic goal is every setter's own C2 code under `InlineSmallCode`.
  Candidate: a rank-indexed dense layout (`bitCount(occupancy & (bit-1))`, a serial check, a lazily created eviction area
  for slot collisions; unslotted tags sent straight to the eviction area). Slots come from graph coloring, at most 63 global, so plain
  `values[slot]` would be too big. **On hold (Doug): still working out how FrozenTags, contributor masks and the store fit together; whether exact positioning is the right move is undecided, and no more churn to the dense store for now.**
- **Benchmark results (Bits, Temurin 17, TraceShapeBenchmark, 2026-11-23):** the no-intercept specialization is
  within noise in both stores (hash +4.0% / +1.9%, dense +3.0% / +0.6% at 5 / 20 children, CIs overlapping, B/op unchanged), so drop it
  as a lever on its own. **Dense vs hash (`newApi`): +9.1% / +9.5% throughput (the 20-child CIs are disjoint), B/op −16% / −22%.** The dense store
  goes on by default in #13514, so **dense-on is the baseline for future experiments.** The fused-run prototype (`setTagsNoIntercept`, one lock
  per run) overlaps with #13030's lock coarsening in `setTagsFrom`. If fusion pays off, its target can be `setTagsFrom`'s path rather than
  a separate bulk API, so plain `setTag` runs and contributors share one path.
- **Static tag counts as sizing seeds (follow-up after #13513 / #13514 land; no dense store churn now):** a fused run knows its
  size, a prototype knows its tags, a contributor mask gives `popcount`. Their union per span is an upper bound. **The goal is zero expansions (no grow allocation or copying), not a tight fit; oversizing is the accepted cost (Doug).** So learned hints should track the high end (max or a high percentile plus headroom), not the mean, and the metric is expansions per span plus their B/op.
  Seed #13513's `SizingHint` with it via the `SpanPrototype` (e.g. `initExpectedTags(n)`, generated or declared), and let learning refine it.
  It fixes what learning can't: first spans, rare operations, and hints evicted from the size-limited table. Steady-state JMH won't show it; measure
  first-N-span cost or a scenario with many operations.
- **Fused runs carry a precomputed tag set (Doug, 2026-11-23; store side, under the store hold):** the run's slot mask and serials are known
  at build time. `occupancy & runMask == 0` means all appends, with no `indexOf` and no per-tag `mayHaveLocal`; only an overlapping run takes the per-tag path.
  The build checks that the run's keys have distinct slots; there's one capacity check for `used + n` (or a presize via the hint) and one mask OR; interception is
  `runMask & interceptedMask` (the bits fixed at build time fold away; split-by-tags is one runtime AND). The result is a loop-free bulk store path with a single growth check.
  It's the same mask contributor analysis produces, so fused runs and contributors share one bulk path. It needs a bulk append entry point on the store
  (confirmed by Doug). Minimal sketch: `int reserveAbsent(long runMask, int level, int n)` (one presence test, one capacity check, OR the mask in, return
  the start index or −1 on overlap so the caller falls back) plus `void appendAt(int index, char serial, Object value)`, an unchecked write made n times under the tag lock; no
  varargs or `Object[]`, and the run's mask and serials fold as constants; the current fused benchmark measures only the setter side (one lock, one call per run).
- **Fusion design rules (2026-11-23):**
  - *Avoid `OptimizeStringConcat`'s fragility* (it matches one exact shape; storing the builder in a local breaks it). Normalize before matching:
    follow the span through locals (`astore`/`aload`, `dup`), skip intervening code that's provably pure and can't throw, and break a run only on a real barrier
    (an unknown call, a branch, the span escaping).
  - *Make misses visible:* a golden-file report of every eligible constant-id `setTag` that wasn't fused, with the reason. Track coverage.
  - *JEP 280's lesson:* where the source can state the intent (contributors / `setTagsFrom`), nothing needs recognizing. Use fusion for code as written,
    the explicit API for new code.
  - *Acceptance bar (Doug): fusion must be better or neutral everywhere.* "Neutral" has to hold even when C2 would have inlined the per-tag setters
    and coarsened their locks itself (lucky compile order). So compare fused vs unfused in both inlining states, set a minimum run length if 2-tag runs
    don't pay, and account for code-cache and metaspace cost (the 4-tag bulk method compiles to about 21 KB with the dense store). Correctness is covered by the per-tag fallback.
- **Fused-run benchmark, dense store on (Mario, Temurin 17, 2026-11-23):** `newApiFused` +4.7% / +0.1%, `newApiFusedConservative` +2.8% / +2.4%
  at 5 / 20 children; all CIs overlap (the 5-child control's forks spread 10%); B/op unchanged. It **meets the better-or-neutral bar but shows no clear gain**: setter-side fusion
  (one lock and one call per run) is worth little on its own, since uncontended monitors are cheap and the ~21 KB out-of-line bulk method loses the per-tag
  folding. **Conclusion (Doug): the sizeable gain needs fast insertion and lock coarsening together**: one presence test per run (`occupancy & runMask`), plain
  appends with no `indexOf`, one capacity check, plus presizing. That needs the bulk append entry point in `DenseIdStore` (store hold: prototype branch only, or wait for the
  FrozenTags design).
- **Fused-run benchmark, hash store (dense off; Mario, Temurin 17, 2026-11-23):** `newApiFused` +5.8% / −2.4%, `newApiFusedConservative` +3.8% / +0.4%
  at 5 / 20 children; all intervals overlap, B/op unchanged (within ±13 B fork variation). Fused/20 has wide within-fork ranges, so its −2.4% is
  within error but is the one point to re-check against the better-or-neutral bar. Short JFRs: the 4-tag bulk wrappers stay out of line in both stores; 3-tag ones inline.
  Same picture as dense-on: setter-side fusion alone is neutral. Fast insertion (`119fa009b2`, dense on only, 5 forks) is queued next on Mario.
- **Fast insertion + one lock per run (`119fa009b2`, dense on, 5 forks; Mario, Temurin 17, 2026-11-23):** vs that revision's own `newApi` control,
  `newApiFused` +3.5% / **+7.9%**, `newApiFusedConservative` **+5.4%** / **+6.0%** at 5 / 20 children. The three bold cells are beyond the JMH intervals;
  Fused/5 overlaps (14% fork spread with one low fork). Fused/20 went from +0.1% (lock only) to +7.9% (also +6.5% over its own previous score), so
  **fast insertion is where the gain is**, and it grows with span count. Nothing is slower than the control, so it **meets the better-or-neutral bar**.
  B/op is unchanged (C/5's −114 B comes from one fork, not established). Caveats: the previous and new runs ran one after the other, not interleaved
  (same machine); only 4 of the 7 jdbc tags take the fast path (`component` is pre-seeded), so a fusion pass that sees whole spans could do better.
  Next steps when the store hold lifts: presizing from the run's known tag count (target zero expansions per span), and checking a 2-tag minimum run length.
- **Follow-up idea (Doug, 2026-11-23): collection presizing, the collections version of fusion's presizing.** Skip copy-and-double growth by sizing
  at construction. *Finding sites:* allocation profiles attribute the waste to `ArrayList.grow`, `HashMap.resize`, `AbstractStringBuilder.ensureCapacityInternal`,
  `ArrayDeque.grow`; the stack names the site. *Size source:* (1) structural, N adds right after construction; (2) learned per-site hints from the fleet
  (like `SizingHint.forOperation`), tracking the high end. **Must be careful (Doug):**
  - Order-safe: `ArrayList`, `ArrayDeque`, `StringBuilder`, `LinkedHashMap`/`LinkedHashSet`; capacity is not observable except memory.
  - **`HashMap`/`HashSet` iteration order depends on table size.** Presizing reorders iteration and breaks code that (wrongly but commonly) depends on it.
    Either exclude hashed collections or presize only to the table size growth would have reached, so final capacity and order are unchanged.
  - `HashMap(int)` takes a capacity, not an expected size: convert (`n / 0.75 + 1`, as `HashMap.newHashMap` on 19+), or it still resizes once.
  - Oversizing long-lived collections costs live set (caches, registries). Gate hints on allocation-site lifetime: presize only sites whose collections die young.
  - Crawl stage: run the census on the tracer's own allocation profile first.
- **Validation for 3:** differential instrumentation tests (rewrite on/off); a benchmark with JFR `jdk.CompilerInlining`
  confirming "already compiled into a big method" disappears for `setTag` call sites.
- Compiler-control notes: negative (`inline:-`) directives for our cold helpers are low-risk. Forced inlining can abort the
  compilation of a big caller (node limit). `@ForceInline` is honored only for boot-loaded classes and is unsupported. All of these rank below
  build-time shaping.

## Open questions / next steps

- **Hotness and cost model (planned 2026-11-20, on hold for a few days at Doug's request):**
  benefit(site, rule) = frequency(site) × Δcost(rule, shape, JDK, input size) − ongoing cost − risk.
  - *Δcost:* a JMH suite of original vs rewrite (ns/op and B/op) on JDK 8/17/21, about 10 benchmarks. A good fit for the cloud worker.
  - *Frequency:* (1) fleet profiles, blocked on the profiling frame filter (see the fleet-hotness thread);
    (2) a static hotness prior in Census: error path (result flows into a Throwable constructor, or inside a catch block),
    startup/config (`<clinit>`, constructors, set/parse/configure), eager logging (`format` passed straight into
    debug/trace), and a weak request-path signal; (3) **our own profiling of real workloads**.
  - *Workload profiling:* **one short, representative run per workload** (default size, a couple of warmup iterations),
    under JFR (CPU plus allocation), aggregated by the first frame outside the JDK under the target JDK methods
    (`Pattern.<init>`/`compile`, `Formatter.parse`, split/replaceAll internals, `CHM.computeIfAbsent`). Results are
    shares of samples, so a noisy laptop is fine and nothing needs handing off. Start with DaCapo Chopin on JDK 21:
    tomcat, spring, h2, kafka, cassandra, lusearch, xalan, pmd. Downloads: `dacapo-23.11-MR2-chopin.zip` is 6.0 GB
    (the minimal variant is 1.4 GB but may lack workload data), and Renaissance 0.16.0 is a 418 MB jar (second suite).
    TechEmpower needs Docker, so it's local only. The projects' own JMH suites are biased toward already-optimized paths,
    so use them to validate rewrites, not to find hot sites.
  - *Hand-off:* only the later with/without timing runs need quiet machines. For those, use a one-command
    `run-workload.sh <suite> <workload> <jdk>` that prints a small summary, one workload per command, posted before the next.

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

## Viability for customer code (discussion with Doug, 2026-11-23)

- **Reports and recommendations alone don't get acted on.** From Doug's time on Azul ReadyNow: only a handful of customers acted on the compilation-prerequisites report, even though Zing customers clearly cared about performance. Doug's rule: (1) show a significant gain first; a report only unlocks the last bit after that. (2) Automate rather than explain a report to one customer after another.
- **What can be automated: changes within freedoms the JVM/JDK spec already grants.** ReadyNow could be automatic because it changed *when* code compiled, not *what* it did. The same applies to compile timing, compiler hints, caching constant `Pattern`s, presizing order-safe collections, and swapping in a method with identical documented semantics (intrinsic-style). Anything a correct program can observe (HashMap order, identity, exception shapes, implementation details) goes in the remainder report or nowhere.
- **Datadog customers have a different risk profile.** They don't buy us for performance and expect the agent to slow them down, so a speedup, or less slowdown than expected, is a pleasant surprise. Aim the feature at **offsetting tracer overhead**: the test for each rule is whether it recovers a measurable share of the tracer's own cost where it fires.
- **Do no harm stays non-negotiable.** For every rule: semantics-preserving by the spec, better-or-neutral performance, a per-rule remote-config off switch, canary rollout (internal services → opt-in → wider) with APM and profiler regression checks, and reporting which rules fired, so the overhead savings are real and attributable, not an accounting effect.
- **No option to bend correctness (Doug, ReadyNow lesson).** There is no "aggressive" mode, no opt-in unsafe rules, and no flags that relax semantics. Configuration can only turn rules *off*. A rule that is only safe under some assumption about the customer's code doesn't ship, not even behind a flag.
- **If a rule is on by default, the bar is higher (Doug).** `UnsafeForcePreinitializeClasses` was off by default and still broke a customer app once someone turned it on. A rule that is on by default reaches every customer, none of whom chose it, and a bug in it affects every user of that tracer version. So:
  - A rule is turned on by default only after it is proven: semantics guaranteed by the spec, differential tests, and clean runs on internal services and then opt-in customers. Off by default *during rollout* limits who is exposed while it is validated. It is not a way to ship something less correct.
  - Correct output isn't enough. Startup and transform time, code cache, metaspace and verifier behavior also count. If a transform fails (VerifyError, unusual bytecode), the class must load unchanged, with no visible error.
  - **The off switch has to work on classes that are already loaded.** Rewritten bytecode is already in the JVM, so a remote-config switch only helps if (a) the rewritten code checks a cheap guard and takes the original path, or (b) we retransform classes back, which carries its own risk. Decide this in the design; don't add it afterwards.
- **What a customer-code rewrite may change (Doug, 2026-11-23): start with peephole-style changes only.**
  - **No field changes in customer classes, not even additions.** Any new static goes in a helper class we inject.
  - Why this also holds up mechanically:
    - The *computed* `serialVersionUID` hashes non-private fields and methods, and whether a `<clinit>` exists. A rewrite that adds a static with an initializer to a `Serializable` class that had no `<clinit>` changes the SUID, and deserializing from hosts that weren't rewritten fails.
    - Reflection, and frameworks that depend on field layout (JPA, Jackson, Kryo/field-order serializers, `Unsafe` offsets), see added members.
    - **JVMTI retransformation can't change a class's fields or methods**, so changing only method bodies keeps "retransform back" possible for the off switch.
  - Same reasoning for methods: no new methods in customer classes. Helpers go in the injected class.
  - Constraints that remain with helpers:
    - **Never move a caller-sensitive call into a helper** (`Class.forName`, `ServiceLoader.load`, `ResourceBundle.getBundle`, `MethodHandles.lookup`, `Logger.getLogger`, …): the caller class changes, and so does the result.
    - An exception thrown inside a helper has an extra stack frame. Code that inspects stack traces can see it, so rewrite only operations that can't throw, or check and throw in the original frame.
    - The helper must be visible from the customer's class loader and module (the same problems our instrumentation helpers solve). Static caches in helpers must not keep customer class loaders alive (weak keys, bounded).
  - **Precedent: field injection for context stores.** The tracer once had a bug from injecting a field that was serialized, and it caused data corruption (Doug). Today `FieldBackedContextInjector` makes injected fields `private transient` (as `TaintableVisitor` does for IAST). It also adds an interface and methods, which change the computed SUID, so it **pins the class's SUID to the value computed before instrumentation** (`SerialVersionUIDInjector`, `dd.trace.serialversionuid.field.injection`, on by default). `transient` alone isn't enough. Rewrite rules should add no members at all. If a rule ever has to, it must reuse both mechanisms, never a new path.
- **First rule shape: substitute a call without touching the stack (Doug).** Replace one call instruction with an `invokestatic` into the injected helper that takes exactly the same operand-stack values and leaves the same result type. No new locals, no `max_stack` change, no new branches or exception handlers.
  - Why: no StackMapTable recomputation. ASM's `COMPUTE_FRAMES` calls `getCommonSuperClass`, which loads classes, a side effect in the middle of a transform. The local variable table and debugger view stay the same. Retransform-back stays trivial.
  - **Keep the instruction length the same where possible.** `invokevirtual`/`invokestatic` are 3 bytes and `invokeinterface` is 5, so an interface call → static call pads with 2 `nop`s. Then no branch offsets shift, and the method body keeps its layout.
  - **Dispatch:** a static helper skips overrides. Substitute only calls whose receiver is a `final` class or method (`String`, `Pattern`), or have the helper check the exact class and fall back to the virtual call.
  - **A null receiver:** the original throws NPE in the caller's frame. The helper must throw NPE too (it will have one extra frame, the known stack-trace caveat).
- **Shape of the call site: `invokestatic` (static→static, or instance→static with the receiver as the first argument).** `invokespecial` (constructors, super, private) and `invokedynamic` are out. So **collection presizing is not first-stage**: it changes a constructor descriptor and pushes a value, which makes it a later, separately justified stage.
- **Complex specializations go in a generated helper; the call site stays stack-neutral (Doug).** E.g. `s.split("\\s*,\\s*")` → `invokestatic Gen$N.split(String, String)`, with all the complexity (precompiled `Pattern`, or a specialized matcher) inside the generated class. Details:
  - **Leave the constant's `ldc` in place.** The helper still receives the regex and checks it by identity (`regex == EXPECTED`; string constants are interned), falling back to `s.split(regex)` otherwise. The call site stays stack-neutral and the helper stays correct if the constant isn't what we expected.
  - **Rewrite only patterns that compile at transform time.** The original throws `PatternSyntaxException` on every call. A precompiled invalid pattern would instead fail the helper's static initializer (`ExceptionInInitializerError`, then `NoClassDefFoundError`). Hold the pattern lazily, or skip the site.
  - **Match the running JDK's semantics.** `split` changed across versions (since 8, a zero-width match at the start gives no leading empty string). Delegating to the running JDK's `Pattern.split` keeps that behavior. A custom generated matcher needs differential tests against `Pattern` on every JDK we support.
  - The JDK already has a fast path for a single-character literal `split`, so the value is in real regexes.
  - **Share generated helpers by (target method, constant)**, not one per call site, to bound metaspace and class count.
- **Constant-pool pre-check prototype (`cpscan/`, 2026-11-23).** Over 59.7k classes from 182 popular libraries, checking for 5 rules (`split`, `replaceAll`, `matches`, `format`, `Pattern.compile`) by name and descriptor among the Utf8 entries rules out **97% of classes with zero false negatives**. **The structure-unaware scan Doug proposed works but isn't the fastest:** 2.8–4.8 µs/class single pass, against **0.9 µs/class for a minimal constant-pool walk** that skips entries by tag and compares Utf8 entries only on exact length. That's about the cost of `new ClassReader(bytes)` alone (0.7), and ~10× cheaper than a full parse (8.7–9.8). About 20 ms per 20k classes. This is a laptop microbenchmark with bytes in memory; check it on the real transform path. It needs nothing from `dd-instrument-java`.
- **Requiring the owner class as a third needle (Doug's idea) is the big selectivity win:** classes that pass at 50 random JDK-call rules drop from 34% to 5.4%, at 500 rules from 55% to 19%, for ~5–25% more scan cost. It stays sound (a call needs a `CONSTANT_Class` text entry holding exactly the owner's name), and the Methodref-resolving second step then runs only where all three needles are present.
- **Load-time scanning everywhere looks viable without reuse across runs (Doug, 2026-11-23).** The sieve (walk + signature bitmap → name/desc/owner needles → Methodref resolution → body parse) costs about 20 ms per 20k classes. That's cheap enough to drop the cache across runs and its setup step, which was ReadyNow's adoption problem until cloud profiles. Plan: **pay for the ~20 ms by saving it elsewhere in agent startup**, so the net startup change is zero. Site lists from the backend arrive after startup through remote config, so they apply only to classes loaded later (lazy, first-request code); rules that are safe everywhere run at load time from the first run. Next step: measure the walk on the real transform path (cold bytes) and find the offset in a startup profile.
- **The same lowering applies to instrumentation type matching (Doug: lower predicates into their fast checks, check structure only when they pass).** Over 58.7k classes, pre-checking the declared method's name and descriptor among the text entries means only **0.79% of classes need a hierarchy check** for 11 common hierarchy matchers (servlet, filter, JDBC, Netty, OkHttp, Kafka, Callable), against 100% without it. There are zero false negatives, and `Runnable.run` is the weakest case at 2.8%. This doesn't apply to matchers that act on the class itself (field or interface injection) or match by name with any descriptor. See `cpscan/README.md`.
- **Agent startup attribution (Mario, `62d4d1a14e`, springboot-jpa, Temurin 17, 5 vCPUs, 10 interleaved runs per arm + 3 async-profiler runs, 2026-11-23).** Time to first HTTP 200, medians: no agent 7.60 s, full agent 11.65 s, agent with integrations disabled 9.49 s. **The agent adds 4.05 s elapsed / 13.3 s CPU, and instrumentations alone (B−C) add 2.16 s / 7.5 s.**
  - **Transform callbacks take ~1.9–2.2 s CPU per start** (6.2% of samples), about 0.12–0.14 ms CPU per loaded class over ~15.8k classes. **`CombiningMatcher.matches` is 28.6% of transform: ~560–650 ms CPU, ≈ 40 µs per class**, including what it calls.
  - **The sieve measured 0.9 µs/class** (≈ 14 ms for 15.8k classes) and rules out 99% of classes for typical hierarchy matchers. Even a fraction of the ~600 ms of matching would be **an order of magnitude more than the ~20 ms load-time rewriting needs.** Paying for rewriting by saving startup elsewhere looks easy, if the lowered matcher can replace most of `CombiningMatcher`'s work.
  - Caveats: calibrated ms are estimates (samples cover ~86% of process CPU); the profile cutoff is approximate (~270 ms stop roundtrip); inclusive matcher time includes type resolution, part of which remains for the ~1% that pass. Transform is only ~2.2 s of B−C's 7.5 s CPU, and where the rest goes (JIT of instrumented code, helper injection, advice running during startup) is not yet attributed.

## General matching design: a globally optimized shared needle table (Doug, 2026-11-24)

- **Prebuilt at agent build time.** Every instrumentation's patterns are string literals (owner, name and descriptor; `?`/`*` for parameters; flag conditions; `Type+` for subtypes). The build compiles them into **one shared, globally optimized needle table**: needles shared across instrumentations are merged, plus the length-keyed and prefix-keyed signature bitmaps. Each matcher gets a small program for the deep pass. At startup the agent loads these tables and builds no matcher object graphs.
- **One pass with early exit.**
  1. Walk the constant pool once, recording entry offsets in reusable scratch (nearly free: 0.87 vs 0.84 µs), checking bitmaps and needles. If no matcher has all its needles, stop (~99% of classes).
  2. For survivors, continue the same pass into flags, super and interfaces, fields and methods. **The sieve's hits give constant-pool indices, so the deep pass compares u2 indices (`name_index`, `descriptor_index`, `super_class`) instead of strings.** Flag masks apply to the same records.
  3. Hierarchy facts for the class itself come from the recorded offsets. Lookups of other classes happen only for matchers that need them, on classes that got this far.
  4. **Hand the offsets and matched members to the transform**, so a matched class is parsed once in total (Stuart's parse-once idea).
- Shape: a lexer generator. Patterns are compiled ahead of time into tables, with one linear pass and early rejection.
- Open: where today's ~600 ms of `CombiningMatcher` CPU actually goes (true positives vs near-misses, hierarchy resolution vs evaluation). Mario's collapsed stacks in the startup-attribution thread can answer it.
- **Prior art already in the agent (2026-11-24).**
  - **Prefix trees compiled at build time:** `ignored_class_name.trie`, `proxy_ignored_class_name.trie` and the IAST/AppSec exclusion tries. Doug: the shared needle table is "pretty much the same idea as the compiled prefix tree", applied to constant-pool contents instead of class names.
  - **IAST call-site instrumentation already rewrites call sites in customer code, decided by a constant-pool check** (`agent-tooling/.../bytebuddy/csi/`, `Advices.ConstantPoolInstrospector`). It walks `Methodref`/`InterfaceMethodref` entries and skips classes with no matching advice. But it allocates per class (an offset table and a char buffer), decodes the referenced strings, runs after ByteBuddy has built a builder, and can read the class file from the loader a second time. **The allocation-free needle walk would speed up IAST startup as a side effect** (Doug: "a nice bonus"). That makes IAST a second measurement target, and the IAST team's production experience with call-site rewriting (loader visibility, injection, correctness problems) is worth asking about before building the rewriter.
- **Matching triage (Mario, agent's built-in triage report, 2026-11-24).** Only 3,683 of ~15.8k loaded classes reach `CombiningMatcher`, because the existing filters already reject the rest. Hierarchy checks: 114 hits vs 559,695 misses. Their cost is building per-class memos (483 ms, ~120 µs each, mostly outline building at 305 ms) plus class-file lookups (124 ms). **Transforming costs as much as matching: 909 ms for 170 advice steps (~5 ms each)**, and full-type builds are only 68 ms of that.
- **Decision (Doug): match hierarchy instrumentations by "declares an advised method".** ByteBuddy `Advice` applies only to a class's declared methods (`ForDeclaredMethods`), so a matched subtype that declares no advised method gains nothing but side effects: rewriting, helper injection, risk. Apply this only to instrumentations with nothing but method advice (`HasMethodAdvice`, about 905 files) and no type advice (`HasTypeAdvice`, about 29 files) or structural change. The one remaining change is when helpers get injected.
- **Fast transformation (Doug: "extra important").** A stack-neutral call substitution can be done as a **byte patch**: append the helper's `Methodref` and related entries to the constant pool, update its count, copy the rest of the file, and patch the call instruction (3→3 bytes, or 5→3+2 `nop`s). No ASM parse, no frame computation, no class loading: microseconds per class.
- **The same applies to IAST call sites.** IAST has 216 Before, 165 After and 5 Around advices, and already uses `COMPUTE_MAXS` only. Turning Before/After advice into a stack-neutral call to a generated wrapper (before-hook, an ordinary virtual call, after-hook) makes most IAST call sites byte patches too. Exclusions: caller-sensitive targets, `invokespecial` (constructors, super, private), targets the wrapper can't access. Open question: the extra stack frame, if IAST inspects stack traces to locate the vulnerable call site.
