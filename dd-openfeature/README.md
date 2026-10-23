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

Use `future_experiments_only` or `ExperimentationOptOutMode.FUTURE_EXPERIMENTS_ONLY` to exclude newer experiments.
Supply the same stored `dd_experimentation_opt_out_at` timestamp with each evaluation.
For exposure-logging allocations, the provider compares `randomizationActivatedAt` with that timestamp:

| Allocation activation | Behavior |
| --- | --- |
| Before the subject's opt-out time | Evaluate normally, including exposure logging |
| Equal to or after the opt-out time | Skip the allocation and continue ordinary delivery |
| Missing or null | Skip the allocation because its activation time is unknown |

Ordinary allocations remain available. Missing subject attributes preserve normal evaluation.
Both enabled modes reject invalid subject timestamps.
Malformed activation metadata returns `PARSE_ERROR` for the affected flag only in future mode with a valid subject timestamp.
Disabled mode, immediate mode, and evaluations without a subject timestamp preserve ordinary evaluation.
Comparisons retain nanosecond precision and account for timezone offsets.
The cutoff does not depend on the SDK clock. It is not a scheduled change or proof of prior enrollment.
An older allocation can still assign a subject for the first time.

Enable future mode after the configuration service supplies stable activation timestamps and preserves ordinary fallback allocations.
Keep the subject's stored opt-out time stable across sessions and SDK instances.
The provider stores no consent history and emits no new event type.
