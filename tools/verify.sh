#!/bin/sh
# verify.sh: read-only checks that the look is on and healthy on the connected device.
#
#   tools/verify.sh [-s serial]
#
# Prints one PASS/FAIL line per check and exits non-zero on any FAIL. Nothing is changed on
# the device except that retro_off/retro_on are dispatched once to produce fresh log lines.
set -u
adb="adb $*"
status=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; status=1; }

overlay=com.android.systemui.rro.retro
current_user=$($adb shell am get-current-user | tr -d '\r')
enabled_for=""
for user in 0 "$current_user"; do
    if $adb shell cmd overlay list --user "$user" 2>/dev/null | grep -q "^\[x\] $overlay"; then
        enabled_for="$enabled_for $user"
    fi
done
if [ -n "$enabled_for" ]; then pass "overlay $overlay enabled for user(s):$enabled_for"
else fail "overlay $overlay not enabled for user 0 or $current_user (cmd overlay enable --user current $overlay, then restart SystemUI)"; fi

errors_before=$($adb logcat -d | grep -c 'Binder buffer full')

$adb shell cmd statusbar carsysui-dispatch-event retro_off >/dev/null
sleep 0.6
$adb shell cmd statusbar carsysui-dispatch-event retro_on >/dev/null
sleep 1.5

tint=$($adb logcat -d | grep '\[retro\] tint' | tail -1)
if [ -n "$tint" ]; then pass "controller put the look on: ${tint#*\[retro\] }"
else fail "no '[retro] tint … on …' line after retro_on (is the overlay panel in window_states, and does its controller XML name RetroController?)"; fi

# the cover carries the pod's name; the strips are SurfaceFlinger mirrors and show as "mirrored from"
surfaces=$($adb shell dumpsys SurfaceFlinger)
cover=$(printf '%s\n' "$surfaces" | grep -c 'retro-cover')
mirrors=$(printf '%s\n' "$surfaces" | grep -c 'mirrored from')
if [ "$cover" -gt 0 ]; then pass "SurfaceFlinger lists the cover ($cover lines) and $mirrors mirror lines"
else echo "INFO  no retro-cover layer: strips=0 or the look is overlay-only (fine if intended)"; fi

no_window=$($adb shell dumpsys input | grep -c "result='NO_WINDOW'")
if [ "$no_window" = 0 ]; then pass "no focus request ended in NO_WINDOW"
else fail "$no_window focus request(s) ended in NO_WINDOW: a panel is not taking input (too many mirror windows: lower retro_barrel_strips, keep jitter 0 on the whole display)"; fi

sleep 2
errors_after=$($adb logcat -d | grep -c 'Binder buffer full')
if [ "$errors_after" -le "$errors_before" ]; then pass "no new 'Binder buffer full' while idle"
else fail "$((errors_after - errors_before)) new 'Binder buffer full' lines while idle: the window list no longer fits one binder call (lower strips)"; fi

if $adb shell dumpsys SurfaceFlinger | grep -q 'h/w composer disabled'; then
    echo "INFO  hardware composer disabled: the tint is visible on screen"
elif $adb shell getprop ro.kernel.qemu | grep -q 1; then
    echo "INFO  emulator with HWC on: the tint is invisible on screen (adb shell service call SurfaceFlinger 1008 i32 1)"
fi

exit $status
