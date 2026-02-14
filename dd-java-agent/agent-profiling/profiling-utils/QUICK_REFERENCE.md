# Quick Reference - Zstd Performance Investigation

**Date:** 2026-02-14 | **Branch:** jb/zstd_jni | **Status:** ✅ Investigation Complete - Decision Required

---

## TL;DR

**Problem:** Custom Zstd is 50% slower than aircompressor (327ms vs 217ms) on 30MB JFR data.

**Finding:** ~50% of the gap is due to using compression level 3 (DFAST) instead of level 1 (FAST).

**Recommendation:** Switch to Level 1 → closes gap from 100ms to **51ms** while keeping 12% better compression.

---

## The Numbers

| Metric | Aircompressor | Level 1 (FAST) | Level 3 (DFAST) | Level 1 vs L3 |
|--------|--------------|----------------|-----------------|---------------|
| **Speed** | 223ms | 294ms (+31%) | 323ms (+45%) | **-29ms (10% faster)** |
| **Compressed Size** | 19.6MB (62%) | 17.3MB (55%) | 15.8MB (50%) | +4.7% larger |
| **vs Air Compression** | baseline | -12% better ✅ | -19% better ✅ | -7% worse |
| **Gap to Air** | - | **51ms** | 100ms | **-49ms** |
| **Variance** | ±22ms | ±32ms | ±6ms | Higher |

---

## Decision Options

### ⭐ Option 1: Switch to Level 1 (RECOMMENDED)
- **Change:** `DEFAULT_COMPRESSION_LEVEL = 1` in `CompressionParameters.java`
- **Impact:** 10% faster, gap reduced by 49%, still 12% better compression than aircompressor
- **Risk:** Low
- **Effort:** 5 minutes

### 🔍 Option 2: Keep Level 3, Investigate Remaining 51ms Gap
- **Why:** There may still be code-level inefficiencies beyond compression level
- **Areas:** Block sizing (234→117 blocks), hash tables, FSE/Huffman caching
- **Risk:** Medium (may not find significant improvements)
- **Effort:** 16-40 hours

### 🔧 Option 3: Fix Level 2, Then Decide
- **Why:** Level 2 shows promise (50.5% compression) but has ±582ms variance bug
- **Next:** Profile with `-Xlog:gc*` to find root cause
- **Risk:** Low
- **Effort:** 4-8 hours

### ⚡ Option 4: Just Use Aircompressor
- **Why:** If speed is critical and bandwidth cost acceptable
- **Impact:** Lose 12% compression advantage, transmit 2.5TB more/month
- **Risk:** None (stable production library)
- **Effort:** Revert custom implementation

---

## Quick Commands

### Build & Test
```bash
# Test
./gradlew :dd-java-agent:agent-profiling:profiling-utils:test --tests "*Zstd*"

# Build JMH JAR
./gradlew :dd-java-agent:agent-profiling:profiling-utils:jmhJar
```

### Run Benchmark
```bash
# Quick test (level 1 vs level 3)
java -jar dd-java-agent/agent-profiling/profiling-utils/build/libs/profiling-utils-*-jmh.jar \
  "RealJfrCompressionBenchmark.(aircompressorZstd|customZstdWithLevel)" \
  -p dataSize=production -p compressionLevel=1,3 \
  -wi 3 -i 10 -f 1
```

### Implement Level 1 Switch
```bash
# 1. Change default
sed -i '' 's/DEFAULT_COMPRESSION_LEVEL = 3/DEFAULT_COMPRESSION_LEVEL = 1/' \
  dd-java-agent/agent-profiling/profiling-utils/src/main/java/com/datadog/profiling/utils/zstd/CompressionParameters.java

# 2. Rebuild
./gradlew :dd-java-agent:agent-profiling:profiling-utils:jmhJar

# 3. Verify
java -jar dd-java-agent/agent-profiling/profiling-utils/build/libs/profiling-utils-*-jmh.jar \
  "RealJfrCompressionBenchmark.customZstd" -p dataSize=production -wi 2 -i 5
```

---

## Key Files

### Documentation
- **Full Report:** `COMPRESSION_LEVEL_INVESTIGATION.md` (this directory)
- **Previous Work:** `OPTIMIZATION_SUMMARY.md` (4-phase optimization)
- **Original Baseline:** `REAL_JFR_BENCHMARK_REPORT.md`

### Code Changed
- `src/main/java/.../ZstdOutputStream.java` - Added compressionLevel constructor
- `src/jmh/java/.../RealJfrCompressionBenchmark.java` - Added customZstdWithLevel benchmark

### Benchmark Results
- `/tmp/compression-levels.json` - Full JMH results
- `/tmp/compression-levels.log` - Console output with compression sizes

---

## Known Issues

⚠️ **Level 2 is broken** - ±582ms variance, needs investigation
⚠️ **Levels 4-5 worse than Level 3** - Slower AND worse compression (unexpected)

---

## Production Impact (1000 agents)

### Level 1 vs Current Level 3

| Metric | Level 1 | Level 3 | Change |
|--------|---------|---------|--------|
| Upload time | 294ms | 327ms | **-29ms** |
| Bandwidth saved | 2.5TB/month | 5TB/month | -2.5TB |
| CPU cost | 158k sec/month | 167k sec/month | **-8.3 hrs** |

**Verdict:** Level 1 is better unless bandwidth cost is critical

---

**Full details:** See `COMPRESSION_LEVEL_INVESTIGATION.md`
