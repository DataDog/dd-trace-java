# Agent Task: create_instrumentation

<!-- Workflow: java_integration, Namespace: gson, Step: create_instrumentation, Iteration: 1 -->

## Available Skills

Skills contain critical domain knowledge. Read the full skill file at `/private/tmp/dd-trace-java-gson-clean/.claude/skills/{name}/SKILL.md`

### add-apm-integrations
Write a new library instrumentation end-to-end. Use when the user ask to add a new APM integration or a library instrumentation.

---

## ⛔ MANDATORY: Read Skills Before ANY Action

**DO NOT start working until you have read the relevant skills.**

1. Read each skill name and description above
2. For EACH skill that could be relevant to your task, read the full `/private/tmp/dd-trace-java-gson-clean/.claude/skills/{name}/SKILL.md` file
3. In your first response, list which skills you read and why they're relevant
4. Only then begin your actual task

**Example:** If writing tests, read any testing-related skills first.
**Example:** If writing integrations, read integration-related skills first.

Skills contain CRITICAL patterns you cannot guess. Read them or fail.

---

# Create Java Instrumentation for gson

You are tasked with creating a complete APM instrumentation for the **gson** library in dd-trace-java.

## Task Details

- **Library**: gson
- **Minimum Version**: 1.6
- **Target System**: Tracing
- **Bootstrap Instrumentation**: no
- **Repository Root**: ~/dd-trace-java

{{#if additional_context}}
## Additional Context

Gson is a Java JSON serialization library. This is a non-HTTP instrumentation that tests decorator-only patterns (no client/server communication). Focus on instrumenting serialization/deserialization methods to track JSON processing operations. Use a lightweight decorator that captures the operation type and any relevant metadata. The span type should be 'json' and the component name should be 'gson'.
{{/if}}

## Instructions

You have access to the `add-apm-integrations` skill which contains comprehensive guidance for creating Java instrumentations. **Follow the skill's instructions exactly** - it is the authoritative source for dd-trace-java conventions.

The skill will guide you through:

1. **Reading authoritative docs** - Sync the skill with the latest docs
2. **Clarifying requirements** - Ensure all necessary information is provided
3. **Finding reference integrations** - Study similar existing instrumentations
4. **Setting up the module** - Create proper directory structure and build.gradle
5. **Writing the InstrumenterModule** - Implement with correct annotations and matchers
6. **Writing the Decorator** - Extend appropriate base decorator classes
7. **Writing Advice classes** - Follow strict rules for static methods, annotations, and span lifecycle
8. **Writing tests** - Cover instrumentation tests, muzzle directives, and latest dep tests
9. **Building and verifying** - Run all required Gradle tasks
10. **Final checklist** - Ensure all requirements are met
11. **Retrospective** - Update the skill with lessons learned

## Important Reminders

- **Always read the docs first** (Step 1 of the skill) - the skill references these as the source of truth
- **Use a reference integration** as a template - don't create from scratch
- **Follow span lifecycle exactly**: startSpan → afterStart → activateSpan (enter); onError → beforeFinish → finish → close (exit)
- **Declare ALL helper classes** in `helperClassNames()` including inner, anonymous, and enum synthetic classes
- **Run spotlessApply** before committing to fix formatting
- **Verify muzzle passes** - this catches missing helper declarations

## Success Criteria

Your output must include:

1. **success**: `true` if instrumentation is complete and tests pass
2. **instrumentation_path**: Path to the created module (e.g., `dd-java-agent/instrumentation/feign/feign-8.0/`)
3. **tests_passing**: `true` if all tests pass
4. **message**: Summary of what was created and any important notes

## Working Directory

You are working in the dd-trace-java repository at: ~/dd-trace-java

All file paths should be relative to this directory.

---

Begin by invoking the `add-apm-integrations` skill and following its comprehensive guidance.


## Expected Output Format

Output must be valid JSON matching this format:

```typescript
{
  success: boolean,  // Whether the instrumentation was successfully created
  instrumentation_path: string,  // Path to the created instrumentation module
  tests_passing?: boolean,  // Whether tests are passing
  message?: string,  // Status message or error details
}
```

**CRITICAL**: Return valid JSON at the top level. Do NOT wrap in `{"output": ...}` or other root level keys.

## Turn Limit

You have **100 turns maximum**.

**Strategy:** Do NOT exhaustively explore. Work in phases: Quick scan -> Focused analysis -> Output.
Aim to complete in ~50 turns. If you hit the limit without output, the task fails.

## Environment

Your current working directory is: `/private/tmp/dd-trace-java-gson-clean`