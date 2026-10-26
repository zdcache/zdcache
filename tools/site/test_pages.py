import unittest
from pathlib import Path

import pages

README = Path(__file__).resolve().parents[2] / "README.md"


def readme(*sections):
    text = "Read this on the site.\n\n# ZDCache\n\nIntro.\n\n"
    for title in [title for _, _, titles in pages.PAGES for title in titles]:
        text += "## " + title + "\n\n" + sections_text(sections, title) + "\n\n"
    return text


def sections_text(sections, title):
    for heading, body in sections:
        if heading == title:
            return body
    return "Text."


class PagesTest(unittest.TestCase):
    def test_the_real_readme_builds_every_page(self):
        built = pages.build(README.read_text(encoding="utf-8"), "zdcache/zdcache")
        self.assertEqual([slug for slug, _, _ in pages.PAGES], list(built))
        self.assertIn("# ZDCache", built[""])
        self.assertIn("{% raw %}\n# Choosing a configuration\n", built["configuration"])
        self.assertLess(built["configuration"].index("# Choosing a configuration"), built["configuration"].index("## Locking and atomicity"))

    def test_sections_appear_in_the_order_of_their_page_not_of_the_readme(self):
        text = readme().replace("## Choosing a configuration\n\nText.\n\n", "")
        text = text.replace("## Search specifications\n", "## Choosing a configuration\n\nFirst.\n\n## Search specifications\n")
        built = pages.build(text, "o/r")
        self.assertIn("{% raw %}\n# Choosing a configuration\n\nFirst.", built["configuration"])

    def test_text_before_the_title_stays_in_the_readme(self):
        home = pages.build(readme(), "o/r")[""]
        self.assertNotIn("Read this on the site", home)
        self.assertIn("# ZDCache\n\nIntro.", home)

    def test_a_heading_on_no_page_fails_the_build(self):
        with self.assertRaisesRegex(pages.SiteError, "'## Something new' is on no page"):
            pages.build(readme() + "## Something new\n\nText.\n", "o/r")

    def test_a_page_heading_missing_from_the_readme_fails_the_build(self):
        text = readme().replace("## Project map\n", "## Map\n")
        with self.assertRaises(pages.SiteError):
            pages.build(text, "o/r")

    def test_a_link_to_a_missing_anchor_fails_the_build(self):
        with self.assertRaisesRegex(pages.SiteError, "#nowhere"):
            pages.build(readme(("Quick start", "See [there](#nowhere).")), "o/r")

    def test_links_to_sections_on_other_pages_point_at_those_pages(self):
        built = pages.build(readme(("Quick start", "See [indexes](#locking-and-atomicity) and [install](#installation)."),
                                   ("Benchmark", "See [rules](#identity-specification-and-update-rules) and [home](#installation).")), "o/r")
        self.assertIn("[indexes](configuration/#locking-and-atomicity)", built[""])
        self.assertIn("[install](#installation)", built[""])
        self.assertIn("[rules](../search-specifications/#identity-specification-and-update-rules)", built["benchmarks"])
        self.assertIn("[home](../#installation)", built["benchmarks"])

    def test_relative_file_links_point_at_the_repository(self):
        built = pages.build(readme(("Project map", "See [the code](src/main/java/A.java).")), "o/r")
        self.assertIn("[the code](https://github.com/o/r/blob/main/src/main/java/A.java)", built["development"])

    def test_headings_inside_code_blocks_do_not_start_sections(self):
        built = pages.build(readme(("Quick start", "```sh\n## not a heading\n```")), "o/r")
        self.assertIn("## not a heading", built[""])

    def test_each_page_starts_with_its_first_heading_as_title(self):
        built = pages.build(readme(), "o/r")
        self.assertIn("{% raw %}\n# Benchmark\n", built["benchmarks"])
        self.assertIn("nav: benchmarks\n", built["benchmarks"])
        self.assertIn("nav: home\n", built[""])

    def test_anchors_follow_the_heading_ids_of_github_and_kramdown(self):
        self.assertEqual("basicdefault-specification", pages.anchor("Basic/default specification"))
        self.assertEqual("2-key-lookups-from-many-threads-approximate-recency",
                         pages.anchor("2. Key lookups from many threads: approximate recency"))
        self.assertEqual("lockingsingle-and-atomically", pages.anchor("`Locking.SINGLE` and `atomically`"))


if __name__ == "__main__":
    unittest.main()
