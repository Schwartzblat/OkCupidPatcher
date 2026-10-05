"""The LikesListSort enum, whose constants are the 10 orderings a cursor can be
replayed under.

The traversal reads the constants reflectively at runtime and skips Apollo's
`UNKNOWN__` sentinel, so the sort list is never hardcoded here.

Anchor: a class that declares enum constants including three of the orderings plus Apollo's `UNKNOWN__` sentinel.
Enum constant names are wire values -- the server parses them -- so R8 cannot
rename them. Measured against 116.0.0: the literal appears in 7 classes
(the enum, its adapter and usages); requiring the enum-field declarations
narrows that to the enum itself.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import class_name


class LikesSortEnum(SimpleArtifactoryFinder):
    ENUM_FIELD_RE = re.compile(r'^\.field public static final enum (?P<name>\w+):', re.M)

    # UNKNOWN__ is Apollo codegen's sentinel for server values the client does
    # not know. It is what separates the generated GraphQL enum from the app's
    # own UI enum of the same ten orderings (ui.likessort), which declares the
    # same constants but has no sentinel -- measured: without it, 2 classes
    # match and the winner would depend on file order.
    REQUIRED = ('LIKES_VIEWS_GLOBAL', 'VIEWED_ME', 'AGE_ASCENDING', 'UNKNOWN__')

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once: see unlimited_rewinds.py.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return all(value in class_data for value in self.REQUIRED)

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self.is_found:
            return                      # not is_once; do the work once anyway
        declared = {m.group('name') for m in self.ENUM_FIELD_RE.finditer(class_data)}
        # The enum itself declares them; the adapter and the usages only
        # reference them.
        if not all(value in declared for value in self.REQUIRED):
            return
        name = class_name(class_data)
        if not name:
            return
        artifacts['LIKES_SORT_ENUM_CLASS_NAME'] = name
        self.is_found = True
