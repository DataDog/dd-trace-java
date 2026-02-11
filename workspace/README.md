# Configuration Descriptions

The goal of this effort is to provide **high-quality, comparable descriptions** for as many tracer configuration keys as we can.

To do that we want to leverage LLMs and run them in a **multi-step pipeline** with **reproducible output** (same inputs ⇒ same JSON structure and stable ordering).

## Goal & Results

The end result we want is a set of JSON outputs (one per step) containing **all configuration keys** (after applying common filters) plus **candidate descriptions** coming from different sources.
This makes it possible to compare descriptions across sources (and potentially across languages) and choose the best final phrasing.

## Process

We do this in multiple steps (as if it were an automated pipeline).
Each step:

- **takes an input file** (the previous step output, or the initial key list)
- **produces an output file** used by the next step or by a developer for review

### What the step scripts must output

Each step is implemented as one or more scripts. Each step’s primary output is a **single JSON file** matching the schema below:

- Steps 1–3 are **extraction steps**:
  - Do **not** invent or paraphrase descriptions; copy the best available text from the source (minor whitespace/format cleanup is OK).
  - Any script logs should go to stderr; the JSON file must contain only JSON.
- Keep outputs **stable**:
  - `documentedConfigurations` sorted by `key`, then `version`
  - `missingConfigurations` sorted by `key`, then `version`
  - `results` ordered by preference (registry → same language docs → other sources docs → llm_generated)
  - within a single `source` (multiple hits allowed), sort by `sourceFile` (then `description`) for determinism
  - (optional) `configurationsToBeAnalyzed` sorted by `key`, then `version`; `references` sorted by `file`, then `line`
- Counters must match arrays:
  - `documentedCount == len(documentedConfigurations)`
  - `missingCount == len(missingConfigurations)`

### Output format

Each step output should have a name fitting this pattern: `configurations_descriptions_step_{{step_id}}.json`

The content of the file itself should be:

```json
{
  "lang": "java",
  "missingCount": 2,
  "documentedCount": 1,
  "documentedConfigurations": [
    {
      "key": "DD_ACTION_EXECUTION_ID",
      "version": "A",
      "results": [
        {
          "description": "This is the description found by the step and it gives context on how to use the key.",
          "shortDescription": "",
          "source": "documentation_same_language",
          "sourceFile": "content/en/path/to/file:line"
        },
        {
          "description": "This is the description found by the step and it gives context on how to use the key.",
          "shortDescription": "",
          "source": "documentation_other_sources",
          "sourceFile": "content/en/path/to/file:line"
        }
      ],
      "missingSources": [
        {
          "source": "registry_doc",
          "reason": "quality"
        }
      ]
    }
  ],
  "missingConfigurations": [
    {
      "key": "DD_MY_KEY_WITH_NO_DESCRIPTION",
      "version": "A",
      "missingReasons": [
        {
          "source": "registry_doc",
          "reason": "quality"
        },
        {
          "source": "documentation_same_language",
          "reason": "not_found"
        }
      ]
    }
  ]
}
```

- `lang`: The language for which the pipeline ran (e.g. `java`).
- `missingCount` / `documentedCount`: Counts of missing vs documented items. Must match array lengths.
- `documentedConfigurations`: Documented key+version entries.
  - Each entry represents a **key+version** pair from the tracer's supported configurations list (in this repo: `metadata/supported-configurations.json`).
  - `version` must be copied as-is from the supported configurations data (e.g. `"A"`, `"B"`, ...).
  - `results`: A list of candidate descriptions found in different sources.
    - `description`: The extracted text (steps 1–3) or generated text (step 4).
    - `shortDescription`: Always present as a string. For steps 1–3 it should be `""`. Step 4 may fill it for `llm_generated` results.
    - `source`: Where the description came from.
    - (optional) `sourceFile`: The file where it found the description, followed by the line in the file (e.g. `content/en/path/to/file.md:123`). Used by step 2-4.
- `missingConfigurations`: Undocumented key+version entries, with explanations of why this step did not produce a usable description.
  - `missingReasons`: An array of source+reason pairs for this key.
  - `missingSources` (on documented entries): Optional bookkeeping for sources that were attempted but rejected.
