package xyz.melodylsp.codec.leaudio;

/**
 * User-facing strings for the LE Audio warning dialog shown inside the wirelesssettings
 * process. Kept separate from the melody-side {@code host.Strings} so the wirelesssettings
 * scope does not pull in host-only UI constants. Hard-coded (no {@code R.string}) for the same
 * resource-id-collision reason documented on {@code host.Strings}.
 */
public final class LeAudioStrings {

    public static final String DIALOG_TITLE_ON = "开启 LE Audio";
    public static final String DIALOG_TITLE_OFF = "关闭 LE Audio";
    public static final String DIALOG_MSG_ON =
            "开启后将使用 LE Audio（LC3）低功耗音频，耳机会短暂断开并重新连接。是否继续？";
    public static final String DIALOG_MSG_OFF =
            "关闭后将回到经典蓝牙音频，耳机会短暂断开并重新连接。是否继续？";
    public static final String CONFIRM = "开启";
    public static final String CONFIRM_OFF = "关闭";
    public static final String CANCEL = "取消";

    private LeAudioStrings() {
    }
}
