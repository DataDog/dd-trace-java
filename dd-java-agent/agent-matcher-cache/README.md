
# Matcher Cache Builder

Aims to speedup startup time by using a pre-built matcher cache.
It's comprised of two parts:
- Matcher Cache Builder CLI
- Matcher Cache agent param

## How to use

1. Build Matcher Cache

Run Agent jar with -mc for usage instruction:

```
$ java -jar dd-java-trace.jar -mc

Matcher Cache Builder CLI usage: -o output-data-file {-cp class-path} [-r csv-report-file]
```

`-o` (output file) It is a mandatory param for the output binary Matcher Cache Data file path. If such a file aready exists, it will be replaced.

`-r` (report file) It is an optional param for the Matcher Cache builder report file path.

`-cp` (classpath) It can point to a directory that contains class files, jars, fat-jars or jmods. `-cp` can be used more than once.

Matcher Cache Builder searches for all the classes in provided class paths (-cp), dd-trace-jave agent classes, and the current JDK classes (specified in the JAVA_HOME env var).
It tries to load each class and checks whether class is going to be instrumented or not and saves this information into the output data file. This output Matcher Cache Data file is passed into the agent.
In order to see which classes were found and where along with the matcher resolution use `-r` to generate a report file.


2. Use Matcher Cache Builder

In order to use a genreated matcher cache data file it needs to be passed as a java property to the java agent:

```
-Ddd.prebuilt.matcher.data.file=<path-to-generated-matcher-cache-data-file>
```

Check logs at the java agent startup to see whether the matcher cache was able to read cache data or not.

## Matcher Cache JFR events

When Matcher Cache is being used it emits next JFR events:

- `datadog.trace.agent.MatcherCacheMiss` for classes that where not found it the cache
- `datadog.trace.agent.MatcherCacheLoading` the time it took to load cache data

## Class Transformation JFR events

When Agent loads classes it produces `datadog.trace.agent.ClassTransformation` events.

It has next properties:

- internal class name
- class size in bytes
- class difference after transformation
- a flag whether the class has been transformed or skipped
- duration
