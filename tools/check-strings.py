#!/usr/bin/env python3
"""Check that the string resources are consistent.

Three mistakes are easy to make while moving hardcoded text into resources and
none of them is obvious from reading a diff:

  - the same name defined twice (aapt fails, but only at package time)
  - a name in one language and not the other (silently falls back to the
    default locale, so the app shows English inside a Japanese screen)
  - a different set of format arguments between the two (crashes at runtime,
    in whichever locale was not exercised)

It also catches the two things aapt only complains about at package time — an
unescaped apostrophe, and a bare % in a string it will try to read as a format
specifier — and reports names nothing refers to. Those are harmless to ship but not
harmless to keep: a second entry meaning "Cancel" is one a later edit can
translate differently, or reach for instead of the one actually wired up.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
DEFAULT = RES / "values/strings.xml"
ARG = re.compile(r"%(\d+\$)?[a-z]")


APOSTROPHE = re.compile(r"(?<!\\)'")
BARE_PERCENT = re.compile(r"%(?![0-9]+\$[sd]|[sd]|%)")


def raw_bodies(path):
    """name -> the text exactly as written, escapes and all."""
    out = {}
    for m in re.finditer(
        r'<(string|plurals) name="([^"]+)"([^>]*)>(.*?)</\1>', path.read_text(), re.S
    ):
        out[m.group(2)] = (m.group(3), m.group(4))
    return out


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

    for path in [DEFAULT] + sorted(RES.glob("values-*/strings.xml")):
        where = path.parent.name
        for name, (attrs, body) in raw_bodies(path).items():
            # CDATA の中はそのまま出力されるので、アポストロフィの規則は掛からない。
            outside = re.sub(r"<!\[CDATA\[.*?\]\]>", "", body, flags=re.S)
            if APOSTROPHE.search(outside):
                problems.append(f"{where}: \'{name}\' has an unescaped apostrophe")
            if BARE_PERCENT.search(outside) and 'formatted="false"' not in attrs:
                problems.append(
                    f"{where}: \'{name}\' has a bare % "
                    '(escape it, or add formatted="false")'
                )

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

    used = set()
    src = RES.parent.parent.parent.parent
    for f in list((src / "app/src/main/java").rglob("*.kt")):
        used |= set(re.findall(r"R\.(?:string|plurals)\.([A-Za-z0-9_]+)", f.read_text()))
    for f in list(RES.rglob("*.xml")) + [src / "app/src/main/AndroidManifest.xml"]:
        used |= set(re.findall(r"@(?:string|plurals)/([A-Za-z0-9_]+)", f.read_text()))
    # 動的参照 (getIdentifier) を使っていないので、静的に見えない参照は無い。
    for n in sorted(set(base) - used - {"app_name"}):
        problems.append(f"{DEFAULT.name}: '{n}' is not referenced anywhere")

    for p in problems:
        print(f"  {p}", file=sys.stderr)
    if problems:
        print(f"\n{len(problems)} problem(s) in the string resources", file=sys.stderr)
        return 1
    print(f"string resources are consistent ({len(base)} entries)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