- (optional) `configurationsToBeAnalyzed`: Key+version entries that had potential matches but could not be deterministically extracted.
  - Each entry contains `references[]` with `file`, `line`, and `source` (and may optionally include a small snippet/context captured by the deterministic script).

The sources we want to use for now are:

- `registry_doc` when extracted from the registry data
- `documentation_same_language` when extracted from the documentation, reading the correct language existing documentation
- `documentation_other_sources`  when extracted from the documentation reading other sources existing documentation (other languages, opentelemetry doc, product specific doc...)
- `llm_generated` when generated using an LLM by understanding how the configuration key is used

`missingReasons` `reason` attribute can have the following values:

- `not_found` when nothing is found
- `quality` when the quality of the data is not good enough (too short, not specific, or not a real description)

### Quality bar (steps 1–4)

All **extraction steps** should reject low-quality text. A description is considered usable if:

- It is **specific**: says what the configuration controls (not just “enables feature X” without context).
- It is **self-contained**: makes sense without requiring readers to “see docs” or click elsewhere.
- It is **not trivially short** (default heuristic: at least 20 characters).

## Steps

### Common context to all steps

Create a mapping of integration name → candidate config keys by scanning **instrumentation module classes** under `dd-java-agent/instrumentation/` and collecting the strings passed to `super(...)` in their constructors.

Integrations are not always defined in `*Instrumentation.java` files. Also include Java files annotated with `@AutoService(InstrumenterModule.class)` (often `*Module.java`, for example `ArmeriaGrpcClientModule.java`), and collect all string literals passed to `super(...)` (example: `super(\"armeria-grpc-client\", \"armeria-grpc\", \"armeria\", ...)`).

Some integrations only expose their names through `protected String[] instrumentationNames()` methods (for example, many `*Decorator.java` classes). Also scan `dd-java-agent/instrumentation/**/*.java` for `instrumentationNames()` methods that `return new String[] { ... }` and collect the names from that returned array:

- If the method returns `new String[0]`, ignore it.
- If an element is not a string literal (for example `COMPONENT_NAME.toString()`, `MULE.toString()`, or `REDIS`), resolve it either by finding its string literal initializer in the same file, or by adding a mapping in `workspace/result/instrumentation_name_constant_map.json`.

**Instrumentation name constant map (`workspace/result/instrumentation_name_constant_map.json`)**

This file is optional, and is used only to resolve `instrumentationNames()` array elements that are not string literals (for example constants imported from another file).

Schema:

```json
{
  "lang": "java",
  "expressionToValue": {
    "REDIS": "redis"
  },
  "fileExpressionToValue": {
    "dd-java-agent/instrumentation/hazelcast/hazelcast-3.6/src/main/java/datadog/trace/instrumentation/hazelcast36/DistributedObjectDecorator.java::COMPONENT_NAME.toString()": "hazelcast-sdk"
  }
}
```

- `expressionToValue`: global mapping applied to any file (key is the exact expression string seen in the `instrumentationNames()` array).
- `fileExpressionToValue`: file-specific mapping. Key format is `<repo-relative-file>::<expression>`.

Example (Hazelcast):

In `dd-java-agent/instrumentation/hazelcast/hazelcast-3.6/.../DistributedObjectDecorator.java`, the method returns `new String[] { COMPONENT_NAME.toString() }` where `COMPONENT_NAME` is defined in `HazelcastConstants` as `"hazelcast-sdk"`. The mapping above forces that expression to resolve to `hazelcast-sdk`.

Normalize each integration name to a token `INTEGRATION` using: uppercase + replace `-` and `.` with `_` (example: `akka-http2` → `AKKA_HTTP2`).

Then skip **only** integration toggle keys that match these patterns (if present in `metadata/supported-configurations.json`):

- `DD_TRACE_<INTEGRATION>_ENABLED` (base toggle)
- `DD_TRACE_<INTEGRATION>_ANALYTICS_ENABLED`
- `DD_TRACE_<INTEGRATION>_ANALYTICS_SAMPLE_RATE`

If there is **no** canonical `DD_TRACE_<INTEGRATION>_ENABLED` key for an integration but there **is** a canonical `DD_INTEGRATION_<INTEGRATION>_ENABLED` key (example: `DD_INTEGRATION_JUNIT_ENABLED`), skip that base toggle instead.

