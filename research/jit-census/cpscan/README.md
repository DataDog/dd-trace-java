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
