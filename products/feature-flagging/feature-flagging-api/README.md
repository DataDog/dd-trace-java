# Internal OpenFeature adapter

This module adapts the shared Feature Flags core to the OpenFeature API.
It is unbundled and is not published as a customer artifact.

The standalone distribution publishes `com.datadoghq:dd-openfeature`.
See [standalone setup](../feature-flagging-standalone/README.md).
The Java agent's OpenFeature instrumentation injects this adapter and the same core.

Keep configuration polling, event transport, and agent tracing implementation out of this module.
