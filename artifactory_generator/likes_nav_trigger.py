"""The real tap-to-profile entry point for a likes-grid card.

`LikesPageFragment.navigateToProfile(String)` -- what [LikesNav] used to target
-- is dead code on 116.0.0: Task 11's on-device verification found zero PATCH
log lines on every tap, traced it to zero callers of that method anywhere in
the dex (confirmed twice -- once by grepping the full extracted smali tree,
once empirically with an unconditional diagnostic log in the hook's own
replacement, which never printed). The live call is
`LikesPageController.triggerNavigateToProfile(User)`:

    invoke-virtual {p1}, Lcom/okcupid/okcupid/data/model/User;->getUserid()Ljava/lang/String;
    ...
    const-string v2, "/profile/"
    ...                                   ; builds "/profile/" + getUserid(), launches it

This finder replaces [LikesNav] and its NAV_PROFILE_* keys outright.

Anchored on shape alone, with no app name or class name as input: a method
taking exactly one reference-typed parameter and returning void, whose body
calls some zero-arg String-returning method on that parameter immediately
before building the "/profile/" literal. That shape matches 18 classes with a
`(L...;)V` taking a `User`, but requiring the String-getter call ahead of the
literal narrows it to exactly one: measured 1/68 270 against 116.0.0, in
`LikesPageController`. The accessor itself is not named here -- `getUserid` is
captured by [DeanonNavProfileId] independently, from the same call site, for
the matching reason: this finder only needs to know WHERE to install the
navigation hook, not which getter it reads.

A second artifact, NAV_TRIGGER_CAME_FROM_CLASS_NAME, is pulled from the rest
of the SAME matched method body (never from a second scan of the class): the
hook that reimplements this method must also reproduce its CameFrom lookup
faithfully (see OpenRealProfile.java and NOTES.md for why that is load-bearing
and not just analytics), and the only way to do that without hardcoding the
enum's name is to read it off the one `sget-object ...->DEFAULT:L<cls>;` this
method itself contains, the same way the method's own class and method name
are read rather than assumed.
"""
import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder

from artifactory_generator._smali import IN_BODY, class_name


class LikesNavTrigger(SimpleArtifactoryFinder):
    # A single-reference-param void method whose body reads some zero-arg
    # String getter off that parameter, then builds "/profile/<result>".
    TARGET_RE = re.compile(
        r'\.method (?:(?:public|private|protected) )?(?:final )?(?P<method_name>\w+)'
        r'(?P<method_sig>\(L[\w/$]+;\)V)'
        + IN_BODY + r'invoke-virtual \{p\d+\}, L[\w/$]+;->\w+\(\)Ljava/lang/String;'
        + IN_BODY + r'const-string [vp]\d+, "/profile/"')

    # The rest of the SAME method, after the route is built, reads an app
    # enum's DEFAULT constant as the fallback of a 4-way switch (see the
    # full body reproduced in NOTES.md). "DEFAULT" is a literal enum member
    # name, not a class name -- the class itself is captured from whichever
    # type declares it, so nothing here assumes what that type is called.
    # Scoped to the rest of THIS method (up to .end method), not the whole
    # class, so it cannot accidentally match an unrelated DEFAULT elsewhere.
    CAME_FROM_RE = re.compile(
        r'sget-object [vp]\d+, L(?P<cls>[\w/$]+);->DEFAULT:L[\w/$]+;')

    def __init__(self, args):
        super().__init__(args)
        # NOT is_once: see incognito_gate.py -- a
        # fired is_once finder is removed mid-iteration and would make stitch
        # skip whatever is registered right after it for this same file.
        self.is_once = False
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return '"/profile/"' in class_data

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

        # The method's own remaining body, to look for the CameFrom fallback
        # read without risking a match from a different method entirely.
        body_end = class_data.find('.end method', matches[0].end())
        if body_end == -1:
            return                      # malformed/unexpected -> refuse
        rest_of_method = class_data[matches[0].end():body_end]
        came_from_matches = list(self.CAME_FROM_RE.finditer(rest_of_method))
        # Expect exactly one enum type read via its DEFAULT constant in this
        # method; more than one (or none) means the shape assumed here no
        # longer holds, and guessing would risk tagging the wrong type.
        if len(came_from_matches) != 1:
            return
        came_from_cls = came_from_matches[0].group('cls').replace('/', '.')

        artifacts['NAV_TRIGGER_CLASS_NAME'] = name
        artifacts['NAV_TRIGGER_METHOD_NAME'] = found['method_name']
        artifacts['NAV_TRIGGER_METHOD_SIG'] = found['method_sig']
        artifacts['NAV_TRIGGER_CAME_FROM_CLASS_NAME'] = came_from_cls
        # Only after every artifact is written.
        self.is_found = True
