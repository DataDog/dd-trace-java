# Problem statement
For the distributed debugging we need a cross-platform lightweight format for specifying the debugger probe points.
The format needs to be flexible enough to allow the future evolution and must be easily parsable in various environments.

# Restrictions
Due to the debugger agent is to be run in-process on various runtimes the used format should not require pulling in any
additional (potentially heavy-weight) libraries. This requirement, thanks to Java not having any widely used config 
format parsers (JSON, YAML) included, pretty much disqualifies anything but XML. And even with XML the situation is
quite hairy and using it in the java agent implementation can bring up severe compatibility issues.

# Decision
Given the requirements and restrictions we are going to define our own simple semi-binary format for definition of the
debugger probe points. In addition to the binary format we will also define its JSON counterpart in the form of a 
JSON schema such that it can be used eg. in the debugger UI to declare the points manually or for an easy (internal) 
protocol debugging.

## Custom semi-binary format
The following semi-binary format is being proposed to be used to define the debugger probe points. It is versioned to allow 
easy evolution and is strictly checked for the sequence length and delimiters allowing to quickly identify and dismiss 
a corrupted probe-point definition. 

```
PROBE_PACK:=VERSION PROBE+
VERSION:=int
PROBE:=PROBE_NAME PROBE_DEF
PROBE_NAME:=IDENTIFIER
PROBE_DEF:=NUM_POINTS PROBE_POINT+
NUM_POINTS:=int
PROBE_POINT:=PROBE_POINT_CONTROL PROBE_POINT_DEF
PROBE_POINT_CONTROL:=bitmask (64 bit){
 - 1: capture return value
 - 2: capture method arguments
 - 4: capture unhandled exceptions
 - 8: capture handled exceptions
 - 16: capture local variables
}
PROBE_POINT_DEF:=METHOD_ENTRYEXIT_POINT | LINE_POINT
METHOD_ENTRYEXIT_POINT:=int(0) TARGET_TYPE METHOD_DEF
LINE_POINT:=int(1) TARGET_TYPE LINENO
TARGET_TYPE:=TYPE_NAME
METHOD_DEF:=ANY | (RETURN_TYPE METHOD_NAME METHOD_ARGS)
METHOD_ARGS:=ANY | (int (ARG_DEF (ARG_DEF)*)?)
RETURN_TYPE:=TYPE_NAME
METHOD_NAME:=ANY | IDENTIFIER
TYPE_NAME:=ANY | (PKG_NAME(\.PKG_NAME)*\.)?SIMPLE_TYPE_NAME
SIMPLE_TYPE_NAME:=ANY | IDENTIFIER
IDENTIFIER:=STRING//[a-zA-Z_\$][a-zA-Z0-9_\$]+// // STRING matching the pattern
ARG_DEF:=TYPE_NAME IDENTIFIER
LINENO=int
ANY:=int(0)
STRING:=int char+ // string is encoded as its length followed by the bytes representation
```

### Expected performance
The format maps well to a simple sequential parser with no backtracking necessary so there are no expected performance
bottlenecks.

### Interoperability
The format is not tied to any particular language, runtime or operating system. It is quite easy to convert to/from
JSON, YAML and XML (or any other configuration languages) if necessary.

