import re
import sys
from pathlib import Path

PAGES = [
    ("", "Home", ["Why ZDCache?", "Quick start", "Performance", "When should you use ZDCache?", "Installation"]),
    ("search-specifications", "Search specifications",
     ["Search specifications", "Basic/default specification", "Identity, specification and update rules"]),
    ("configuration", "Configuration", ["Choosing a configuration", "Locking and atomicity"]),
    ("benchmarks", "Benchmarks", ["Benchmark"]),
    ("limitations", "Limitations", ["Current limitations and follow-up work"]),
    ("development", "Development", ["Requirements and build", "Project map"]),
]

HEADING = re.compile(r"^(#{1,6}) +(.+?) *$")
ANCHOR_LINK = re.compile(r"\]\(#([^)]+)\)")
FILE_LINK = re.compile(r"\]\(([^)#:]+)\)")


class SiteError(Exception):
    pass


def anchor(title):
    text = title.replace("`", "").lower()
    return re.sub(r"[^\w\- ]", "", text).replace(" ", "-")


def headings(lines):
    fenced = False
    for number, line in enumerate(lines):
        if line.startswith("```"):
            fenced = not fenced
            continue
        match = None if fenced else HEADING.match(line)
        if match:
            yield number, len(match.group(1)), match.group(2)


def split(readme):
    lines = readme.split("\n")
    found = list(headings(lines))
    first = next((number for number, level, _ in found if level == 1), None)
    if first is None:
        raise SiteError("README.md has no '# ' heading")
    starts = [(number, title) for number, level, title in found if level == 2]
    intro_end = starts[0][0] if starts else len(lines)
    sections = [("", "\n".join(lines[first:intro_end]))]
    for index, (number, title) in enumerate(starts):
        end = starts[index + 1][0] if index + 1 < len(starts) else len(lines)
        sections.append((title, "\n".join(lines[number:end])))
    return sections


def relative(source, target):
    up = "" if source == "" else "../"
    return up + (target + "/" if target else "") or "./"


def build(readme, repository):
    page_of = {title: slug for slug, _, titles in PAGES for title in titles}
    texts = {}
    for title, text in split(readme):
        if title and title not in page_of:
            raise SiteError("README.md heading '## " + title + "' is on no page; add it to PAGES in tools/site/pages.py")
        texts[title] = text
    missing = [title for title in page_of if title not in texts]
    if missing:
        raise SiteError("PAGES lists headings that README.md does not have: " + ", ".join(missing))
    contents = {slug: ([texts[""]] if not slug else []) + [texts[title] for title in titles] for slug, _, titles in PAGES}

    anchors = {}
    for slug, body in contents.items():
        for _, _, title in headings("\n\n".join(body).split("\n")):
            anchors.setdefault(anchor(title), slug)

    pages = {}
    for slug, title, _ in PAGES:
        body = "\n\n".join(contents[slug]).strip() + "\n"
        if slug:
            body = "#" + body[2:] if body.startswith("## ") else body

        def link(match, slug=slug):
            target = match.group(1)
            if target not in anchors:
                raise SiteError("README.md links to #" + target + ", which no heading has")
            page = anchors[target]
            return "](#" + target + ")" if page == slug else "](" + relative(slug, page) + "#" + target + ")"

        body = ANCHOR_LINK.sub(link, body)
        body = FILE_LINK.sub(lambda match: "](https://github.com/" + repository + "/blob/main/" + match.group(1) + ")", body)
        front = "---\nlayout: default\ntitle: " + ("ZDCache" if not slug else title + " · ZDCache") + "\nnav: " + (slug or "home") + "\n---\n"
        pages[slug] = front + "{% raw %}\n" + body + "{% endraw %}\n"
    return pages


def main(arguments):
    readme, output, repository = Path(arguments[0]), Path(arguments[1]), arguments[2]
    for slug, page in build(readme.read_text(encoding="utf-8"), repository).items():
        target = output / slug / "index.md" if slug else output / "index.md"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(page, encoding="utf-8")


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except SiteError as error:
        sys.exit(str(error))
