#!/system/bin/sh
#
# MT Manager / Android shell helper for MelodyCodecTweaker.
#
# It prints a readable snapshot of:
# - current media session package / title / artist / album
# - latest LHDC V5 encoder quality_mode / target bit rate / sample rate / codec_specific_1
# - memory patch status and common failure signals from logcat
#
# Default behavior:
# - parse the latest logcat buffer
# - then listen to fresh logcat for LIVE_SECONDS seconds
#
# Tip: while the script is listening, switch LHDC quality away from "音质优先"
# and back, or reconnect the headset, so Android emits fresh encoder logs.

LIVE_SECONDS="${LIVE_SECONDS:-10}"
TAIL_LINES="${TAIL_LINES:-6000}"
KEEP_LOGS="${KEEP_LOGS:-0}"

case "$1" in
    --dump)
        LIVE_SECONDS=0
        ;;
    --live)
        if [ -n "$2" ]; then
            LIVE_SECONDS="$2"
        fi
        ;;
    --help|-h)
        printf '%s\n' \
            "Usage:" \
            "  sh mt_lhdc_status.sh          # parse recent logs, then listen 10s" \
            "  sh mt_lhdc_status.sh --dump   # only parse current logcat buffer" \
            "  sh mt_lhdc_status.sh --live 20" \
            "" \
            "Optional env vars:" \
            "  LIVE_SECONDS=15" \
            "  TAIL_LINES=8000" \
            "  KEEP_LOGS=1" \
            "" \
            "During live capture, trigger A2DP reconfiguration:" \
            "  - switch LHDC quality to another option and back to 音质优先, or" \
            "  - reconnect the headset."
        exit 0
        ;;
esac

case "$LIVE_SECONDS" in
    ''|*[!0-9]*) LIVE_SECONDS=10 ;;
esac
case "$TAIL_LINES" in
    ''|*[!0-9]*) TAIL_LINES=6000 ;;
esac

command_exists() {
    command -v "$1" >/dev/null 2>&1
}

pick_work_dir() {
    for d in "${TMPDIR:-}" /data/local/tmp /sdcard/Download /sdcard .; do
        [ -n "$d" ] || continue
        if [ -d "$d" ] && [ -w "$d" ]; then
            echo "$d"
            return
        fi
    done
    echo "."
}

WORK_DIR="$(pick_work_dir)"
BASE="${WORK_DIR}/mt_lhdc_status_$$"
MEDIA_RAW="${BASE}_media.txt"
MEDIA_INFO="${BASE}_media_info.txt"
LOG_DUMP="${BASE}_dump.log"
LOG_LIVE="${BASE}_live.log"
LOG_ALL="${BASE}_all.log"

cleanup() {
    if [ "$KEEP_LOGS" != "1" ]; then
        rm -f "$MEDIA_RAW" "$MEDIA_INFO" "$LOG_DUMP" "$LOG_LIVE" "$LOG_ALL" 2>/dev/null
    fi
}
trap cleanup EXIT INT TERM

need_tool() {
    if ! command_exists "$1"; then
        echo "缺少命令: $1"
        exit 1
    fi
}

need_tool awk
need_tool logcat

HAVE_SU=0
if command_exists su; then
    if su -c true >/dev/null 2>&1; then
        HAVE_SU=1
    fi
fi

echo "Melody LHDC 状态采集"
echo "日志窗口: 最近 ${TAIL_LINES} 行 + 实时监听 ${LIVE_SECONDS} 秒"
if [ "$HAVE_SU" = "1" ]; then
    echo "权限模式: root"
else
    echo "权限模式: 普通 shell/app（可能读不到系统蓝牙日志）"
fi
echo

MEDIA_SOURCE="none"
: > "$MEDIA_RAW"
if [ "$HAVE_SU" = "1" ]; then
    su -c "cmd media_session list-sessions" >> "$MEDIA_RAW" 2>/dev/null
    if [ -s "$MEDIA_RAW" ]; then
        MEDIA_SOURCE="root:cmd media_session"
    fi
    su -c "dumpsys media_session" >> "$MEDIA_RAW" 2>/dev/null
    if [ -s "$MEDIA_RAW" ]; then
        if [ "$MEDIA_SOURCE" = "none" ]; then
            MEDIA_SOURCE="root:dumpsys media_session"
        else
            MEDIA_SOURCE="${MEDIA_SOURCE}+dumpsys"
        fi
    fi