## JSON format
This is defined mostly for the convenience of manually defining the probe points in a human readable (and writable) way.

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "http://datadogh1.com/debugger/probe.json",
  "title": "Probe Point",
  "description": "A distributed debugger probe point definition",
  "type": "object",
  "properties": {
    "version": {
      "description": "The probe definition format version number",
      "type": "integer",
      "const": 0
    },
    "name": {
      "description": "The probe name",
      "type": "string"
    },
    "points": {
      "description": "The probe points",
      "type": "array",
      "items": { "$ref": "#/definitions/probePoint" },
      "minItems": 1,
      "uniqueItems": true
    }
  },
  "definitions": {
    "methodArgument": {
      "description": "Method argument name and type",
      "type": "object",
      "properties": {
        "name": {
          "type": "string"
        },
        "type": {
          "type": "string",
          "pattern": "\\*|[a-zA-Z_\\$][a-zA-Z0-9_\\$]*"
        }
      },
      "required": ["type"]
    },
    "probePoint": {
      "description": "The probe point definition",
      "oneOf": [
        {
          "description": "Method entry/exit probe point definition",
          "type": "object",
          "required": ["kind"],
          "properties": {
            "kind": {
              "type": "string",
              "const": "entry_exit"
            },
            "capture": {
              "description": "The probe point capture control",
              "type": "array",
              "items": {
                "type": "string",
                "enum": ["arguments", "return", "handled", "unhandled"]
              },
              "minItems": 0,
              "uniqueItems": true
            },
            "targetType": {
              "description": "The probe point target type",
              "type": "string",
              "pattern": "\\*|[a-zA-Z_\\$][a-zA-Z0-9_\\$]*"
            },
            "returnType": {
              "description": "The probe point target method return type",
              "type": "string",
              "pattern": "\\*|[a-zA-Z_\\$][a-zA-Z0-9_\\$]*"
            },
            "methodName": {
              "description": "The probe point target method name",
              "type": "string",
              "pattern": "\\*|[a-zA-Z_\\$][a-zA-Z0-9_\\$]*"
            },
            "arguments": {
              "description": "The probe point target method arguments in exact order",
              "type": "array",
              "items": { "$ref": "#/definitions/methodArgument" },
              "minItems": 0,
              "uniqueItems": true
            }
          }
        },
        {
          "description": "Code line probe point definition",
          "type": "object",
          "required": ["kind", "targetType", "line"],
          "properties": {
            "kind": {
                "type": "string",
                "const": "line"
            },
            "capture": {
              "description": "The probe point capture control",
              "type": "array",
              "items": {
                "type": "string",
                "enum": ["arguments", "locals"]
              },
              "minItems": 0,
              "uniqueItems": true
            },
            "targetType": {
              "description": "The probe point target type",
              "type": "string",
              "pattern": "\\*|[a-zA-Z_\\$][a-zA-Z0-9_\\$]*"
            },
            "line": {
              "description": "The probe point target line number",
              "type": "integer"
            }
          }
        }
      ]
    }
  },
  "required": [ "version", "name", "points" ]
}
```
 

### Examples
In the following sections several probes are defined in the binary as well as JSON format.
The binary format examples are using numbers prefixed by \ to represent the actual binary
numbers.

1. matching exact method and arguments and capturing both return value and the arg values

    `\0\6probe1\1\0\3\18com.datadog.Target\7boolean\7method1\3\3int\1x\4long\1y\16java.lang.String\1z`
    ```json
    {
      "version": 0,
      "name": "probe1",
      "points": [
        {
          "kind": "entry_exit",
          "capture": [
            "return", "arguments"
          ],
          "targetType": "com.datadog.Target",
          "returnType": "boolean",
          "methodName": "method1",
          "arguments": [
            {"name": "x", "type": "int"},
            {"name": "y", "type": "long"},
            {"name": "z", "type": "java.lang.String"}
          ]
        }
      ]   
    }
    ```

2. matching all methods of a type and capturing the arg values only

    `\0\6probe1\1\0\2\18com.datadog.Target\0`
    
    ```json
        {
          "version": 0,
          "name": "probe1",
          "points": [
            {
              "kind": "entry_exit",
              "capture": [
                "arguments"
              ],
              "type": "com.datadog.Target"
            }
          ]
        }
    ```

3. matching type methods with given signature and return type and capture the return value and unhandled exceptions

    `\0\6probe1\1\0\5\18com.datadog.Target\7boolean\0\3\3int\1x\4long\1y\16java.lang.String\1z`
    
    ```json
        {
          "version": 0,
          "name": "probe1",
          "points": [
            {
              "kind": "entry_exit",
              "capture": [
                "return", "unhandled"
              ],
              "targetType": "com.datadog.Target",
              "returnType": "boolean",
              "arguments": [
                {"name": "x", "type": "int"},
                {"name": "y", "type": "long"},
                {"name": "z", "type": "java.lang.String"}
              ]
            }
          ]   
        }
    ```

4. matching type methods with given signature and any return type and capture the arguments and handled exceptions

    `\0\6probe1\1\0\10\18com.datadog.Target\0\0\3\3int\1x\4long\1y\16java.lang.String\1z`

    ```json
            {
              "version": 0,
              "name": "probe1",
              "points": [
                {
                  "kind": "entry_exit",
                  "capture": [
                    "return", "handled"
                  ],
                  "targetType": "com.datadog.Target",
                  "arguments": {
                    "x": "int",
                    "y": "long",
                    "z": "java.lang.String"
                  }
                }
              ]   
            }
    ```
5. matching line number (123) and capturing the local variables

    `\0\6probe1\1\1\16\18com.datadog.Target\123`
    
    ```json
            {
              "version": 0,
              "name": "probe1",
              "points": [
                {
                  "kind": "line",
                  "capture": [
                    "locals"
                  ],
                  "targetType": "com.datadog.Target",
                  "line": 123
                }
              ]   
            }
    ```

6. multiple points defined - example 4 and 5 combined

    `\0\6probe1\2\0\10\18com.datadog.Target\0\0\3\3int\1x\4long\1y\16java.lang.String\1z\1\\1\16\18com.datadog.Target\123`

    ```json
            {
              "version": 0,
              "name": "probe1",
              "points": [
                {
                  "kind": "entry_exit",
                  "capture": [
                    "return", "handled"
                  ],
                  "targetType": "com.datadog.Target",
                  "arguments": {
                    "x": "int",
                    "y": "long",
                    "z": "java.lang.String"
                  }
                },
                {
                  "kind": "line",
                  "capture": [
                    "locals"
                  ],
                  "targetType": "com.datadog.Target",
                  "line": 123
                }
              ]   
            }
    ```
  
## Future challenges
For the future work we are expecting to have a form of a simple expression language allowing filtering and preprocessing
the collected data as well as querying additional sources (public getters etc.) for more data. Such expression language
is not within the scope of the probe definition format work and will be addressed as a separate task.
