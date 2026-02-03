# Techdebt Cleanup Skill

Analyze changes on the current branch to identify and fix technical debt, code duplication, and unnecessary complexity.

## Instructions

### Step 1: Get Branch Changes

Find the upstream remote and compare against it:

```bash
# Find upstream (DataDog org repo) and show changes
UPSTREAM=$(git remote -v | grep -E 'DataDog/[^/]+(.git)?\s' | head -1 | awk '{print $1}')
if [ -z "$UPSTREAM" ]; then
  echo "No DataDog upstream found, using origin"
  UPSTREAM="origin"
fi
echo "Comparing against: $UPSTREAM/master"
git diff ${UPSTREAM}/master --stat
git diff ${UPSTREAM}/master --name-status
```

If no changes exist, inform the user and stop.

If changes exist, read the diff and the full content of modified source files (not test files) to understand context.

### Step 2: Analyze for Issues

Look for:

**Code Duplication**
- Similar code blocks that should be extracted into shared functions
- Copy-pasted logic with minor variations

**Unnecessary Complexity**
- Over-engineered solutions (abstractions used only once)
- Excessive indirection or layers
- Backward compatibility shims that aren't needed

**Redundant Code**
- Dead code paths
- Overly defensive checks for impossible scenarios

### Step 3: Report and Fix

Present a concise summary of issues found with file:line references.

Then ask the user if they want you to fix the issues. When fixing:
- Make one logical change at a time
- Do NOT change behavior, only refactor
- Skip trivial or stylistic issues