Skip only the base toggle(s) above — do **not** skip other integration-scoped keys ending in `_ENABLED` (e.g. keep `DD_TRACE_RABBITMQ_PROPAGATION_ENABLED`).

To make filtering auditable/reviewable, Step 1 also writes an extra JSON artifact listing all keys filtered out by these integration toggle rules:

- `workspace/result/filtered_configuration_keys.json`

This file is deterministic (stable ordering) and contains the list of filtered keys plus the pattern/token that triggered the filtering.

When a key moves from missingConfigurations to documentedConfigurations, prior missingReasons should become missingSources.

Use aliases as search terms while keeping only canonical keys in outputs

You should only look at the `en` locale folder.

### 1 - Registry documentation

Label: `registry_doc`

Registry current data is available here: https://dd-feature-parity.azurewebsites.net/configurations/

The very first step of the pipeline should retrieve the data available there and use it to extract descriptions when possible.

If no documentation is found, or the documentation is lacking quality (e.g. less than 20 characters or obviously incomplete), it should be marked as such with `missingReasons` / `missingSources` using:

- `reason: "not_found"` when the registry has no description
- `reason: "quality"` when a description exists but is not usable

#### What the AI should do

Generate **step 1** similarly to the other steps’ split approach:

- a deterministic **registry extraction** script (pure extraction + basic quality heuristics)
- a reviewable **overrides** file (data, not code) to reject low-quality registry descriptions using LLM/human judgment
- a deterministic **merge** script that produces the final `configurations_descriptions_step_1.json`

The extraction script produces `configurations_descriptions_step_1_extracted.json` by joining:

- the tracer key list from `metadata/supported-configurations.json` (keys + `version` letters)
- the registry JSON from `https://dd-feature-parity.azurewebsites.net/configurations/`

The script must be deterministic and safe (read-only inputs, write-only output).

**Script contract (expected by the pipeline):**

- Inputs (registry extraction script; CLI args or constants):
  - `--lang` (example: `java`)
  - `--supported-configurations` (default: `metadata/supported-configurations.json`)
  - `--output` (directory where the output `configurations_descriptions_step_1_extracted.json` will be produced. Default: ./workspace/result)
- Output:
  - `configurations_descriptions_step_1_extracted.json` matching the schema defined above.

- Inputs (merge script):
  - `--step-1-extracted` (default: `./workspace/result/configurations_descriptions_step_1_extracted.json`)
  - `--step-1-overrides` (default: `./workspace/result/step_1_overrides.json`)
  - `--output` (directory where the final output `configurations_descriptions_step_1.json` will be produced. Default: `./workspace/result`)
- Output (merge script):
  - `configurations_descriptions_step_1.json` matching the schema defined above.

**Registry parsing requirements:**

- The registry endpoint returns a JSON array. Each element has:
  - `name` (configuration key, e.g. `DD_AGENT_HOST`)
  - `configurations[]`, where each entry can include:
    - `version` (e.g. `"A"`, `"B"`, `"C"`) which maps to our `version`
    - `description` (may be `null`, `"null"`, empty string, or real text)
    - `implementations[]` with `language` (e.g. `"golang"`, `"java"`, ...) and `to` field that specifies the last version that implements this config (e.g. `v2.13.0` or `latest` or null (== `latest`))
- Build an index `registryByKey[name]`.

**Per key+version behavior:**

For every key+version from `supported-configurations.json`:

- Locate the registry entry (canonical key first, then aliases as fallback):
  - Prefer matching the canonical key name (`key`) against registry `name`.
  - If not present, try aliases from `aliases[]` (sorted lexicographically for determinism). If an alias matches a registry `name`, use that registry entry but keep the output `key` canonical.
  - If neither canonical nor any alias match: mark missing with `{ "source": "registry_doc", "reason": "not_found" }`.
- Choose a registry configuration record deterministically:
  - Prefer a record where `version (registry) == version (json file)`.
  - If no record matches `version (registry) == version (json file)`, fall back to:
    - a record whose `implementations[]` includes `language == lang`, with the highest `to` version field (null or "latest" are the highest versions), else
    - the first record with a non-empty `description`, else
    - mark missing with `reason: "not_found"`.
