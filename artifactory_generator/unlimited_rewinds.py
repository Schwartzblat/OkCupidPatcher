"""Unlimited rewinds -- the rewind token setter.

The rewind manager keeps an int token count in which **-1 means unlimited**,
and its setter maps a null argument onto that sentinel:

    public void setUserTokens(Integer tokens) {
        if (tokens == null) this.tokens = -1;     // UNLIMITED_TOKENS
        else                this.tokens = tokens.intValue();
        ... refresh the Compose replay state, still honouring hasCachedCard ...
    }

The hook rewrites the *argument* to null rather than forcing the gate, because
the gate is

    canRewind() = hasCachedCard && (tokens > 0 || tokens == -1)

and forcing the whole expression true would also assert a cached card exists
when none does. Filtering the argument leaves hasCachedCard, the replay-state
refresh and every other condition intact. That is why this finder targets a
void setter instead of a boolean predicate like every other finder here.

Anchoring: no app class, method or field name is an input. The class filter is
a framework reference R8 cannot rename, and the method is pinned purely by
shape -- the null -> -1 sentinel mapping written straight onto the parameter.
Measured against 116.0.0: the shape alone matches **1 method in 1 class out of
all 68 270**, and the filter is a cheap pre-pass that 889 of them survive.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name

# The setter refreshes a Compose state holder. Framework type, so it cannot be
# renamed; used only to skip 67 381 classes cheaply, never to identify this one.
COMPOSE_STATE = 'Landroidx/compose/runtime/MutableState;'


class UnlimitedRewinds(SimpleArtifactoryFinder):
    # An instance method taking a boxed Integer and returning void whose body
    # maps a null argument onto the -1 sentinel and stores it:
    #
    #     if-nez p1, :cond_0
    #     const/4 p1, -0x1
    #     iput p1, p0, ...->tokens:I
    #
    # 'public' plus an optional 'final' is load-bearing: it cannot match a
    # static (whose replacement would need no leading thiz) and an abstract
    # declaration has no body for the sentinel to match in.
    TARGET_RE = re.compile(
        r'\.method public (?:final )?(?P<method_name>\w+)(?P<method_sig>\(Ljava/lang/Integer;\)V)'
        + IN_BODY + r'if-nez p1, :cond_\w+'
        + IN_BODY + r'const/4 p1, -0x1'
        + IN_BODY + r'iput p1,')

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once, for the same reason as [IncognitoGate]: a fired is_once
        # finder is removed from the list mid-iteration, which makes stitch's
        # loop skip the next finder for that file (see NOTES.md). Never removed
        # means never able to cause it, whatever the list order. The guard in
        # extract_artifacts keeps the cost of staying in the list at one
        # boolean per class.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return COMPOSE_STATE in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self.is_found:
            return                      # not is_once; do the work once anyway
        matches = list(self.TARGET_RE.finditer(class_data))
        if len(matches) != 1:
            return                      # ambiguous -> do not fire
        name = class_name(class_data)
        if not name:
            return
        found = matches[0].groupdict()
        artifacts['UNLIMITED_REWINDS_CLASS_NAME'] = name
        artifacts['UNLIMITED_REWINDS_METHOD_NAME'] = found['method_name']
        artifacts['UNLIMITED_REWINDS_METHOD_SIG'] = found['method_sig']
        # Only after every artifact is written.
        self.is_found = True
