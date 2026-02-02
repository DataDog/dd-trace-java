# Manual Crash Tracking Setup

This document describes how to manually enable crash tracking for Java applications when automatic configuration is not possible or desired.

## Overview

Crash tracking captures JVM crash information (segmentation faults, SIGABRT, OutOfMemoryError) and uploads it to Datadog for analysis. The setup differs between JVM implementations:

| JVM Type | Auto-Configuration | Manual Setup Required |
|----------|-------------------|----------------------|
| HotSpot (OpenJDK, Oracle, Zulu, etc.) | Yes (via native library) | Only if native library fails to load |
| J9/OpenJ9 (IBM Semeru, Eclipse OpenJ9) | No | Always |

## HotSpot JVMs

### Automatic Configuration (Default)

On HotSpot-based JVMs, the Datadog Java agent automatically configures crash tracking by setting the `-XX:OnError` JVM flag at runtime using native library access. No manual configuration is needed.

### Manual Configuration

If automatic configuration fails (check agent logs for warnings), you can manually configure crash tracking:

```bash
java -XX:OnError="/path/to/dd_crash_uploader.sh %p" \
     -XX:ErrorFile=/tmp/hs_err_pid%p.log \
     -javaagent:/path/to/dd-java-agent.jar \
     -jar your-application.jar
```

**Parameters:**
- `-XX:OnError` - Command executed when JVM crashes. The `%p` is replaced with the process ID.
- `-XX:ErrorFile` - Location where the crash log (hs_err) file is written. Default: `hs_err_pid<pid>.log` in the working directory.

### Locating the Crash Uploader Script

The agent deploys the crash uploader script to a temporary directory. Check agent logs at startup for the exact path:

```
DEBUG datadog.crashtracking - Crash uploader script deployed to: /tmp/dd_crash_uploader.sh
```

Or locate it manually:
```bash
find /tmp -name "dd_crash_uploader.sh" 2>/dev/null
```

## J9/OpenJ9 JVMs (IBM Semeru, Eclipse OpenJ9)

### Why Manual Configuration is Required

J9/OpenJ9 JVMs use a different mechanism for crash handling (`-Xdump` instead of `-XX:OnError`), and this option **cannot be modified at runtime**. Manual configuration at JVM startup is always required.

### Configuration

Add the `-Xdump:tool` option to your JVM arguments:

```bash
java -Xdump:tool:events=gpf+abort,exec=/path/to/dd_crash_uploader.sh\ %pid \
     -javaagent:/path/to/dd-java-agent.jar \
     -jar your-application.jar
```

