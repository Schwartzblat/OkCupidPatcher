"""The entitlement tier that turns an ordinary Like into a "Priority Like".

Priority Like is **not** a separate action. It is a re-skin of the one Like
the app already sends: every place that presents it reads the same predicate
with the same feature constant, and swaps the Like artwork (or its label) for
the Priority variant when it answers true. Measured against 116.0.0, all six
sites, each one `getUserHasPremium(ALIST_PREMIUM_PLUS)` (or a one-line wrapper
around it):

* `ui.profile.widgets.BottomFabsWidget.init` -- the profile Like FAB becomes
  `priority_like_fab` instead of `fab_like`.
* `ui.profile.viewModels.ProfilePhotoViewModel` -- the photo CTA label becomes
  `priority_like` ("PRIORITY LIKE") instead of `like`, and its icon
  `priority_like_icon_profile`. Reached through the private
  `getHasPremiumPlus()Z`, whose `Lazy` initializer is that predicate.
* `ui.likes.view.toplevelvoting.UserCardVotingContainer` -- the Likes-page
  vote FAB becomes `priority_like_likes_fab`.
* `ui.likes.view.toplevelvoting.UserCardLikeAnimation.animate` -- the
  fly-away heart becomes `priority_likes_likes_animation`.
* `ui.doubletake.view.card.usercardv2.UserCardViewV2` -- the DoubleTake card
  FAB becomes `priority_like_fab_dt`.
* `ui.common.oklayouts.UserCardComposeKt` -- the Compose card heart becomes
  `priority_likes_heart` instead of `vote_like_heart`, via
  `UserCardState.isPremiumPlusUser`, a field set from the same predicate in
  `UserCardViewModel`.

Because [PremiumGate] forces that predicate true for *every* feature, the
patched build claims all six. The server computes priority from the real
subscription, so for an account that is not actually paying the claim is
false: the Like goes out as an ordinary `VoteActionType.LIKE` either way. The
patch therefore denies this one constant, which restores the ordinary Like
artwork everywhere at once -- see `PremiumGate.java` for the collateral that
choice accepts, and NOTES.md for the whole enumeration.

Anchor: the enum that declares the paid-feature constants. Enum constant names
are wire values -- `mapToPremiumsDictionary` parses them out of the session's
GraphQL `Premiums` block -- so R8 cannot rename them, which is the same
reasoning [LikesSortEnum] records. The constant is also required to appear as
a `const-string` literal, because that is the string the enum's `<clinit>`
hands to `Enum(String, int)` and therefore what `Enum.name()` returns at
runtime; the hook compares against `name()`, not against a field, so a build
that kept the field but changed the literal must not match.

Excluding `UNKNOWN__` is load-bearing: Apollo's codegen'd `graphql.api.type.PREMIUM`
declares the same constants plus that sentinel, and without the exclusion both
match (measured: 2 classes with it, 1 without). The app's own enum is the one
the predicate takes.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class PriorityLikeTier(SimpleArtifactoryFinder):
    ENUM_FIELD_RE = re.compile(r'^\.field public static final enum (?P<name>\w+):', re.M)

    # The paid-feature set. Only five of the ten constants are ever looked up
    # by this client (NOTES.md, "which flags the app actually consults"); those
    # five are required here, so the finder cannot be satisfied by some
    # unrelated enum that happens to carry one tier name.
    REQUIRED = ('ALIST_BASIC', 'ALIST_PREMIUM', 'ALIST_PREMIUM_PLUS',
                'INCOGNITO_BUNDLE', 'VIEW_VOTES')

    # Apollo's sentinel for server values the client does not know. Present on
    # the generated GraphQL enum, absent on the app's own.
    APOLLO_SENTINEL = 'UNKNOWN__'

    # The tier that carries Priority Like. Named here, deliberately: it is a
    # server-parsed wire value, not an obfuscated identifier, and it is the
    # one thing about this feature that the app cannot rename unilaterally.
    # The class that declares it is discovered, never assumed.
    TIER = 'ALIST_PREMIUM_PLUS'

    # The same tier as the `Subscriptions` enum spells it -- a second wire
    # value, used by [PriorityLikeModal] below rather than here. The two enums
    # are different types with different member names for one tier, which is
    # why both are written down.
    TIER_SUBSCRIPTION = 'PREMIUM_PLUS'

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once: see unlimited_rewinds.py -- a fired is_once finder is
        # removed mid-iteration and makes stitch skip the next finder for that
        # same file.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return all(value in class_data for value in self.REQUIRED)

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self.is_found:
            return                      # not is_once; do the work once anyway
        declared = {m.group('name') for m in self.ENUM_FIELD_RE.finditer(class_data)}
        # Declaring, not merely referencing: the mapper and every call site
        # reference these names too.
        if not all(value in declared for value in self.REQUIRED):
            return
        if self.APOLLO_SENTINEL in declared:
            return                      # the generated GraphQL enum, not the app's
        if '"{0}"'.format(self.TIER) not in class_data:
            return                      # no <clinit> literal -> Enum.name() unknown
        name = class_name(class_data)
        if not name:
            return
        artifacts['PREMIUM_PLUS_FEATURE_CLASS_NAME'] = name
        artifacts['PREMIUM_PLUS_FEATURE_CONSTANT'] = self.TIER
        self.is_found = True


class PriorityLikeModal(SimpleArtifactoryFinder):
    """The Priority Likes pitch modal, which is the other way the feature is offered.

    `PriorityLikesModalUseCase.shouldShow(user)` decides whether to interrupt a
    liking session with `PriorityLikesModalScreen` -- "Get your Likes seen
    sooner with Priority Likes, included in Premium Plus" over
    `prioritylikesgif`. Its body, in full:

        subscription != PREMIUM_PLUS && (likesCountToday(user) == 15
                                         || likesCountToday(user) == 30)

    Two reasons it belongs to this patch rather than being left alone.

    It is an *offer* of the feature [PriorityLikeTier] has just established the
    build cannot deliver, so suppressing the artwork while leaving the sales
    pitch in place would be half a job.

    And it is the one place where denying ALIST_PREMIUM_PLUS would make the
    patched build *worse* than it was. `UserProviderConcrete.getSubscription()`
    returns the highest tier whose feature the predicate accepts, so with that
    one constant denied it answers PREMIUM where it used to answer
    PREMIUM_PLUS -- which is the honest tier, and which switches this modal
    back on at the 15th and 30th like of the day. Forcing `shouldShow` false
    keeps it off.

    Anchored on shape plus one wire-level enum member name: a public
    `(String)Z` method that calls a zero-argument getter returning some type T
    and then reads T's `PREMIUM_PLUS` constant. Requiring the getter's return
    type and the constant's owner to be the *same* type is what makes it
    specific -- measured 1/68 270 against 116.0.0. The class, the method and
    the tier type are all outputs.

    Reimplemented outright (2-arg, no backup): the method is a pure predicate
    over a subscription and a counter, it returns a constant here, and
    NOTES.md's backup-entry-instruction rule makes a call-through the wrong
    tool for a body whose first instruction is a `const-string`.
    """

    TARGET_RE = re.compile(
        r'\.method public (?:final )?(?P<method_name>\w+)(?P<method_sig>\(Ljava/lang/String;\)Z)'
        + IN_BODY + r'invoke-interface \{[vp]\d+\}, L[\w/$]+;->\w+\(\)L(?P<tier>[\w/$]+);'
        + IN_BODY + r'sget-object [vp]\d+, L(?P<owner>[\w/$]+);->' + PriorityLikeTier.TIER_SUBSCRIPTION
        + r':L(?P=owner);')

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once: see unlimited_rewinds.py.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return PriorityLikeTier.TIER_SUBSCRIPTION in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self.is_found:
            return                      # not is_once; do the work once anyway
        # The getter's return type must be the very type whose PREMIUM_PLUS is
        # read, or this is two unrelated reads that happen to share a method.
        matches = [m for m in self.TARGET_RE.finditer(class_data)
                   if m.group('tier') == m.group('owner')]
        if len(matches) != 1:
            return                      # ambiguous (or none) -> do not fire
        name = class_name(class_data)
        if not name:
            return
        found = matches[0].groupdict()
        artifacts['PRIORITY_MODAL_CLASS_NAME'] = name
        artifacts['PRIORITY_MODAL_METHOD_NAME'] = found['method_name']
        artifacts['PRIORITY_MODAL_METHOD_SIG'] = found['method_sig']
        self.is_found = True
