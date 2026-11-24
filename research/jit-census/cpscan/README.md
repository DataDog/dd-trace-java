# Constant-pool pre-check prototype

`CpScan.java` measures one-sided pre-checks that rule out classes before a real parse when deciding whether a rewrite rule could apply. The rules are 5 calls: `String.split`, `replaceAll`, `matches`, `format` and `Pattern.compile`. A class can call `owner.name(desc)` only if both the name and the descriptor exist as `CONSTANT_Utf8` entries, so "absent" is exact and "present" means "maybe".

## Corpus

`corpus.txt`: the latest cached version of 182 popular library artifacts (Spring, Jackson, Netty, Hibernate, Guava, Commons, Tomcat, Kafka, Jetty, gRPC, OkHttp, logging, Reactor, RESTEasy, JDBC drivers). That's 59,689 classes, 4.2 KB per class on average. To run it, resolve the coordinates to local jar paths, one per line, e.g. from `~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<version>/*/<artifact>-<version>.jar`:

```
javac -cp asm-9.8.jar CpScan.java && java -Xmx4g -cp asm-9.8.jar:. CpScan jar-paths.txt
```

## Results (2026-11-23, Apple Silicon laptop, single thread, bytes in memory, best of 5 timed rounds)

Selectivity, the same for every scanner. "real" means an actual invoke found by ASM:

| rule | maybe | real | false negatives |
|---|---|---|---|
| String.split | 0.41% | 0.38% | 0 |
| String.replaceAll | 0.10% | 0.09% | 0 |
| String.matches | 0.48% | 0.05% | 0 |
| String.format | 2.19% | 2.08% | 0 |
| Pattern.compile | 0.40% | 0.40% | 0 |
| **any rule** | **3.2%** | 2.8% | 0 |

Cost per class (µs):

| scanner | JDK 21 | JDK 8 |
|---|---|---|
| naive: 2 `indexOf` per rule over the whole file | 11.2 | 9.1 |
| structure-unaware, single pass anchored on `(` | 4.8 | 2.8 |
| **constant-pool walk, compare Utf8 entries only by exact length** | **0.92** | **0.88** |
| `new ClassReader(bytes)` alone (indexes the constant pool) | 0.71 | 0.74 |
| full ASM visit of method bodies (no debug, no frames) | 8.7 | 9.8 |

## Takeaways

- **The pre-check idea holds:** 97% of classes are ruled out with zero false negatives.
- **A minimally structured scan beats a structure-unaware one by 3–5×.** Walking the constant pool skips each non-Utf8 entry in O(1) by its tag. It compares only the Utf8 entries whose length exactly matches a needle (itself a one-sided pre-check), and it never touches code or attributes. A structure-unaware scan has to touch every byte of the file. It costs only ~0.2 µs more than the `ClassReader` constructor alone.
- About 0.9 µs per class means ~20 ms for 20k classes, against ~175 ms for a full parse. The walk must treat any malformed class or exception as "maybe" to stay sound.
- Caveats: a laptop microbenchmark with warm caches and bytes already in memory. On the real class-load path the bytes arrive cold. Measure inside the agent's transformer before relying on the absolute numbers.

## Scaling with the number of rules (`CpScanScale.java`)

The rules are real JDK methods (owner under `java/`) that the corpus calls, sampled with a fixed seed after the 5 originals. All walks are allocation-free per class and agree with each other on every class (checked). JDK 21 shown; JDK 8 is within ~10%. µs/class:

| rules | needles | maybe | linear | indexed (len→first byte) | + signature filter | two-pass |
|---|---|---|---|---|---|---|
| 5 | 10 | 3.1% | 0.86 | 0.84 | **0.79** | 0.89 |
| 50 | 85 | 34% | 1.40 | 1.08 | **0.91** | 1.16 |
| 500 | 625 | 55% | 3.60 | 1.73 | **1.33** | 1.90 |
| 2000 | 1805 | 62% | 8.05 | 2.76 | **1.97** | 2.92 |