- Extract `description`:
  - Treat `null`, `"null"`, empty/whitespace, or anything that fails the quality bar as `reason: "quality"`.
  - Otherwise, produce a `results` entry:
    - `source: "registry_doc"`
    - `description`: exact extracted text (trim whitespace)
    - `shortDescription: ""`

**Output assembly requirements:**

- Start from the full set of key+version pairs (so every supported key appears exactly once across `documentedConfigurations` or `missingConfigurations`).
- There should be no "sourceFile" field
- Ensure stable ordering and correct counts as described in “What the step scripts must output”.

**Overrides (step_1_overrides.json)**

Because some registry descriptions can be longer than 20 chars but still low-quality/generic, Step 1 uses an overrides file reviewed/generated by an LLM (or a human) and then merged deterministically.

The overrides file should only reject registry descriptions (it must not invent/rewrite descriptions). Each rejection entry must include the **exact registry description** being rejected (copied from `configurations_descriptions_step_1_extracted.json`) to make review easy and to prevent overrides from applying to the wrong extracted data.

Example format:

```json
{
  "lang": "java",
  "rejectRegistryDescriptions": [
    {
      "key": "DD_SOME_KEY",
      "version": "A",
      "reason": "quality",
      "description": "Bad quality description."
    }
  ]
}
```

### 2 - Own Documentation extract

Label: `documentation_same_language`

This step attempts to find descriptions in **existing Datadog documentation** for the *same tracer language* as `--lang`.

This is an *extraction step*:
- Do **not** invent or paraphrase.
- The prompt should tell the AI what do to and which file to manipulate.

#### Documentation repo remarks

The Datadog documentation is example based. Parsing the documentation with a parser is not ideal as it retrieves example sentences that do not really describe the key
but rather a usecase shown.

There is also no generic structure of documentation that we can easily parse with a script to get decent results.

Instead of parsing the best thing would be to ask the LLM to extract description when it finds one. It should not invent anything but simply extract data when
found.

#### Inputs

- The previous step output `configurations_descriptions_step_1.json`
- A local checkout of the Datadog documentation repository (or the ability to clone it): `https://github.com/DataDog/documentation`.

#### What the AI should do

Generate a **step 2 script** which reads step 1 output and produces `configurations_descriptions_step_2.json`

Because LLM calls are inherently non-deterministic, step 2 is split into:

- a deterministic **context extraction** script (may build inputs for the LLM in configurationsToBeAnalyzed)
- a reviewable **overrides** file produced by the LLM (data, not code)
- a deterministic **merge** script that merges overrides into the final step JSON

There can be multiple doc hits for the same source.

**Script contract (expected by the pipeline):**

- Inputs (CLI args or constants):
  - `--lang` (default: `java`)
  - `--supported-configurations` (default: `metadata/supported-configurations.json`)
  - `--step-1-input` (default: `./workspace/result/configurations_descriptions_step_1.json`)
  - `--doc-folder` (default: `./workspace/documentation`)
  - `--locale` (the folder that it will look into in `content`, default: `en`)
  - `--output` (directory where the output `configurations_descriptions_step_2.json` will be produced. Default: `./workspace/result`)
- Output:
  - JSON file matching the schema defined above.

**How to parse/extract the description form the documentation**

Most configs will look like one of these:
```markdown
`dd.version`
: **Environment Variable**: `DD_VERSION`<br>
**Default**: `null`<br>
Your application version (for example, 2.5, 202003181415, 1.3-alpha). Available for versions 0.48+.
```
You need to extract that last line, this is the description. It still needs to pass quality checks as defined above.
Some docs use header variants like `**Environment Variable (Deprecated)**:` and/or include extra metadata lines like `**System Property**:`; treat those as metadata and still extract the actual description paragraph below.

Another common format is a **configuration table** that includes an `Environment Variable` column and a `Description` column, for example:

```markdown
| Environment Variable | System Property | Description |
| `DD_TRACE_SAMPLING_RULES` | `dd.trace.sampling.rules` | Set a sampling rate at the root of the trace for services that match the specified rule. |
```

In that case, extract the `Description` cell for the row (minor whitespace/`<br>` cleanup is OK) and use the table row’s line number for `sourceFile` (for example: `content/en/tracing/trace_collection/dd_libraries/java.md:81`).

