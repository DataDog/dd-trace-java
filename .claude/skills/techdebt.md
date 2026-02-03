# Techdebt Cleanup Skill

Analyze and refactor changes on the current branch to reduce technical debt, eliminate code duplication, and remove unnecessary complexity.

## Instructions

You are a senior engineer performing a technical debt review of the changes introduced on this branch. Your goal is to refine the code to be cleaner, simpler, and more maintainable.

### Step 1: Gather Context

Find the remote pointing to the upstream DataDog/dd-trace-java repository and get all changes:

```bash
# Find upstream remote and get diff stats
UPSTREAM=$(git remote -v | grep -E 'DataDog/dd-trace-java(.git)?\s' | head -1 | awk '{print $1}')
echo "Upstream remote: $UPSTREAM"
git diff ${UPSTREAM}/master --stat
git diff ${UPSTREAM}/master --name-status
```

Then read the full diff to understand the changes:

```bash
UPSTREAM=$(git remote -v | grep -E 'DataDog/dd-trace-java(.git)?\s' | head -1 | awk '{print $1}')
git diff ${UPSTREAM}/master
```

### Step 2: Analyze for Technical Debt

Review all changes looking for these specific issues:

#### Code Duplication
- Similar code blocks that could be extracted into shared functions/methods
- Copy-pasted logic with minor variations
- Repeated patterns that could use a common abstraction

#### Unnecessary Complexity
- Over-engineered solutions (abstractions for single use cases)
- Excessive indirection or layers
- Backward compatibility shims that aren't needed

#### Redundant Code
- Dead code paths
- Excessive null checks or error handling for impossible scenarios
- Comments that just repeat what the code does

#### Verbose Patterns
- Boilerplate that could be simplified
- Explicit types where inference works
- Unnecessarily defensive code

### Step 3: Report Findings

Present findings in this format:

```
## Technical Debt Analysis

### Summary
- Files analyzed: X
- Issues found: Y

### Issues by Category

#### 1. Code Duplication
[List each instance with file:line references and explanation]

#### 2. Unnecessary Complexity
[List each instance with file:line references and explanation]

#### 3. Redundant Code
[List each instance with file:line references and explanation]

### Recommended Refactorings
[Prioritized list of specific changes to make]
```

### Step 4: Implement Fixes (if requested)

If the user wants you to fix the issues:

1. Start with the highest-impact, lowest-risk changes
2. Make one logical change at a time
3. Explain each change briefly
4. Do NOT introduce new features or change behavior - only refactor

### Guidelines

- **Be conservative**: Only flag clear issues, not stylistic preferences
- **Preserve behavior**: Refactoring must not change functionality
- **Stay focused**: Only analyze changes on this branch, not the entire codebase
- **Be specific**: Always include file paths and line numbers
- **Prioritize**: Focus on issues that matter, skip trivial ones

### Example Duplication Fix

Before:
```java
// In FileA.java
if (config != null && config.isEnabled() && config.getValue() > 0) {
    process(config.getValue());
}

// In FileB.java
if (config != null && config.isEnabled() && config.getValue() > 0) {
    handle(config.getValue());
}
```

After:
```java
// In ConfigUtils.java
public static Optional<Integer> getValidValue(Config config) {
    if (config != null && config.isEnabled() && config.getValue() > 0) {
        return Optional.of(config.getValue());
    }
    return Optional.empty();
}

// In FileA.java
ConfigUtils.getValidValue(config).ifPresent(this::process);

// In FileB.java
ConfigUtils.getValidValue(config).ifPresent(this::handle);
```

## User Invocation

This skill can be invoked with `/techdebt` to analyze the current branch for technical debt.