fi
if command_exists cmd; then
    BEFORE_MEDIA="$(awk 'END { print NR + 0 }' "$MEDIA_RAW" 2>/dev/null)"
    cmd media_session list-sessions >> "$MEDIA_RAW" 2>/dev/null
    AFTER_MEDIA="$(awk 'END { print NR + 0 }' "$MEDIA_RAW" 2>/dev/null)"
    if [ "$AFTER_MEDIA" != "$BEFORE_MEDIA" ]; then
        if [ "$MEDIA_SOURCE" = "none" ]; then
            MEDIA_SOURCE="direct:cmd media_session"
        else
            MEDIA_SOURCE="${MEDIA_SOURCE}+direct_cmd"
        fi
    fi
fi
if command_exists dumpsys; then
    BEFORE_MEDIA="$(awk 'END { print NR + 0 }' "$MEDIA_RAW" 2>/dev/null)"
    dumpsys media_session >> "$MEDIA_RAW" 2>/dev/null
    AFTER_MEDIA="$(awk 'END { print NR + 0 }' "$MEDIA_RAW" 2>/dev/null)"
    if [ "$AFTER_MEDIA" != "$BEFORE_MEDIA" ]; then
        if [ "$MEDIA_SOURCE" = "none" ]; then
            MEDIA_SOURCE="direct:dumpsys media_session"
        else
            MEDIA_SOURCE="${MEDIA_SOURCE}+direct_dumpsys"
        fi
    fi
fi

LOG_SOURCE="none"
if [ "$HAVE_SU" = "1" ]; then
    su -c "logcat -d -v time -b all -t $TAIL_LINES" > "$LOG_DUMP" 2>/dev/null
    [ -s "$LOG_DUMP" ] && LOG_SOURCE="root:logcat -d -t"
    if [ ! -s "$LOG_DUMP" ]; then
        su -c "logcat -d -v time -b all" > "$LOG_DUMP" 2>/dev/null
        [ -s "$LOG_DUMP" ] && LOG_SOURCE="root:logcat -d"
    fi
fi
if [ ! -s "$LOG_DUMP" ]; then
    logcat -d -v time -b all -t "$TAIL_LINES" > "$LOG_DUMP" 2>/dev/null
    [ -s "$LOG_DUMP" ] && LOG_SOURCE="direct:logcat -d -t"
fi
if [ ! -s "$LOG_DUMP" ]; then
    logcat -d -v time -b all > "$LOG_DUMP" 2>/dev/null
    [ -s "$LOG_DUMP" ] && LOG_SOURCE="direct:logcat -d"
fi

: > "$LOG_LIVE"
LIVE_SOURCE="none"
if [ "$LIVE_SECONDS" -gt 0 ]; then
    echo "正在监听新日志 ${LIVE_SECONDS} 秒..."
    echo "现在可以切换一次 LHDC 音质，或重连耳机来触发 encoder update。"
    if [ "$HAVE_SU" = "1" ]; then
        su -c "logcat -v time -b all" > "$LOG_LIVE" 2>/dev/null &
        LIVE_SOURCE="root:logcat live"
    else
        logcat -v time -b all > "$LOG_LIVE" 2>/dev/null &
        LIVE_SOURCE="direct:logcat live"
    fi
    LOGCAT_PID=$!
    sleep "$LIVE_SECONDS"
    kill "$LOGCAT_PID" 2>/dev/null
    wait "$LOGCAT_PID" 2>/dev/null
    echo
fi

cat "$LOG_DUMP" "$LOG_LIVE" > "$LOG_ALL" 2>/dev/null
LOG_LINES="$(awk 'END { print NR + 0 }' "$LOG_ALL" 2>/dev/null)"
[ -n "$LOG_LINES" ] || LOG_LINES=0

