package datadog.trace.api.iast;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InstrumentationBridgeTest {

  @AfterEach
  void cleanup() {
    InstrumentationBridge.clearIastModules();
  }

  @ParameterizedTest(name = "{0} can be registered")
  @MethodSource("moduleCanBeRegisteredArguments")
  void moduleCanBeRegistered(String moduleName, Class<? extends IastModule> module) {
    IastModule instance = mock(module);
    InstrumentationBridge.registerIastModule(instance);

    IastModule result = InstrumentationBridge.getIastModule(module);

    assertSame(instance, result);
  }

  static Stream<Arguments> moduleCanBeRegisteredArguments() {
    return InstrumentationBridge.getIastModules().stream()
        .map(module -> arguments(module.getSimpleName(), module));
  }

  @Test
  void unsupportedModulesThrowExceptions() {
    IastModule unsupported = mock(IastModule.class);

    assertThrows(
        UnsupportedOperationException.class,
        () -> InstrumentationBridge.registerIastModule(unsupported));

    assertThrows(
        UnsupportedOperationException.class,
        () -> InstrumentationBridge.getIastModule(IastModule.class));
  }
}
