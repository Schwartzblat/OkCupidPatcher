"""Shared smali helpers for the OkCupid finders."""
import re

# stitch's own CLASS_NAME_RE is r'\.class public.*L(?P<name>[\w/]+)'. Two
# problems for this app: '$' is not in [\w/], so a nested class such as
# Foo$Companion truncates to its *outer* class, and the pattern only matches
# '.class public'. This one keeps the '$' and does not care about access flags.
CLASS_RE = re.compile(r'^\.class[^\n]*?L(?P<name>[\w/$]+);', re.MULTILINE)

# "still inside this method body" -- a lazy run that cannot cross into the
# next method. Prefer this over a fixed line distance, which breaks on
# recompilation.
IN_BODY = r'(?:(?!\.end method)[\s\S])*?'


def class_name(class_data: str):
    """Dotted FQN of the class in this smali file, ready for Class.forName."""
    match = CLASS_RE.search(class_data)
    return match.group('name').replace('/', '.') if match else None
