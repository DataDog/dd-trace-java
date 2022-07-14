package datadog.trace.agent.tooling.bytebuddy.outline;

import net.bytebuddy.description.type.TypeDescription;

/** Parses bytecode or loaded types into descriptions. */
interface TypeParser {

  TypeDescription parse(byte[] bytecode);

  TypeDescription parse(Class<?> loadedType);
}
