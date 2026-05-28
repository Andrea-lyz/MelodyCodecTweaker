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

    public static final String CODEC_DISPLAY_TITLE = "当前编解码器";
    public static final String QUALITY_OPTION_TITLE = "播放质量";
    public static final String SAMPLE_RATE_OPTION_TITLE = "采样率";
    public static final String REMEMBER_TOGGLE_TITLE = "记住此耳机的选择";
    public static final String REMEMBER_TOGGLE_SUMMARY =
            "关闭时每次重连按系统默认值；开启后下次连接会自动应用你上次的设置。";

    public static final String STATE_NO_DEVICE = "未连接耳机";
    public static final String STATE_NO_A2DP = "当前设备不支持 A2DP 编解码控制";
    public static final String STATE_CODEC_UNKNOWN = "暂时无法获取编解码器信息";
    public static final String STATE_CODEC_UNKNOWN_ACTION = "重试";
    public static final String STATE_PERMISSION_REQUIRED = "授予蓝牙连接权限";
    public static final String STATE_VERSION_UNCALIBRATED = "面板版本未校准，部分功能可能受限";

    public static final String BANNER_VIA_SETTINGS = "已通过开发者选项写入，可能需要重连耳机生效";
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

    public static final String QUALITY_LDAC_990 = "990 kbps（音质优先）";
    public static final String QUALITY_LDAC_660 = "660 kbps（标准）";
    public static final String QUALITY_LDAC_330 = "330 kbps（连接优先）";
    public static final String QUALITY_LHDC_V1 = "LHDC V1";
    public static final String QUALITY_LHDC_V2 = "LHDC V2";
    public static final String QUALITY_LHDC_V3 = "LHDC V3";
    public static final String QUALITY_LHDC_V5 = "LHDC V5";

    private Strings() {
    }
}
