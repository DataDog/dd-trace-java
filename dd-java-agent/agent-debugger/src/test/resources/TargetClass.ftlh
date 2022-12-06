public class ${className} {
  public ${methodModifiers} ${returnType} ${methodName}(${argumentType} arg1, int switcher) {
    ${argumentType} ${varName} = arg1;
    { // ${lineRangeStart}
      ${scopedVarType} ${scopedVarName} = ${scopedVarValue};
      ${varName} = ${argumentValue}; // ${lineProbe}
    }
    try {
      if (switcher == 1) {
        throw new java.lang.RuntimeException();
      } else if (switcher == 2) {
        throw new java.lang.IllegalArgumentException();
      }
      arg1 = ${argumentValue}; // ${lineRangeEnd}
    } catch (java.lang.IllegalArgumentException e) {
      switcher = -1;
    }
    <#if returnType != "void">return ${returnValue};</#if>
  }
}