awk '
function trim(s) {
    gsub(/\r/, "", s)
    gsub(/^[[:space:]]+/, "", s)
    gsub(/[[:space:]]+$/, "", s)
    return s
}
function value_after(line, key, v) {
    if (index(line, key) == 0) return ""
    v = line
    sub("^.*" key, "", v)
    sub(/,.*/, "", v)
    return trim(v)
}
function metadata_value(line, key, v) {
    if (index(line, key) == 0) return ""
    v = line
    sub("^.*" key, "", v)
    sub(/,[[:space:]]*[A-Za-z0-9_.]+=.*/, "", v)
    sub(/[})][[:space:]]*$/, "", v)
    return trim(v)
}
function package_from_header(line, v) {
    v = line
    sub(/^[[:space:]]*Session[[:space:]]+/, "", v)
    sub(/[\/ ].*$/, "", v)
    if (v ~ /^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$/) return v
    return ""
}
function package_after(line, key, v) {
    if (index(line, key) == 0) return ""
    v = line
    sub("^.*" key, "", v)
    sub(/[ ,})].*$/, "", v)
    return trim(v)
}
function reset() {
    pkg = ""; title = ""; artist = ""; album = ""; playing = 0
}
function save_first() {
    if (first_pkg == "" && pkg != "") {
        first_pkg = pkg; first_title = title; first_artist = artist
        first_album = album; first_state = playing ? "playing" : "seen"
    }
}
function save_playing() {
    if (best_pkg == "" && pkg != "") {
        best_pkg = pkg; best_title = title; best_artist = artist
        best_album = album; best_state = "playing"
    }
}
function finish() {
    if (pkg != "") {
        save_first()
        if (playing) save_playing()
    }
}
BEGIN { reset() }
/^[[:space:]]*(Session|SessionRecord)/ && NR > 1 {
    finish()
    reset()
}
{
    line = $0
    if (line ~ /^[[:space:]]*Session[[:space:]]+/) {
        v = package_from_header(line)
        if (v != "") pkg = v
    }
    if (index(line, "package=") > 0) {
        v = package_after(line, "package=")
        if (v != "") pkg = v
    }
    if (index(line, "packageName=") > 0) {
        v = package_after(line, "packageName=")
        if (v != "") pkg = v
    }
    if (index(line, "pkg=") > 0) {
        v = package_after(line, "pkg=")
        if (v != "") pkg = v
    }
    if (line ~ /state=3|STATE_PLAYING/) playing = 1
    if (index(line, "android.media.metadata.TITLE=") > 0) {
        title = metadata_value(line, "android.media.metadata.TITLE=")
    } else if (index(line, "title=") > 0 && title == "") {
        title = metadata_value(line, "title=")
    }
    if (index(line, "android.media.metadata.ARTIST=") > 0) {
        artist = metadata_value(line, "android.media.metadata.ARTIST=")
    } else if (index(line, "artist=") > 0 && artist == "") {
        artist = metadata_value(line, "artist=")
    }
    if (index(line, "android.media.metadata.ALBUM=") > 0) {
        album = metadata_value(line, "android.media.metadata.ALBUM=")
    } else if (index(line, "album=") > 0 && album == "") {
        album = metadata_value(line, "album=")
    }
}
END {
    finish()
    if (best_pkg != "") {
        print best_pkg; print best_title; print best_artist; print best_album; print best_state
    } else if (first_pkg != "") {
        print first_pkg; print first_title; print first_artist; print first_album; print first_state
    } else {
        print "未知"; print "未知"; print "未知"; print "未知"; print "unknown"
    }
}
' "$MEDIA_RAW" > "$MEDIA_INFO" 2>/dev/null

PLAYER_PKG="$(sed -n '1p' "$MEDIA_INFO" 2>/dev/null)"
TRACK_TITLE="$(sed -n '2p' "$MEDIA_INFO" 2>/dev/null)"
TRACK_ARTIST="$(sed -n '3p' "$MEDIA_INFO" 2>/dev/null)"
TRACK_ALBUM="$(sed -n '4p' "$MEDIA_INFO" 2>/dev/null)"
PLAYER_STATE="$(sed -n '5p' "$MEDIA_INFO" 2>/dev/null)"

[ -n "$PLAYER_PKG" ] || PLAYER_PKG="未知"
[ -n "$TRACK_TITLE" ] || TRACK_TITLE="未知"
[ -n "$TRACK_ARTIST" ] || TRACK_ARTIST="未知"
[ -n "$TRACK_ALBUM" ] || TRACK_ALBUM="未知"
[ -n "$PLAYER_STATE" ] || PLAYER_STATE="unknown"

NOW="$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)"
DEVICE="$(getprop ro.product.model 2>/dev/null)"
ROM="$(getprop ro.build.version.incremental 2>/dev/null)"

awk \
    -v now="$NOW" \
    -v device="$DEVICE" \
    -v rom="$ROM" \
    -v player_pkg="$PLAYER_PKG" \
    -v track_title="$TRACK_TITLE" \
    -v track_artist="$TRACK_ARTIST" \
    -v track_album="$TRACK_ALBUM" \
    -v player_state="$PLAYER_STATE" \
    -v tail_lines="$TAIL_LINES" \
    -v live_seconds="$LIVE_SECONDS" \
    -v log_source="$LOG_SOURCE" \
    -v live_source="$LIVE_SOURCE" \
    -v media_source="$MEDIA_SOURCE" \
    -v log_lines="$LOG_LINES" '
