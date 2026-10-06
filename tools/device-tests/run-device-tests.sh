#!/usr/bin/env bash
# Runs every opt-in Android device test with one command:
#
#   tools/device-tests/run-device-tests.sh            (SKIP_BUILD=1 reuses the last debug build)
#
# Needs two emulators named Starglean_QA and Starglean_Location_QA (the tests refuse to run
# anywhere else). Each one boots with wiped data and is shut down afterwards. Tests that
# contact real services (nearbyNetworkQa, liveWeather, captureWeather) are never enabled.
# Uses the debug build, so the release signing key is not needed.
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
APP="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TESTS="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
RUNNER="com.starglean.app.test/androidx.test.runner.AndroidJUnitRunner"
P="com.starglean.app"
OUT="$ROOT/app/build/device-test-results/$(date +%Y%m%d-%H%M%S)"
SERIAL=""
FAILED=0
mkdir -p "$OUT"

adb() { "$ADB" -s "$SERIAL" "$@"; }

boot() {  # boot <avd> <port>
  "$EMULATOR" -list-avds | grep -qx "$1" || { echo "Missing emulator $1. Create it in Android Studio first."; exit 1; }
  "$ADB" devices | grep -q "emulator-$2" && { echo "Port $2 is busy. Close the running emulator first."; exit 1; }
  SERIAL="emulator-$2"
  echo "== $1: booting with fresh data"
  "$EMULATOR" -avd "$1" -port "$2" -no-window -gpu host -no-snapshot -wipe-data -no-boot-anim \
    > "$OUT/emulator-$1.log" 2>&1 &
  adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
  adb install -r -t "$APP" > /dev/null && adb install -r -t "$TESTS" > /dev/null || { echo "Install failed"; exit 1; }
  # Warm-up launch: the first start after a fresh install can exceed a test's 12 s wait.
  adb shell am start -W -n "$P/.MainActivity" > /dev/null
  adb shell am force-stop "$P"
}

shutdown() {
  adb emu kill > /dev/null 2>&1
  while "$ADB" devices | grep -q "$SERIAL"; do sleep 1; done
  SERIAL=""
}
trap '[ -n "$SERIAL" ] && "$ADB" -s "$SERIAL" emu kill > /dev/null 2>&1' EXIT

fresh_data() {  # empty app data, plus the permissions the location tests expect
  adb shell pm clear "$P" > /dev/null
  adb shell pm grant "$P" android.permission.ACCESS_COARSE_LOCATION
  adb shell pm grant "$P" android.permission.ACCESS_FINE_LOCATION
  adb shell appops set "$P" android:mock_location allow
}

run() {  # run <name> <am instrument arguments...>
  local name="$1"; shift
  adb shell am instrument -w "$@" "$RUNNER" > "$OUT/$name.log" 2>&1
  local result
  result="$(grep -E '^OK \(|^Tests run:' "$OUT/$name.log" | tail -1)"
  case "$result" in OK*) ;; *) FAILED=$((FAILED + 1)); result="FAILED  ${result:-no result, see log}" ;; esac
  printf '%-34s %s\n' "$name" "$result" | tee -a "$OUT/summary.txt"
}

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  echo "== Building debug app and tests"
  export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
  "$ROOT/gradlew" -p "$ROOT" :app:assembleDebug :app:assembleDebugAndroidTest -q || exit 1
fi

# Starglean_QA: every UI check that does not need the location emulator, in one run.
boot Starglean_QA 5560
flags=()
for f in accountPresentationQa arrivalQa discoveryPromptQa improvementsQa locationEntryQa \
    onlineNavigationQa placeQa publishingLocationQa reactionNoticeQa readingShellQa \
    registrationQa repairVisualQa writingPresentationQa; do
  flags+=(-e "$f" true)
done
run qa-emulator-suite "${flags[@]}" -e notClass "$P.EnglishEditionDeviceTest,$P.PhoneCollectionUpgradeDeviceTest,\
$P.LocalCollectionFlowDeviceTest,$P.LocalMediaFlowDeviceTest,$P.core.draft.WritingDraftFlowDeviceTest,\
$P.core.location.ForegroundLocationDeviceTest"
shutdown

# Starglean_Location_QA: these tests expect an empty library, so each gets fresh data.
boot Starglean_Location_QA 5562
fresh_data; run english-pages -e englishQa true -e class "$P.EnglishEditionDeviceTest#fourPhonePagesAndDisclosureUseEnglish"
fresh_data; run collection-flow -e starsQa true -e class "$P.LocalCollectionFlowDeviceTest"
fresh_data; run published-star -e starsQa true -e class "$P.PublishedStarPageDeviceTest"
fresh_data; run media-flow -e completionMediaQa true -e class "$P.LocalMediaFlowDeviceTest"
fresh_data; run foreground-location -e locationQa true -e class "$P.core.location.ForegroundLocationDeviceTest"

# Draft survives a cold process, as after Android kills the app in the background.
fresh_data; run draft-prepare -e draftPhase prepare -e class "$P.core.draft.WritingDraftFlowDeviceTest"
adb shell am force-stop "$P"
run draft-verify-after-kill -e draftPhase verify -e class "$P.core.draft.WritingDraftFlowDeviceTest"

# Local data survives installing over the existing app. PhoneCollectionUpgradeDeviceTest is
# left out: it needs data written by an older release APK.
upgrade_check() {  # upgrade_check <name> <flag> <test>
  fresh_data
  run "$1-prepare" -e upgradePhase prepare -e "$2" true -e class "$3"
  adb install -r -t "$APP" > /dev/null
  run "$1-verify" -e upgradePhase verify -e "$2" true -e class "$3"
}
upgrade_check english-upgrade englishQa "$P.EnglishEditionDeviceTest#existingEntryIsUnchangedByTheEnglishUpdate"
upgrade_check location-upgrade locationQa "$P.core.location.ForegroundLocationDeviceTest#explicitUpgradeFixtureSurvivesReplacingOldApk"
shutdown

echo
echo "Logs: $OUT"
[ "$FAILED" -eq 0 ] && echo "All device test runs passed." || echo "$FAILED run(s) failed."
[ "$FAILED" -eq 0 ]
