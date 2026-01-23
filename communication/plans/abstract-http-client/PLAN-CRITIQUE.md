# Plan Critique - HTTP Client Abstraction

## Critical Issues Found

### 🔴 Issue 1: High-Risk UDS Support Validation Too Late

**Problem:** Unix Domain Socket support with jnr-unixsocket on Java 11-15 is a high-risk item that's tested in Phase 4, Task 4.3 - quite late in the implementation.

**Impact:** If jnr-unixsocket doesn't work as expected, we'll discover this after completing most of the abstraction layer.

**Recommendation:** Add a Phase 0 (or early Phase 1 task) to create a spike/proof-of-concept:
- Verify jnr-unixsocket works with JDK HttpClient on Java 11-15
- Test on the actual target platforms
- Fail fast if this approach won't work

---

### 🟡 Issue 2: Missing HttpRequestBody.multipart() Implementation

**Problem:** Task 6.2 (flare-utils) mentions "Replace okhttp3.MultipartBody with custom multipart implementation or HttpRequestBody factory" but there's no task in Phase 1 to implement this.

**Impact:** Task 6.2 cannot be completed without the multipart support.

**Recommendation:** Add to Phase 1, Task 1.2:
- Create `HttpRequestBody.multipart()` factory method
- Implement multipart/form-data encoding
- Add tests for multipart body creation

---

### 🟡 Issue 3: Parallelization Opportunity Missed

**Problem:** Phase 3 (OkHttp implementation) and Phase 4 (JDK implementation) are sequential, but they're independent and could be implemented in parallel.

**Impact:** Longer development time than necessary.

