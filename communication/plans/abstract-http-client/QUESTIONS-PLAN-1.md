<!-- INSTRUCTIONS FOR ANSWERING QUESTIONS -->
<!--
- Answer each question inline below the question
- You can edit the questions if they're unclear
- Add your answers under each question
- When done, save the file and let me know
-->

## Q1: Multipart implementation scope for flare-utils?

TracerFlareService uses `okhttp3.MultipartBody` to upload flare reports. Should we:
- **Option A**: Implement full multipart/form-data spec (RFC 2388) - more complete but more work
- **Option B**: Implement minimal multipart encoding sufficient for flare-utils use case only
- **Option C**: Find alternative approach (e.g., use existing library, different upload format)
Option B

## Q2: Should we add Phase 0 for risk mitigation?

The plan currently starts directly with abstractions. Should we add Phase 0 to:
- Verify jnr-unixsocket dependency exists/add it
- Create spike to validate JDK HttpClient + jnr-unixsocket on Java 11-15 works
- Verify Platform.isJavaVersionAtLeast() utility exists

**Options:**
- **Option A**: Yes, add Phase 0 - fail fast on risks
- **Option B**: No, handle these during implementation as needed
Option B. We already use jnr-unixsocket elsewhere and know it works

## Q3: Rollback strategy during transition?

If critical issues are discovered late (Phase 7), what's the rollback approach?
- **Option A**: Keep deprecated OkHttpUtils.* methods functional until all testing complete, allow switching back
- **Option B**: No rollback - fix issues forward only
- **Option C**: Feature flag to switch between old/new implementation during testing
Option B

## Q4: Import verification enforcement?

Should we have automated verification that okhttp3.* imports don't exist outside :communication module?
- **Option A**: Yes - fail build if violations found (strict enforcement)
- **Option B**: Yes - warn only (soft enforcement)
- **Option C**: No - manual verification is sufficient
Option C. As it would not be exposed as API, we can check OkHttp is not added as dependency

## Q5: Phased rollout or all-at-once?

Should we:
- **Option A**: Enable JDK HttpClient for all Java 11+ users immediately after testing
- **Option B**: Phased rollout - enable for specific modules first, then gradually expand
- **Option C**: Configuration option to let users choose (default to JDK HttpClient on Java 11+)
Option C

---

## Anything else you'd like to mention?

**Additional context or clarifications:**


<!-- Save this file when you're done -->
