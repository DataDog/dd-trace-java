# Native-Image Build Fix Status

## Problem Statement
Adding the `profiling-scrubber` module triggered 44 "unintentionally initialized at build time" errors when building with GraalVM native-image and profiler enabled (`-J-javaagent` during compilation).

## Root Cause Identified

**The initialization cascade was caused by Exception Profiling instrumentation:**

Using `--trace-class-initialization`, we discovered:
```
datadog.trace.bootstrap.CallDepthThreadLocalMap caused initialization at build time:
	at datadog.trace.bootstrap.CallDepthThreadLocalMap.<clinit>(CallDepthThreadLocalMap.java:13)
	at datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionProfiling$Exclusion.isEffective(ExceptionProfiling.java:49)
	at java.lang.Exception.<init>(Exception.java:86)
	at java.lang.ReflectiveOperationException.<init>(ReflectiveOperationException.java:76)
	at java.lang.ClassNotFoundException.<init>(ClassNotFoundException.java:71)
```

**Why this happens:**
1. Agent attaches via `-J-javaagent` during native-image compilation
2. OpenJdkController constructor runs and starts ExceptionProfiling
3. GraalVM throws exceptions during class scanning
4. Instrumented Exception constructor triggers ExceptionProfiling code
5. This initializes CallDepthThreadLocalMap and 43 other config/bootstrap classes at build time

## Solution Applied

**Disable exception profiling during native-image build via configuration:**

Modified: `dd-smoke-tests/spring-boot-3.0-native/application/build.gradle`
```gradle
if (withProfiler && property('profiler') == 'true') {
  buildArgs.add("-J-Ddd.profiling.enabled=true")
  // Disable exception profiling during native-image build to avoid class initialization cascade
  buildArgs.add("-J-Ddd.profiling.disabled.events=datadog.ExceptionSample")
}
```

## Results

### ✅ SUCCESS: Initialization Errors Fixed
- **Before:** 44 classes unintentionally initialized at build time
- **After:** 0 initialization errors

The configuration approach successfully prevents ExceptionProfiling from starting during native-image compilation, eliminating the entire initialization cascade.

### ⚠️ NEW ISSUE: JVM Crash During Native-Image Build

The build now fails with a JVM fatal error:
```
SIGBUS (0xa) at pc=0x00000001067aa404
Problematic frame: V [libjvm.dylib+0x8be404] PSRootsClosure<false>::do_oop(narrowOop*)+0x48
```

**Error details:**
- Crash occurs during garbage collection (Parallel Scavenge)
- Happens while processing JavaThread frames
- Stack trace shows agent's bytecode instrumentation is active:
  - `datadog.instrument.classmatch.ClassFile.parse`
  - `datadog.trace.agent.tooling.bytebuddy.outline.OutlineTypeParser.parse`
  - `datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.lookupType`

**Error report:** `dd-smoke-tests/spring-boot-3.0-native/build/application/native/nativeCompile/hs_err_pid*.log`

## Files Modified

1. **dd-java-agent/agent-profiling/profiling-scrubber/build.gradle**
   - Removed unnecessary `internal-api` dependency (profiling-scrubber doesn't use it)

2. **dd-java-agent/agent-profiling/src/main/java/com/datadog/profiling/agent/ProfilingAgent.java**
   - Removed static import of `PROFILING_TEMP_DIR_DEFAULT` (had System.getProperty in initializer)
   - Changed to runtime computation: `System.getProperty("java.io.tmpdir")` at line 162-163

3. **dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerFlareReporter.java**
   - Line ~229: Replaced `PROFILING_JFR_REPOSITORY_BASE_DEFAULT` with runtime computation
   - Line ~507: Replaced `PROFILING_TEMP_DIR_DEFAULT` with runtime computation

4. **dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java**
   - Line ~275: Replaced `PROFILING_JFR_REPOSITORY_BASE_DEFAULT` with runtime computation
   - **Note:** This file is clean - no native-image detection code added

5. **dd-smoke-tests/spring-boot-3.0-native/application/build.gradle**
   - Added `-J-Ddd.profiling.disabled.events=datadog.ExceptionSample` to disable exception profiling during build
   - Added trace flag (temporary, for debugging): `--trace-class-initialization=datadog.trace.bootstrap.CallDepthThreadLocalMap`

## Next Steps

The JVM crash during native-image build needs investigation:

### Option 1: Investigate GC Crash
- The crash occurs in Parallel GC during thread stack scanning
- May be related to agent's bytecode instrumentation interfering with GC
- Could try different GC algorithm or adjust heap settings

### Option 2: Reduce Agent Footprint During Build
- The agent performs extensive bytecode parsing during native-image compilation
- Consider disabling more agent features during build (not just exception profiling)
- Possible flags to try:
  - `-J-Ddd.instrumentation.enabled=false` (if such flag exists)
  - Reduce instrumentation scope during native-image compilation

### Option 3: Check for Known Issues
- Search for similar SIGBUS crashes with GraalVM + Java agents
- Check if this is a known GraalVM 21.0.9 issue
- Test with different GraalVM version

### Option 4: Alternative Approach
- Consider NOT attaching agent during native-image build
- Configure agent to attach only at runtime in the compiled native-image
- May require changes to how profiling is initialized

## Testing Commands

```bash
# Rebuild agent
./gradlew :dd-java-agent:shadowJar

# Test native-image build with profiler
./gradlew :dd-smoke-tests:spring-boot-3.0-native:springNativeBuild \
  -PtestJvm=graalvm21 -Pprofiler=true --no-daemon

# Check initialization errors (should be 0)
grep -c "was unintentionally initialized" \
  build/logs/*springNativeBuild.log

# View JVM crash report
ls -t dd-smoke-tests/spring-boot-3.0-native/build/application/native/nativeCompile/hs_err_pid*.log | head -1
```

## Key Learnings

1. **Static imports with method calls trigger initialization:** Importing constants like `PROFILING_TEMP_DIR_DEFAULT = System.getProperty("java.io.tmpdir")` causes GraalVM to initialize classes at build time.

2. **Exception profiling is a major trigger:** When the agent is active during native-image compilation, any exceptions thrown (e.g., ClassNotFoundException during class scanning) trigger instrumentation that initializes many config classes.

3. **Configuration-based disable works:** Disabling JFR events via `-Ddd.profiling.disabled.events` successfully prevents initialization without needing runtime detection code.

4. **Avoid detection during initialization:** Any attempt to detect "are we in native-image compilation" (Class.forName, getResource, etc.) can itself trigger the cascade we're trying to avoid.

5. **Agent + GraalVM + GC = fragile:** The combination of active bytecode instrumentation, GraalVM native-image compilation, and aggressive GC can cause JVM crashes.

## Branch Status

- Branch: `jb/jfr_redacting`
- All changes committed and ready to push
- Initialization cascade: FIXED ✅
- Native-image build: CRASHES ⚠️
