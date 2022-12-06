# Debugger Probes
## Version: 0
## Probe definition
- **probeId**
  - universally unique ID assigned by backend
- **language**
  - the target application programming language
  - java | python | go | c# | ...
- **version**
  - the debugger version; used to properly maintain backward compatibility
- **orgId**
  - the organization ID
- **appId**
  - the application ID
- **created**
  - creation timestamp
- **updated**
  - update timestamp
- **active**
  - whether the probe is active or not
- **tags**
  - list of tags associated with the probe
- **where**
  - **typeName**
    - class/type name
  - **methodName**
    - method/function name
  - _[optional]_ **sourceFile**
    - in some languages source file might need to be specified
  - _[optional]_ **signature**
    - method/function signature
    - may be omitted or not applicable for certain languages
  - _[optional]_ **lines**
    - an array of source code lines
    - scoped and valid only in the specified method/function
    - supports single value (eg. `1`) and range (eg. `1-3`)
    - for line-range the probe data will be captured before the range starts and after the range ends
- _[optional]_**when** - when no other conditions are specified it defaults to ‘**always**’

_Note: this part will get replaced once we have debugger expressions figured out_
  - _[optional]_ **durationThreshold**
    - only if the execution took longer than X ns
    - **scope** the duration computation scopes - one of:
        - _[default]_ **method** - duration threshold is computed for method execution
        - **line** - duration threshold is computed for line/line range execution
    - **value** the duration in nanoseconds
- _[optional]_ **tags**
  - a list of tag values
    - a tag is either `key` or `key=value`
- _[optional]_ **capture** - options related to capturing values
  - _[optional]_ **maxReferenceDepth** converter max reference depth, default is 1
  - _[optional]_ **maxCollectionSize** arrays/collections max elements collected, default is 100
  - _[optional]_ **maxLength** max length of toString representation, after that an ellipsis is added, default is 255
- _[optional]_ **sampling** - options related to capture sampling
  - _[optional]_ **snapshotsPerSecond** maximum rate of snapshot captured and sent per second, default is 1/s

### Examples
##### Fig. 1: Simple method entry/exit probe
```json
{
  "included": [
    {
      "type": "probes",
      "id": "aa5f3922-35f1-40a1-9da9-25e4cdf22073",
      "attributes": {
        "created": "2021-03-19T17:00:53.044955+00:00",
        "active": false,
        "language": "java",
        "where": {
          "typeName": "org.thymeleaf.standard.expression.SimpleExpression",
          "sourceFile": null,
          "methodName": "executeSimple",
          "lines": null,
          "signature": "(IExpressionContext, SimpleExpression, IStandardVariableExpressionEvaluator, StandardExpressionExecutionContext)"
        },
        "when": null,
        "capture": null,
        "sampling": null,
        "tags": [],
        "userId": "342867d2-2cf1-11ea-ad29-07d1e9ed1dda"
      }
    }
  ],
  "data": {
    "type": "debugger-configurations",
    "id": "petclinic-benchmark",
    "attributes": {
      "service_name": "petclinic-benchmark",
      "allow_list": null
    },
    "relationships": {
      "probes": {
        "data": [
          {
            "type": "probes",
            "id": "aa5f3922-35f1-40a1-9da9-25e4cdf22073"
          }
        ]
      }
    }
  }
}
```

##### Fig. 2: file/line number capturing probe with capture & sampling settings

```json
{
  "included": [
    {
      "type": "probes",
      "id": "aa5f3922-35f1-40a1-9da9-25e4cdf22073",
      "attributes": {
        "created": "2021-03-19T17:00:53.044955+00:00",
        "active": false,
        "language": "java",
        "where": {
          "typeName": null,
          "sourceFile": "VetController.java",
          "methodName": null,
          "lines": ["345"],
          "signature": null
        },
        "when": null,
        "capture": {
          "maxReferenceDepth": 1,
          "maxCollectionSize": 100,
          "maxLength": 255
        },
        "sampling": {
          "snapshotsPerSecond": 1.0
        },
        "tags": [],
        "userId": "342867d2-2cf1-11ea-ad29-07d1e9ed1dda"
      }
    }
  ],
  "data": {
    "type": "debugger-configurations",
    "id": "petclinic-benchmark",
    "attributes": {
      "service_name": "petclinic-benchmark",
      "allow_list": null
    },
    "relationships": {
      "probes": {
        "data": [
          {
            "type": "probes",
            "id": "aa5f3922-35f1-40a1-9da9-25e4cdf22073"
          }
        ]
      }
    }
  }
}
```

