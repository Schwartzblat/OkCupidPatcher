"""The generated holder of the LikesYouPageQuery document.

Reading the document at runtime is what keeps a version-specific GraphQL
string out of this patcher's source. The companion object exposes it through a
zero-argument String getter.

Anchor: the literal `query LikesYouPageQuery(` -- the document's own opening,
which cannot be renamed because it is the wire format. Measured against
116.0.0: 1 class out of 68 270.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import class_name


class LikesQueryDoc(SimpleArtifactoryFinder):
    # A zero-arg String method. The document is returned straight from a
    # `const-string` in the getter body (there is no OPERATION_DOCUMENT field
    # reference to anchor on -- measured), so the shape is: exactly one such
    # method in the class. The class filter is already unique, so this only
    # has to disambiguate within it.
    GETTER_RE = re.compile(
        r'^\.method public final (?P<method_name>\w+)(?P<method_sig>\(\)Ljava/lang/String;)',
        re.M)

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once: see incognito_gate.py -- a removed
        # is_once finder makes stitch skip the next finder for that file.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return 'query LikesYouPageQuery(' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        if self.is_found:
            return                      # not is_once; do the work once anyway
        matches = list(self.GETTER_RE.finditer(class_data))
        if len(matches) != 1:
            return
        name = class_name(class_data)
        if not name:
            return
        found = matches[0].groupdict()
        artifacts['LIKES_QUERY_DOC_CLASS_NAME'] = name
        artifacts['LIKES_QUERY_DOC_METHOD_NAME'] = found['method_name']
        artifacts['LIKES_QUERY_DOC_METHOD_SIG'] = found['method_sig']
        # Only after every artifact is written.
        self.is_found = True
