# Datadog OpenFeature provider

## Per-subject experimentation opt-out

Set `DD_FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE=immediate` before constructing the provider.
The default is `disabled`. Existing configuration delivery and credentials are still required.

```java
import com.datadog.openfeature.Provider;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;

OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(new Provider());
boolean enabled = api.getClient().getBooleanValue("new-checkout", false,
    new MutableContext("subject-123")
        .add("dd_experimentation_opt_out_at", "2026-10-19T12:00:00Z"));
```

In immediate mode, a valid RFC3339 timestamp excludes the subject from allocations that record experiment exposures.
Evaluation continues through ordinary feature allocations. If none match, the provider returns the application's default.
A future timestamp also takes effect immediately. It does not schedule exclusion.

Omit the attribute for subjects who can participate.
Invalid, null, empty, or non-string values return the application's default with `INVALID_CONTEXT` and a diagnostic.
Timestamp validation uses the same parser as allocation `startAt` and `endAt`.
Disabled mode ignores the attribute, including invalid values.

An explicit option overrides configuration:

```java
import com.datadog.openfeature.ExperimentationOptOutMode;

Provider provider = new Provider(new Provider.Options()
    .experimentationOptOutMode(ExperimentationOptOutMode.DISABLED));
```

`DEFAULT` reads the normal SDK configuration sources, including
`-Ddd.feature.flags.experimentation.opt.out.mode=immediate`.
The provider resolves the mode at construction. Unsupported values throw `IllegalArgumentException`.

This setting is independent of evaluation telemetry opt-out.
Immediate withdrawal can change an enrolled subject's experience. Account for this change in experiment analysis.
