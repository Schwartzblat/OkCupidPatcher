"""The Cupid's Picks card: where it binds, and what it knows about the person.

OkCupid Picks ("Cupid's Picks", `cupids_picks_stack_title`) is the Standouts
carousel. Each card is an Epoxy `EpoxyModelWithView` whose `bind(view)` wires
exactly two things the user can tap -- measured off 116.0.0's smali:

    CardView            -> listener.onProfileSelected(props)    ; opens /profile/<id>
    SuperLikeFabComponent -> listener.onProfileSuperLiked(props) ; SuperLike, or a rate card

There is **no plain Like on the Picks screen at all**, which is what
`PicksLikeButton` adds. Without SuperLike tokens -- the normal state for an
account the patch has unlocked but that is not really paying -- the one
available action is a purchase wall, so the screen currently offers no way to
like anybody without leaving it.

Two finders, because the information is split across two classes:

* **PicksCardBase** -- the abstract base that owns `bind` and the `props`
  field. It yields how to get from a model to the person (`props` ->
  `getUser()`), and the FAB class, which is the view the added Like button is
  positioned against.
* **PicksCardBinders** -- the concrete subclasses, which are what actually
  gets hooked. Each one overrides `bind(view)` and carries its own Kotlin
  `bridge synthetic bind(Object)` -- and the bridge is what Epoxy calls, so
  hooking the base's bridge would never fire. Two subclasses on 116.0.0 (the
  photo card and the question card), so this emits a `|`-separated target list
  the way [PremiumGate] does rather than one named class.

**Why the bridge and not `bind` itself.** The hook has to run *after* the
original binding, and NOTES.md's "ArtHooks backup-entry-instruction rule"
rules out a 3-argument call-through for anything whose entry instruction is
not known-safe. The bridge needs no call-through: its entire body is a
`check-cast` plus a virtual call to the specialised `bind`, so a 2-argument
replacement that reflectively invokes that same specialised method is a
*faithful* reimplementation, not an approximation -- and the method it invokes
is not hooked, so there is no recursion and no backup. That is why
`PICKS_CARD_TARGETS` names the `(Ljava/lang/Object;)V` overload and
`PICKS_CARD_BIND_VIEW_CLASS_NAME` is emitted alongside it: the replacement
needs the parameter type to look the real `bind` up by signature rather than
by guessing which of the two overloads it found.

Nothing here is pinned by name. The base is anchored on *shape*: a class that
invokes a single-reference-argument void callback whose name ends in
`SuperLiked` through an interface (1/68 270 classes on 116.0.0 once the rest
of the shape is required), declares exactly one `bind(<one reference>)V`, and
in that method's body reads a field and calls a zero-argument getter on it.
The subclasses are anchored on their relationship to whatever base that is:
`.super` the base, a constructor whose last parameter is one of the base's own
nested types (the listener interface), and a `bind` that `invoke-super`s into
the base's `bind` with a non-`Object` parameter -- which excludes the sibling
`ExplanationModel`, whose bridge casts to a different view type and whose
super-call goes to Epoxy. Measured: exactly the two Picks models.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name

# Packages whose classes are framework/library code rather than the app's own.
# Used to tell the Picks FAB (an app view) apart from the CardView that gets a
# click listener in the same method body.
_FRAMEWORK_PREFIXES = ('android/', 'androidx/', 'java/', 'javax/', 'kotlin/',
                       'kotlinx/', 'com/google/', 'com/airbnb/', 'okhidden/')


def _is_app_class(internal_name: str) -> bool:
    return not any(internal_name.startswith(prefix) for prefix in _FRAMEWORK_PREFIXES)


class PicksCardBase(SimpleArtifactoryFinder):
    # "a one-reference-argument void callback whose name ends in SuperLiked,
    # dispatched through an interface". Shape, not name: a release that renames
    # onProfileSuperLiked to onCardSuperLiked still matches, while the
    # interface that merely *declares* it (and the fragment that implements it)
    # does not, because neither contains an invoke-interface to it.
    SUPERLIKE_CALL_RE = re.compile(
        r'invoke-interface \{[vp]\d+, [vp]\d+\}, L[\w/$]+;->\w*SuperLiked\(L[\w/$]+;\)V')

    # The Epoxy bind hook: public, one reference parameter, void. The bridge
    # overload is excluded by requiring exactly one match -- `.method public
    # bridge synthetic bind(Ljava/lang/Object;)V` carries the extra
    # `bridge synthetic` between `public` and the name, so it cannot match.
    BIND_RE = re.compile(
        r'^\.method public (?:final )?(?P<bind_name>\w+)\(L(?P<view>[\w/$]+);\)V', re.M)

    # Inside that method: read a field, then call a zero-argument getter on
    # whatever came out. The register is back-referenced so this cannot pair a
    # field read with a getter call on some other object. The 120-character
    # window spans the blank line and any `.line` marker apktool emits between
    # the two instructions, and nothing larger, so the pair stays adjacent.
    PROPS_USER_RE = re.compile(
        r'iget-object (?P<reg>[vp]\d+), [vp]\d+, L[\w/$]+;->(?P<field>\w+):L(?P<props>[\w/$]+);'
        r'[\s\S]{0,120}?'
        r'invoke-virtual \{(?P=reg)\}, L(?P=props);->(?P<getter>\w+)\(\)L(?P<user>[\w/$]+);')

    # The view that gets the SuperLike click listener. Both the CardView and
    # the FAB are cast-then-setOnClickListener in this body, so the app/library
    # split below is what separates them; the register back-reference keeps the
    # cast and the listener call bound to the same view.
    FAB_RE = re.compile(
        r'check-cast (?P<reg>[vp]\d+), L(?P<cls>[\w/$]+);'
        + IN_BODY
        + r'invoke-virtual \{(?P=reg), [vp]\d+\}, Landroid/view/View;->setOnClickListener\(')

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once: see unlimited_rewinds.py.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return bool(self.SUPERLIKE_CALL_RE.search(class_data))

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self.is_found:
            return                      # not is_once; do the work once anyway
        binds = list(self.BIND_RE.finditer(class_data))
        if len(binds) != 1:
            return                      # no single bind to read -> not the card base
        bind = binds[0]
        body_end = class_data.find('.end method', bind.end())
        if body_end == -1:
            return                      # malformed -> refuse rather than guess
        body = class_data[bind.end():body_end]

        # Scoped to this one method body, never the whole class: the class also
        # holds the click lambdas, which read the same fields.
        getters = {(m.group('field'), m.group('getter'), m.group('user'))
                   for m in self.PROPS_USER_RE.finditer(body)}
        if len(getters) != 1:
            return                      # ambiguous (or none) -> do not fire
        field, getter, user = getters.pop()

        fabs = {m.group('cls') for m in self.FAB_RE.finditer(body)
                if _is_app_class(m.group('cls'))}
        if len(fabs) != 1:
            # The added Like button is positioned by copying this view's
            # layout params. No FAB means the card's action row is not the
            # shape this patch knows how to extend, and a guessed position is
            # worse than no button -- PicksLikeButton logs the unsubstituted
            # placeholder and installs nothing.
            return
        name = class_name(class_data)
        if not name:
            return

        artifacts['PICKS_CARD_BASE_CLASS_NAME'] = name
        artifacts['PICKS_PROPS_FIELD_NAME'] = field
        artifacts['PICKS_PROPS_USER_METHOD_NAME'] = getter
        artifacts['PICKS_USER_CLASS_NAME'] = user.replace('/', '.')
        artifacts['PICKS_FAB_CLASS_NAME'] = fabs.pop().replace('/', '.')
        # Only after every artifact is written.
        self.is_found = True


class PicksCardBinders(SimpleArtifactoryFinder):
    SUPER_RE = re.compile(r'^\.super L(?P<base>[\w/$]+);', re.M)

    # Kotlin's covariant-override bridge. This is the method Epoxy calls, and
    # the one that gets hooked.
    BRIDGE_RE = re.compile(
        r'^\.method public bridge synthetic (?P<bridge_name>\w+)'
        r'(?P<bridge_sig>\(Ljava/lang/Object;\)V)', re.M)

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once, and for the same second reason PremiumGate gives: every
        # card subclass is wanted, so the scan must not stop at the first.
        self.is_once = False
        self.is_found = False
        self.targets = []
        self.view_class = None

    def class_filter(self, class_data: str) -> bool:
        # Cheap: a Kotlin covariant bind bridge. Narrowed properly below.
        return bool(self.BRIDGE_RE.search(class_data))

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        super_match = self.SUPER_RE.search(class_data)
        if not super_match:
            return
        base = super_match.group('base')

        # ...and the class must be constructed with one of the base's own
        # nested types as its last argument -- the card listener. Without this,
        # the shape below matches any Kotlin covariant override chain.
        listener_ctor = re.search(
            r'\.method public constructor <init>\((?:L[\w/$]+;|\[|[ZBCSIJFD])*L'
            + re.escape(base) + r'\$\w+;\)V', class_data)
        if not listener_ctor:
            return

        # Every (Object)V bridge in the class is considered, not just the
        # first: a Kotlin class can carry several, and the bind one is picked
        # by its relationship to the base rather than by position.
        matched = []
        for bridge in self.BRIDGE_RE.finditer(class_data):
            bridge_name = bridge.group('bridge_name')
            # The specialised override this bridge forwards to must itself
            # chain to the direct superclass's method of the same name, with a
            # parameter type more specific than Object. That is what ties this
            # class to a base owning a real bind, and what excludes an Epoxy
            # model whose super-call goes to the framework's own bind(Object).
            specialised = re.search(
                r'invoke-super \{p0, p1\}, L' + re.escape(base) + r';->'
                + re.escape(bridge_name) + r'\(L(?P<view>[\w/$]+);\)V', class_data)
            if not specialised or specialised.group('view') == 'java/lang/Object':
                continue
            matched.append((bridge, specialised))
        if len(matched) != 1:
            return                      # ambiguous (or none) -> do not fire
        bridge, specialised = matched[0]
        bridge_name = bridge.group('bridge_name')

        name = class_name(class_data)
        if not name:
            return
        view = specialised.group('view').replace('/', '.')
        if self.view_class is not None and self.view_class != view:
            # Two card subclasses binding different view types would mean the
            # single PICKS_CARD_BIND_VIEW_CLASS_NAME key is a lie. Refuse.
            return
        self.view_class = view

        target = '{0}:{1}:{2}'.format(name, bridge_name, bridge.group('bridge_sig'))
        if target in self.targets:
            return
        self.targets.append(target)
        # Sorted, so the artifactory is byte-identical run to run regardless of
        # the order glob walked the smali dirs.
        artifacts['PICKS_CARD_TARGETS'] = '|'.join(sorted(self.targets))
        artifacts['PICKS_CARD_BIND_VIEW_CLASS_NAME'] = view
        self.is_found = True
