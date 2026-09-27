#!/usr/bin/env python3
"""Builds the SAP Help catalog and page-link graph from SAP's documentation repository.

src/main/resources/sap-help/catalog.tsv — the pages;
src/main/resources/sap-help/links.tsv   — which page links to which (the graph).

One line per page of docs/ISuite_Integrations_APIs in github.com/SAP-docs/btp-integration-suite
(the Markdown source of help.sap.com, CC BY 4.0, (c) SAP SE):

    path <TAB> title <TAB> summary

- title:   the page's first heading (falls back to the file name)
- summary: the first sentence under that heading, which SAP writes as a short description.
           It is what lets "AS4" find "Configure Receiver Channel with ebMS3 Push".

links.tsv: one line per link between two catalog pages,  from_id <TAB> to_id,
where an id is the file name without .md. Links to other sites are left out.

Usage:  python3 scripts/build_sap_help_catalog.py [commit-sha]     (default: current main)
Downloads every page once (~1,700 requests, a few minutes); needs internet, no token.
"""
import concurrent.futures
import json
import os
import re
import ssl
import sys
import urllib.request

REPO = "SAP-docs/btp-integration-suite"
FOLDER = "docs/ISuite_Integrations_APIs/"
OUT = "src/main/resources/sap-help/catalog.tsv"
LINKS_OUT = "src/main/resources/sap-help/links.tsv"
LINK = re.compile(r"\[[^\]]+]\(([^)\s#]+\.md)(?:#[^)]*)?\)")
SKIP = ("index", "readme", "what-s-new")
MAX_SUMMARY = 240


# python.org builds on macOS ship without CA certificates; fall back to the system's.
_CA = next((f for f in ("/etc/ssl/cert.pem",) if os.path.exists(f)), None)
SSL = ssl.create_default_context(cafile=_CA) if _CA else ssl.create_default_context()


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "cpi-assistant-catalog"})
    with urllib.request.urlopen(request, timeout=60, context=SSL) as r:
        return r.read().decode("utf-8", "replace")


def clean(text):
    text = re.sub(r"!\[[^\]]*]\([^)]*\)", "", text)            # images
    text = re.sub(r"\[([^\]]+)]\([^)]*\)", r"\1", text)         # links keep their text
    text = re.sub(r"\\([()\[\]*_#`>])", r"\1", text)            # markdown escapes
    text = re.sub(r"<[^>]+>", "", text)                          # inline html
    text = text.replace("*", "").replace("\t", " ")
    return re.sub(r"\s+", " ", text).strip()


def slug_title(path):
    slug = re.sub(r"-[0-9a-f]{7}$", "", path.rsplit("/", 1)[1][:-3])
    return " ".join(w.capitalize() for w in slug.split("-"))


def page_id(path):
    return path.rsplit("/", 1)[-1][:-3]


def describe(path, sha):
    try:
        markdown = get(f"https://raw.githubusercontent.com/{REPO}/{sha}/{path}")
    except Exception as e:  # a page we cannot read keeps its file-name title
        print(f"  ! {path}: {e}", file=sys.stderr)
        return path, slug_title(path), "", []
    targets = sorted({page_id(target) for target in LINK.findall(markdown) if "://" not in target})
    title, summary, after_heading = None, "", False
    for line in markdown.splitlines():
        s = line.strip()
        if not s or s.startswith("<!--") or s.startswith("<a name"):
            continue
        if title is None and s.startswith("# "):
            title, after_heading = clean(s[2:]), True
            continue
        if after_heading:
            if s.startswith(("#", ">", "|", "<table", "-", "1.")):
                break          # no description paragraph: leave the summary empty
            summary = clean(s)
            break
    if len(summary) > MAX_SUMMARY:
        summary = summary[:MAX_SUMMARY].rsplit(" ", 1)[0] + " …"
    return path, title or slug_title(path), summary, targets


def main():
    sha = sys.argv[1] if len(sys.argv) > 1 else json.loads(get(f"https://api.github.com/repos/{REPO}/commits/main"))["sha"]
    tree = json.loads(get(f"https://api.github.com/repos/{REPO}/git/trees/{sha}?recursive=1"))
    paths = sorted(t["path"] for t in tree["tree"] if t["type"] == "blob" and t["path"].startswith(FOLDER)
                   and t["path"].endswith(".md") and not t["path"].rsplit("/", 1)[1].lower().startswith(SKIP))
    print(f"{len(paths)} pages at {sha}", file=sys.stderr)
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        rows = list(pool.map(lambda p: describe(p, sha), paths))
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(f"# SAP Integration Suite documentation pages, from github.com/{REPO}\n")
        f.write(f"# (the Markdown source of help.sap.com, CC BY 4.0, (c) SAP SE). Folder {FOLDER.rstrip('/')}\n")
        f.write(f"# at commit {sha}. Built by scripts/build_sap_help_catalog.py.\n")
        f.write("# Columns: path <TAB> title (the page heading) <TAB> summary (its first sentence)\n")
        for path, title, summary, _ in rows:
            f.write(f"{path}\t{title}\t{summary}\n")
    print(f"wrote {len(rows)} rows, {sum(1 for r in rows if r[2])} with a summary", file=sys.stderr)

    known = {page_id(path) for path, *_ in rows}
    edges = sorted({(page_id(path), target) for path, _, _, targets in rows for target in targets
                    if target in known and target != page_id(path)})
    with open(LINKS_OUT, "w", encoding="utf-8") as f:
        f.write(f"# Links between the pages of catalog.tsv, at commit {sha}.\n")
        f.write("# Built by scripts/build_sap_help_catalog.py. Columns: from_id <TAB> to_id\n")
        for source, target in edges:
            f.write(f"{source}\t{target}\n")
    print(f"wrote {len(edges)} links between {len({e[0] for e in edges} | {e[1] for e in edges})} pages", file=sys.stderr)


if __name__ == "__main__":
    main()
