"""The app's own plain-Like primitive, and the source tag a Picks like carries.

A Like in this app is one call, and it is the same call for a like and for a
pass -- the boolean decides:

    BatchVoteService.submitVote(String userId, VoteSource voteSource,
                                User user, boolean like) : Flowable<BatchVoteResponse>

which builds `BatchVoteRequest(listOf(BatchVote(voteSource, userId, like, 0,
user.userData)))` and sends it as the `UserVote` mutation with
`VoteActionType.LIKE`. Both of the app's own like paths go through it --
`ui.profile.util.LikeManager.submitLike` (the profile FAB) and
`ui.likes.viewmodel.LikesPageViewModel.submitVote` (the Likes grid) -- so
reusing it is reusing the shipped vote flow rather than reimplementing one.
Nothing about it is entitlement-gated: the request carries no balance and no
premium claim, which NOTES.md records as the measured enforcement posture.

`PicksLikeButton` reaches the service with no Dagger graph of its own: the host
`Application` is the DI root (`DiExtensionsKt.getOkGraph(Context)` is
`(OkGraphProvider) context.applicationContext).getOkGraph()`), so the hook
walks zero-argument getters from the Application object until one's *return
type* is `VOTE_SERVICE_CLASS_NAME`. That walk is over types only; the sole
getter chain it actually invokes is the one that leads to the service. Which
is why this finder emits the service **interface** -- the graph declares the
getter by interface type, not by implementation.

Two finders:

* **PicksVoteService** -- anchored on the implementation, not the interface,
  because the implementation's body carries `Intrinsics.checkNotNullParameter`
  literals for its own parameter names. `"userId"` and `"voteSource"` are
  genuine `const-string` instructions in the dex, not `kotlin.Metadata`, so
  this survives a build that drops metadata. The signature shape
  `(String, <ref>, <ref>, boolean) -> <ref>` then pins the method and names
  both reference parameter types. Measured: 1/68 270 classes on 116.0.0.
* **PicksVoteSource** -- the `VoteSource` enum, for the tag the vote is
  attributed to. `TransformExtKt.normalize` maps it onto the GraphQL enum; it
  is attribution only and does not decide whether the vote is a like.

`DOUBLETAKE` is the honest tag for a Picks like: Picks *is* a DoubleTake stack
(`DoubleTakeStackType.STANDOUTS`), and the screen's own SuperLike path already
reports `TrackingSource.DOUBLETAKE`. The enum has no STANDOUTS member. The
name is written here on the same reasoning [LikesSortEnum] gives -- enum
constant names are wire values the server parses, so R8 cannot rename them --
and the class that declares it is discovered rather than assumed. Excluding
Apollo's `UNKNOWN__` sentinel is what separates the app's enum from the
codegen'd `graphql.api.type.VoteSource` of the same name, which carries the
same constants (measured: 2 classes with the sentinel allowed, 1 without).
`PicksLikeButton` then checks the constant really belongs to the enum the
signature named, so a disagreement between the two finders is a logged refusal
rather than a wrong vote.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class PicksVoteService(SimpleArtifactoryFinder):
    # (String, <ref>, <ref>, boolean) -> <ref>, whose body null-checks a
    # parameter called "userId" and one called "voteSource". The names come
    # from the implementation's own Intrinsics calls, so they are instructions
    # in the method body rather than annotation data.
    SUBMIT_RE = re.compile(
        r'\.method public (?:final )?(?P<method_name>\w+)'
        r'\(Ljava/lang/String;L(?P<vote_source>[\w/$]+);L(?P<user>[\w/$]+);Z\)L[\w/$]+;'
        + IN_BODY + r'const-string [vp]\d+, "userId"'
        + IN_BODY + r'const-string [vp]\d+, "voteSource"')

    # The interface the graph declares its getter by. Required to be the only
    # one: a class implementing several interfaces gives no way to tell which
    # one carries the vote API, and picking wrong means the runtime walk finds
    # nothing.
    IMPLEMENTS_RE = re.compile(r'^\.implements L(?P<iface>[\w/$]+);', re.M)

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once: see unlimited_rewinds.py.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return '"userId"' in class_data and '"voteSource"' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self.is_found:
            return                      # not is_once; do the work once anyway
        matches = list(self.SUBMIT_RE.finditer(class_data))
        if len(matches) != 1:
            return                      # ambiguous -> do not fire
        interfaces = self.IMPLEMENTS_RE.findall(class_data)
        if len(interfaces) != 1:
            return
        impl = class_name(class_data)
        if not impl:
            return
        found = matches[0].groupdict()
        artifacts['VOTE_SERVICE_CLASS_NAME'] = interfaces[0].replace('/', '.')
        artifacts['VOTE_SERVICE_IMPL_CLASS_NAME'] = impl
        artifacts['VOTE_SUBMIT_METHOD_NAME'] = found['method_name']
        artifacts['VOTE_SOURCE_CLASS_NAME'] = found['vote_source'].replace('/', '.')
        artifacts['VOTE_USER_CLASS_NAME'] = found['user'].replace('/', '.')
        self.is_found = True


class PicksVoteSource(SimpleArtifactoryFinder):
    ENUM_FIELD_RE = re.compile(r'^\.field public static final enum (?P<name>\w+):', re.M)

    # Four of the fifteen orderings, chosen to be jointly distinctive rather
    # than individually: every one of them also appears in the codegen'd
    # GraphQL enum, which is what the sentinel exclusion is for.
    REQUIRED = ('DOUBLETAKE', 'INCOMING_LIKES_SUPERLIKE', 'QUESTION_SEARCH', 'MAILBOX')

    APOLLO_SENTINEL = 'UNKNOWN__'

    # What a like sent from the Picks carousel is attributed to. Picks is a
    # DoubleTake stack and the enum has no STANDOUTS member.
    SOURCE = 'DOUBLETAKE'

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
        # Declaring, not merely referencing: the adapter and every call site
        # reference these names too.
        if not all(value in declared for value in self.REQUIRED):
            return
        if self.APOLLO_SENTINEL in declared:
            return                      # the generated GraphQL enum, not the app's
        name = class_name(class_data)
        if not name:
            return
        artifacts['VOTE_SOURCE_ENUM_CLASS_NAME'] = name
        artifacts['VOTE_SOURCE_CONSTANT'] = self.SOURCE
        self.is_found = True