If the documentation does not look similar for a found environment variable, add a (temporary) reference (file+line) to it in the resulting JSON, that will then be analyzed by the LLM to see if this is an example, or actual documentation that we can extract a description from.

It should look like this:
```json
{
  "lang": "java",
  "missingCount": 2,
  "documentedCount": 1,
  "documentedConfigurations": [
    {
      "key": "DD_ACTION_EXECUTION_ID",
      "version": "A",
      "results": [
        {
          "description": "This is the description found by the step and it gives context on how to use the key.",
          "shortDescription": "",
          "source": "documentation_same_language",
          "sourceFile": "content/en/path/to/file:line"
        }
      ],
      "missingSources": [
        {
          "source": "registry_doc",
          "reason": "quality"
        }
      ]
    }
  ],
  "configurationsToBeAnalyzed": [
    {
      "key": "DD_JMXFETCH_ENABLED",
      "version": "A",
      "references": [
        {
          "file": "content/en/serverless/guide/datadog_forwarder_java.md",
          "line": 66,
          "source": "documentation_same_language"
        }
      ]
    }
  ],
  "missingConfigurations": [
    {
      "key": "DD_MY_KEY_WITH_NO_DESCRIPTION",
      "version": "A",
      "missingReasons": [
        {
          "source": "registry_doc",
          "reason": "quality"
        },
        {
          "source": "documentation_same_language",
          "reason": "not_found"
        }
      ]
    }
  ],
}
```

Note: The script must limit itself to its own language documentation (e.g. Java) in that step!

**Step 2 vs Step 3 partitioning (path-based)**

Deduce whether a documentation file belongs to “same language docs” vs “other sources” from its path (under `content/<locale>/`):

- Same language docs (Step 2): include only files matching:
  - `tracing/trace_collection/library_config/<lang>.md`
  - `**/<lang>.md` (basename equals `<lang>.md`, e.g. `content/en/tracing/trace_collection/dd_libraries/java.md`)
  - `**/*_<lang>.md` (basename ends with `_<lang>.md`, e.g. `datadog_forwarder_java.md`)
  - `**/*<lang>*/**/*.md` (path segment equals `<lang>`, e.g. `content/en/tracing/trace_collection/custom_instrumentation/java/otel.md`)
- Other sources (Step 3): any other file under `content/<locale>/` (Step 3 must exclude everything Step 2 would scan).

**What to do after executing the parsing script**

(This part should not be a script, use your LLM capabilities!)
Go through all the `configurationsToBeAnalyzed` items, and read the content of each references. Read the documentation around that reference to deduce if this is an example. First, be conservative about the context you are capturing. If it is not enough, make it larger until you can understand what is going on. If it is an example, skip it. If not, get the description as-is (do not change the wording) and add it to the result. The documentation has no generic structures so use your LLM capabilities to understand what and where is the description of a configuration.
If the description passes quality checks but you believe it is not clear enough, ask for clarifications.
Also review extracted `results[]` for quality. When a candidate description does not meet the quality bar (too generic / not self-contained), reject it via the step overrides file (for Step 3: `step_3_overrides.json` `rejectResults[]`).

### 3 - Other sources documentation extract

Label: `documentation_other_sources`

This step is very similar to step 2, except it scans only “other sources” documentation as defined in the partitioning rules above (exclude same-language docs by path, as Step 2 already handled them).

Extra parsing notes (Step 3):

- Some “other sources” docs (notably OpenTelemetry mapping docs) use definition-list blocks like `` `OTEL_FOO` `` followed by a `**Datadog convention**:` line (for example ``: **Datadog convention**: `DD_TRACE_EXTENSIONS_PATH` ``). Treat these as structured config blocks and extract the description below.
- Ignore env-var tokens explicitly negated in docs with a leading `!` (for example `` `!DD_INTEGRATIONS_ENABLED` `` should **not** be parsed as `DD_INTEGRATIONS_ENABLED`).

Because LLM calls are inherently non-deterministic, Step 3 is split into:

- a deterministic **other-sources extraction** script (may build inputs for the LLM in `configurationsToBeAnalyzed`)
- a reviewable **overrides** file produced by the LLM/human (data, not code)
- a deterministic **merge** script that merges overrides into the final step JSON

