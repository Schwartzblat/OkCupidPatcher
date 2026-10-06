import argparse
from pathlib import Path

from stitch import Stitch
from stitch.common import ExternalModule

# Signature finders live in ./artifactory_generator/ and are registered here.
from artifactory_generator.premium_gate import PremiumGate
from artifactory_generator.likes_cap_gate import LikesCapGate
from artifactory_generator.blurred_user_flag import BlurredUserFlag
from artifactory_generator.blurred_card_flag import BlurredCardFlag
from artifactory_generator.unlimited_rewinds import UnlimitedRewinds
from artifactory_generator.likes_payload import LikesPayload
from artifactory_generator.likes_nav_trigger import LikesNavTrigger
from artifactory_generator.likes_query_doc import LikesQueryDoc
from artifactory_generator.likes_name_doc import LikesNameDoc
from artifactory_generator.likes_card_name import LikesCardName
from artifactory_generator.likes_sort_enum import LikesSortEnum
from artifactory_generator.likes_user_accessors import UserAccessors
# NOT dead: DeanonNavProfileId is registered below and feeds OpenRealProfile.java.
# (DeanonPagePayload, the other class in this file, is the unused one.)
from artifactory_generator.deanon_likes import DeanonNavProfileId
from artifactory_generator.priority_like_tier import PriorityLikeTier, PriorityLikeModal
from artifactory_generator.picks_card import PicksCardBase, PicksCardBinders
from artifactory_generator.picks_vote import PicksVoteService, PicksVoteSource

PROVIDER_CLASS = 'com.smali_generator.InitProviderOkCupidPremium'

# The subscription check ApkCrawler injects alongside the hooks, as a second
# ExternalModule. Stitch writes this into the target's manifest as the provider's
# android:name, and the paywall module generates a class of that name from the
# PAYWALL_PROVIDER_CLASS artifact. Both are derived from this one constant so
# they cannot drift: a manifest naming a class that is not in the dex installs
# fine and dies at launch. Named per app so a shared logcat says which build is
# talking, the same convention as the Moovit, Mako and WhatsApp patchers.
PAYWALL_PROVIDER = 'com.paywall.InitProviderPaywallOkCupid'


def get_args():
    parser = argparse.ArgumentParser(description='Patch OkCupidPremium with the smali_generator hook module.')
    parser.add_argument('-p', '--apk-path', dest='apk_path', help='APK / XAPK / APKM path', required=True)
    parser.add_argument('-o', '--output', dest='output', help='Output APK path', required=False,
                        default='output.apk')
    parser.add_argument('-t', '--temp', dest='temp_path', help='Temp path for extracted content', required=False,
                        default='./temp')
    parser.add_argument('--arch', dest='arch', help='ABI whose libarthooks.so gets injected', required=False,
                        default='arm64-v8a',
                        choices=['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'])
    parser.add_argument('-g', '--google-api-key', dest='api_key', help='Custom google api key', required=False,
                        default=None)
    parser.add_argument('--no-sign', dest='should_sign', help='Whether to sign the output APK',
                        action='store_false', required=False, default=True)
    parser.add_argument('--extra-artifacts', dest='extra_artifacts',
                        help='Extra artifacts for the artifactory, in the format "key:value"',
                        required=False, default=[], nargs='+')
    parser.add_argument('--paywall', dest='paywall', help='Path to the paywall module to inject', required=False,
                        default=None)
    args, _ = parser.parse_known_args()
    return args


def main():
    args = get_args()
    extra_artifacts = {artifact.split(':', 1)[0]: artifact.split(':', 1)[1] for artifact in args.extra_artifacts}
    external_modules = [
        ExternalModule(Path(__file__).parent / './smali_generator', PROVIDER_CLASS)
    ]
    if args.paywall is not None:
        extra_artifacts.setdefault('PAYWALL_PROVIDER_CLASS', PAYWALL_PROVIDER.rsplit('.', 1)[1])
        external_modules.append(ExternalModule(Path(args.paywall), PAYWALL_PROVIDER))
    # PremiumGate first on purpose: it is the only finder that scans classes
    # another finder also anchors on, so it goes ahead of anything that could
    # be removed mid-iteration. It is is_once=False, which makes the set immune
    # to stitch's skip bug whatever the order -- see NOTES.md. Do not reorder
    # without re-reading that.
    artifactory_list = [
        PremiumGate(args),
        LikesCapGate(args),
        BlurredUserFlag(args),
        BlurredCardFlag(args),
        # is_once=False, so it can neither be skipped by nor cause the
        # mid-iteration removal bug -- its class filter (a Compose framework
        # reference) is the broadest here, 889 classes. The likes finders that
        # follow are is_once=False too, for the same reason.
        UnlimitedRewinds(args),
        LikesPayload(args),
        LikesNavTrigger(args),
        LikesQueryDoc(args),
        LikesNameDoc(args),
        LikesCardName(args),
        LikesSortEnum(args),
        UserAccessors(args),
        # NOTE: this finder lives in deanon_likes.py, a file that also holds the
        # unregistered DeanonPagePayload. The file is NOT dead: this class is
        # load-bearing (its DEANON_NAV_ID_* keys are substituted into
        # OpenRealProfile.java). Only DeanonPagePayload is unused.
        # Reads the accessor straight off the "/profile/" call site inside
        # whatever LikesNavTrigger finds, so it is the one that stays correct
        # if the app ever reads a different getter there -- deliberately not
        # the same key UserAccessors picks (USER_ID_METHOD_NAME, which is
        # free to be either getId or getUserid; see likes_user_accessors.py).
        # OpenRealProfile's substitution must land on this one specifically,
        # never on whatever submitVote reads.
        DeanonNavProfileId(args),
        # The Priority Like suppression and the Picks like button. All five are
        # is_once=False, like everything from UnlimitedRewinds down, so none of
        # them can be removed mid-iteration and make stitch skip the finder
        # registered after it -- which matters most for the last entries, where
        # a skip would be silent. Order among themselves is irrelevant: no two
        # of them anchor on the same class.
        PriorityLikeTier(args),
        PriorityLikeModal(args),
        PicksCardBase(args),
        PicksCardBinders(args),
        PicksVoteService(args),
        PicksVoteSource(args),
    ]
    with Stitch(
            apk_path=args.apk_path,
            output_apk=args.output,
            temp_path=args.temp_path,
            artifactory_list=artifactory_list,
            google_api_key=args.api_key,
            external_modules=external_modules,
            arch=args.arch,
            should_sign=args.should_sign,
            extra_artifacts=extra_artifacts,
    ) as stitch:
        stitch.patch()


if __name__ == '__main__':
    main()
