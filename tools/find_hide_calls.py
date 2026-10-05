"""Find every call site that hides a View (setVisibility with GONE/INVISIBLE).

We need this because the host keeps re-hiding the detail page's rows after we reveal
them (0.5.27/0.5.28). Rather than poll, this locates the actual call sites so the
fix can be a targeted hook.

Method: in smali, a constant is loaded with `const/4 vX, 0x8` (or const/16, const)
immediately before the invoke. We scan for that pattern ending in
`Landroid/view/View;->setVisibility`.
"""
import glob
import io
import os
import re
import sys

ROOTS = sys.argv[1:] or ['smali1', 'smali2']

# const/4 = 0x8 (GONE), 0x4 (INVISIBLE); const/16 and const use the same tail text.
PAT = re.compile(
    r'const(?:/4|/16)?\s+(\w+),\s+(0x[0-9a-fA-F]+|\d+)\s*\n'
    r'\s*invoke-(?:virtual|static|direct|interface)\s*\{[^}]*\},\s*'
    r'Landroid/view/View;->setVisibility\(I\)V')

hits = []
for root in ROOTS:
    for path in glob.glob(os.path.join(root, '**', '*.smali'), recursive=True):
        try:
            s = io.open(path, encoding='utf-8', errors='replace').read()
        except Exception:
            continue
        if 'setVisibility' not in s:
            continue
        for m in PAT.finditer(s):
            value = int(m.group(2), 0)
            if value in (4, 8):  # INVISIBLE, GONE
                line = s[:m.start()].count('\n') + 1
                hits.append((path, line, value))

print(f'call sites that hide a view: {len(hits)}')
for path, line, value in hits:
    vis = 'GONE' if value == 8 else 'INVISIBLE'
    print(f'  {vis:9} {path}:{line}')
