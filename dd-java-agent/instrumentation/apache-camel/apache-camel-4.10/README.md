# Camel route instrumentation

## Exchange state

Route context is temporarily stored in an exchange property. Camel copies exchange
properties when creating split and multicast branches; each branch starts its own
route state while retaining its parent context. Completion restores the enclosing
state or removes the property. Application code that lists exchange properties can
observe this temporary value.

The state is deliberately not serializable. Camel 4.10.3's DefaultExchangeHolder
rejects it as an invalid property value, with DEBUG logging, when properties are
included in a serialized exchange. Enabling serialized values does not make this
state eligible for transfer. CamelSerializationTest verifies actual Java holder
roundtrips with serialized values enabled and disabled. The logging level follows
Camel's source; the test runtime's inactive logging backend cannot measure it.

Do not reuse Camel's OTEL_ACTIVE_SPAN key: that state belongs to the native tracer.
Moving to an agent context store would require explicitly propagating context at
exchange-copy boundaries.

## Propagation and routing overhead

Sending events inject the currently active context into message headers. Headers
remain available after the send, as in Camel's native tracing adapter. Local
endpoints can therefore expose propagation headers to application code or logging.
CamelTransportTest and CamelMessagingTest verify that existing HTTP and JMS
instrumentation replaces the outbound parent with its transport span, preserving
route parentage. Header-only transfers remain supported by CamelPropagationTest.
Blanket header restoration could overwrite application mutations or race deferred
consumers; maintaining a transport-scheme allowlist would miss custom components.

The standard CaseInsensitiveMap is updated in place. Custom maps retain a copy
path because they can be immutable or shared. Without Datadog exchange state,
processor interception delegates the original callback without attaching context.
With state, callback wrapping is needed even when the current context already
matches: completion may execute on another thread.

Event notification still incurs Camel event processing and, for notified sends,
timing and callback work. Native-tracer detection remains dynamic so services
registered after startup take precedence. Caching absence indefinitely would break
that behavior; lifecycle-aware caching and replacing the notifier need separate
route-level measurements. A header-injection allocation control cannot establish
these costs, application throughput, or the overhead of asynchronous routes.
