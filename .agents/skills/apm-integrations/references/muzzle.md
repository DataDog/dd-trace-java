# Muzzle Directives

> Referenced from `SKILL.md` Step 9.2. Everything about `muzzle { pass { … } fail { … } }` blocks and the traps that fail CI.

## Muzzle directives (mandatory)

In `build.gradle`, add `muzzle` blocks. **There are three valid patterns** — choose based on whether your version range is open-ended or bounded, and if bounded, why.

**Pattern A — Open-ended range** (your instrumentation supports `[minVersion, ∞)` with no upper bound). Use `assertInverse = true` — the plugin auto-asserts that versions below `minVersion` fail muzzle:

```groovy
muzzle {
  pass {
    group = "com.example"
    module = "framework"
    versions = "[$minVersion,)"
    assertInverse = true
  }
}
```

**Pattern B — Bounded range, sibling module takes over at `maxVersion`** (a separate module in `dd-java-agent/instrumentation/` covers `[maxVersion, ∞)`). **Do NOT use `assertInverse = true`** — the plugin can pick sibling-covered versions as inverse-test targets, causing unexpected failures:

```
> Task :muzzle-AssertFail-redis.clients-jedis-jedis-3.6.2 FAILED
MUZZLE PASSED JedisInstrumentation BUT FAILURE WAS EXPECTED
```

To avoid this, declare the pass range AND an explicit fail range below `$minVersion` (the sibling module covers versions above `$maxVersion`):

```groovy
muzzle {
  pass {
    group = "com.example"
    module = "framework"
    versions = "[$minVersion,$maxVersion)"
  }
  fail {
    group = "com.example"
    module = "framework"
    versions = "[,$minVersion)"
  }
}
```

**Pattern C — Bounded range, incompatible major version above `maxVersion`** (the same `group:module` coordinates republish a completely different API at `maxVersion`). Use `assertInverse = true` — versions above `maxVersion` genuinely fail muzzle because the API is incompatible:

```groovy
muzzle {
  pass {
    group = "commons-httpclient"
    module = "commons-httpclient"
    versions = "[2.0,4.0)"
    assertInverse = true
  }
}
```

## Test dependencies should default to the module's declared minimum version

**Default:** the base `testImplementation` dep version in `build.gradle` should match the module's declared minimum version (from the module directory suffix and the muzzle `versions = "[X.Y,)"` range). Pinning `testImplementation` to a newer version than the module claims to support silently exercises a version the module wasn't declared to work with, and `muzzle` won't catch it because muzzle checks classpath compatibility, not test behavior.

```groovy
// WRONG — module is jedis-3.0 (min = 3.0.0) but tests run against 4.0 with no justification
muzzle {
  pass { group = "redis.clients"; module = "jedis"; versions = "[3.0,)" }
}
dependencies {
  testImplementation("redis.clients:jedis:4.0.0")   // ← breaks the min-version guarantee
}

// DEFAULT — testImplementation at the declared min; latestDepTestImplementation for the newest
dependencies {
  testImplementation("redis.clients:jedis:3.0.0")
  latestDepTestImplementation("redis.clients:jedis:+")
}
```

**Justified deviation:** a module may `compileOnly` against the declared minimum and `testImplementation` against a higher version when the test genuinely requires a class/API that only exists in the higher version. In that case:

- Document the reason with a comment at the top of `build.gradle` (a reviewer must be able to see why immediately)
- The `super(...)` alias should reflect the version the code exercises, not the compile-time minimum

Example — `dd-java-agent/instrumentation/spark/sparkjava-2.3/build.gradle`:

```groovy
// building against 2.3 and testing against 2.4 because JettyHandler is available since 2.4 only
dependencies {
  compileOnly group: 'com.sparkjava', name: 'spark-core', version: '2.3'
  testImplementation group: 'com.sparkjava', name: 'spark-core', version: '2.4'
}
```

Without a written justification, prefer the default (test-at-min). This is a stricter form of the `latestDep` range rule (see Step 9.3 of the main SKILL.md) — it applies to the *base* test dependency too, not just latestDep.

## Do NOT use `assertInverse = true` unless the declared min is the actual minimum compatible version

The `assertInverse = true` directive tells muzzle to auto-test versions below the declared minimum and assert they fail. If your instrumentation is actually compatible with versions below the declared minimum (a common case when only ONE of several instrumentation classes requires the new feature), this auto-assertion will fail with:

```
> Task :module:muzzle-AssertFail-group-module-X.Y.Z FAILED
MUZZLE PASSED FeignClientInstrumentation BUT FAILURE WAS EXPECTED
```

**Rule**: If you can't verify (via running `./gradlew muzzle` against a sweep of older versions) that the declared min version is truly the minimum compatible version, **omit `assertInverse = true`**:

```groovy
// Conservative — passes muzzle without asserting unprovable inverse claims
muzzle {
  pass {
    group = "io.github.openfeign"
    module = "feign-core"
    versions = "[10.8,)"
    // NO assertInverse here — we don't know the true minimum
  }
}
```

Add `assertInverse = true` only when you've empirically verified the min via local muzzle sweep. Otherwise, leaving it off is correct.

This is common whenever any instrumentation class in the module is compatible with versions below the declared min — `assertInverse` then contradicts that class's compatibility.

## Muzzle range must exclude incompatible major versions

If the library you are instrumenting has a major version break where a newer major version
is published under the **same** `group:module` coordinates but with a completely different API,
your muzzle `pass` range must have an explicit upper bound to exclude the incompatible major:

```groovy
// WRONG — open range accidentally covers 4.x (different API, same coordinates)
muzzle {
  pass {
    group = "com.example"
    module = "framework"
    versions = "[2.0,)"   // 4.x would also match but has completely different API
    assertInverse = true
  }
}

// CORRECT — bounded range excludes 4.x
muzzle {
  pass {
    group = "com.example"
    module = "framework"
    versions = "[2.0,4.0)"
    assertInverse = true
  }
}
```

**How to check**: search Maven Central for the `group:module` coordinates and look for versions
that are clearly a different generation (major API rewrites). Look at the existing dd-trace-java
module for the same library to see how it bounds its range.

## Library-specific muzzle quirks: skipVersions

Some libraries have **malformed release versions** in Maven Central (e.g., a version literally named `jedis-3.6.2` with a `jedis-` prefix). These break the muzzle plugin's version-resolution algorithm — the task name becomes `muzzle-AssertFail-redis.clients-jedis-jedis-3.6.2` (doubled "jedis") and the muzzle plan can include them in the wrong directive.

The fix is `skipVersions` in the affected directive:

```groovy
muzzle {
  fail {
    group = "redis.clients"
    module = "jedis"
    versions = "[,3.0.0)"
    skipVersions += "jedis-3.6.2"  // bad release version ("jedis-" prefix)
  }
}
```

**How to discover these**: when verify fails with a task name that has a doubled module name (e.g., `redis.clients-jedis-jedis-3.6.2`), check the existing production module for the same library at another version. If it has `skipVersions` entries, copy them. This is library-specific tribal knowledge that lives in the existing modules.

When in doubt, **search adjacent module build.gradle files for `skipVersions`** before declaring a new version-bounded module's muzzle directives.
