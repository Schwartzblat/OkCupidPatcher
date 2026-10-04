"""`UserCardState.showBlurred` -- the Compose render flag.

`UserCardComposeKt` branches on this to pick the blurred composition path, and
it reads it from the card's own state object rather than from the `User`. It is
a separate copy of the flag, so it needs its own hook -- forcing
[BlurredUserFlag] false fixes the URL that gets loaded, this fixes what the
card draws.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class BlurredCardFlag(SimpleArtifactoryFinder):
    # Same shape, same componentN caveat, as BlurredUserFlag.
    TARGET_RE = re.compile(
        r'\.method public final (?P<method_name>(?!component\d)\w+)(?P<method_sig>\(\)Z)'
        + IN_BODY + r'->showBlurred:Z')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return 'UserCardState(userImage=' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        matches = list(self.TARGET_RE.finditer(class_data))
        if len(matches) != 1:
            return
        name = class_name(class_data)
        if not name:
            return
        found = matches[0].groupdict()
        artifacts['BLURRED_CARD_FLAG_CLASS_NAME'] = name
        artifacts['BLURRED_CARD_FLAG_METHOD_NAME'] = found['method_name']
        artifacts['BLURRED_CARD_FLAG_METHOD_SIG'] = found['method_sig']
        self.is_found = True
