# Internal OpenFeature adapter

This module adapts the evaluator-only artifact from `feature-flagging-lib` to the OpenFeature API.
It is unbundled and is not published as a customer artifact.

The standalone distribution publishes `com.datadoghq:dd-openfeature`.
See [standalone setup](../feature-flagging-standalone/README.md).
The Java agent's OpenFeature instrumentation injects this adapter and the same evaluator artifact.

Keep configuration polling, event transport, and agent tracing implementation out of this module.
