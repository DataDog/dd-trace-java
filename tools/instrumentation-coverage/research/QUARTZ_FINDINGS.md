# Quartz: a small instrumentation suite over a broad scheduler surface

## Why this candidate

Quartz has four existing instrumentation scenarios: simple scheduling, cron scheduling, XML configuration, and starting an independent job trace when scheduling under a parent trace. This is a plausible place to find unexercised functionality; a small test count is not itself proof of poor instrumentation.

## Run and scope

Ran `:dd-java-agent:instrumentation:quartz-2.0:test -PtestJvm=21` without collection and then with the observer. Both runs passed the same four cases with no skips, failures, or errors. The test dependency is Quartz 2.0.0; other version suites were not run.

Production transformer and registered tracer were verified, including transformation of QuartzTestJob. Collection reported no errors or dropped observations. The unchanged job fixture is explicitly included as an entry-advice control, separate from the library inventory.

| Scope | Eligible methods | Observed | Unobserved | Non-root entries | Root entries |
| --- | ---: | ---: | ---: | ---: | ---: |
| Seven selected Quartz library classes | 328 | 137 | 191 | 80 | 806 |
| QuartzTestJob.execute fixture control | 1 | 1 | 0 | 4 | 0 |
| Combined inventory displayed in report | 329 | 138 | 191 | 84 | 806 |

Thus 58.2% of this selected library-method inventory was not observed. This is not a percentage for all Quartz or a measure of missing instrumentation.

The selected classes are StdScheduler, QuartzScheduler, JobRunShell, RAMJobStore, SimpleTriggerImpl, CronTriggerImpl, and JobExecutionContextImpl. JDBC/clustered execution is outside this inventory.

## Findings and next tests

1. **Pause/resume/cancellation: 0 of 34 mapped methods observed.** Inspect lifecycle behavior through pauseTrigger/resumeTrigger, unscheduleJob, rescheduleJob, and interruption scenarios. Assert whether a job runs, stops, or is prevented from running, as well as job context isolation. These operations do not necessarily require a caller Context.
2. **Job entry works in the observed success cases.** QuartzTestJob.execute had non-root Context on all four entries. The instrumentation deliberately creates a fresh trace for every job. Do not propagate the scheduling parent just to make more scheduler methods green: the existing parent-scheduling test explicitly expects separate traces.
3. **Failure and refire are the most directly relevant next instrumentation tests.** The existing fixture succeeds in every scenario; it does not throw JobExecutionException. Add a deterministic throwing job to exercise the advice's throwable path and validate error reporting and Context restoration. Separately test bounded immediate refire, checking the expected per-execution behavior rather than assuming the span topology. Restore/isolation assertions need additional boundary evidence: method-entry counts alone cannot prove them.
4. **Root scheduler execution is not a proven gap.** JobRunShell.run starts before the job's execute advice; store/trigger/lifecycle code may legitimately run outside job Context. Even JobExecutionContext accessors can be called by decoration before activation. Interpret the root stacks using these boundaries.

Unobserved methods also include lifecycle/configuration/accessor paths. They do not all warrant dedicated tests. Functionality grouping and expected behavior matter more than maximizing the method count.

## Artifacts

- Run again: `bash tools/instrumentation-coverage/research/legacy/run-quartz.sh`.
- Report: `dd-java-agent/instrumentation/quartz-2.0/build/reports/context-coverage/QuartzTest/report.html`.
- Adjacent report.json/report.md contain raw evidence; validation.json records baseline matching and per-class counts.
- Baseline XML: `tools/instrumentation-coverage/build/reports/quartz-baseline/TEST-QuartzTest.xml`.
- New curated Quartz capability map: job execution, triggers, pause/resume/cancellation, in-memory store, job dispatch/completion, scheduler lifecycle. Scenario prompts are proposals; no new tests or fixes have been executed.
- Browser checks verify the 329-method inventory, 0/34 lifecycle category, the 4/0 job control, and absence of Spring-specific simulation plans. Existing Spring UI checks also pass.

This run demonstrates how the report can reveal missing behavioral evidence while avoiding a false diagnosis of missing context propagation.