#### Overrides (step_3_overrides.json)

The Step 3 overrides file supports:

- `addResults[]`: add a high-quality `documentation_other_sources` result for a key+version.
- `rejectResults[]`: remove a low-quality `documentation_other_sources` candidate (must match exactly the extracted `description` + `sourceFile`).

Example format:

```json
{
  "lang": "java",
  "rejectResults": [
    {
      "key": "DD_SOME_KEY",
      "version": "A",
      "reason": "quality",
      "result": {
        "description": "Bad / non-self-contained description text to remove (must match exactly).",
        "shortDescription": "",
        "source": "documentation_other_sources",
        "sourceFile": "content/en/path/to/file.md:123"
      }
    }
  ],
  "addResults": [
    {
      "key": "DD_SOME_KEY",
      "version": "A",
      "result": {
        "description": "High-quality description text to add.",
        "shortDescription": "",
        "source": "documentation_other_sources",
        "sourceFile": "content/en/path/to/file.md:456"
      }
    }
  ]
}
```

### 4 - Code parser

Label: `code_context` (context packet; not a description source)

This step looks at the **dd-trace-java source code** and deduces what each configuration does based on *how it is defined, read, and used at runtime*.
Unlike steps 1–3 (extraction-only), step 4 is allowed to **generate** a description, but it must be **grounded in code evidence**.

The output of this step is still the usual step JSON (`configurations_descriptions_step_4.json`) where generated descriptions are added as:

- `source: "llm_generated"`
- `description`: full description inferred from code
- `shortDescription`: optional short summary (may be filled for step 4)

#### Inputs

- The previous step output: `configurations_descriptions_step_3.json`
- A local checkout of **dd-trace-java** (this repository)
- The tracer key list: `metadata/supported-configurations.json`

#### What the AI should do

Generate a **step 4** implementation similar to step 2’s split approach:

- a deterministic **code context extraction** script (build inputs for the LLM)
- a reviewable **overrides** file produced by the LLM (data, not code)
- a deterministic **merge** script that merges overrides into the final step JSON

The goal is reproducibility: the deterministic scripts should always produce the same JSON given the same inputs; the LLM output is captured in an overrides file that can be committed/reused.

#### Script contract (expected by the pipeline)

- Inputs (CLI args or constants):
  - `--lang` (default: `java`)
  - `--supported-configurations` (default: `metadata/supported-configurations.json`)
  - `--step-3-input` (default: `./workspace/result/configurations_descriptions_step_3.json`)
  - `--repo-root` (path to the dd-trace-java checkout; default: `.`)
  - `--output` (directory where outputs will be produced. Default: `./workspace/result`)
- Output:
  - A context JSON file, e.g. `configurations_descriptions_step_4_code_context.json`
  - A final step JSON matching the schema defined above: `configurations_descriptions_step_4.json`

#### Deterministic code context extraction (what it must collect)

For every key+version that is still missing after step 3 (or where previous results were rejected for quality), the script must produce a compact set of **code references** that an LLM can use to infer behavior.

Use the dd-trace-java code structure as the primary guide (see also `docs/add_new_configurations.md`):

- **Config definitions** live in `dd-trace-api/src/main/java/datadog/trace/api/config/**`
  - Config constants do *not* include the `DD_` prefix. The environment variable name is derived by normalizing the constant value (uppercase, replace `.` and `-` with `_`, prefix with `DD_`). This normalization is already enforced in the build tooling (see `buildSrc/.../ConfigInversionLinter.kt`).
- **Defaults** live in `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java` (and also in `metadata/supported-configurations.json`).
- **Final values are read and stored** in `internal-api/src/main/java/datadog/trace/api/Config.java` using `ConfigProvider` (and in some cases directly from env vars via `getEnv("DD_...")`).
- **Runtime behavior** is typically controlled by `Config` getters across the repo (scan `**/src/main/java/**` and exclude tests/build output).

For each configuration, prefer references in this order (deterministically):

1. Definition of the config constant in `datadog/trace/api/config/*` (file+line)
2. Where it is read in `Config.java` (file+line and the `ConfigProvider.getXxx(...)` call)
3. A small number of “strong signal” usages (file+line) where the value:
   - gates behavior (e.g. `if (config.isX()) ...`)
   - changes a parameter (timeouts, sample rates, limits, endpoints)
   - selects an implementation (switch/if on enum/string)

