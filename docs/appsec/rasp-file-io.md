# RASP File I/O — CSI Pattern

How to implement RASP call sites for `java.io` / `java.nio.file` operations.

## Call site structure

```java
@CallSite(
    spi = {RaspCallSites.class},       // required — registers as RASP call site
    helpers = FileIORaspHelper.class)   // required — injects helper into target classloader
public class FilesCallSite {

  @CallSite.Before("fully.qualified.MethodSignature(java.nio.file.Path, ...)")
  public static void beforeXxx(@CallSite.Argument(N) @Nullable final Path path) {
    if (path != null) {
      FileIORaspHelper.INSTANCE.beforeFileLoaded(path.toString());
    }
  }
}
```

Both `spi` and `helpers` are mandatory:
- Without `spi = {RaspCallSites.class}`: the call site is not activated when RASP is enabled.
- Without `helpers = FileIORaspHelper.class`: `NoClassDefFoundError` at runtime when the helper is accessed.

## `@CallSite.Before` descriptor rules

- Always use fully-qualified type names: `java.nio.file.Path`, not `Path`.
- Varargs are declared as arrays: `java.nio.file.CopyOption[]`, not `CopyOption...`. ByteBuddy always sees the array form in bytecode.

## Operation → event mapping

| `Files.*` operation | `beforeFileLoaded` (LFI) | `beforeFileWritten` |
|---|---|---|
| `copy(InputStream, Path, ...)` | no | yes (target) |
| `copy(Path, Path, ...)` | yes (source) | yes (target) — dual event |
| `copy(Path, OutputStream)` | yes (source) | no (OutputStream ≠ filesystem path) |
| `move(Path, Path, ...)` | no | yes (target) |
| `read*`, `newInputStream`, `lines*` | yes | no |
| `write*`, `newOutputStream`, `newBufferedWriter` | no | yes |

`Files.copy(Path, Path)` is the only dual-event case in this module.

## `FileIORaspHelper` contract

- `INSTANCE` is `public static` (not final) — replaceable in tests with a mock.
- `invokeRaspCallback` has three early returns: `!isAppSecRaspEnabled()`, `callback == null`, `span == null / ctx == null`.
- `BlockingException` is always re-thrown (separate catch from the general `Throwable` catch).
- Any other `Throwable` is suppressed with `LOGGER.debug` — RASP must never crash the application.

## Testing

```groovy
void 'test RASP Files.copy path to path'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final source = newFile('src.txt').toPath()          // newFile() creates file on disk — required for reads
    final target = temporaryFolder.resolve('dst.txt')   // resolve() → path without creating — required for writes

    when:
    TestFilesSuite.copyPathToPath(source, target)

    then:
    1 * helper.beforeFileLoaded(source.toString())
    1 * helper.beforeFileWritten(target.toString())
}
```

Rules:
- Read operations → `newFile(name).toPath()` (file must exist on disk).
- Write operations → `temporaryFolder.resolve(name)` (empty path; the operation creates it).
- Always add a negative assertion `0 * helper.beforeFileWritten(_)` when the operation should not fire a write event.
- Spock automatically restores the `INSTANCE` mock after each test — no manual cleanup needed.
