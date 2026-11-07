package edu.cufe.auction.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 纯内存五槽位凭据库，按有界流读取外部配置。
 *
 * <p>明文只存在于私有字段；对外只有遮罩视图；异常与 {@link #toString()} 不含内容。
 * {@code credential} 是同包可信服务端的取值边界，不是 HTTP 权限检查。</p>
 */
public final class KeySlots {

    /** 固定槽位数量。 */
    public static final int SLOT_COUNT = 5;

    private static final String[] SLOT_IDS = {"key-1", "key-2", "key-3", "key-4", "key-5"};
    private static final Pattern SHAPE = Pattern.compile("sk-[A-Za-z0-9_-]{16,128}");
    private static final int MAX_BYTES = 16 * 1024;
    private static final int MASK_STARS = 8;

    private final String key1;
    private final String key2;
    private final String key3;
    private final String key4;
    private final String key5;

    private KeySlots(String first, String second, String third, String fourth, String fifth) {
        this.key1 = first;
        this.key2 = second;
        this.key3 = third;
        this.key4 = fourth;
        this.key5 = fifth;
    }

    /**
     * 只读槽位视图。
     *
     * @param slotId     固定槽位标识
     * @param masked     固定星号加末 4 位
     * @param configured 是否已配置
     */
    public record SlotView(String slotId, String masked, boolean configured) {

        /** 校验字段。 */
        public SlotView {
            Objects.requireNonNull(slotId, "槽位标识");
            Objects.requireNonNull(masked, "遮罩");
            if (masked.length() < MASK_STARS + 4) {
                throw new IllegalArgumentException("遮罩长度非法");
            }
        }
    }

    /**
     * 读取外部凭据文件：UTF-8（可带 BOM），恰好 5 个非空行。
     *
     * @param file 外部本机文件
     * @return 槽位库
     * @throws IOException              文件不可读（固定文案）
     * @throws IllegalArgumentException 行数/空行/重复/字符/格式/大小不合法
     */
    public static KeySlots load(Path file) throws IOException {
        Objects.requireNonNull(file, "凭据文件");
        byte[] bytes;
        try {
            bytes = readBounded(file);
        } catch (IOException failure) {
            throw new IOException("读取凭据文件失败（细节由上层按路径处理）");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r\n|\r|\n", -1)) {
            lines.add(line);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        if (lines.size() != SLOT_COUNT) {
            throw new IllegalArgumentException("凭据文件必须恰好 5 行");
        }
        String[] values = new String[SLOT_COUNT];
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < SLOT_COUNT; index++) {
            String value = lines.get(index).strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("凭据行不能为空");
            }
            if (!isPrintableAscii(value)) {
                throw new IllegalArgumentException("凭据行含不允许的字符");
            }
            if (!SHAPE.matcher(value).matches()) {
                throw new IllegalArgumentException("凭据行格式不合法");
            }
            if (!seen.add(value)) {
                throw new IllegalArgumentException("凭据行重复");
            }
            values[index] = value;
        }
        return new KeySlots(values[0], values[1], values[2], values[3], values[4]);
    }

    /**
     * 只读槽位视图，顺序固定 key-1..key-5。
     *
     * @return 不可变遮罩视图
     */
    public List<SlotView> views() {
        List<SlotView> views = new ArrayList<>(SLOT_COUNT);
        for (int index = 0; index < SLOT_COUNT; index++) {
            views.add(new SlotView(SLOT_IDS[index], mask(valueAt(index)), true));
        }
        return List.copyOf(views);
    }

    /**
     * 同包可信服务端的取值边界（不是 HTTP 权限检查）。
     *
     * @param slotId 固定槽位标识
     * @return 明文
     * @throws IllegalArgumentException 未知槽位（文案不复述输入）
     */
    String credential(String slotId) {
        for (int index = 0; index < SLOT_COUNT; index++) {
            if (SLOT_IDS[index].equals(slotId)) {
                return valueAt(index);
            }
        }
        throw new IllegalArgumentException("未知槽位");
    }

    /** 只输出槽位标识。 */
    @Override
    public String toString() {
        return "KeySlots[key-1, key-2, key-3, key-4, key-5]";
    }

    private static byte[] readBounded(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                total += read;
                if (total > MAX_BYTES) {
                    throw new IllegalArgumentException("凭据文件超过 16 KiB");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String valueAt(int index) {
        return switch (index) {
            case 0 -> key1;
            case 1 -> key2;
            case 2 -> key3;
            case 3 -> key4;
            case 4 -> key5;
            default -> throw new IllegalArgumentException("未知槽位");
        };
    }

    private static String mask(String value) {
        String last4 = value.substring(value.length() - 4);
        return "*".repeat(MASK_STARS) + last4;
    }

    private static boolean isPrintableAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x20 || current > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
