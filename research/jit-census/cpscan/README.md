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
