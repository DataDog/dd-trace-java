# Supported Configurations Registry

> Referenced from `SKILL.md` Steps 4, 6, and 9.1. How to register integration names in `metadata/supported-configurations.json` so CI linters pass.

## Two distinct CI checks — different names, different key shapes

- **`super(...)` args in `InstrumenterModule`** → validated by `dd-gitlab/config-inversion-linter` as `DD_TRACE_<NAME>_ENABLED` entries. Every name passed to the constructor must have a `_ENABLED` entry.
- **`instrumentationNames()` in the decorator** → validated by `checkDecoratorAnalyticsConfigurations` as `DD_TRACE_<NAME>_ANALYTICS_ENABLED` / `DD_TRACE_<NAME>_ANALYTICS_SAMPLE_RATE` entries.

## `_ENABLED` entry (for each `super(...)` arg)

For each new integration name `<NAME>` (uppercase, dashes/dots replaced with underscores — `couchbase-3` → `DD_TRACE_COUCHBASE_3_ENABLED`), add:

```json
"DD_TRACE_<NAME>_ENABLED": [
  {
    "version": "A",
    "type": "boolean",
    "default": "true",
    "aliases": ["DD_TRACE_INTEGRATION_<NAME>_ENABLED", "DD_INTEGRATION_<NAME>_ENABLED"]
  }
],
```

**Default value:** the `_ENABLED` default mirrors the module's `defaultEnabled()` return value — `"true"` for typical integrations. Set `"default": "false"` only if the module overrides `defaultEnabled()` to return `false` (e.g. OpenTelemetry, Hazelcast, sparkjava). For new integrations this is always `"true"`; when modifying an existing module, verify with: `grep -A2 "defaultEnabled" dd-java-agent/instrumentation/<framework>*`.

## Analytics entries (for each decorator `instrumentationNames()` return value)

```json
"DD_TRACE_<NAME>_ANALYTICS_ENABLED": [
  {
    "version": "A",
    "type": "boolean",
    "default": "false",
    "aliases": ["DD_<NAME>_ANALYTICS_ENABLED"]
  }
],
"DD_TRACE_<NAME>_ANALYTICS_SAMPLE_RATE": [
  {
    "version": "A",
    "type": "decimal",
    "default": "1.0",
    "aliases": ["DD_<NAME>_ANALYTICS_SAMPLE_RATE"]
  }
],
```

## Rules

**Place entries alphabetically** in the JSON file.

**Type names — match existing conventions**: use `"boolean"`, `"string"`, `"int"`, `"decimal"` (for floating-point — NOT `"double"`). The `dd-gitlab/validate_supported_configurations_v2_local_file` CI job will fail with non-canonical type names.

The Gradle checks (`checkInstrumenterModuleConfigurations`, `checkDecoratorAnalyticsConfigurations`) validate the JSON automatically — they will fail with a parse error if the file is malformed.

**How to discover whether entries are missing**: after writing the instrumentation, search `metadata/supported-configurations.json` for each name used in `super(...)` and decorator `instrumentationNames()`. If any is absent, add it. Do not assume master already has it — version-specific integration names (e.g. `sparkjava-2.3` vs `sparkjava-2.4`) are not interchangeable.
