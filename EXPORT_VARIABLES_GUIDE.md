# Exporting DD Configuration Variables

This document explains how to export random DD configuration variables without running a command, allowing you to use them in your own scripts.

## Two Approaches Available

### Approach 1: `fuzz-export-vars.sh` (Recommended)

A standalone script that generates export statements you can eval in your shell.

#### Usage

```bash
# Export random variables
eval "$(./fuzz-export-vars.sh)"

# Then run your application
java -javaagent:dd-java-agent.jar -jar myapp.jar
```

#### Advantages
- Simple and straightforward
- Works in any script
- No need to source anything
- Clean output

#### Control Number of Parameters

```bash
# Export 5 random parameters
FUZZ_MAX_PARAMS=5 eval "$(./fuzz-export-vars.sh)"
```

### Approach 2: `fuzz-configs.sh` in Export-Only Mode

Use the main fuzzer script in export-only mode.

#### Usage

```bash
# Set export-only mode
export FUZZ_EXPORT_ONLY=true

# Source the fuzzer (doesn't run commands)
source ./fuzz-configs.sh 1 ""
```

#### Advantages
- Uses the same script as full fuzzing
- Includes logging
- More detailed output

## Examples

### Example 1: Basic Export and Run

```bash
#!/bin/bash

# Export random configurations
eval "$(./fuzz-export-vars.sh)"

# Run your application
java -javaagent:dd-java-agent.jar -jar myapp.jar
```

### Example 2: Multiple Test Runs with Different Configs

```bash
#!/bin/bash

for i in {1..10}; do
    echo "Test run $i"
    
    # Clear previous DD variables
    unset $(env | grep '^DD_' | cut -d'=' -f1)
    
    # Export new random configuration
    eval "$(./fuzz-export-vars.sh)"
    
    # Run your application
    java -jar myapp.jar
    
    sleep 2
done
```

### Example 3: Export Specific Number of Parameters

```bash
#!/bin/bash

# Export only 3 random parameters
FUZZ_MAX_PARAMS=3 eval "$(./fuzz-export-vars.sh)"

echo "Running with minimal configuration:"
env | grep '^DD_'

java -jar myapp.jar
```

### Example 4: Capture Variables for Later Use

```bash
#!/bin/bash

# Generate and save export statements
./fuzz-export-vars.sh 2>/dev/null > /tmp/dd-config.sh

# Review the configuration
cat /tmp/dd-config.sh

# Apply it when ready
source /tmp/dd-config.sh

# Run your application
java -jar myapp.jar
```

### Example 5: Use in CI/CD Pipeline

```bash
#!/bin/bash
# .gitlab-ci.yml or similar

test_with_random_config:
  script:
    - eval "$(./fuzz-export-vars.sh)"
    - echo "Testing with configuration:"
    - env | grep '^DD_'
    - mvn clean test
```

## Clearing Variables

To clear all DD_ environment variables:

```bash
# Unset all DD_ variables
unset $(env | grep '^DD_' | cut -d'=' -f1)

# Verify
env | grep '^DD_'  # Should return nothing
```

## Comparing Approaches

| Feature | fuzz-export-vars.sh | fuzz-configs.sh (export-only) |
|---------|---------------------|-------------------------------|
| Simplicity | ⭐⭐⭐ Very simple | ⭐⭐ Moderate |
| Logging | ⭐ To stderr only | ⭐⭐⭐ Full logging |
| File size | ⭐⭐⭐ Lightweight | ⭐ Larger |
| Dependencies | Just jq | jq + full script |
| Use case | Quick exports | Integrated testing |

## Troubleshooting

### Variables Not Exported

```bash
# Wrong - runs in subshell, variables don't persist
$(./fuzz-export-vars.sh)

# Correct - use eval
eval "$(./fuzz-export-vars.sh)"
```

### Too Many/Few Variables

```bash
# Control the number
FUZZ_MAX_PARAMS=5 eval "$(./fuzz-export-vars.sh)"
```

### Need to See What's Being Exported

```bash
# The script outputs info to stderr, so you'll see it
eval "$(./fuzz-export-vars.sh)"

# Or capture the export statements first
./fuzz-export-vars.sh 2>&1 | tee /tmp/config.log | tail -n +2 | source /dev/stdin
```

## Integration Patterns

### Pattern 1: Test Suite Integration

```bash
#!/bin/bash
# run-test-suite.sh

for test in tests/*.sh; do
    echo "Running $test with random config..."
    
    # Fresh configuration for each test
    unset $(env | grep '^DD_' | cut -d'=' -f1)
    eval "$(./fuzz-export-vars.sh)"
    
    bash "$test"
done
```

### Pattern 2: Docker Container Testing

```bash
#!/bin/bash

# Generate configuration
eval "$(./fuzz-export-vars.sh)"

# Pass to Docker container
docker run \
    $(env | grep '^DD_' | sed 's/^/-e /') \
    my-app:latest
```

### Pattern 3: Configuration Files

```bash
#!/bin/bash

# Generate Java system properties file
./fuzz-export-vars.sh 2>/dev/null | \
    sed 's/export //' | \
    sed "s/'//g" | \
    sed 's/^/-D/' | \
    sed 's/=/=/' > /tmp/java-opts.txt

# Use with Java
java @/tmp/java-opts.txt -jar myapp.jar
```

## Best Practices

1. **Clear Between Runs**: Always unset previous DD_ variables before exporting new ones
2. **Log Configuration**: Save the exported configuration for reproducibility
3. **Reasonable Limits**: Use FUZZ_MAX_PARAMS to avoid overwhelming configurations
4. **Test Isolation**: Each test should use a fresh set of variables
5. **Document**: Save configurations that expose bugs for later reproduction

## Environment Variables

### For `fuzz-export-vars.sh`

- `FUZZ_MAX_PARAMS` - Maximum number of parameters to export (default: 10)

### For `fuzz-configs.sh` (export-only mode)

- `FUZZ_EXPORT_ONLY` - Set to "true" to enable export-only mode
- Other variables from main fuzzer still apply

## See Also

- `FUZZ_README.md` - Full fuzzer documentation
- `FUZZ_QUICKSTART.md` - Quick start guide
- `example-use-export-vars.sh` - Working example script

## Summary

**Quick Export:**
```bash
eval "$(./fuzz-export-vars.sh)"
java -jar myapp.jar
```

**With Options:**
```bash
FUZZ_MAX_PARAMS=5 eval "$(./fuzz-export-vars.sh)"
```

**In a Loop:**
```bash
for i in {1..10}; do
    unset $(env | grep '^DD_' | cut -d'=' -f1)
    eval "$(./fuzz-export-vars.sh)"
    java -jar myapp.jar
done
```

That's it! You're ready to use random DD configurations in your scripts.

