"""The two `User` accessors the card capture needs: its id and its photo list.

For a gated entry the id is the locally generated `placeholder_}<uuid>` and the
first photo is the real `primaryImage.square225` URL the server leaked -- so
these two together are exactly the card-to-path binding.

Anchor: the `User` data-class toString prefix, already used by
[BlurredUserFlag] and measured unique. Methods are pinned by the field each
one reads, never by name.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class UserAccessors(SimpleArtifactoryFinder):
    ID_RE = re.compile(
        r'\.method public final (?P<method_name>(?!component\d)\w+)\(\)Ljava/lang/String;'
        + IN_BODY + r'->userid:Ljava/lang/String;')

    PHOTOS_RE = re.compile(
        r'\.method public final (?P<method_name>(?!component\d)\w+)\(\)Ljava/util/List;'
        + IN_BODY + r'->photos:Ljava/util/List;')

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once: see incognito_gate.py.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return 'User(matchHighlights=' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self.is_found:
            return                      # not is_once; do the work once anyway
        ids = list(self.ID_RE.finditer(class_data))
        photos = list(self.PHOTOS_RE.finditer(class_data))
        # DELIBERATE EXCEPTION to this project's "refuse when ambiguous" rule:
        # more than one getter reads `userid` (getId and getUserid are both
        # one-line reads of the same field), so either is correct for our
        # purpose. finditer yields in source order, so ids[0] is the first
        # declared -- deterministic for a given APK. The photo list has a
        # single accessor and must be unambiguous.
        if not ids or len(photos) != 1:
            return
        name = class_name(class_data)
        if not name:
            return
        artifacts['USER_MODEL_CLASS_NAME'] = name
        artifacts['USER_ID_METHOD_NAME'] = ids[0].group('method_name')
        artifacts['USER_PHOTOS_METHOD_NAME'] = photos[0].group('method_name')
        self.is_found = True
