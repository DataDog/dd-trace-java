# Final Plan Summary - HTTP Client Abstraction

## Plan Status: ✅ FINALIZED

The plan has been reviewed, critiqued, and updated based on your answers. It is now ready for implementation.

---

## Key Changes from Initial Plan

### 1. **Added Minimal Multipart Support** (Phase 1, Task 1.2)
- Implemented `HttpRequestBody.multipart()` for flare-utils use case
- Minimal RFC 2388 implementation (not full spec)
- Includes Part interface with name, content, filename, contentType

### 2. **Added Configuration Support** (Phase 2, Task 2.3)
- System property: `dd.http.client.implementation`
- Values: `auto` (default), `okhttp`, `jdk`
- `auto` = JDK HttpClient on Java 11+, OkHttp otherwise
- Allows users to force a specific implementation

### 3. **Parallelization Noted** (Phase 3 & 4)
- Phase 3 (OkHttp impl) and Phase 4 (JDK impl) can run in parallel
- Independent implementations can be developed concurrently

### 4. **Updated Verification** (Phase 8, Task 8.3)
- Manual verification of dependencies (not automated import checking)
- Check build.gradle files for okhttp dependencies
- Run `./gradlew dependencies` to verify no transitive okhttp leakage

### 5. **No Phase 0 Added**
- jnr-unixsocket already in use, known to work
- No upfront spike needed

---

## Plan Structure Overview

### **Phase 1: Core Abstractions** (Foundation)
- Task 1.1: HttpUrl abstraction (interface + OkHttp/JDK implementations)
- Task 1.2: HttpRequestBody abstraction (msgpack, json, gzip, **multipart**)
- Task 1.3: HttpResponse abstraction
- Task 1.4: HttpRequest abstraction
- Task 1.5: HttpListener abstraction

### **Phase 2: HttpClient Interface & Builder**
- Task 2.1: HttpClient interface (execute, executeWithRetries)
- Task 2.2: HttpClient.Builder interface (timeouts, proxy, UDS, etc.)
- Task 2.3: Implementation selection logic (**with configuration support**)

### **Phase 3: OkHttp Implementation** *(can run parallel with Phase 4)*
- Task 3.1: OkHttpClientAdapter
- Task 3.2: OkHttpClientBuilder
- Task 3.3: OkHttp request body adapters

### **Phase 4: JDK HttpClient Implementation** *(can run parallel with Phase 3)*
- Task 4.1: JdkHttpClientAdapter
- Task 4.2: JdkHttpClientBuilder
- Task 4.3: JDK Unix Domain Socket support (jnr-unixsocket for Java 11-15, native for Java 16+)
- Task 4.4: JDK request body publishers

### **Phase 5: Update Communication Module Internals**
- Task 5.1: HttpRetryPolicy
- Task 5.2: OkHttpUtils
- Task 5.3: SharedCommunicationObjects (private fields + getters)
- Task 5.4: BackendApi interface
- Task 5.5: IntakeApi
- Task 5.6: EvpProxyApi
- Task 5.7: DDAgentFeaturesDiscovery
- Task 5.8: BackendApiFactory

### **Phase 6: Update Dependent Modules**
- Task 6.1: remote-config-core (remove okhttp dependency)
- Task 6.2: flare-utils (use multipart factory)
- Task 6.3: feature-flagging (use json factory)
- Task 6.4: telemetry
- Task 6.5: dd-trace-core
- Task 6.6: All dd-java-agent modules (8 modules)

### **Phase 7: Integration Testing & Verification**
- Task 7.1: Cross-implementation test suite
- Task 7.2: Integration tests with MockWebServer
- Task 7.3: Full module test suite
- Task 7.4: Smoke tests on Java 8, 11, 16, 17
- Task 7.5: Performance verification (no regression)

### **Phase 8: Documentation & Cleanup**
- Task 8.1: Update build.gradle files
- Task 8.2: Update code coverage exclusions
- Task 8.3: Final verification (dependencies, tests)

---

## Configuration

**System Property:** `dd.http.client.implementation`

**Values:**
- `auto` (default) - Use JDK HttpClient on Java 11+, OkHttp on Java 8-10
- `okhttp` - Force OkHttp implementation on all Java versions
- `jdk` - Force JDK HttpClient (fails on Java < 11)

**Example Usage:**
```bash
# Force OkHttp on Java 11+
-Ddd.http.client.implementation=okhttp

# Force JDK HttpClient
-Ddd.http.client.implementation=jdk

# Auto-detect (default)
-Ddd.http.client.implementation=auto
# or omit the property entirely
```

---

## Key Requirements Met

✅ **No OkHttp API exposure** - All abstractions internal to communication module
✅ **Auto-detect Java version** - Use Platform.isJavaVersionAtLeast(11)
✅ **Configuration option** - System property to force implementation
✅ **Strict feature parity** - Both implementations support all features
✅ **No performance regression** - Benchmarks in Phase 7
✅ **No behavior changes** - Extensive testing ensures compatibility
✅ **Update all dependents** - 15+ modules updated
✅ **TDD throughout** - Tests written before implementation
✅ **Unix domain sockets** - jnr-unixsocket for Java 11-15, native for Java 16+
✅ **Multipart support** - Minimal implementation for flare-utils

---

## Estimated Impact

- **Files Modified:** ~50-70 files
- **Modules Updated:** 15+ modules
- **New Classes:** ~30-35 new classes (abstractions + implementations + multipart)
- **Test Files:** ~35-45 new/updated test files
- **Build Changes:** 10+ build.gradle files updated

---

## Risk Mitigation

1. **UDS Support Risk** - MITIGATED: Already using jnr-unixsocket elsewhere
2. **Performance Risk** - MITIGATED: Benchmarks in Phase 7.5
3. **Behavioral Differences** - MITIGATED: Cross-implementation test suite in Phase 7.1
4. **Breaking Changes** - MITIGATED: No public API changes, only internal refactoring
5. **Configuration Complexity** - MITIGATED: Simple system property with sensible default

---

## Implementation Strategy

1. **Fix forward only** - No rollback strategy, address issues as they arise
2. **Manual verification** - Check dependencies manually, not automated
3. **All-at-once deployment** - All modules updated together (not phased)
4. **Configuration available** - Users can force OkHttp if needed

---

## Next Steps

The plan is ready for Phase 4: Implementation.

When ready, proceed with:
1. Start with Phase 1 (Core Abstractions)
2. Follow TDD: Write tests before implementation
3. Update PLAN.md after each task completion
4. Run tests frequently to catch issues early

---

## Questions or Concerns?

If any questions arise during implementation, document them and we can address them before proceeding.

The plan is comprehensive and accounts for all identified risks and requirements. Implementation can begin when you're ready.
