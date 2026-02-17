# DSM QA Tester Memory

## Project Structure
- Tracer source: `/Users/piotr.wolski/go/src/github.com/DataDog/dd-trace-java`
- Built tracer jar: `dd-java-agent/build/libs/dd-java-agent-<version>-SNAPSHOT.jar`
- DSM core code: `dd-trace-core/src/main/java/datadog/trace/core/datastreams/`
- DSM tests: `dd-trace-core/src/test/groovy/datadog/trace/core/datastreams/`
- Kafka instrumentation (pre-3.8): `dd-java-agent/instrumentation/kafka/kafka-clients-0.11/`
- Kafka instrumentation (3.8+): `dd-java-agent/instrumentation/kafka/kafka-clients-3.8/`

## Test Patterns
- Use `CapturingPayloadWriter` for in-process unit tests (reliable, no server needed)
- `DataStreamsWritingTest` uses mock HTTP server pattern (can be flaky in some envs)
- Use `ControllableTimeSource` + `advance()` + `report()` to trigger DSM flush
- Tests must call `dataStreams.start()` before adding data
- See [patterns.md](patterns.md) for detailed patterns

## Known Issues
- `DataStreamsWritingTest` pre-existing tests fail in local dev env (mock HTTP server issue)
- `Multiple points are correctly grouped in multiple buckets` test is flaky (NoOpHistogram)
- Build command: `./gradlew :dd-java-agent:shadowJar` (takes ~5 min)
- Test command: `./gradlew :dd-trace-core:test --tests "..."`

## Kafka Config Feature (branch: piotr.wolski/capture-kafka-producer-consumer-configs)
- `KafkaConfigReport` implements `InboxItem`, uses type+config for equals/hashCode
- Dedup via `ConcurrentHashMap<KafkaConfigReport, Boolean>` in DefaultDataStreamsMonitoring
- `clear()` resets the dedup cache
- Serialized as "Configs" array in MsgPack StatsBucket, each entry has "Type" and "Config" map
- Gated behind `Config.get().isDataStreamsEnabled()`
