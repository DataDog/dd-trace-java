package datadog.trace.api;

import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_METHODS;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.removeSysConfig;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class MethodFilterConfigParserTest {

  @TableTest({
    "scenario                        | value                                                           | expected                                    ",
    "unset                           |                                                                 | [:]                                         ",
    "blank                           | ' '                                                             | [:]                                         ",
    "wildcard only                   | '*'                                                             | [:]                                         ",
    "class without methods           | 'some.package.ClassName'                                        | [:]                                         ",
    "only empty methods              | 'some.package.ClassName[ , ]'                                   | [:]                                         ",
    "leading empty method            | 'some.package.ClassName[ , method]'                             | [:]                                         ",
    "empty method in the middle      | 'some.package.ClassName[ method1,  , method2]'                  | [:]                                         ",
    "single trailing empty method    | 'some.package.Class$Name[ method , ]'                           | ['some.package.Class$Name': {method}]       ",
    "two trailing empty methods      | 'some.package.Class$Name[ method , , ]'                         | [:]                                         ",
    "trailing comma                  | 'ClassName[ method1,]'                                          | [ClassName: {method1}]                      ",
    "two methods                     | 'ClassName[method1 , method2]'                                  | [ClassName: {method1, method2}]             ",
    "two classes with trailing ;     | 'Class$1[method1 ] ; Class$2[ method2];'                        | ['Class$1': {method1}, 'Class$2': {method2}]",
    "duplicate class                 | 'Duplicate[method1] ; Duplicate[method2]  ;Duplicate[method3];' | [Duplicate: {method1, method2, method3}]    ",
    "duplicate class with wildcard   | 'Duplicate[method1] ; Duplicate[method2]  ;Duplicate[*];'       | [Duplicate: {method1, method2, '*'}]        ",
    "wildcard method                 | 'ClassName[*]'                                                  | [ClassName: {'*'}]                          ",
    "partial wildcard                | 'ClassName[asdfg*]'                                             | [:]                                         ",
    "wildcard then method            | 'ClassName[*,asdfg]'                                            | [:]                                         ",
    "method then wildcard            | 'ClassName[asdfg,*]'                                            | [:]                                         ",
    "wildcard class and method class | 'Class[*] ; Class$2[ method2];'                                 | [Class: {'*'}, 'Class$2': {method2}]        ",
    "trailing spaces                 | 'Class[*] ; Class$2[ method2];      '                           | [Class: {'*'}, 'Class$2': {method2}]        ",
    "leading and trailing spaces     | '     Class[*] ; Class$2[ method2];      '                      | [Class: {'*'}, 'Class$2': {method2}]        ",
    "leading spaces                  | '     Class[*] ; Class$2[ method2];'                            | [Class: {'*'}, 'Class$2': {method2}]        ",
    "two classes without trailing ;  | 'c1[m1,m2];c2[m1,m2]'                                           | [c1: {m1, m2}, c2: {m1, m2}]                ",
    "methods separated from class    | 'c1;[m1,m2]'                                                    | [:]                                         ",
    "methods separated by ;          | 'c1[m1;m2]'                                                     | [:]                                         ",
    "trailing comma after class      | 'c1[m1],'                                                       | [:]                                         ",
    "trailing class without methods  | 'c1[m1];asdfg'                                                  | [:]                                         ",
    "leading class without methods   | 'asdfg;c1[m1]'                                                  | [:]                                         "
  })
  void configuration(String value, Map<String, Set<String>> expected) {
    if (value != null) {
      injectSysConfig(TRACE_METHODS, value);
    } else {
      removeSysConfig(TRACE_METHODS);
    }

    assertEquals(expected, InstrumenterConfig.get().getTraceMethods());
  }

  @Test
  void surviveVeryLongList() {
    Set<String> methodSet = new HashSet<>(asList("m1", "m2"));
    Map<String, Set<String>> expected = new HashMap<>();
    List<String> classes = new ArrayList<>();
    for (int index = 1; index <= 600; index++) {
      String className = "c" + index;
      expected.put(className, methodSet);
      classes.add(className + "[" + String.join(",", methodSet) + "]");
    }
    injectSysConfig(TRACE_METHODS, String.join(";", classes));

    assertEquals(expected, InstrumenterConfig.get().getTraceMethods());
  }
}
