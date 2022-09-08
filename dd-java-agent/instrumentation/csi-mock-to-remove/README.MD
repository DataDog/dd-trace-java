# csi-mock-to-remove

## Goal
This project is a mock to showcase how the new call site instrumenter API should be used.

**_NOTE:_** It should be removed before merging the current pull request

## Files

### Sample call sites
Showcases all three different call sites:
1. **datadog.trace.instrumentation.csi.sample.SampleCallSite** shows how to define the pointcuts and advices
2. **datadog.trace.instrumentation.csi.sample.SampleInstrumenter** example of an instrumenter

### IAST call sites
Showcases how a future Iast instrumenter with propagation for String/StringBuilder plus an exclussion list would look like:
1. **datadog.trace.instrumentation.csi.iast.StringCallSite** call sites for String
2. **datadog.trace.instrumentation.csi.iast.StringBuilderCallSite** call sites for StringBuilder
2. **datadog.trace.instrumentation.csi.iast.IastInstrumenter** IAST instrumenter with trie based exclusion list

## Benchmark

The project contains a little K6 based benchmark that can be run with:
```
csi-mock-to-remove> gradle benchmark
```
