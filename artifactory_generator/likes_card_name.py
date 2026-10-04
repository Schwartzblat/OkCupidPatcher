"""`UserCardState.name` and `.userImage` -- what the Likes grid draws.

The gated grid renders `"------"` for every hidden person: `toBlurredUser`
fabricates the `User` with that literal, and the card's own state object
carries it through to the composition. Substituting it needs two accessors on
the same state object -- the name to replace, and the image URL that says
*which card this is*, since the normalized photo path is the join key the
whole likes package is built on.

Both are plain field reads on a Kotlin data class, so they are matched the
same way [BlurredCardFlag] matches `showBlurred` on this very class: by the
field the getter returns, never by the getter's name.

The name getter's backing field is emitted too. The hook reads it directly
instead of calling the getter's backup: a three-argument call-through on this
method segfaults the first time the grid composes (see NOTES.md's cold-start
rule -- `iget-object` is *not* a safe backup entry, measured).

Anchor: the data class's own `toString` prefix, `UserCardState(userImage=`,
which is generated from the declaration and so cannot drift from the fields
without the fields changing too.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class LikesCardName(SimpleArtifactoryFinder):
    # componentN is excluded for the same reason as in BlurredCardFlag: a
    # Kotlin data class generates a componentN() alias for every field, and
    # it reads the same field as the real getter.
    NAME_RE = re.compile(
        r'\.method public final (?P<method_name>(?!component\d)\w+)(?P<method_sig>\(\)Ljava/lang/String;)'
        + IN_BODY + r'->(?P<field_name>name):Ljava/lang/String;')
    IMAGE_RE = re.compile(
        r'\.method public final (?P<method_name>(?!component\d)\w+)(?P<method_sig>\(\)Ljava/lang/String;)'
        + IN_BODY + r'->userImage:Ljava/lang/String;')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return 'UserCardState(userImage=' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        names = list(self.NAME_RE.finditer(class_data))
        images = list(self.IMAGE_RE.finditer(class_data))
        if len(names) != 1 or len(images) != 1:
            return
        owner = class_name(class_data)
        if not owner:
            return
        artifacts['CARD_NAME_CLASS_NAME'] = owner
        artifacts['CARD_NAME_METHOD_NAME'] = names[0].groupdict()['method_name']
        artifacts['CARD_NAME_METHOD_SIG'] = names[0].groupdict()['method_sig']
        # The hook reads this field directly rather than calling the getter's
        # backup -- see the class doc on ShowRealName.
        artifacts['CARD_NAME_FIELD_NAME'] = names[0].groupdict()['field_name']
        artifacts['CARD_IMAGE_METHOD_NAME'] = images[0].groupdict()['method_name']
        # Only after every artifact is written.
        self.is_found = True
