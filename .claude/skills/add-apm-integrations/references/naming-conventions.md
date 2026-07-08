# Naming Conventions

> Referenced from `SKILL.md` Step 4 (module directory name) and Step 4.2 (Java class/file naming).

## Module directory name must end with a version OR an allowed suffix

dd-trace-java's `dd-gitlab/check-instrumentation-naming` plugin
(`buildSrc/.../naming/InstrumentationNamingPlugin.kt`) enforces:

> Module name must end with a version (e.g., `2.0`, `3.1`) OR one of: `-common`, `-stubs`, `-iast`

Pick a directory name like `$framework-$minVersion` (e.g. `okhttp-3.0`, `jedis-3.0`). For shared
helpers/stubs/iast support code factored out across version-specific modules, use the documented
suffixes above.

## Java naming consistency (CRITICAL — non-negotiable)

The filename and the declared `public class` name MUST match exactly, character-for-character including case. Java will not compile a file where they differ.

**Pick one canonical name per class, then use that exact string everywhere:**

- The filename (e.g., `JMSDecorator.java`)
- The class declaration inside (e.g., `public class JMSDecorator`)
- Every `import static <pkg>.<ClassName>.<member>` across all other files in the module
- Every reference in the form `<ClassName>.<member>` or `<ClassName>.class`

**Convention in dd-trace-java**: acronym classes use **uppercase**:

- CORRECT: `JMSDecorator`, `gRPCInstrumentation`, `JDBCConnection`
- WRONG: `JmsDecorator`, `GrpcInstrumentation`, `JdbcConnection`

This matches the existing module conventions. When in doubt, match a reference instrumentation's casing exactly (see Step 3).

**After generating each module, sanity-check before declaring done:**

```bash
# Every public class declaration must match its filename
cd dd-java-agent/instrumentation/$framework/$framework-$version/src/main/java
for f in $(find . -name '*.java'); do
  CLS=$(grep -oE 'public (final )?(abstract )?class [A-Za-z0-9_]+' "$f" | awk '{print $NF}')
  EXPECTED=$(basename "$f" .java)
  [ "$CLS" = "$EXPECTED" ] || echo "MISMATCH: $f declares '$CLS'"
done
```

If any MISMATCH lines print, fix them before moving on.