##### Fig. 3: Line-range capturing probe
```json
{
  "included": [
    {
      "type": "probes",
      "id": "aa5f3922-35f1-40a1-9da9-25e4cdf22073",
      "attributes": {
        "created": "2021-03-19T17:00:53.044955+00:00",
        "active": false,
        "language": "java",
        "where": {
          "typeName": "org.thymeleaf.standard.expression.SimpleExpression",
          "sourceFile": null,
          "methodName": "executeSimple",
          "lines": ["239-280"],
          "signature": null
        },
        "when": null,
        "capture": null,
        "sampling": null,
        "tags": [],
        "userId": "342867d2-2cf1-11ea-ad29-07d1e9ed1dda"
      }
    }
  ],
  "data": {
    "type": "debugger-configurations",
    "id": "petclinic-benchmark",
    "attributes": {
      "service_name": "petclinic-benchmark",
      "allow_list": null
    },
    "relationships": {
      "probes": {
        "data": [
          {
            "type": "probes",
            "id": "aa5f3922-35f1-40a1-9da9-25e4cdf22073"
          }
        ]
      }
    }
  }
}
```

##### Fig. 4: Multi line capturing probe & when settings

```json
{
  "included": [
    {
      "type": "probes",
      "id": "aa5f3922-35f1-40a1-9da9-25e4cdf22073",
      "attributes": {
        "created": "2021-03-19T17:00:53.044955+00:00",
        "active": false,
        "language": "java",
        "where": {
          "typeName": "org.thymeleaf.standard.expression.SimpleExpression",
          "sourceFile": null,
          "methodName": "executeSimple",
          "lines": [
            "239",
            "263"
          ],
          "signature": "(IExpressionContext, SimpleExpression, IStandardVariableExpressionEvaluator, StandardExpressionExecutionContext)"
        },
        "when": {
          "durationThreshold": {
            "scope": "LINE",
            "value": 1000000000
          }
        },
        "capture": null,
        "sampling": null,
        "tags": [],
        "userId": "342867d2-2cf1-11ea-ad29-07d1e9ed1dda"
      }
    }
  ],
  "data": {
    "type": "debugger-configurations",
    "id": "petclinic-benchmark",
    "attributes": {
      "service_name": "petclinic-benchmark",
      "allow_list": null
    },
    "relationships": {
      "probes": {
        "data": [
          {
            "type": "probes",
            "id": "aa5f3922-35f1-40a1-9da9-25e4cdf22073"
          }
        ]
      }
    }
  }
}
```

## Data collection
### Structures
#### Snapshot
Snapshot is defined by:
- **language**
  - the target application programming language
  - java | python | go | c# | ...
- **version**
  - the debugger version; used to properly maintain backward compatibility
- **timestamp**
  - Epoch time when the snapshot has started collecting
- **duration**
  - the time elapsed between the initialization and commit of the snapshot in nanoseconds
- **probe**
  - **id** a unique identifier connecting the data to the defining probe
  - **location** where the probe is defined inside source code
    - **type** class/type name
    - **method** method name
    - **file** in some languages source file might need to be specified
    - **lines** an array of source code lines
- **thread**
  - **id**
  - **name**
- **stack**
  - array of `CapturedStackFrame` values ordered top-down (eg. first element is the top of the stack)
- **captures**
  - **entry** `CapturedContext` at entry of the method
  - **lines** `CapturedContext` at specific source lines
  - **caughtExceptions** `CapturedContext` data about caught exceptions during method execution
  - **return** `CapturedContext` at exit of the method

#### CapturedStackFrame
- [optional] **fileName** - the originating file name
- **functionName** - platform specific textual representation of the method/function
- **lineNumber** - the line number or -1 if not available

#### CapturedContext
- **arguments**
  - a map of `CapturedValue` instances corresponding to the method/function arguments
- _[optional]_ **locals**
  - a map of `CapturedValue` instances corresponding to valid local variables
  - return value is encoded with the special name `@return`
- _[optional]_ **throwable**
  - a `CapturedThrowable` instance representing the thrown uncaught exception
- _[optional]_ **fields**
  - a map of `CapturedValue`instances corresponding to the fields of the class instance

#### CapturedValue
- **type**
  - the value type (if available)
- **value**
  - textual representation of the value

#### CapturedThrowable
- **type**
  - the exception type
- **message**
  - the exception message
- [optional] **stacktrace**
    - array of `CapturedStackFrame` values ordered top-down (eg. first element is the top of the stack)

### Probe snapshot collection
#### Method probes
On method/function entry a new probe snapshot is open if permitted by the rate limiter.
Then the corresponding 'CapturedContext' for 'entry' capture point is written.
Once the method/function exits (either by return or exception) the 'when' part of the probe definition is consulted
to apply an additional filter before finalizing the probe snapshot and marking it for upload.
If the probe snapshot does not pass through the additional filter the collected data is thrown away.