- **The floor is the walk itself, ~0.7–0.8 µs/class** (`new ClassReader` alone is 0.71). It's a serial chain (each entry's offset depends on the one before), so it doesn't vectorize.
- **Signature filter (Doug's idea):** an 8 KB bitmap over hash(length, first, middle, last byte) of every needle. A text entry whose signature bit is unset is skipped before any candidate lookup. It keeps up to 50 rules at the floor. Checking the last byte first inside the compare alone barely helped (2.63 vs 2.76 µs at 2000 rules): the cost is the number of candidates, not the length of each compare.
- **What's left at large rule counts** is needles that share a (length, first byte) bucket: 154 compares/class for 10.7 real matches at 2000 rules (unfiltered: 273). Keying the candidate lists by the full signature, not only (length, first byte), should remove most of that.
- **Two passes** (first record each text entry's offset and length, then match) are slightly *slower* for matching alone. Their value is precision: with recorded offsets, a second step can resolve Methodref → NameAndType → Utf8 and check the owner, for the "maybe" classes only.
- **Selectivity matters more than speed.** Rules over random real JDK calls make 34% of classes "maybe" at 50 rules, because common names/descriptors (`get`, `()V`, `(I)I`) are everywhere. The pre-check only rules classes out if rule targets are rare calls (regex, `format`), or with an owner-precise second step for the maybe classes.

### Adding the owner class as a third needle (`-Downer=true`, Doug's idea)

A call to `owner.name(desc)` needs a `CONSTANT_Class` entry whose name is a text entry holding exactly the owner's internal name (e.g. `java/util/regex/Pattern`). So also requiring that exact text entry keeps the check sound. Inside descriptors the owner appears as `Ljava/util/regex/Pattern;`, which is a different text entry, so exact matching tells them apart. JDK 21, µs/class with the signature filter:

| rules | maybe (name+desc) | **maybe (+owner)** | cost (name+desc) | cost (+owner) |
|---|---|---|---|---|
| 5 | 3.1% | 3.1% | 0.80 | 0.81 |
| 50 | 34% | **5.4%** | 0.92 | 0.96 |
| 500 | 55% | **19%** | 1.35 | 1.60 |
| 2000 | 62% | **39%** | 2.01 | 2.52 |

The owner check cuts the classes that need a real look by 6× at 50 rules, for ~5% more scan cost. Owners like `java/lang/String` filter nothing; rare owners (`Pattern`, `MessageFormat`, `java/util/regex/Matcher`) filter a lot. It also means the Methodref-resolving second step only runs where all three needles are present.

## Lowering instrumentation matchers (`CpScanInstr.java`)

A hierarchy matcher such as "subtype of T that declares `name(desc)`" needs the class to **declare** the method. A class that doesn't declare it inherits the method from an ancestor, which gets matched and transformed itself. So the method's name and descriptor must both be text entries in the class's own constant pool. The pre-check uses only the class's own bytes; the hierarchy question, which needs other classes, is asked only of classes that pass. Ground truth: ASM-declared methods, with the hierarchy resolved over the corpus plus the running JDK. Types that can't be resolved count as "not a subtype", so "true" is a lower bound. That's why javax servlet is 0: the API jar isn't in the corpus.

| matcher | passes pre-check | declares | true match | false negatives |
|---|---|---|---|---|
| HttpServlet.service | 0.01% | 0.01% | 0.01% | 0 |
| jakarta Servlet.service | 0.03% | 0.02% | 0.01% | 0 |
| Filter.doFilter | 0.02% | 0.02% | 0.02% | 0 |
| Statement.executeQuery(String) | 0.02% | 0.01% | 0.01% | 0 |
| Statement.execute(String) | 0.13% | 0.01% | 0.01% | 0 |
| PreparedStatement.executeQuery() | 0.03% | <0.01% | <0.01% | 0 |
| Netty channelRead | 0.03% | 0.02% | <0.01% | 0 |
| OkHttp Interceptor.intercept | 0.02% | 0.02% | 0.02% | 0 |
| Kafka Consumer.poll(Duration) | 0.04% | 0.02% | 0.01% | 0 |
| Callable.call | 0.50% | 0.18% | 0.16% | 0 |
| Runnable.run | 2.79% | 1.34% | 1.23% | 0 |

**With all of these except Runnable, 0.79% of classes need a hierarchy check, against 100% without the pre-check.** Specific descriptors (`service`, `doFilter`, `intercept`) are nearly exact. Generic shapes (`run()V`, `call()Ljava/lang/Object;`) are the weak case, but even `Runnable.run` rules out 97% of classes.