The script must keep the output reviewable and stable:

- cap the number of usages collected per key (for example: max 5)
- ignore `src/test` and build/generated output

The context file format is flexible, but it must contain **file+line references** plus small snippets. For example:

```json
{
  "lang": "java",
  "configurationsToBeAnalyzed": [
    {
      "key": "DD_DOGSTATSD_START_DELAY",
      "version": "A",
      "references": [
        {
          "file": "dd-trace-api/src/main/java/datadog/trace/api/config/TracerConfig.java",
          "line": 123,
          "source": "code_context"
        },
        {
          "file": "dd-trace-api/src/main/java/datadog/trace/api/Config.java",
          "line": 456,
          "source": "code_context"
        }
      ]
    }
  ]
}
```

#### What to do after executing the code context script

(This part should not be a script, use your LLM capabilities!)

For each `configurationsToBeAnalyzed` item:

- Open the referenced code locations (and nearby code) and infer **what the configuration controls**.
- If there is not enough evidence to make a self-contained description, do **not** guess: keep it missing.
- Produce a reviewable overrides file that will be merged deterministically.

#### Merge behavior (final step output)

The merge script must:

- read step 3 output + the overrides file
- produce `configurations_descriptions_step_4.json` matching the main schema
- add the generated result under `results[]` with `source: "llm_generated"` (and optional `shortDescription`) and `sourceFile` must point to one of the code references used (format: `<file>:<line>`, using values from `configurationsToBeAnalyzed.references[]`)
- preserve stable ordering rules (and update counts correctly)

#### Step 4 working artifacts (files you edit)

Step 4 is intentionally split into deterministic scripts + reviewable “human/LLM judgment” artifacts.
In this repo, the working files for continuing Step 4 are:

- `workspace/result/step_4_overrides.json`: the only place where we “add” `llm_generated` descriptions.
- `workspace/result/step_4_reasoning.md`: a per-key log of *how* the description was inferred (mapping + evidence + inference).
- `workspace/result/unknown_configurations.json`: keys where code evidence is insufficient to write a self-contained description.

The deterministic outputs are:

- `workspace/result/configurations_descriptions_step_4_code_context.json`: context packet produced by `step_4_code_context_extract.py`.
- `workspace/result/configurations_descriptions_step_4.json`: merged output produced by `step_4_merge.py`.

Terminology reminder: this repository contains the **Java tracer** (some paths are historically named `dd-java-agent/`). The **Datadog Agent** is a separate program; only call it “Agent” when you mean the daemon that receives traces and forwards them upstream.

#### Step 4 continuation runbook (how to keep going in another chat)

This is the manual process used to fill Step 4, one key at a time, while keeping the pipeline reproducible.

##### 0) Pick the next keys to analyze

The “source of truth” for what is still missing is `workspace/result/configurations_descriptions_step_4.json` (not Step 3).
To continue in batches (for example: 20 at a time), list the next missing keys:

```bash
python3 - <<'PY'
import json
from pathlib import Path
p = Path("workspace/result/configurations_descriptions_step_4.json")
obj = json.loads(p.read_text(encoding="utf-8"))
missing = obj.get("missingConfigurations", [])
print("missingCount =", obj.get("missingCount"))
for i, it in enumerate(missing[:30], 1):
  print(f"{i:2d} {it['key']} {it['version']}")
PY
```

Notes:
- If the first items are already present in `workspace/result/unknown_configurations.json`, skip them and take the next ones.
- Keep the **output key canonical** (as listed in `metadata/supported-configurations.json`) even if you search by aliases.

##### 1) Generate deterministic “seed context” for a single key

Run the deterministic context extractor for just the key you’re working on:

```bash
python3 workspace/steps/step_4_code_context_extract.py --only-key DD_SOME_KEY
```

This writes/updates `workspace/result/configurations_descriptions_step_4_code_context.json` with:
- config constant definitions (if found) under `dd-trace-api/src/main/java/datadog/trace/api/config/**`
- best-effort references in `internal-api/src/main/java/datadog/trace/api/Config.java` (where the value is read/stored)
- small code snippets around each reference