**Important:** Note the backslash (`\`) before `%pid` - this escapes the space and is required.

### Configuration Options

| Option | Description |
|--------|-------------|
| `events=gpf+abort` | Trigger on General Protection Fault (SIGSEGV) and SIGABRT |
| `exec=<command>` | Command to execute when event occurs |
| `%pid` | Token replaced with process ID |

### Custom Javacore File Location

By default, J9 writes javacore files to the current working directory. You can customize the location using `-Xdump:java:file=`:

```bash
# Custom javacore location
-Xdump:java:file=/var/log/javacores/javacore.%pid.%seq.txt
```

**Important:** The Datadog agent automatically detects custom javacore paths from your JVM arguments. When you specify `-Xdump:java:file=`, the crash uploader script will search for javacore files in that location.

**Supported path formats:**
- **Directory path:** `/var/log/crashes/` - searches for `javacore.*<pid>*.txt` files in this directory
- **File pattern:** `/var/log/crashes/javacore.%pid.%seq.txt` - substitutes `%pid` with process ID
- **Exact file:** `/var/log/crashes/javacore.txt` - uses the file directly if it exists

### Additional J9 Dump Options

```bash
# Disable default system dumps (optional, reduces disk usage)
-Xdump:system:none

# Full example with custom javacore path
java -Xdump:tool:events=gpf+abort,exec=/opt/datadog/dd_crash_uploader.sh\ %pid \
     -Xdump:java:file=/var/log/crashes/javacore.%pid.%seq.txt \
     -javaagent:/path/to/dd-java-agent.jar \
     -jar your-application.jar
```

### J9 Dump Events Reference

| Event | Description |
|-------|-------------|
| `gpf` | General Protection Fault (SIGSEGV, SIGBUS) |
| `abort` | SIGABRT signal |
| `systhrow` | System exception thrown (including OutOfMemoryError) |
| `user` | User-initiated dump (SIGQUIT/Ctrl+\) |

To capture OutOfMemoryError events as well:
```bash
-Xdump:tool:events=gpf+abort+systhrow,exec=/path/to/dd_crash_uploader.sh\ %pid
```

### J9 File Path Tokens

When specifying file paths for `-Xdump:java:file=`, you can use these tokens:

| Token | Description |
|-------|-------------|
| `%pid` | Process ID |
| `%seq` | Sequence number (increments for each dump) |
| `%Y` | Year (4 digits) |
| `%m` | Month (2 digits) |
| `%d` | Day (2 digits) |
| `%H` | Hour (2 digits) |
| `%M` | Minute (2 digits) |
| `%S` | Second (2 digits) |

Example with date-based directory:
```bash
-Xdump:java:file=/var/log/crashes/%Y%m%d/javacore.%pid.%seq.txt
```

## Verifying Configuration

### Check Agent Logs

At startup, the agent logs crash tracking initialization status:

**HotSpot (successful):**
```
DEBUG datadog.crashtracking - Crashtracking initialized
```

**J9 (not configured):**
```
INFO datadog.crashtracking - J9 JVM detected. To enable crash tracking, add this JVM argument at startup:
INFO datadog.crashtracking -   -Xdump:tool:events=gpf+abort,exec=/tmp/dd_crash_uploader.sh\ %pid
```

**J9 (configured):**
```
DEBUG datadog.crashtracking - J9 crash tracking: -Xdump:tool already configured, crash uploads enabled
```

### Test Crash Upload (Development Only)

To verify crash tracking works, you can intentionally crash the JVM in a test environment:

```java
// WARNING: Only use in test environments!
import sun.misc.Unsafe;
import java.lang.reflect.Field;

Field f = Unsafe.class.getDeclaredField("theUnsafe");
f.setAccessible(true);
Unsafe unsafe = (Unsafe) f.get(null);
unsafe.putAddress(0, 0); // Causes SIGSEGV
```

## Troubleshooting

### Crash Uploader Script Not Found

If the crash uploader script doesn't exist:
1. Ensure the Datadog agent is loaded (`-javaagent` option)
2. Check that crash tracking is enabled: `dd.crash_tracking.enabled=true`
3. Verify the agent has write access to the temp directory

### Crash Not Reported

1. **Check script permissions:** The crash uploader script must be executable
   ```bash
   chmod +x /path/to/dd_crash_uploader.sh
   ```

2. **Verify Java is in PATH:** The script invokes Java to upload the crash
   ```bash
   which java
   ```

3. **Check configuration file:** The script reads configuration from `dd_crash_uploader_pid<PID>.cfg`
   ```bash
   cat /tmp/dd_crash_uploader_pid*.cfg
   ```

### J9: Javacore Not Found

The crash uploader script looks for javacore files in the current working directory. If your application changes directories, specify an absolute path:

```bash
-Xdump:java:file=/var/log/crashes/javacore.%pid.%seq.txt
```

## Container Environments

### Docker

```dockerfile
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/datadog/dd-java-agent.jar"

# For J9 JVMs, add:
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/datadog/dd-java-agent.jar -Xdump:tool:events=gpf+abort,exec=/opt/datadog/dd_crash_uploader.sh\ %pid"
```

### Kubernetes

```yaml
env:
  - name: JAVA_TOOL_OPTIONS
    value: "-javaagent:/opt/datadog/dd-java-agent.jar"
  # For J9 JVMs:
  - name: JAVA_TOOL_OPTIONS
    value: "-javaagent:/opt/datadog/dd-java-agent.jar -Xdump:tool:events=gpf+abort,exec=/opt/datadog/dd_crash_uploader.sh\\ %pid"
```

Note the double backslash (`\\`) in YAML to properly escape the space.

## References

- [HotSpot VM Options](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html)
- [OpenJ9 -Xdump Documentation](https://eclipse.dev/openj9/docs/xdump/)
- [OpenJ9 Java Dump Documentation](https://eclipse.dev/openj9/docs/dump_javadump/)
