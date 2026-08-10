package xyz.melodylsp.codec.host;

import xyz.melodylsp.codec.label.CodecLabelTable;

/**
 * 宿主侧 native 补丁适配提醒（toast 矩阵，见 docs/native-patch-adoption-plan.md §5.3）。
 *
 * <p>两个补丁只与「切到音质优先（固定 1000/900）」相关：码率 branch（B）管固定码率写入
 * 是否生效，快切等价（F）管切档时是否重建输出。判定规则：
 * <ul>
 *   <li>主动切换：B unsupported → 由写失败路径弹「未适配」（拒绝语义，见
 *       {@code showWriteFailedToast*}）；B 正常且 F unsupported → 选中即弹「未完整适配」预提醒；</li>
 *   <li>记忆回放：B unsupported → 弹「未适配」（系统更新后补丁失效的主要提示路径，避免无声
 *       降级）；B 正常且 F unsupported → 弹「未完整适配」；</li>
 *   <li>状态未知一律静默；切到其他档位一律静默。</li>
 * </ul>
 */
public final class NativePatchAdvisory {

    private static volatile boolean bitrateUnsupported;
    private static volatile boolean fastSwitchUnsupported;

    private NativePatchAdvisory() {
    }

    /** 由 CodecController 在收到补丁状态广播时更新（每进程一份）。 */
    public static void update(boolean bitrateUnsupported, boolean fastSwitchUnsupported) {
        NativePatchAdvisory.bitrateUnsupported = bitrateUnsupported;
        NativePatchAdvisory.fastSwitchUnsupported = fastSwitchUnsupported;
    }

    public static boolean isBitrateUnsupported() {
        return bitrateUnsupported;
    }

    public static boolean isFastSwitchUnsupported() {
        return fastSwitchUnsupported;
    }

    /** 固定 1000/900 档（音质优先）才需要适配提醒。 */
    public static boolean isFixedLhdcTier(long qualityLowByte) {
        return qualityLowByte == CodecLabelTable.LHDC_QUALITY_FIXED_900
                || qualityLowByte == CodecLabelTable.LHDC_QUALITY_FIXED_1000;
    }

    /**
     * 用户主动选择档位时的预提醒：仅 F 缺失且 B 正常时返回「未完整适配」；
     * B 缺失不在此处预弹（拒绝语义由写失败路径承担，避免双 toast 叠加）。
     */
    public static String selectionToast(long qualityLowByte) {
        if (!isFixedLhdcTier(qualityLowByte)) return null;
        if (!bitrateUnsupported && fastSwitchUnsupported) {
            return Strings.TOAST_NATIVE_PATCH_PARTIAL;
        }
        return null;
    }

    /**
     * 记忆回放应用音质优先时的提醒：B 优先于 F（系统更新后 B 失效是主要提示路径）。
     */
    public static String replayToast(long qualityLowByte) {
        if (!isFixedLhdcTier(qualityLowByte)) return null;
        if (bitrateUnsupported) return Strings.TOAST_NATIVE_PATCH_UNSUPPORTED;
        if (fastSwitchUnsupported) return Strings.TOAST_NATIVE_PATCH_PARTIAL;
        return null;
    }
}