The extractor uses the config inversion convention (normalize the **config value** like `trace.foo.bar` into `DD_TRACE_FOO_BAR`) to map env var keys back to config constants when possible.

Important: the context extractor is only a *starting point*; it does not reliably capture the strongest runtime usage sites. You must still find “strong signal” usage manually (next section).

##### 2) Map `DD_...` → internal config token(s)

Most tracer settings are defined as string constants in `dd-trace-api/src/main/java/datadog/trace/api/config/**`:

- The constant **value** is the “config token” (example: `trace.grpc.ignored.inbound.methods`).
- The **system property** is typically `dd.` + token (example: `dd.trace.grpc.ignored.inbound.methods`).
- The **environment variable** is derived from the token by normalization (example: `DD_TRACE_GRPC_IGNORED_INBOUND_METHODS`).

If the env var name does not appear literally in code, use these deterministic search keys:

- Search for the **config constant symbol** (strip the `DD_` prefix): `DD_TRACE_FLUSH_INTERVAL` → search for `TRACE_FLUSH_INTERVAL`.
- Search for the **config token** string value (from the config definition): `trace.flush.interval`.
- Search for `configProvider.get...(` call sites in `internal-api/.../Config.java` to find type + default.

If you still cannot find a config constant definition:
- Check `metadata/supported-configurations.json` `aliases[]` and try searching for the aliases’ stripped token names too.
- Treat the key as potentially **dead/unused** (supported in metadata but not implemented) and be ready to mark it unknown.

##### 3) Find “strong signal” runtime usage (manual)

After you locate how the value is read into `Config`:

- Prefer production code under `**/src/main/java/**` and avoid tests unless they point you to the real implementation.
- Find where the value actually changes behavior. Good signals:
  - feature gates: `if (Config.get().isX()) { ... }`
  - parameters: passing the value into a constructor/scheduler/writer/limit/timeout
  - integration gating: `InstrumenterConfig.get().isIntegrationEnabled(...)` / `configProvider.isEnabled(...)`
  - instrumentation matching shortcuts: `onlyMatchKnownTypes()` / `isIntegrationShortcutMatchingEnabled(...)`

Practical grep workflow:

- Start from the getter in `Config` (or the field name) and search repo-wide:
  - `rg "getGrpcIgnoredInboundMethods\\("`
  - `rg "isGrpcServerTrimPackageResource\\("`
- If the config is integration-scoped, search for integration name strings:
  - `rg "isIntegrationEnabled\\(.*\\\"grpc-server-code-origin\\\""`
- If the config is “legacy tracing” style, search for:
  - `rg "isLegacyTracingEnabled\\("`

##### 4) Write the description (grounded, self-contained)

When writing the `llm_generated` description:

- Explain **what the configuration controls** and the user-visible effect on tracing behavior.
- Include important constraints from code (units, ranges, default behavior, interactions).
- Prefer mentioning the **default** when it is meaningful (from `metadata/supported-configurations.json`, `ConfigDefaults`, or the `configProvider.getXxx(..., default)` call).
- Keep it comparable across keys: avoid implementation trivia unless it explains behavior.
- Use correct terminology: “tracer” for this library; “Datadog Agent” only for the external daemon.

##### 5) Record the decision (overrides + reasoning or unknown)

If you can write a high-quality description:

- Append an `addResults[]` entry to `workspace/result/step_4_overrides.json`:
  - `source` must be `llm_generated`
  - `sourceFile` must be a repo-relative `<file>:<line>` pointing to **one** of the evidence locations you used
- Append a matching section to `workspace/result/step_4_reasoning.md` including:
  - mapping (`DD_...` ↔ internal config token/symbol)
  - the key code references you used
  - the inference you applied

If evidence is insufficient (no runtime usage, or behavior can’t be stated confidently):

- Add the key+version to `workspace/result/unknown_configurations.json` with a concrete reason (what you searched, what was missing).

##### 6) Re-merge and verify counts

After adding overrides/unknowns, re-run the deterministic merge:

```bash
python3 workspace/steps/step_4_merge.py
```

Then check that counts move in the expected direction and that your newly-described key is no longer in `missingConfigurations`.


