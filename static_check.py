"""Static checks for the compile-error classes we keep hitting.

1. A method returning a primitive must never be compared against null.
   (0.5.18: findPreferenceByKeyRecursive() returns boolean, written as `!= null`.)
2. Brace / paren balance, catching a botched edit that left a block unclosed.
Deliberately does NOT try to resolve identifiers or call targets. Both attempts
produced heavy false positives: keywords like if/for/synchronized drown the
signal, javap omits inherited methods so View.getWidth() reads as missing, and a
lambda-scope check flags every English word inside the comment above the lambda.
Scope/undefined-variable errors are left to the compiler — the only check that
gets them right — and CI is cached to make that loop cheap.

Run from the MelodyLink-Bose directory:
    python static_check.py
"""
import io
import os
import re
import sys

FILES = [
    'app/src/main/java/com/melody/melodylink/hook/HookModule.java',
    'app/src/main/java/com/melody/melodylink/hook/PrefRef.java',
]

JAVA_KEYWORDS = {
    'if', 'for', 'while', 'switch', 'catch', 'synchronized', 'return', 'new',
    'super', 'this', 'assert', 'do', 'else', 'try', 'throw', 'instanceof',
    'final', 'static', 'private', 'public', 'protected', 'void', 'int', 'long',
    'boolean', 'double', 'float', 'char', 'byte', 'short', 'class', 'interface',
    'extends', 'implements', 'package', 'import', 'break', 'continue', 'case',
    'default', 'null', 'true', 'false', 'var', 'record', 'yield', 'sealed',
    'permits', 'non', 'abstract', 'native', 'synchronized', 'transient',
    'volatile', 'strictfp', 'throws', 'enum', 'else',
}

problems = []

for path in FILES:
    if not os.path.isfile(path):
        print(f'SKIP missing {path}')
        continue
    s = io.open(path, encoding='utf-8').read()

    # 1. primitive-returning methods compared to null
    for name in re.findall(
            r'(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?'
            r'(?:boolean|int|long|short|byte|char|float|double)\s+(\w+)\s*\(', s):
        for m in re.finditer(
                r'(?<![\w.])' + name + r'\s*\([^()]*\)\s*(?:!=|==)\s*null', s):
            line = s[:m.start()].count('\n') + 1
            problems.append(
                f'{path}:{line}: {name}() returns a primitive, cannot compare to null')

    # 2. balance, ignoring string/char literals and comments
    for op, cl in (('{', '}'), ('(', ')')):
        depth = 0
        lowest = 0
        in_str = in_chr = esc = False
        i = 0
        while i < len(s):
            ch = s[i]
            if esc:
                esc = False
            elif in_str:
                if ch == '\\':
                    esc = True
                elif ch == '"':
                    in_str = False
            elif in_chr:
                if ch == '\\':
                    esc = True
                elif ch == "'":
                    in_chr = False
            elif ch == '"':
                in_str = True
            elif ch == "'":
                in_chr = True
            elif s.startswith('//', i):
                j = s.find('\n', i)
                i = len(s) if j < 0 else j
                continue
            elif s.startswith('/*', i):
                j = s.find('*/', i + 2)
                i = len(s) if j < 0 else j + 2
                continue
            elif ch == op:
                depth += 1
            elif ch == cl:
                depth -= 1
                lowest = min(lowest, depth)
            i += 1
        if depth != 0 or lowest < 0:
            problems.append(
                f'{path}: unbalanced {op}{cl} (final={depth}, min={lowest})')

if problems:
    print(f'PROBLEMS FOUND: {len(problems)}')
    for p in sorted(set(problems)):
        print('  ', p)
    sys.exit(1)
print('Static checks passed.')
