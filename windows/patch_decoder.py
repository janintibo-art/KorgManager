"""Adapte le code C du décodeur es12wav pour la compilation Windows (MinGW)."""
import glob
import re
import sys

for f in glob.glob("*.c") + glob.glob("*.h"):
    src = open(f, encoding="utf-8", errors="ignore").read()
    orig = src
    src = src.replace(
        "#include <unistd.h>",
        "#ifdef _WIN32\n#include <direct.h>\n#include <io.h>\n"
        "#else\n#include <unistd.h>\n#endif",
    )
    # mkdir(chemin, 0777) -> mkdir(chemin) : sous Windows mkdir n'a qu'un argument
    src = re.sub(r"mkdir\s*\(([^;]*?),\s*0[0-7]+\s*\)", r"mkdir(\1)", src)
    if src != orig:
        open(f, "w", encoding="utf-8").write(src)
        print("patche :", f)
print("patch termine")
