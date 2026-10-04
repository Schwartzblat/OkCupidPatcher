"""The generated holder of the SingleNotificationInfo document.

This is the query that turns a recovered user id into a display name:

    query SingleNotificationInfo($userId: String!) {
      me { match(id: $userId) { user { id displayname primaryImage { square225 } } } }
    }

It is registered server-side (see NOTES.md's "Operation registry" section),
takes the id as a *variable*, and returns the card's `square225` path beside
the name -- the same join key the rest of this package keys on, so every name
arrives with its own proof of which card it belongs to.

Read at runtime for the same reason as `likes_query_doc.py`: a version-specific
GraphQL string never enters this patcher's source. The companion object exposes
it through a zero-argument String getter.

Anchor: the literal `query SingleNotificationInfo(` -- the document's own
opening, which cannot be renamed because it is the wire format.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import class_name


class LikesNameDoc(SimpleArtifactoryFinder):
    # Same shape as LikesQueryDoc: the document is returned straight from a
    # `const-string` in the getter body, so the class that *contains* the
    # document literal is the companion, and it holds exactly one zero-arg
    # String method. The class filter is already unique; this only has to
    # disambiguate within it.
    GETTER_RE = re.compile(
        r'^\.method public final (?P<method_name>\w+)(?P<method_sig>\(\)Ljava/lang/String;)',
        re.M)

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once: see incognito_gate.py -- a removed is_once finder makes
        # stitch skip the next finder for that file.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return 'query SingleNotificationInfo(' in class_data

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
        artifacts['NAME_QUERY_DOC_CLASS_NAME'] = name
        artifacts['NAME_QUERY_DOC_METHOD_NAME'] = found['method_name']
        artifacts['NAME_QUERY_DOC_METHOD_SIG'] = found['method_sig']
        # Only after every artifact is written.
        self.is_found = True
