# Reproduce hung test and collect dumps (including child process)

Sometimes a test that passes locally fails on CI due to a timeout.
One common indicator is an error saying that a condition was not satisfied after some time and several attempts.
For example:

```
Condition not satisfied after 30.00 seconds and 39 attempts
...
Caused by: Condition not satisfied:
decodeTraces.find { predicate.apply(it) } != null
```

This failure often masks a real problem, usually a deadlock or livelock in the tested application or in the test
process itself.
To investigate these issues, collect thread and heap dumps to simplify root-cause analysis.

Use this guide when a test repeatedly times out on CI while passing locally and you need actionable JVM dumps.
Step 1 is optional; it only reduces CI turnaround time.

See this [PR](https://github.com/DataDog/dd-trace-java/pull/10698) for an example investigation using this guide.

## Step 0: Setup

Create a branch for testing.

## Step 1 (Optional): Modify build scripts to minimize CI time.

These are temporary debugging-only changes. Revert them after collecting dumps.

Modify `.gitlab-ci.yml`:

- Keep Java versions you want to test, for example Java 21 only:

```
DEFAULT_TEST_JVMS: /^(21)$/
```

- Comment out heavy jobs, like `check_base, check_inst, muzzle, test_base, test_inst, test_inst_latest`.

Modify `buildSrc/src/main/kotlin/dd-trace-java.configure-tests.gradle.kts`:

- Replace the timeout from 20 minutes to 10 minutes:

```
timeout.set(Duration.of(10, ChronoUnit.MINUTES))
```

## Step 2: Modify the target test.

Adjust the target test so it stays alive until Gradle timeout triggers dump collection. For Spock tests, one option is
to use `PollingConditions` with a long timeout in a base class or directly in the target test class:

```
@Shared
protected final PollingConditions hangedPoll = new PollingConditions(timeout: 700, initialDelay: 0, delay: 5, factor: 2)
```

> [!NOTE]
> Use `timeout: 700` if you executed step 1, otherwise use `timeout: 1500`

This poll keeps the test running until Gradle detects timeout and `DumpHangedTestPlugin` triggers dump collection.
Use this poll in the test, for example by replacing `defaultPoll` with `hangedPoll`:

```
waitForTrace(hangedPoll, checkTrace())
```

In other test frameworks, use an equivalent approach to keep the test running past the timeout (for example,
`Thread.sleep(XXX)` in a temporary debugging branch).
The main goal is to keep the test process alive to allow dump collection for all related JVMs.

## Step 3: Run the test on CI and collect dumps.

- Commit your changes.
- Push the reproducer branch to trigger the GitLab pipeline.
- Wait for the target test job to hit timeout.
- In job logs, confirm the dump hook executed (look for `Taking dumps after ... for :...`).
- Wait until the job fails and download job artifacts.
  ![Download dumps](how_to_dump_hanged_test/download_dumps.png)

> [!NOTE]
> You may need to re-run CI several times if the bug is not reproduced on the first try.

Quick verification checklist:

- The test job timed out (not failed fast for another reason).
- Logs contain `Taking dumps after ... for :...`.
- Downloaded artifacts include dump files from the failed run.

## Step 4: Locate dumps by JVM type

### HotSpot/OpenJDK (heap + thread dumps):

- Open the report folder of the failed module/test task.
- You should see files such as `<pid>-heap-dump-<timestamp>.hprof`, `<pid>-thread-dump-<timestamp>.log`, and
  `all-thread-dumps-<timestamp>.log`.
  ![Dumps](how_to_dump_hanged_test/dumps.png)

### IBM JDK (javacore thread dumps only):

- In this case, dumps are produced via `kill -3` and written as `javacore` text files (thread dumps).
- Collect root-level javacore artifacts with the path pattern `reports/javacore.YYYYMMDD.HHMMSS.PID.SEQ.txt`.
  ![Javacores](how_to_dump_hanged_test/javacores.png)

## Step 5: Run the investigation

Use tools like Eclipse MAT, or ask Codex or Claude to analyze collected dumps.
