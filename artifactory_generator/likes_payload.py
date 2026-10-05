"""The Likes page payload -- where the cursor and the cards arrive together.

`LikesPageRepo$LikesPagePayload` carries the user list and the pagination
cursor as sibling fields:

    userList:Lcom/okcupid/okcupid/domain/ObservableData;   // List<User>, wrapped
    nextPagingKey:Ljava/lang/String;

The constructor is NOT hooked: its first instruction is
`const-string v0, "userList"` (Kotlin's checkNotNullParameter), and a
constructor hook must call through or the object comes back uninitialised.
A call-through backup on a `const-string` entry segfaulted 3/3 cold launches
(measured). The mechanism is a hypothesis, not an established fact; NOTES.md,
"The ArtHooks backup-entry-instruction rule", is the single source for the
evidence and its limits.

So the getter is hooked instead, reimplemented outright with the 2-arg
ArtHooks form (no backup, no call-through). `getUserList()` is a pure getter
-- `iget-object v0, p0, ...->userList:...; return-object v0` -- and `thiz` is
the payload instance, so the same call can also read the `nextPagingKey`
field off it: one hook gives the card list and its boundary cursor together.

Pinned by the field it reads (`->userList:`), not by name, the same
convention [UserAccessors] uses and for the same reason: a getter's *name* is
not a stable artifact, but the *field* a Kotlin data class getter reads is.
`component1()` reads the same field and is excluded the same way
[UserAccessors] excludes `component\\d` from its id getter -- it exists only
for destructuring and is not the call site production code actually uses.

Anchored on the Kotlin data-class toString prefix, which is a string literal
R8 cannot rename, exactly as [BlurredUserFlag] anchors on `User(matchHighlights=`.
Measured against 116.0.0: 1 class out of 68 270.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class LikesPayload(SimpleArtifactoryFinder):
    # A zero-arg method returning the ObservableData field named userList.
    # Excludes component1(), the Kotlin-generated destructuring alias that
    # reads the same field but is not how the app's UI code reaches it.
    GETTER_RE = re.compile(
        r'\.method public final (?P<method_name>(?!component\d)\w+)\(\)'
        r'(?P<return_sig>L[\w/$]+;)'
        + IN_BODY + r'->userList:Lcom/okcupid/okcupid/domain/ObservableData;')

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once: a fired is_once finder is removed from the list
        # mid-iteration, which makes stitch skip the NEXT finder for that same
        # file (see unlimited_rewinds.py). Never removed means never able to
        # cause that, whatever gets registered after this one.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return 'LikesPagePayload(userList=' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self.is_found:
            return                      # not is_once; do the work once anyway
        matches = list(self.GETTER_RE.finditer(class_data))
        if len(matches) != 1:
            return                      # ambiguous -> do not fire
        name = class_name(class_data)
        if not name:
            return
        artifacts['LIKES_PAYLOAD_CLASS_NAME'] = name
        artifacts['LIKES_PAYLOAD_GETTER_NAME'] = matches[0].group('method_name')
        artifacts['LIKES_PAYLOAD_GETTER_SIG'] = \
            '()' + matches[0].group('return_sig')
        # Only after every artifact is written.
        self.is_found = True
