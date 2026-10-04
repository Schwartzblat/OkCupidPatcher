"""Resolve the page-boundary entry of LIKED YOU / VIEWED YOU to a real user id.

Gated entries arrive as the `MatchPreview` GraphQL type, which carries no
identifier at all; the client fabricates a local `User` whose id is
`"placeholder_}" + randomUUID()` and whose name is the literal `------`. So the
card cannot open a profile: there is nothing on the device to navigate to.

The pagination cursor is the exception. `pageInfo.after` is `base64url(<user
id>)` of the **last entry of the page just returned** -- see
`../REPORT-cursor-deanonymization.md`, which is the finding this implements.
Pairing that cursor with the entry it belongs to turns exactly one card per page
into a real, openable profile.

Status: [DeanonNavProfileId] is registered in main.py and load-bearing (its
DEANON_NAV_ID_* keys are substituted into OpenRealProfile.java).
[DeanonPagePayload] is unregistered and unconsumed (never instantiated, so it
cannot fire); the capture hook targets the payload getter via LikesPayload.

Two finders, because the two halves live in different classes:

* [DeanonPagePayload] finds the one constructor where a paged user list and its
  cursor arrive together.
* [DeanonNavProfileId] finds the accessor that profile navigation actually reads
  the id from -- discovered from the `"/profile/"` call site rather than
  assumed, so it follows the app if that path ever changes which getter it uses.

Deliberately NOT the same accessor the vote path uses: `submitVote` reads a
different method off the same field, so liking a card still submits the
placeholder and cannot cast a real vote on a resolved person.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class DeanonPagePayload(SimpleArtifactoryFinder):
    """The constructor carrying (paged list, counts, cursor, hasMore, promos)."""

    # Pinned purely by shape: a constructor whose first parameter is the app's
    # paging-state wrapper and which also takes a String cursor, a boolean and a
    # List. Measured against 116.0.0: 109 classes reference ObservableData and
    # exactly ONE declares this constructor.
    TARGET_RE = re.compile(
        r'\.method public constructor (?P<method_name>\<init\>)'
        r'(?P<method_sig>\(L[\w/$]*ObservableData;L[\w/$]+;Ljava/lang/String;ZLjava/util/List;\)V)')

    def __init__(self, args):
        super().__init__(args)
        # is_once=False for the same reason as the rest of this set: a finder
        # removed mid-iteration makes stitch skip the next one for that file.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return 'ObservableData;' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self.is_found:
            return
        matches = list(self.TARGET_RE.finditer(class_data))
        if len(matches) != 1:
            return                      # ambiguous -> do not fire
        name = class_name(class_data)
        if not name:
            return
        found = matches[0].groupdict()
        artifacts['DEANON_PAYLOAD_CLASS_NAME'] = name
        artifacts['DEANON_PAYLOAD_METHOD_NAME'] = found['method_name']
        artifacts['DEANON_PAYLOAD_METHOD_SIG'] = found['method_sig']
        self.is_found = True


class DeanonNavProfileId(SimpleArtifactoryFinder):
    """Whichever getter profile navigation builds its `/profile/<id>` URL from."""

    # The navigation method reads a String off its User parameter and appends it
    # to the literal "/profile/". Capture the class and method it called: the
    # anchor is a URL path, which R8 cannot rename, and the answer is read out
    # of the call site instead of being named here. One match in 68 270 classes.
    TARGET_RE = re.compile(
        r'invoke-virtual \{p\d+\}, L(?P<cls>[\w/$]+);->(?P<method_name>\w+)'
        r'(?P<method_sig>\(\)Ljava/lang/String;)'
        + IN_BODY + r'const-string [vp]\d+, "/profile/"')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return '"/profile/"' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self.is_found:
            return
        matches = list(self.TARGET_RE.finditer(class_data))
        if len(matches) != 1:
            return                      # ambiguous -> do not fire
        found = matches[0].groupdict()
        artifacts['DEANON_NAV_ID_CLASS_NAME'] = found['cls'].replace('/', '.')
        artifacts['DEANON_NAV_ID_METHOD_NAME'] = found['method_name']
        artifacts['DEANON_NAV_ID_METHOD_SIG'] = found['method_sig']
        self.is_found = True
