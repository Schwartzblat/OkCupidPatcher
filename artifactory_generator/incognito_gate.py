"""Incognito availability -- `SessionHelper.isIncognitoEnabled()`.

Split out of the former `session_gates.py`: the premium half of that finder is
now covered by the multi-target [PremiumGate], which discovers `SessionHelper`
by shape instead of by name.

`UserProviderConcrete` has an instance method of the same name that falls
through to this static one, so this single static covers both.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class IncognitoGate(SimpleArtifactoryFinder):
    # A genuinely static zero-arg boolean reading Session.isIncognito(). The
    # class's only other static ()Z (isLoggedIn) never touches it.
    TARGET_RE = re.compile(
        r'\.method public static final (?P<method_name>\w+)(?P<method_sig>\(\)Z)'
        + IN_BODY + r'->isIncognito\(\)')

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once, and this one is not optional either: this finder's
        # anchor class is SessionHelper, which is also one of the classes
        # PremiumGate has to scan. A fired is_once finder is removed from the
        # list mid-iteration, which makes the loop skip the next finder FOR
        # THAT FILE -- so an is_once IncognitoGate sitting immediately before
        # PremiumGate would cost PremiumGate its SessionHelper target, leaving
        # PREMIUM_GATE_TARGETS populated with one triple instead of two and the
        # gate none the wiser. Never removed means never able to cause it.
        #
        # The gate's "never fired" FAIL only covers is_once finders, so the
        # safety net here is its other check: all three keys below would be
        # missing, and the three {{...}} in IncognitoGate.java would FAIL as
        # placeholders with no artifactory key.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        # A log literal unique to SessionHelper across all 68 270 classes.
        return 'session load error' in class_data

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
        artifacts['INCOGNITO_GATE_CLASS_NAME'] = name
        artifacts['INCOGNITO_GATE_METHOD_NAME'] = found['method_name']
        artifacts['INCOGNITO_GATE_METHOD_SIG'] = found['method_sig']
        # Only after every artifact is written.
        self.is_found = True