**Recommendation:** Note in the plan that Phase 3 and Phase 4 can be worked on concurrently by different developers (or sequentially by priority: OkHttp first since it's the fallback).

---

### 🟡 Issue 4: Missing Explicit Import Verification Task

**Problem:** Phase 8, Task 8.3 mentions "Verify no okhttp3.* imports outside communication module" but this is just a bullet point, not a proper test task.

**Impact:** Easy to miss during final verification.

**Recommendation:** Add explicit task in Phase 8:
- Create automated test/script that scans all modules except :communication
- Fail build if okhttp3.* imports found outside communication module
- Run as part of CI verification

---

### 🟢 Issue 5: Missing jnr-unixsocket Dependency Verification

**Problem:** Plan assumes jnr-unixsocket is "already added and used" but doesn't verify this or add it if missing.

**Impact:** Build failure when implementing Task 4.3.

**Recommendation:** Add to Phase 0 or early Phase 1:
- Verify jnr-unixsocket is in dependencies
- Add to communication/build.gradle.kts if missing
- Verify version compatibility with Java 11-15

---

## Task Sequencing Review

### ✅ Strengths
1. **TDD properly applied** - Every implementation has tests first
2. **Good dependency order** - Abstractions → Implementations → Updates
3. **Clear phases** - Logical grouping of related work
4. **Comprehensive coverage** - All affected modules identified

### ⚠️ Improvements Needed

**Recommended Phase 0: Risk Mitigation & Setup**
Add before Phase 1:
- [ ] 🟥 **Task 0.1: Verify jnr-unixsocket dependency**
  - [ ] 🟥 Check if jnr-unixsocket is in dependencies
  - [ ] 🟥 Add to build.gradle.kts if missing
  - [ ] 🟥 Update PLAN.md

- [ ] 🟥 **Task 0.2: Spike - JDK HttpClient with UDS on Java 11-15**
  - [ ] 🟥 Write test: Create minimal test using jnr-unixsocket with JDK HttpClient
  - [ ] 🟥 Implement: Proof-of-concept integration
  - [ ] 🟥 Test: Verify on Java 11, 13, 15
  - [ ] 🟥 Update PLAN.md with findings

- [ ] 🟥 **Task 0.3: Verify Platform.isJavaVersionAtLeast() availability**
  - [ ] 🟥 Check that Platform utility exists and has needed methods
  - [ ] 🟥 Update PLAN.md

---

## Dependencies & Ordering Review

### ✅ Good Ordering
- Phase 1 (Abstractions) must complete before Phase 2 (HttpClient interface)
- Phase 2 must complete before Phase 3/4 (Implementations)
- Phase 3/4 must complete before Phase 5 (Communication internals)
- Phase 5 must complete before Phase 6 (Dependent modules)

### 🔄 Parallelization Opportunities
1. **Phase 3 & Phase 4 can run in parallel** (OkHttp impl + JDK impl)
2. **Within Phase 6**, different modules can be updated in parallel:
   - remote-config-core (6.1)
   - flare-utils (6.2)
   - feature-flagging (6.3)
   - telemetry (6.4)
   - dd-trace-core (6.5)
   - All dd-java-agent modules (6.6) can be done in parallel

### 📋 Blocking Dependencies
- Task 1.2 (HttpRequestBody) blocks Task 6.2 (flare-utils multipart) - **MISSING subtask for multipart()**
- Task 5.3 (SharedCommunicationObjects) blocks all of Phase 6
- Phase 7 (Integration tests) blocks Phase 8 (Finalization)

---

## Risk Management Review

### ✅ Good Risk Mitigation
1. **Cross-implementation test suite** (Task 7.1) - Ensures parity
2. **Multiple Java version testing** (Task 7.4) - Catches version-specific issues
3. **Performance verification** (Task 7.5) - Prevents regression

### ⚠️ Missing Risk Mitigation
1. **No rollback strategy** - What if we discover critical issues in Phase 7?
   - Recommendation: Keep OkHttpUtils.* as deprecated but functional until Phase 8 complete
   - Create feature flag to switch between old and new implementation during testing

2. **UDS risk tested too late** (Phase 4.3)
   - Recommendation: Move to Phase 0 as spike

3. **No incremental deployment strategy**
   - Recommendation: Consider phased rollout - enable JDK HttpClient only for specific modules first

---

## Scope Control Review

### ✅ Well-Scoped
- Clear boundary: HTTP client abstraction within communication module
- No feature creep: Only abstracting existing functionality
- Defined completion: All tests pass, no regressions

### ⚠️ Potential Scope Issues
1. **Multipart support for flare-utils** - Not clearly defined in scope
   - Is this full multipart/form-data spec compliance?
   - Or just enough for flare-utils use case?
   - Recommendation: Define minimal requirements

2. **HTTP/2 differences** - Plan says "let implementations use defaults" but doesn't address potential behavioral differences
   - Recommendation: Add task to document any behavioral differences between OkHttp and JDK HttpClient

---

## Technical Readiness Review

### ✅ Ready
- Abstractions well-defined
- Implementation strategy clear
- Testing approach solid

### ⚠️ Needs Verification
1. **jnr-unixsocket dependency** - Verify it exists
2. **Platform utility** - Verify it has isJavaVersionAtLeast(11)
3. **ProxySelector compatibility** - JDK HttpClient uses ProxySelector, OkHttp uses Proxy
4. **Dispatcher/Executor mapping** - OkHttp Dispatcher is different from plain Executor

### 📋 Required Dependencies
Communication module build.gradle.kts should have:
```kotlin
implementation(libs.okhttp)       // existing
implementation(libs.okio)         // existing
// Add if missing:
implementation("com.github.jnr:jnr-unixsocket:0.38.21") // for Java 11-15 UDS
```

---

## Efficiency & Reuse Review

### ✅ Good Reuse
- Leveraging existing OkHttpUtils patterns
- Reusing HttpRetryPolicy logic
- Using existing test infrastructure (MockWebServer)

### ⚠️ Potential Duplication
- OkHttpUrl and JdkHttpUrl will have similar wrapping logic
- Consider shared base class or utilities for common URL operations

---

## Communication & Checkpoints Review

### ✅ Good Checkpoints
- End of each phase is a natural checkpoint
- Test commands provided for verification
- Progress tracking via PLAN.md updates

### ⚠️ Missing User Decisions
1. **Multipart implementation complexity** - Should user approve minimal vs full implementation?
2. **Rollback strategy** - Should user be informed about rollback approach?
3. **Phased rollout** - Should user decide on incremental deployment?

---

## Missing Tasks

### 🔴 Critical Missing Tasks

1. **Phase 0: Add Risk Mitigation tasks** (described above)

2. **Phase 1, Task 1.2: Add multipart support**
   ```
   - [ ] 🟥 Write test: Test multipart body creation
   - [ ] 🟥 Implement: HttpRequestBody.multipart() factory
   - [ ] 🟥 Implement: Multipart encoding with boundaries
   - [ ] 🟥 Test: Run ./gradlew :communication:test --tests "*Multipart*"
   - [ ] 🟥 Update PLAN.md
   ```

3. **Phase 8: Add import verification task**
   ```
   - [ ] 🟥 **Create import verification check**
     - [ ] 🟥 Write test: Scan all modules for okhttp3.* imports
     - [ ] 🟥 Implement: Gradle task or script to verify
     - [ ] 🟥 Test: Run verification and ensure it catches violations
     - [ ] 🟥 Update PLAN.md
   ```

### 🟡 Nice-to-Have Missing Tasks

4. **Document behavioral differences**
   - Create document listing any differences between OkHttp and JDK HttpClient
   - HTTP/2 vs HTTP/1.1 defaults
   - Connection pooling behavior
   - Retry semantics

5. **Migration guide**
   - Document for consumers if they need to change anything
   - Should be empty (no consumer changes needed) but good to verify

---

## Overall Assessment

### Strengths ✅
1. Comprehensive coverage of all affected modules
2. Proper TDD approach throughout
3. Good phase structure and dependencies
4. Thorough testing strategy

### Weaknesses ⚠️
1. High-risk items (UDS) tested too late
2. Missing Phase 0 for setup and risk mitigation
3. Missing multipart implementation for flare-utils
4. No explicit rollback strategy
5. No automated import verification

### Recommendation
**Add Phase 0** with risk mitigation tasks, then proceed with implementation. The plan is fundamentally sound but needs upfront risk validation.

---

## Revised Phase Order Recommendation

0. **Phase 0: Setup & Risk Mitigation** (NEW)
   - Verify dependencies
   - Spike UDS on Java 11-15
   - Verify Platform utility

1. **Phase 1: Core Abstractions** (ENHANCED)
   - Add multipart support to Task 1.2

2-7. **Phases 2-7: As planned** (UNCHANGED)
   - Note Phase 3 & 4 can be parallel

8. **Phase 8: Documentation & Cleanup** (ENHANCED)
   - Add explicit import verification task
   - Add behavioral differences documentation

---

## Questions for User

Before finalizing the plan, clarify:

1. **Multipart implementation scope**: Full multipart/form-data spec or minimal for flare-utils?
2. **Rollback strategy**: Keep deprecated OkHttpUtils during transition?
3. **Phased rollout**: Deploy incrementally or all-at-once?
4. **Import verification**: Should this fail the build or just warn?
