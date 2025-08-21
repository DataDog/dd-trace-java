# Proposal: Standardize Configuration Type Conversion Behavior

## Summary

We propose standardizing configuration type conversion behavior across all data types in the next major release, specifically changing how invalid boolean values are handled to match the behavior of other numeric types.

## Current Problem

### Inconsistent Behavior Between Data Types

Currently, our configuration system exhibits inconsistent behavior when handling invalid values:

**Numeric Types (Integer, Float, Double, Long):**
```java
// Environment: DD_SOME_INT=invalid_number
int value = configProvider.getInteger("some.int", 42);
// Result: 42 (default value) ✅
```

**Boolean Type:**
```java
// Environment: DD_SOME_BOOL=invalid_boolean  
boolean value = configProvider.getBoolean("some.bool", true);
// Result: false (hardcoded fallback) ❌
```

### Why This Is Problematic

1. **Unexpected Behavior**: Users expect invalid values to fall back to their explicitly provided defaults
2. **Silent Failures**: Invalid boolean configurations silently become `false`, making debugging difficult
3. **API Inconsistency**: Different data types behave differently for the same logical error condition
4. **Code Complexity**: Requires custom exception handling and special-case logic

## Current Implementation (Temporary Workaround)

To maintain backward compatibility while fixing our test suite, we implemented a temporary solution:

```java
// ConfigConverter.java
static class InvalidBooleanValueException extends IllegalArgumentException {
  // Custom exception for backward compatibility
}

public static Boolean booleanValueOf(String value) {
  // ... validation logic ...
  throw new InvalidBooleanValueException("Invalid boolean value: " + value);
}

// ConfigProvider.java  
catch (ConfigConverter.InvalidBooleanValueException ex) {
  // Special case: return false instead of default for backward compatibility
  return (T) Boolean.FALSE;
}
```

This approach works but adds complexity and maintains the inconsistent behavior.

## Proposed Solution

### For Next Major Release: Standardize All Type Conversions

1. **Remove Custom Boolean Logic**: Eliminate `InvalidBooleanValueException` and special handling
2. **Consistent Exception Handling**: All invalid type conversions throw `IllegalArgumentException`
3. **Consistent Fallback Behavior**: All types fall back to user-provided defaults

### After the Change

```java
// All types will behave consistently:
int intValue = configProvider.getInteger("key", 42);        // invalid → 42
boolean boolValue = configProvider.getBoolean("key", true); // invalid → true  
float floatValue = configProvider.getFloat("key", 3.14f);   // invalid → 3.14f
```

## Implementation Plan

### Phase 1: Next Major Release
- Remove `InvalidBooleanValueException` class
- Update `ConfigConverter.booleanValueOf()` to throw `IllegalArgumentException`
- Remove special boolean handling from `ConfigProvider.get()`
- Update documentation and migration guide

### Phase 2: Communication
- Release notes highlighting the breaking change
- Update configuration documentation with examples
- Provide migration guidance for affected users

## Breaking Change Impact

### Who Is Affected
- Users who rely on invalid boolean values defaulting to `false`
- Applications that depend on the current behavior for error handling

### Migration Path
Users can adapt by:
1. **Fixing Invalid Configurations**: Update configurations to use valid boolean values
2. **Adjusting Defaults**: If they want `false` as fallback, explicitly set `false` as the default
3. **Adding Validation**: Implement application-level validation if needed

### Example Migration
```java
// Before (relied on invalid → false)
boolean feature = configProvider.getBoolean("feature.enabled", true);

// After (explicit about wanting false for invalid values)  
boolean feature = configProvider.getBoolean("feature.enabled", false);
```

## Benefits

### For Users
- **Predictable Behavior**: Invalid values consistently fall back to provided defaults
- **Better Debugging**: Clear error signals when configurations are invalid
- **Explicit Intent**: Default values clearly express intended fallback behavior

### For Maintainers
- **Simplified Codebase**: Remove custom exception types and special-case logic
- **Consistent Testing**: All type conversions can be tested with the same patterns
- **Reduced Complexity**: Fewer edge cases and branches to maintain

## Recommendation

We recommend implementing this change in the next major release (e.g., v2.0) because:

1. **Improves User Experience**: More predictable and debuggable configuration behavior
2. **Reduces Technical Debt**: Eliminates custom workarounds and special cases
3. **Aligns with Principle of Least Surprise**: Consistent behavior across all data types
4. **Proper Breaking Change Window**: Major release is the appropriate time for behavior changes

## Questions for Discussion

1. Do we agree this inconsistency is worth fixing with a breaking change?
2. Should we provide any additional migration tooling or validation?
3. Are there other configuration behaviors we should standardize at the same time?
4. What timeline works best for communicating this change to users?

---

**Next Steps**: If approved, we'll create implementation issues and begin updating documentation for the next major release cycle.
