
# Questions

1. LlmObsSpan is just a wrapper for an apm span. The datadog.trace.llmobs.writer.ddintake.LLMObsSpanMapper.map maps only LLM spans.
2. The current implementation does not activate the underlying APM span. This leaves any child OpenAI HTTP spans disconnected, which seems incorrect.
3. It must produce APM spans when LLMObs is turned off? And vice versa.
4. Also, should the LMObs without APM scenario be considered? Then how would it work if it relies on APM so much?


# Convo for the upcoming work

1. openai-java instrumentation covering
   - completions stream/create
   - chat/completions stream/create
   - responses stream/create
   - embeddings

2. unit tests as part of dd-trace-java

3. async parts (not currently covered by llmobs integration tests)