#### Line probes
Before executing the specified line, a new probe snapshot is open and a 'CapturedContext' is collected.
Snapshots are immediately committed if passing the rate limited sampling.

For the observability purposes the number of probe snapshots dismissed either due to the limiter or the filter should be
recorded using the platform specific means (eg. JFR for Java, logging etc.)

### Examples

##### Fig. 5: Snapshot Example
```json
{
  "snapshot": {
    "id": "26e0e0ac-66d9-438b-abc6-7a7f9321152a",
    "version": 2,
    "timestamp": 1621257965011,
    "stack": [
      {
        "fileName": "VetController.java",
        "function": "org.springframework.samples.petclinic.vet.VetController.showVetList(VetController.java:123)",
        "lineNumber": 123
      },
      {
        "fileName": "NativeMethodAccessorImpl.java",
        "function": "sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)",
        "lineNumber": -2
      },
      {
        "fileName": "NativeMethodAccessorImpl.java",
        "function": "sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)",
        "lineNumber": 62
      },
      {
        "fileName": "DelegatingMethodAccessorImpl.java",
        "function": "sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)",
        "lineNumber": 43
      },
      {
        "fileName": "Method.java",
        "function": "java.lang.reflect.Method.invoke(Method.java:498)",
        "lineNumber": 498
      },
      {
        "fileName": "Thread.java",
        "function": "java.lang.Thread.run(Thread.java:748)",
        "lineNumber": 748
      }
    ],
    "captures": {
      "entry": {
        "arguments": {
          "model": {
            "type": "java.util.Map",
            "value": "{}"
          }
        },
        "locals": null,
        "throwable": null,
        "fields": {
          "result": {
            "type": "int",
            "value": "0"
          },
          "garbageStart": {
            "type": "long",
            "value": "0"
          },
          "executor": {
            "type": "java.util.concurrent.ExecutorService",
            "value": "FinalizableDelegatedExecutorService()"
          },
          "vets": {
            "type": "org.springframework.samples.petclinic.vet.VetRepository",
            "value": "org.springframework.data.jpa.repository.support.SimpleJpaRepository@141de041"
          },
          "garbage": {
            "type": "java.util.List",
            "value": "[]"
          },
          "counter": {
            "type": "int",
            "value": "0"
          },
          "syntheticLiveSet": {
            "type": "java.util.concurrent.atomic.AtomicReference",
            "value": "null"
          }
        }
      },
      "lines": null,
      "caughtExceptions": null,
      "return": {
        "arguments": {
          "model": {
            "type": "java.util.Map",
            "value": "{vets=Vets(vets=[org.springframework.samples.petclinic.vet.Vet@44bcc2bc, org.springframework.samples.petclinic.vet.Vet@663542a9, org.springframework.samples.petclinic.vet.Vet@2f1889a2, org.springframework.samples.petclinic.vet.Vet@32e1bcce, org.springframework....)}"
          }
        },
        "locals": {
          "traceId": {
            "type": "java.lang.String",
            "value": "7860180492300679968"
          },
          "spanId": {
            "type": "java.lang.String",
            "value": "3601437217571584056"
          },
          "vets": {
            "type": "org.springframework.samples.petclinic.vet.Vets",
            "value": "Vets(vets=[Vet(specialties=[]), Vet(specialties=[radiology]), Vet(specialties=[surgery, dentistry]), Vet(specialties=[surgery]), Vet(specialties=[radiology]), Vet(specialties=[]), Vet(specialties=[]), Vet(specialties=[]), Vet(specialties=[]), Vet(specialt...)"
          },
          "@return": {
            "type": "java.lang.String",
            "value": "vets/vetList"
          }
        },
        "throwable": null,
        "fields": {
          "result": {
            "type": "int",
            "value": "0"
          },
          "garbageStart": {
            "type": "long",
            "value": "0"
          },
          "executor": {
            "type": "java.util.concurrent.ExecutorService",
            "value": "FinalizableDelegatedExecutorService()"
          },
          "vets": {
            "type": "org.springframework.samples.petclinic.vet.VetRepository",
            "value": "org.springframework.data.jpa.repository.support.SimpleJpaRepository@141de041"
          },
          "garbage": {
            "type": "java.util.List",
            "value": "[]"
          },
          "counter": {
            "type": "int",
            "value": "0"
          },
          "syntheticLiveSet": {
            "type": "java.util.concurrent.atomic.AtomicReference",
            "value": "null"
          }
        }
      }
    },
    "probe": {
      "id": "d8292a99-0073-43f2-856c-e527a0ca6996",
      "location": {
        "type": "VetController",
        "method": "showVetList",
        "file": null,
        "lines": null
      }
    },
    "language": "java",
    "traceId": null,
    "spanId": null
  }
}
```
