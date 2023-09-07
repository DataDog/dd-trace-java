package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class MethodFilterConfigParserTest extends DDSpecification {
  def "test configuration \"#value\""() {
    setup:
    if (value) {
      injectSysConfig("dd.trace.methods", value)
    } else {
      removeSysConfig("dd.trace.methods")
    }

    expect:
    InstrumenterConfig.get().traceMethods == expected

    where:
    value                                                           | expected
    null                                                            | [:]
    " "                                                             | [:]
    "*"                                                             | [:]
    "some.package.ClassName"                                        | [:]
    "some.package.ClassName[ , ]"                                   | [:]
    "some.package.ClassName[ , method]"                             | [:]
    "some.package.ClassName[ method1,  , method2]"                  | [:]
    "some.package.Class\$Name[ method , ]"                          | ["some.package.Class\$Name": ["method"].toSet()]
    "some.package.Class\$Name[ method , , ]"                        | [:]
    "ClassName[ method1,]"                                          | ["ClassName": ["method1"].toSet()]
    "ClassName[method1 , method2]"                                  | ["ClassName": ["method1", "method2"].toSet()]
    "Class\$1[method1 ] ; Class\$2[ method2];"                      | ["Class\$1": ["method1"].toSet(), "Class\$2": ["method2"].toSet()]
    "Duplicate[method1] ; Duplicate[method2]  ;Duplicate[method3];" | ["Duplicate": ["method1", "method2", "method3"].toSet()]
    "Duplicate[method1] ; Duplicate[method2]  ;Duplicate[*];"       | ["Duplicate": ["method1", "method2", "*"].toSet()]
    "ClassName[*]"                                                  | ["ClassName": ["*"].toSet()]
    "ClassName[asdfg*]"                                             | [:]
    "ClassName[*,asdfg]"                                            | [:]
    "ClassName[asdfg,*]"                                            | [:]
    "Class[*] ; Class\$2[ method2];"                                | ["Class": ["*"].toSet(), "Class\$2": ["method2"].toSet()]
    "Class[*] ; Class\$2[ method2];      "                          | ["Class": ["*"].toSet(), "Class\$2": ["method2"].toSet()]
    "     Class[*] ; Class\$2[ method2];      "                     | ["Class": ["*"].toSet(), "Class\$2": ["method2"].toSet()]
    "     Class[*] ; Class\$2[ method2];"                           | ["Class": ["*"].toSet(), "Class\$2": ["method2"].toSet()]
    "c1[m1,m2];c2[m1,m2]"                                           | ["c1": ["m1", "m2"].toSet(), "c2": ["m1", "m2"].toSet()]
    "c1;[m1,m2]"                                                    | [:]
    "c1[m1;m2]"                                                     | [:]
    "c1[m1],"                                                       | [:]
    "c1[m1];asdfg"                                                  | [:]
    "asdfg;c1[m1]"                                                  | [:]
  }

  def "survive very long list"() {
    setup:
    def mset = ["m1", "m2"].toSet()
    Map<String, Set<String>> expected = [:]
    def methods = (1..600).collect {
      expected.put("c$it".toString(), mset)
      return "c$it[${mset.join(",")}]"
    }.join(";")
    injectSysConfig("dd.trace.methods", methods)

    expect:
    InstrumenterConfig.get().traceMethods == expected
  }
}
