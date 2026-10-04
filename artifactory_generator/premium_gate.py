"""Every implementation of the app's paid-feature predicate, found by shape.

There is no `isPremium()` flag. Entitlement arrives as a GraphQL
`SessionQuery$Premiums` block, is mapped into a
`HashMap<PremiumFeatures, Boolean>`, and is read back through exactly one
method shape:

    boolean <name>(<one reference>)   // map.get(feature) == Boolean.TRUE

Two classes carry that method on 116.0.0 -- the `UserProvider` implementation
and the `SessionHelper` session singleton -- and which classes carry it is
precisely the kind of thing that moves between releases. So this finder does
not name either of them: it anchors on the two *framework* references the body
cannot avoid (`HashMap.get` and `Boolean.TRUE`, neither of which R8 can
rename), pins the method by shape, and emits one `class:method:sig` triple per
carrier under a single key.

The payoff over naming the carriers: if a third class picks up the predicate
next release, it is hooked automatically. The cost is documented in NOTES.md --
one key cannot distinguish "found both carriers" from "found one", so the gate
cannot catch a partial result. Read the gate's printed value, not just its
exit code.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class PremiumGate(SimpleArtifactoryFinder):

    MAP_ANCHOR = 'Ljava/util/HashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;'
    TRUE_ANCHOR = 'Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;'

    # boolean <name>(<one reference>) whose body does map.get(..) == Boolean.TRUE.
    #
    # Note what the pattern excludes as much as what it matches: 'public' with
    # an optional 'final' and nothing else means a `static` target can never
    # match, because 'static ' would sit between them. That is load-bearing --
    # every target hooked from this key shares the one replacement shape
    # (Object thiz, Object feature), and a static target would need a
    # thiz-less replacement instead.
    GATE_RE = re.compile(
        r'\.method public (?:final )?(?P<method_name>\w+)(?P<method_sig>\(L[\w/$]+;\)Z)'
        + IN_BODY + re.escape(MAP_ANCHOR)
        + IN_BODY + re.escape(TRUE_ANCHOR))

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once. Two reasons, both necessary:
        #   1. Every carrier is wanted, so the scan must not stop at the first.
        #   2. stitch's generate_artifactory removes a fired is_once finder
        #      from the list it is iterating, which makes the loop skip the
        #      NEXT finder for that file. A finder that is never removed can
        #      never cause that skip -- and, being last to nothing, can never
        #      be the one skipped either. See NOTES.md.
        self.is_once = False
        self.is_found = False
        self.targets = []

    def class_filter(self, class_data: str) -> bool:
        # Cheap pre-filter: 9 of 68 270 classes carry both references.
        return self.MAP_ANCHOR in class_data and self.TRUE_ANCHOR in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        matches = list(self.GATE_RE.finditer(class_data))
        if len(matches) != 1:
            return                      # ambiguous in this class -> skip it
        name = class_name(class_data)
        if not name:
            return
        found = matches[0].groupdict()
        target = '{0}:{1}:{2}'.format(name, found['method_name'], found['method_sig'])
        if target in self.targets:
            return
        self.targets.append(target)
        # Sorted, so the artifactory is byte-identical run to run regardless of
        # the order glob walked the smali dirs.
        artifacts['PREMIUM_GATE_TARGETS'] = '|'.join(sorted(self.targets))
        self.is_found = True
