# Resilience4j Instrumentation - Quick Reference

## Run Tests Now

```bash
cd /Users/junaidahmed/dd-trace-java

# Run all tests
./run-resilience4j-tests.sh --all

# Run with build
./run-resilience4j-tests.sh --build --all

# Run specific component
./run-resilience4j-tests.sh --component RateLimiterTest

# Generate HTML report
./run-resilience4j-tests.sh --all --report
open dd-java-agent/instrumentation/resilience4j/resilience4j-comprehensive/build/reports/tests/test/index.html
```

## What Gets Tested

| Component | Tests | What's Verified |
|-----------|-------|-----------------|
| **RateLimiter** | 2 tests, 8 variants | Supplier/Callable decoration, permit tracking, metrics |
| **Bulkhead** | 3 tests, 12 variants | Supplier/Callable/Runnable, concurrent call limits |
| **ThreadPoolBulkhead** | 2 tests, 8 variants | Thread pool metrics, queue depth, async operations |
| **TimeLimiter** | 3 tests | Timeout tracking, Future/CompletionStage handling |
| **CircuitBreaker** | 5 tests, 8 variants | All states (CLOSED/OPEN/HALF_OPEN), failure rates |
| **Retry** | 4 tests | Max attempts, exponential backoff, wait duration |

## Test Commands Reference

```bash
# Individual component tests
./run-resilience4j-tests.sh --component RateLimiterTest
./run-resilience4j-tests.sh --component BulkheadTest
./run-resilience4j-tests.sh --component ThreadPoolBulkheadTest
./run-resilience4j-tests.sh --component TimeLimiterTest
./run-resilience4j-tests.sh --component CircuitBreakerTest
./run-resilience4j-tests.sh --component RetryTest

# Quick smoke test (2 components)
./run-resilience4j-tests.sh --quick

# Clean build + test
./run-resilience4j-tests.sh --clean --build --all

# Using Gradle directly
./gradlew :dd-java-agent:instrumentation:resilience4j:resilience4j-comprehensive:test
./gradlew :dd-java-agent:instrumentation:resilience4j:resilience4j-comprehensive:test --tests "*RateLimiterTest"
```

## Expected Output

### Success
```
═══════════════════════════════════════════════════════════════
  Test Summary
═══════════════════════════════════════════════════════════════

Passed Tests (6):
  ✓ RateLimiterTest
  ✓ BulkheadTest
  ✓ ThreadPoolBulkheadTest
  ✓ TimeLimiterTest
  ✓ CircuitBreakerTest
  ✓ RetryTest

Total: 6 tests
Passed: 6
Failed: 0

All tests passed!
```

## Span Tags Verified

### RateLimiter
- `resilience4j.rate_limiter.name`
- `resilience4j.rate_limiter.metrics.available_permissions` (when metrics enabled)
- `resilience4j.rate_limiter.metrics.number_of_waiting_threads` (when metrics enabled)

### Bulkhead (Semaphore)
- `resilience4j.bulkhead.name`
- `resilience4j.bulkhead.type` = "semaphore"
- `resilience4j.bulkhead.metrics.available_concurrent_calls` (when metrics enabled)
- `resilience4j.bulkhead.metrics.max_allowed_concurrent_calls` (when metrics enabled)

### ThreadPoolBulkhead
- `resilience4j.bulkhead.name`
- `resilience4j.bulkhead.type` = "threadpool"
- `resilience4j.bulkhead.metrics.thread_pool_size` (when metrics enabled)
- `resilience4j.bulkhead.metrics.core_thread_pool_size` (when metrics enabled)
- `resilience4j.bulkhead.metrics.maximum_thread_pool_size` (when metrics enabled)
- `resilience4j.bulkhead.metrics.remaining_queue_capacity` (when metrics enabled)

### TimeLimiter
- `resilience4j.time_limiter.name`
- `resilience4j.time_limiter.timeout_duration_ms`
- `resilience4j.time_limiter.cancel_running_future`

### CircuitBreaker
- `resilience4j.circuit_breaker.name`
- `resilience4j.circuit_breaker.state` (CLOSED/OPEN/HALF_OPEN)
- `resilience4j.circuit_breaker.metrics.failure_rate` (when metrics enabled)
- `resilience4j.circuit_breaker.metrics.slow_call_rate` (when metrics enabled)
- `resilience4j.circuit_breaker.metrics.buffered_calls` (when metrics enabled)
- `resilience4j.circuit_breaker.metrics.failed_calls` (when metrics enabled)
- `resilience4j.circuit_breaker.metrics.slow_calls` (when metrics enabled)

### Retry
- `resilience4j.retry.name`
- `resilience4j.retry.max_attempts`

## Files Created

```
dd-java-agent/instrumentation/resilience4j/resilience4j-comprehensive/
├── src/test/groovy/
│   ├── RateLimiterTest.groovy          (110 lines)
│   ├── BulkheadTest.groovy             (141 lines)
│   ├── ThreadPoolBulkheadTest.groovy   (131 lines)
│   ├── TimeLimiterTest.groovy          (125 lines)
│   ├── CircuitBreakerTest.groovy       (238 lines)
│   └── RetryTest.groovy                (204 lines)
│
├── run-resilience4j-tests.sh           (275 lines) - Test runner
├── RESILIENCE4J_TEST_REPORT.md         (full documentation)
└── RESILIENCE4J_QUICK_REFERENCE.md     (this file)
```

## Bugs Fixed

1. **TimeLimiterInstrumentation.java:30** - Removed contradictory matcher
2. **ThreadPoolBulkheadInstrumentation.java:31** - Removed unnecessary matcher

## PR Information

- **PR:** https://github.com/DataDog/dd-trace-java/pull/10317
- **Branch:** `feature/resilience4j-comprehensive-instrumentation`
- **Latest Commit:** `14d1e31142` (bug fixes + tests)
- **Previous Commit:** `ce55aaa244` (initial implementation)

## Help

```bash
./run-resilience4j-tests.sh --help
```

Shows all available options and examples.

## Troubleshooting

### Java Not Found
```bash
# Install Java 17+ first
brew install openjdk@17

# Or download from https://adoptium.net/
```

### Build Fails
```bash
# Try clean build
./run-resilience4j-tests.sh --clean --build --all
```

### View Logs
Test logs are saved to `/tmp/[TestName].log`

```bash
# View specific test log
cat /tmp/RateLimiterTest.log
```

## Next Steps After Tests Pass

1. ✅ Verify all tests pass locally
2. Update CHANGELOG.md (if required by project)
3. Wait for CI pipeline in PR #10317
4. Address any review feedback
5. Merge to master

---

**Quick Links:**
- 📄 [Full Test Report](RESILIENCE4J_TEST_REPORT.md)
- 🔗 [PR #10317](https://github.com/DataDog/dd-trace-java/pull/10317)
- 📦 [Delivery Package](/Users/junaidahmed/resilience4j-instrumentation-delivery/)
