import javalang
import os
import re


# Helper function to validate kebab-case
def is_kebab_case(name):
    return re.match(r'^[a-z0-9]+(-[a-z0-9.]+)*$', name) is not None

def find_java_files(directory):
    java_files = []
    for root, _, files in os.walk(directory):
        for file in files:
            if file.endswith(".java"):
                java_files.append(os.path.join(root, file))
    return java_files

def parse_java_file(file_path):
    with open(file_path, 'r', encoding='utf-8') as file:
        return javalang.parse.parse(file.read())

def validate_inheritance(tree, file_name):
    issues = []
    for path, node in tree:
        if isinstance(node, javalang.tree.ClassDeclaration):
            # Skip static inner classes
            if any(modifier for modifier in node.modifiers if modifier == "static") and '.' in node.name:
                continue

            if node.name.endswith("Decorator"):
                if not node.extends or "Decorator" not in node.extends.name:
                    issues.append(f"{file_name}: Class '{node.name}' should extend a class with 'Decorator' in its name.")
            elif node.extends and "Decorator" in node.extends.name:
                if not node.name.endswith("Decorator"):
                    issues.append(f"{file_name}: Class '{node.name}' extends a 'Decorator' class but does not end with 'Decorator'.")

            if node.name.endswith("Instrumentation") or node.name.endswith("Module"):
                # should extend something
                if not node.extends and not node.implements:
                    issues.append(f"{file_name}: Class '{node.name}' should extend or implement a class with 'Instrument' in its name.")
                # if it extends something, it should extend an instrument
                elif not ((node.extends and "Instrument" in node.extends.name) or (node.implements and any("Instrument" in impl.name for impl in node.implements))):
                    issues.append(f"{file_name}: Class '{node.name}' should extend a class with 'Instrument' in its name.")
            elif node.extends and "Instrument" in node.extends.name:
                if not node.name.endswith("Instrumentation") or not node.name.endswith("Module"):
                    issues.append(f"{file_name}: Class '{node.name}' extends a 'Instrument' class but does not end with 'Instrumentation' or 'Module'.")
            elif node.implements and any("Instrument" in impl for impl in node.implements):
                if not node.name.endswith("Instrumentation") or not node.name.endswith("Module"):
                    issues.append(f"{file_name}: Class '{node.name}' implements a 'Instrument' class but does not end with 'Instrumentation' or 'Module'.")

            if node.name.endswith("Advice"):
                # Check for methods with an @Advice annotation
                if not any(
                        isinstance(member, javalang.tree.MethodDeclaration) and
                        any(anno.name.startswith("Advice") for anno in member.annotations)
                        for member in node.body
                ):
                    issues.append(f"{file_name}: Class '{node.name}' does not have a method tagged with an @Advice annotation.")
    return issues

def validate_project_structure(base_directory):
    issues = []

    # Check kebab-case for subdirectories
    instrumentation_path = os.path.join(base_directory, "dd-java-agent", "instrumentation")
    if not os.path.exists(instrumentation_path):
        issues.append(f"Path '{instrumentation_path}' does not exist.")
        return issues

    for subdir in os.listdir(instrumentation_path):
        if os.path.isdir(os.path.join(instrumentation_path, subdir)):
            if not is_kebab_case(subdir):
                issues.append(f"Subdirectory '{subdir}' is not in kebab-case.")

    # Validate Java files in subdirectories
    for subdir in os.listdir(instrumentation_path):
        subdir_path = os.path.join(instrumentation_path, subdir)
        if os.path.isdir(subdir_path):
            java_files = find_java_files(os.path.join(subdir_path, "src", "main", "java"))
            for java_file in java_files:
                try:
                    tree = parse_java_file(java_file)
                    issues.extend(validate_inheritance(tree, java_file))
                except (javalang.parser.JavaSyntaxError, UnicodeDecodeError):
                    issues.append(f"Could not parse Java file '{java_file}'.")

    return issues

if __name__ == "__main__":
    base_directory = "."  # Change this to the root directory of your project
    problems = validate_project_structure(base_directory)

    if problems:
        print("\n".join(problems))
    else:
        print("All checks passed!")
