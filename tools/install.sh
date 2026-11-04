#!/bin/sh
# install.sh: the two wiring edits that make CarSystemUI link this pod, applied idempotently.
#
#   tools/install.sh [path/to/packages/apps/Car/SystemUI] [--check]
#
# Without a path the SystemUI directory is taken to be three levels above this script
# (SystemUI/pods/retro-overlay/tools). --check only reports; nothing is written.
# The build itself (m CarSystemUI RetroOverlayRRO) is left to you.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
systemui=${1:-$(cd "$here/../../.." && pwd)}
check=false
for argument in "$@"; do [ "$argument" = --check ] && check=true; done
[ "$systemui" = --check ] && systemui=$(cd "$here/../../.." && pwd)

bp="$systemui/Android.bp"
module="$systemui/src/com/android/systemui/car/wm/scalableui/panel/controller/PanelControllerModule.java"
library='"CarSystemUI-Retro-Overlay"'
import_line='import com.android.systemui.car.retro.RetroPanelModule;'
include='RetroPanelModule.class'

for file in "$bp" "$module"; do
    [ -f "$file" ] || { echo "not a CarSystemUI tree: $file missing" >&2; exit 2; }
done
grep -q '^carsysui_pods = \[' "$bp" || { echo "$bp has no carsysui_pods list; this tree is older than aosp-17_r1" >&2; exit 2; }
grep -q '@Module(includes = {' "$module" || { echo "$module has no @Module(includes = {...}); edit it by hand" >&2; exit 2; }

status=0
if grep -q "$library" "$bp"; then
    echo "Android.bp: $library already in carsysui_pods"
elif $check; then
    echo "Android.bp: $library MISSING from carsysui_pods"; status=1
else
    sed -i "/^carsysui_pods = \[/a\\    $library," "$bp"
    echo "Android.bp: added $library to carsysui_pods"
fi

if grep -q "$import_line" "$module"; then
    echo "PanelControllerModule.java: import present"
elif $check; then
    echo "PanelControllerModule.java: import MISSING"; status=1
else
    # after the last import, so the file keeps its import block in one place
    last_import=$(grep -n '^import ' "$module" | tail -1 | cut -d: -f1)
    sed -i "${last_import}a\\$import_line" "$module"
    echo "PanelControllerModule.java: import added"
fi

if grep -q "$include" "$module"; then
    echo "PanelControllerModule.java: $include already included"
elif $check; then
    echo "PanelControllerModule.java: $include MISSING from @Module(includes = {...})"; status=1
else
    sed -i "s/@Module(includes = {/@Module(includes = { $include,/" "$module"
    echo "PanelControllerModule.java: $include added to @Module includes"
fi

if $check; then
    [ $status = 0 ] && echo "wired" || echo "not wired; run without --check"
    exit $status
fi

echo
echo "Wired. Next, in your lunch'ed shell:"
echo "    m CarSystemUI RetroOverlayRRO"
echo "then push and enable as in README.md, 'Run the sample'."
