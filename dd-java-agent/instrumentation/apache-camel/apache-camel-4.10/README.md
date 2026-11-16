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
