#!/usr/bin/env python3
"""Check that the string resources are consistent.

Three mistakes are easy to make while moving hardcoded text into resources and
none of them is obvious from reading a diff:

  - the same name defined twice (aapt fails, but only at package time)
  - a name in one language and not the other (silently falls back to the
    default locale, so the app shows English inside a Japanese screen)
  - a different set of format arguments between the two (crashes at runtime,
    in whichever locale was not exercised)
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
DEFAULT = RES / "values/strings.xml"
ARG = re.compile(r"%(\d+\$)?[a-z]")


def load(path):
    """name -> format signature, and the names in file order (to spot duplicates)."""
    root = ET.parse(path).getroot()
    names, sigs = [], {}
    for el in root:
        if el.tag not in ("string", "plurals"):
            continue
        name = el.get("name")
        names.append(name)
        if el.tag == "string":
            sigs[name] = sorted(m.group(0) for m in ARG.finditer("".join(el.itertext())))
        else:
            # 単複の項目数は言語によって違う（英語は 2、日本語は 1）。項目ごとの
            # 出現数を足すと必ず食い違うので、使っている指定子の集合で比べる。
            sigs[name] = sorted(
                {
                    m.group(0)
                    for item in el.findall("item")
                    for m in ARG.finditer("".join(item.itertext()))
                }
            )
    return names, sigs


def main():
    problems = []
    names, base = load(DEFAULT)
    dupes = {n for n in names if names.count(n) > 1}
    for n in sorted(dupes):
        problems.append(f"{DEFAULT.name}: '{n}' is defined more than once")

    for path in sorted(RES.glob("values-*/strings.xml")):
        locale = path.parent.name
        names, other = load(path)
        for n in sorted({n for n in names if names.count(n) > 1}):
            problems.append(f"{locale}: '{n}' is defined more than once")
        for n in sorted(set(base) - set(other)):
            problems.append(f"{locale}: '{n}' is missing")
        for n in sorted(set(other) - set(base)):
            problems.append(f"{locale}: '{n}' has no entry in the default locale")
        for n in sorted(set(base) & set(other)):
            if base[n] != other[n]:
                problems.append(
                    f"{locale}: '{n}' uses {other[n]} "
                    f"but the default uses {base[n]}"
                )

    for p in problems:
        print(f"  {p}", file=sys.stderr)
    if problems:
        print(f"\n{len(problems)} problem(s) in the string resources", file=sys.stderr)
        return 1
    print(f"string resources are consistent ({len(base)} entries)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
