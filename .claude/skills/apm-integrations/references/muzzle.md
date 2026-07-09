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

**Background**: this failure mode is common when a module has both a "sync" instrumentation class (works on older versions) and an "async" instrumentation class (requires a newer API). Declaring the higher version as the muzzle min is conservative for `compileOnly`, but `assertInverse = true` then auto-tests a lower version with ONLY the sync class hooks active — that passes, creating the "MUZZLE PASSED BUT FAILURE WAS EXPECTED" failure.

## Muzzle range must exclude incompatible major versions

If the library you are instrumenting has a major version break where a newer major version
is published under the **same** `group:module` coordinates but with a completely different API
(e.g. `commons-httpclient:commons-httpclient` 2.x vs 4.x), your muzzle `pass` range must
have an explicit upper bound to exclude the incompatible major:

```groovy
// WRONG — open range accidentally covers commons-httpclient 4.x (different artifact family)
muzzle {
  pass {
    group = "commons-httpclient"
    module = "commons-httpclient"
    versions = "[2.0,)"   // 4.x would also match but has completely different API
    assertInverse = true
  }
}

// CORRECT — bounded range excludes 4.x
muzzle {
  pass {
    group = "commons-httpclient"
    module = "commons-httpclient"
    versions = "[2.0,4.0)"
    assertInverse = true
  }
}
```

**How to check**: search Maven Central for the `group:module` coordinates and look for versions
that are clearly a different generation (3.x → 4.x breaks, major API rewrites). Look at the
existing dd-trace-java module for the same library to see how it bounds its range.

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
