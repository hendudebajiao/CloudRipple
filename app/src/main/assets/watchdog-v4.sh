#!/system/bin/sh
# Installed by this APK after KernelSU authorization. Never protect another app.
ROOT=/data/adb/notification_island
SCRIPT=$ROOT/watchdog-v4.sh
BOOT_SCRIPT=/data/adb/service.d/notification-island.sh
PKG=@PACKAGE@
APP_UID=@UID@
USER_ID=@USER@
FILES=@FILES@
COMPONENT=$PKG/com.bajiao.notificationisland.IslandNotificationListener
if ! mkdir "$ROOT/lock" 2>/dev/null; then
  old=$(cat "$ROOT/lock/pid" 2>/dev/null)
  case $old in ''|*[!0-9]*) ;; *)
    cmd=$(tr '\000' ' ' < /proc/$old/cmdline 2>/dev/null)
    case "$cmd" in "sh $SCRIPT "|"/system/bin/sh $SCRIPT ") exit 0;; esac;; esac
  rm -f "$ROOT/lock/pid"
  rmdir "$ROOT/lock" 2>/dev/null
  mkdir "$ROOT/lock" 2>/dev/null || exit 0
fi
echo $$ > "$ROOT/lock/pid"
cleanup() {
  if valid_pid "$protected_pid" && [ "$(cat /proc/$protected_pid/oom_score_adj 2>/dev/null)" = -900 ]; then
    echo "$original_score" > /proc/$protected_pid/oom_score_adj 2>/dev/null
  fi
  if [ "$(cat "$ROOT/lock/pid" 2>/dev/null)" = "$$" ]; then
    rm -f "$ROOT/lock/pid"
    rmdir "$ROOT/lock" 2>/dev/null
    if [ "$uninstalled" = 1 ]; then
      timeout 6 dumpsys deviceidle whitelist -"$PKG" >/dev/null 2>&1
      rm -f "$BOOT_SCRIPT" "$ROOT/enabled" "$ROOT/watchdog-v2.sh" "$ROOT/watchdog-v3.sh" "$SCRIPT"
      rmdir "$ROOT" 2>/dev/null
    fi
  fi
}
trap cleanup EXIT
trap 'exit 0' TERM INT
valid_pid() {
  case $1 in ''|*[!0-9]*) return 1;; esac
  [ "$(tr -d '\000' < /proc/$1/cmdline 2>/dev/null)" = "$PKG:monitor" ] || return 1
  [ "$(stat -c %u /proc/$1 2>/dev/null)" = "$APP_UID" ]
}
package_state() {
  # A failed/partial PackageManager response must never trigger deletion.
  packages=$(timeout 6 cmd package list packages -U --user "$USER_ID" 2>/dev/null) || return 2
  printf '%s\n' "$packages" | grep -Eq '^package:android uid:[0-9]+$' || return 2
  printf '%s\n' "$packages" | grep -Fxq "package:$PKG uid:$APP_UID"
}
protect() {
  p=$1
  score=$(cat /proc/$p/oom_score_adj 2>/dev/null)
  if [ "$protected_pid" != "$p" ]; then
    protected_pid=$p
    original_score=${score:-250}
    [ "$original_score" = -900 ] && original_score=250
  fi
  [ "$score" = -900 ] || echo -900 > /proc/$p/oom_score_adj 2>/dev/null
  # Vendor freezing can disagree with ActivityManager.isFrozen; inspect the kernel.
  for cg in /sys/fs/cgroup/apps/uid_$APP_UID/pid_$p/cgroup.freeze /sys/fs/cgroup/uid_$APP_UID/pid_$p/cgroup.freeze; do
    [ -f "$cg" ] && [ "$(cat "$cg" 2>/dev/null)" = 1 ] && echo 0 > "$cg" 2>/dev/null
  done
  # Some kernels expose a v1 freezer hierarchy instead.
  group=$(sed -n 's/^[0-9]*:freezer://p' /proc/$p/cgroup 2>/dev/null)
  case "$group" in *..*|/|'') ;; *)
    cg=/dev/freezer$group/freezer.state
    [ -f "$cg" ] && [ "$(cat "$cg" 2>/dev/null)" = FROZEN ] && echo THAWED > "$cg" 2>/dev/null;; esac
}
last_recover=0
last_rebind=0
last_package_check=0
missing_since=0
package_ready=0
uninstalled=0
while [ -f "$ROOT/enabled" ]; do
  now=$(date +%s)
  if [ $((now-last_package_check)) -ge 10 ]; then
    last_package_check=$now
    package_state
    state=$?
    if [ "$state" = 0 ]; then
      package_ready=1
      missing_since=0
    else
      package_ready=0
      if [ "$state" = 1 ]; then
        [ "$missing_since" = 0 ] && missing_since=$now
        # Leave time for package replacement; require repeated successful queries.
        if [ $((now-missing_since)) -ge 30 ]; then
          uninstalled=1
          exit 0
        fi
      else
        missing_since=0
      fi
    fi
  fi
  if [ "$package_ready" != 1 ]; then sleep 2; continue; fi
  p=$(cat "$FILES/monitor-pid" 2>/dev/null)
  if ! valid_pid "$p"; then
    p=
    for candidate in $(pidof "$PKG:monitor" 2>/dev/null); do
      if valid_pid "$candidate"; then p=$candidate; break; fi
    done
  fi
  if valid_pid "$p"; then protect "$p"; fi
  stamp=$(stat -c %Y "$FILES/listener-heartbeat" 2>/dev/null)
  stamp=${stamp:-0}
  if ! valid_pid "$p" || [ $((now-stamp)) -gt 12 ]; then
    if [ $((now-last_recover)) -ge 8 ]; then
      last_recover=$now
      # Foreground broadcast has no Activity and no standby FGS notification.
      timeout 6 content call --user "$USER_ID" --uri "content://$PKG.runtime" --method refresh >/dev/null 2>&1 &
    fi
    # Only repair a stale listener. Never cycle permission for a healthy stream.
    if [ $((now-stamp)) -gt 20 ] && [ $((now-last_rebind)) -ge 20 ]; then
      last_rebind=$now
      if settings --user "$USER_ID" get secure enabled_notification_listeners | tr ':' '\n' | grep -Fxq "$COMPONENT"; then
        timeout 6 cmd notification disallow_listener "$COMPONENT" "$USER_ID" >/dev/null 2>&1
        timeout 6 cmd notification allow_listener "$COMPONENT" "$USER_ID" >/dev/null 2>&1
      fi
    fi
  fi
  sleep 2
done
