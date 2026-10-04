"""The daily-likes cap.

Note the inverted sense: this one must be forced to *false*. UNLIMITED_LIKES
through PremiumGate is the cleaner fix; this is the belt-and-braces one that
also covers the rate-card breather logic.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class LikesCapGate(SimpleArtifactoryFinder):
    # The only ()Z method in the class, and its body compares the remaining
    # like count. Pinned by shape rather than name.
    TARGET_RE = re.compile(
        r'\.method public final (?P<method_name>\w+)(?P<method_sig>\(\)Z)'
        + IN_BODY + r'LikesRemaining\(\)I')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        # No single literal identifies this class, so require the combination
        # of three members that only the likes-cap manager declares together.
        return ('DEFAULT_MAX_RATE_CARD_VIEWS' in class_data
                and 'decrementLikesRemaining' in class_data
                and 'incrementRateCardViews' in class_data)

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        matches = list(self.TARGET_RE.finditer(class_data))
        if len(matches) != 1:
            return
        name = class_name(class_data)
        if not name:
            return
        found = matches[0].groupdict()
        artifacts['LIKES_CAP_GATE_CLASS_NAME'] = name
        artifacts['LIKES_CAP_GATE_METHOD_NAME'] = found['method_name']
        artifacts['LIKES_CAP_GATE_METHOD_SIG'] = found['method_sig']
        self.is_found = True
