"""`User.showBlurred` -- the flag that picks the blurred image URL.

The LIKED YOU / VIEWED YOU teaser arrives as an `ApolloFakeUser`, and the
GraphQL query asks for **both** images:

    primaryImage        { square225 }   <- clear
    primaryImageBlurred { square800 }   <- server-blurred

`ApolloExtensionsKt.toBlurredUser` puts the clear `square225` into the User's
photos list and the blurred `square800` into `User.blurredPhoto`, then sets
`showBlurred = true`. `OkUserCardViewModel.getUserImage()` is where the choice
happens:

    if (user.getShowBlurred()) return getBlurredPhoto();        // square800
    else return photos[0].get_400x400() ?: photos[0].get_225x225();

So forcing this flag false makes the card render the clear thumbnail the client
already holds -- no URL rewriting, no extra request.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class BlurredUserFlag(SimpleArtifactoryFinder):
    # A public final zero-arg boolean reading the showBlurred backing field.
    #
    # The negative lookahead matters: Kotlin emits a `componentN()Z`
    # destructuring accessor with a body byte-for-byte identical to the
    # getter's, so without it this matches twice and the finder correctly
    # refuses to fire. componentN is a compiler convention, not an app name,
    # which is why excluding it is structural rather than guesswork.
    TARGET_RE = re.compile(
        r'\.method public final (?P<method_name>(?!component\d)\w+)(?P<method_sig>\(\)Z)'
        + IN_BODY + r'->showBlurred:Z')

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        # The data-class toString prefix: a string literal, unique to User
        # across all 68k classes. Note that ', showBlurred=' alone is NOT
        # unique -- it also appears in UserCardState, which has its own
        # finder.
        return 'User(matchHighlights=' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        matches = list(self.TARGET_RE.finditer(class_data))
        if len(matches) != 1:
            return                      # ambiguous -> do not fire
        name = class_name(class_data)
        if not name:
            return
        found = matches[0].groupdict()
        artifacts['BLURRED_USER_FLAG_CLASS_NAME'] = name
        artifacts['BLURRED_USER_FLAG_METHOD_NAME'] = found['method_name']
        artifacts['BLURRED_USER_FLAG_METHOD_SIG'] = found['method_sig']
        self.is_found = True