function trim(s) {
    gsub(/\r/, "", s)
    gsub(/^[[:space:]]+/, "", s)
    gsub(/[[:space:]]+$/, "", s)
    return s
}
function value_after(line, key, v) {
    if (index(line, key) == 0) return ""
    v = line
    sub("^.*" key, "", v)
    sub(/[ ,].*$/, "", v)
    return trim(v)
}
function number_after(line, key, v) {
    if (index(line, key) == 0) return ""
    v = line
    sub("^.*" key "[ ]*", "", v)
    sub(/[^0-9].*$/, "", v)
    return trim(v)
}
function package_after(line, key, v) {
    if (index(line, key) == 0) return ""
    v = line
    sub("^.*" key, "", v)
    sub(/[ ,})].*$/, "", v)
    return trim(v)
}
function short_line(line) {
    if (length(line) > 220) return substr(line, 1, 220) "..."
    return line
}
function known_s1(v) {
    if (v == "32776") return "32776 (音质优先 / 1000 kbps)"
    if (v == "32777") return "32777 (自适应 / ABR)"
    if (v == "32775") return "32775 (900 kbps)"
    return v != "" ? v : "未知"
}
function known_rate(v) {
    if (v == "") return "未知"
    if (v == "44100") return "44.1 kHz (44100 Hz)"
    if (v == "48000") return "48 kHz (48000 Hz)"
    if (v == "88200") return "88.2 kHz (88200 Hz)"
    if (v == "96000") return "96 kHz (96000 Hz)"
    if (v == "176400") return "176.4 kHz (176400 Hz)"
    if (v == "192000") return "192 kHz (192000 Hz)"
    if (v == "0x1" || v == "1") return "44.1 kHz (rate=0x1)"
    if (v == "0x2" || v == "2") return "48 kHz (rate=0x2)"
    if (v == "0x4" || v == "4") return "88.2 kHz (rate=0x4)"
    if (v == "0x8" || v == "8") return "96 kHz (rate=0x8)"
    if (v == "0x10" || v == "16") return "176.4 kHz (rate=0x10)"
    if (v == "0x20" || v == "32") return "192 kHz (rate=0x20)"
    return v
}
function display_quality(q) {
    if (q == "") return "未在日志窗口找到"
    if (encoder_stale) return q " (旧 encoder 日志，早于本次 target bit rate)"
    return q
}
{
    line = $0
    log_count++

    if (fallback_pkg == "") {
        focus_line = 0
        if (index(line, "requestAudioFocus") > 0) focus_line = 1
        if (index(line, "MediaFocusControl") > 0) focus_line = 1
        if (index(line, "AudioFocus") > 0) focus_line = 1
        if (focus_line) {
            if (index(line, "pkg=") > 0) fallback_pkg = package_after(line, "pkg=")
            else if (index(line, "packageName=") > 0) fallback_pkg = package_after(line, "packageName=")
            else if (index(line, "package=") > 0) fallback_pkg = package_after(line, "package=")
        }
    }

    if (index(line, "lhdc.memory_patch") > 0) {
        patch_line = line
        if (index(line, "status=") > 0) patch_status = value_after(line, "status=")
        if (index(line, "success=") > 0) patch_success = value_after(line, "success=")
    }

    if (index(line, "quality_mode=") > 0) {
        quality_mode = value_after(line, "quality_mode=")
        encoder_line = line
        encoder_seq = NR
        sample_rate = value_after(line, "sample_rate=")
        pcm_fmt = value_after(line, "pcm_fmt=")
        mtu = value_after(line, "mtu=")
        peer_mtu = value_after(line, "peer_mtu=")
        max_idx = value_after(line, "maxBitRateIdx=")
        min_idx = value_after(line, "minBitRateIdx=")
    }

    if (index(line, "target bit rate:") > 0) {
        target_bitrate = number_after(line, "target bit rate:")
        max_bitrate = number_after(line, "max bit rate:")
        target_line = line
        target_seq = NR
    }

    if (index(line, "codec_specific_1:") > 0 && index(line, "LHDC") > 0) {
        codec_specific1 = number_after(line, "codec_specific_1:")
        codec_sample_rate = number_after(line, "sample_rate:")
        codec_line = line
    }

    if (index(line, "bt.native.setCodecConfigPreference") > 0 && index(line, "s1=") > 0) {
        request_s1 = value_after(line, "s1=")
        request_rate = value_after(line, "rate=")
        request_line = line
        request_seq = NR
    }

    if (index(line, "ignore target bitrate") > 0) ignore_line = line
    if (index(line, "write.timeout") > 0) timeout_line = line
}
END {
    if ((player_pkg == "" || player_pkg == "未知") && fallback_pkg != "") player_pkg = fallback_pkg
    latest_config_seq = target_seq
    if (request_seq > latest_config_seq) latest_config_seq = request_seq
    encoder_stale = (quality_mode != "" && latest_config_seq > encoder_seq)

    print "========== Melody LHDC 状态 =========="
    print "采集时间 : " (now != "" ? now : "未知")
    print "设备     : " (device != "" ? device : "未知")
    print "系统版本 : " (rom != "" ? rom : "未知")
    print "日志窗口 : 最近 " tail_lines " 行 + 实时监听 " live_seconds " 秒"
    print "日志来源 : " log_source " + " live_source "，共 " log_lines " 行"
    print "媒体来源 : " media_source
    print ""

    print "[播放器]"
    print "包名 : " (player_pkg != "" ? player_pkg : "未知")
    print "状态 : " (player_state != "" ? player_state : "unknown")
    print "曲目 : " (track_title != "" ? track_title : "未知")
    print "艺人 : " (track_artist != "" ? track_artist : "未知")
    print "专辑 : " (track_album != "" ? track_album : "未知")
    print ""

    print "[LHDC / A2DP]"
    print "quality_mode      : " display_quality(quality_mode)
    print "target bit rate   : " (target_bitrate != "" ? target_bitrate : "未知")
    print "max bit rate      : " (max_bitrate != "" ? max_bitrate : "未知")
    print "codec_specific_1  : " known_s1(codec_specific1 != "" ? codec_specific1 : request_s1)
    print "采样率            : " known_rate(sample_rate != "" ? sample_rate : (codec_sample_rate != "" ? codec_sample_rate : request_rate))
    print "sample_rate(raw)  : encoder=" (sample_rate != "" ? sample_rate : "未知") ", codec=" (codec_sample_rate != "" ? codec_sample_rate : "未知") ", request=" (request_rate != "" ? request_rate : "未知")
    print "pcm_fmt           : " (pcm_fmt != "" ? pcm_fmt : "未知")
    print "bitrate idx       : min=" (min_idx != "" ? min_idx : "未知") ", max=" (max_idx != "" ? max_idx : "未知")
    print "mtu               : peer=" (peer_mtu != "" ? peer_mtu : "未知") ", encoder=" (mtu != "" ? mtu : "未知")
    print ""

    print "[内存补丁]"
    if (patch_line != "") {
        print "状态 : " (patch_status != "" ? patch_status : "未知") ", success=" (patch_success != "" ? patch_success : "未知")
    } else {
        print "状态 : 未在日志窗口找到启动补丁日志"
    }
    print ""

    print "[判断]"
    if (quality_mode == "HIGH1_1000(8)") {
        if (encoder_stale) {
            print "结论 : 曾看到 1000 kbps encoder 日志，但它早于最新配置请求；请等待后续 encoder update 再确认。"
        } else {
            print "结论 : 已看到 encoder 进入 1000 kbps 档位。"
        }
    } else if (target_bitrate == "8" && max_bitrate == "8") {
        if (encoder_stale) {
            print "结论 : 蓝牙栈已接受 target bit rate 8；当前 quality_mode 来自旧日志，还没抓到本次切换后的 encoder update。"
        } else {
            print "结论 : 蓝牙栈已接受 target bit rate 8；还需要 encoder update 日志确认 quality_mode。"
        }
    } else if (quality_mode ~ /ABR/) {
        print "结论 : 日志窗口显示当前/最近仍是自适应。"
    } else {
        if (log_lines == 0) {
            print "结论 : 没读到 logcat。请确认 MT 管理器已授予 root，或在 adb shell 中执行。"
        } else {
            print "结论 : 日志窗口内没有足够的 LHDC 码率证据。请实时监听时触发一次音质切换或重连。"
        }
    }
    if (ignore_line != "") print "警告 : 发现 ignore target bitrate，目标码率曾被蓝牙栈忽略。"
    if (timeout_line != "") print "警告 : 发现 write.timeout，模块回读确认曾超时。"
    if (ignore_line == "" && timeout_line == "") print "异常信号 : 未见 ignore target bitrate / write.timeout。"
    print ""

    print "[证据]"
    if (encoder_line != "") print "encoder : " short_line(encoder_line)
    if (target_line != "") print "target  : " short_line(target_line)
    if (codec_line != "") print "codec   : " short_line(codec_line)
    if (request_line != "") print "request : " short_line(request_line)
    if (patch_line != "") print "patch   : " short_line(patch_line)
    if (encoder_line == "" && target_line == "" && codec_line == "" && request_line == "" && patch_line == "") {
        print "无匹配日志。"
    }
}
' "$LOG_ALL"

if [ "$KEEP_LOGS" = "1" ]; then
    echo
    echo "原始日志已保留:"
    echo "$LOG_ALL"
fi
