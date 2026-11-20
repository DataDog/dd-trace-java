# Discarded ideas

Ideas we considered and dropped (or superseded), with the reason and what would make us revisit
them. Add to this list whenever an idea is dropped, so nobody re-proposes it without the context. Newest
reasoning wins: if a "revisit if" condition is met, move the idea back into FINDINGS.md.

## Rewrites rejected as invalid (they change observable behavior)

| Idea | Why it's invalid | Revisit if |
|---|---|---|
| `Hashtable` → `HashMap` (even local and non-escaping) | `Hashtable` throws on null keys and values, and iterates in a different order | never |
| `Collectors.toList()` → `Stream.toList()` | `Stream.toList()` is unmodifiable, while `Collectors.toList()` gives a mutable `ArrayList` | never |
| Per-site constants for `SimpleDateFormat`, `MessageFormat`, `DecimalFormat`, `Arrays.asList(...)` | mutable and not thread-safe, so sharing one instance across calls is a bug | never (but see the `ThreadLocal` / prototype-clone variants in FINDINGS) |
| `s.toLowerCase().equals(x)` → `equalsIgnoreCase` | depends on locale (tr/az/lt), and some characters change differently (Kelvin sign, `İ`, final sigma) | never; use the ASCII fast path that falls back instead |
| `trim().isEmpty()` → `isBlank()` | `trim` strips chars ≤ `' '`, while `isBlank` uses Unicode whitespace | never; use a "≤ `' '`" scan helper |
| Regex `\s` / `\d` → `Character.isWhitespace` / `isDigit` | regex classes are ASCII unless `UNICODE_CHARACTER_CLASS` is set | never |
| `matches("prefix.*")` → `startsWith` | `.` doesn't match line terminators without DOTALL | never; use `startsWith` plus a scan for line terminators |
| `(?i)` + literal → `equalsIgnoreCase` | default `CASE_INSENSITIVE` is ASCII-only | never |
| `split("|")` treated as a literal pipe | an empty alternation, which splits between every character | never |
| `replaceAll(LIT, REP)` → `replace(LIT, REP)` with `REP` passed through unchanged | the replacement has its own syntax (`\\`, `$`, `$n`) | never; un-escape it, and skip `$n` |
| Presizing a `HashMap`/`HashSet` beyond the size growth would reach | changes iteration order | never; cap at growth's final table size |
| Boxing constructors → `valueOf`, or `new String(s)` → `s`, as automatic rewrites | changes identity (`==`, `synchronized`) | as an opt-in suggestion only |
| Moving caller-sensitive calls (`Class.forName`, `Method.invoke`, `ServiceLoader.load`, …) into a static helper | the caller changes, so the class loader or access check changes | use indy instead, since its bootstrap gets the caller's `Lookup` |
| Wrapping log calls in a helper (log guards) | Log4j `%L`/`%M` and Logback caller data would report the helper as the caller | never; log guards must be inline bytecode |

## Rewrites dropped as not worth it

| Idea | Why it was dropped | Revisit if |
|---|---|---|
| Swapping a local `StringBuffer`/`Vector` for `StringBuilder`/`ArrayList` (biased locking was removed in JDK 18) | fails the subsumption test: the non-escape proof it needs is what C2's escape analysis plus lock elision already does, so it only helps in the interpreter, C1, huge methods (>8,000 bytes of bytecode) or failed inlining. It also needs StackMapTable edits. (Doug, 2026-11-20) | the census shows hot sites in huge or non-inlined methods |
| `split("\\t")` and the other fast-path misses as a rewrite target | 0 `"\\t"` sites in libraries or the JDK; only about 43 S0 sites in the whole baseline | application-code scans show it. Either way, do the upstream JDK fast-path patch |
| Rewriting JDK classes themselves | their sites are cold (tooling, config, errors), and they're bootstrap/CDS classes with the strictest retransform rules | never; send JDK fixes upstream |
| `toArray(new T[c.size()])` presizing | slower than `new T[0]` (the zero-length form avoids zeroing) | never; the inverse rewrite is a suggestion |
| Learning collection sizes online, inside the process | you never know when a collection's size is final, and growth happens inside JDK code that doesn't know the allocation site | never; use allocation profiles instead (sizing hints keyed by site) |
| Jackson Blackbird / regex-cache POCs (Brian/Stuart) | about 0.02% of fleet CPU; the regex cache's class rewriting added 184 ms at startup | the regex idea is back in a different form: constant-pool pre-scan, call substitution, hot-gated |

## Mechanisms superseded

| Idea | Superseded by | Why |
|---|---|---|
| condy as the baseline mechanism | classify at transform time, plus plain `ldc`/`invokestatic` | only about 22% of baseline sites are in class files ≥55. Unmaintained legacy code is mostly <51, with no indy |
| Classifying regexes only at indy link time | classifying at transform time by default, with indy only for sites classified at runtime or that switch themselves over once hot | same reason: indy needs class files ≥51 |
| Rewriting every candidate site at class load | report-only first, then rewrite at load only the sites on the profiler's hot list, then broaden the free rewrites | most sites are cold. Rewriting them costs startup time and adds exposure for no benefit |
| Tracking hotness for every rule | hotness gating only for rules that keep something alive (cached `Pattern`s, side classes, retransform) | free substitutions don't need it |
| Self-promoting sites as the first runtime mechanism | deferred to a later phase | the most complex mechanism, and only needed where no profile exists |

## Measurement approaches dropped

| Approach | Why it was dropped | Instead |
|---|---|---|
| Fleet-wide profiling flame graph (`getProfilingFlamegraph`, `family:java`) for hotness | it aggregates only about 100 profiles, and `frameRegexFilter` matched nothing, even a `Thread.run` control | profiling timeseries filtered on a stack-frame field, once a filter matches a control frame (pending) |
| Treating a zero from a filtered profiling query as "rare" | the filters failed silently | always run a positive-control frame first |
| Trusting Maven Central's `<release>` as "latest" | it returned pre-releases (netty 5.0.0.Alpha2, betas, RCs) and guava `r09` | `fetch.sh` picks the highest stable version that starts with a digit |

## From the original "Datadog JIT" proposal

| Idea | Why it was set aside | Note |
|---|---|---|
| Patching customer behavior to create lock-in ("customers can't uninstall") | Doug: intended lock-in is the wrong goal | proving a fix on a canary group and letting the customer commit it beats patching it |
| Shipping native code or extra dependencies (e.g. a native crypto provider) | exposure we lack the environment testing for (platforms, FIPS, crashes) | still the POC's "most promising" result, so it would need an explicit decision |
| Stack-wide redesigns (adding Redis caches, new processes) | out of scope for an SDK, and more exposure still | |
