package xyz.melodylsp.codec.host;

/**
 * Hard-coded user-facing strings. We deliberately avoid {@code context.getString(R.string.x)}
 * inside the host process: the module APK has its own resource id range that happens to
 * collide with arbitrary host resources, so {@code Resources#getText(int)} resolves to
 * unrelated XML resources from the host APK. Using literal strings sidesteps the entire
 * resource subsystem.
 */
public final class Strings {

    public static final String CODEC_BLOCK_TITLE = "蓝牙音质";

    public static final String QUALITY_OPTION_TITLE = "播放质量";
    public static final String SAMPLE_RATE_OPTION_TITLE = "采样率";
    public static final String REMEMBER_TOGGLE_TITLE = "记住此耳机的选择";
    public static final String REMEMBER_TOGGLE_SUMMARY =
            "关闭时每次重连按系统默认值；开启后下次连接会自动应用你上次的设置。";

    public static final String STATE_NO_DEVICE = "未连接耳机";
    public static final String STATE_CODEC_UNKNOWN = "暂时无法获取编解码器信息";

    public static final String BANNER_VIA_SETTINGS = "已写入开发者选项";
    public static final String BANNER_VIA_ROOT = "已通过 root 写入开发者选项，蓝牙正在重连";
    public static final String TOAST_APPLY_FAILED = "切换未生效，请重试";

    public static final String FRESHNESS_LABEL_FORMAT = "上次同步：%s";
    public static final String QUALITY_UNKNOWN_VALUE_FORMAT = "未知档位（specific1=%s）";

    public static final String CODEC_LABEL_SBC = "SBC";
    public static final String CODEC_LABEL_AAC = "AAC";
    public static final String CODEC_LABEL_APTX = "aptX";
    public static final String CODEC_LABEL_APTX_HD = "aptX HD";
    public static final String CODEC_LABEL_APTX_ADAPTIVE = "aptX Adaptive";
    public static final String CODEC_LABEL_LDAC = "LDAC";
    public static final String CODEC_LABEL_LHDC = "LHDC";
    public static final String CODEC_LABEL_OPUS = "Opus";
    public static final String CODEC_LABEL_LC3 = "LC3";

    public static final String QUALITY_LDAC_990 = "990 kbps（音质优先）";
    public static final String QUALITY_LDAC_660 = "660 kbps（标准）";
    public static final String QUALITY_LDAC_330 = "330 kbps（连接优先）";
    public static final String QUALITY_LHDC_CONNECTION = "连接优先";
    public static final String QUALITY_LHDC_BALANCED = "均衡";
    public static final String QUALITY_LHDC_HIGH = "音质优先";

    // LE Audio switch (TODO B1 / B2). The confirmation dialog text lives in
    // leaudio.LeAudioStrings because the dialog is shown from the wirelesssettings process.
    public static final String LE_AUDIO_TITLE = "LE Audio";
    public static final String LE_AUDIO_SUMMARY_ON = "已开启（LC3 低功耗音频）";
    public static final String LE_AUDIO_SUMMARY_OFF = "已关闭（经典蓝牙音频）";
    public static final String LE_AUDIO_SUMMARY_UNKNOWN = "正在获取状态…";

    private Strings() {
    }
}
