package edu.cufe.auction.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 五槽位凭据库的边界：只加载服务端文件、只暴露遮罩、异常与 toString 不泄露明文。
 */
class KeySlotsTest {

    private static Path work(String name) throws IOException {
        Path dir = Path.of("target", "web-test", "keys", name);
        Files.createDirectories(dir);
        return dir;
    }

    private static Path file(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("keys.txt");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file;
    }

    private static String[] five() {
        return new String[]{
                "sk-aaaaaaaaaaaaaaaa", "sk-bbbbbbbbbbbbbbbb", "sk-cccccccccccccccc",
                "sk-dddddddddddddddd", "sk-eeeeeeeeeeeeeeee"};
    }

    @Test
    @DisplayName("五行合法文件：槽位顺序固定、遮罩只留末四位、明文可取")
    void loadsFiveSlots() throws Exception {
        Path file = file(work("ok"), five());
        KeySlots slots = KeySlots.load(file);

        List<KeySlots.SlotView> views = slots.views();
        assertEquals(5, views.size());
        for (int index = 0; index < 5; index++) {
            KeySlots.SlotView view = views.get(index);
            assertEquals("key-" + (index + 1), view.slotId());
            assertTrue(view.configured());
            assertTrue(view.masked().startsWith("********"), view.masked());
            assertEquals(12, view.masked().length());
            assertEquals(five()[index].substring(five()[index].length() - 4),
                    view.masked().substring(8));
            assertEquals(five()[index], slots.credential("key-" + (index + 1)));
            assertFalse(view.masked().contains("sk-"));
        }
    }

    @Test
    @DisplayName("BOM 与结尾空行可接受，行内空白被裁剪")
    void toleratesBomAndTrailingBlank() throws Exception {
        Path dir = work("bom");
        Path file = dir.resolve("keys.txt");
        Files.writeString(file, "\uFEFF" + String.join("\r\n", five()) + "\r\n\r\n",
                StandardCharsets.UTF_8);
        KeySlots slots = KeySlots.load(file);

        assertEquals(five()[0], slots.credential("key-1"));
        assertEquals(five()[4], slots.credential("key-5"));
    }

    @Test
    @DisplayName("未知槽位拒绝且不复述输入")
    void unknownSlot() throws Exception {
        KeySlots slots = KeySlots.load(file(work("unknown"), five()));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> slots.credential("key-9"));
        assertFalse(failure.getMessage().contains("key-9"));
    }

    @Test
    @DisplayName("行数不是五、空行、重复、非 ASCII、形状不符全部拒绝")
    void rejectsInvalidFiles() throws Exception {
        String[] four = {"sk-aaaaaaaaaaaaaaaa", "sk-bbbbbbbbbbbbbbbb", "sk-cccccccccccccccc",
                "sk-dddddddddddddddd"};
        assertThrows(IllegalArgumentException.class,
                () -> KeySlots.load(file(work("four"), four)));
        assertThrows(IllegalArgumentException.class, () -> KeySlots.load(file(work("six"),
                five()[0], five()[1], five()[2], five()[3], five()[4], "sk-ffffffffffffffff")));
        assertThrows(IllegalArgumentException.class,
                () -> KeySlots.load(file(work("blank"), five()[0], "", five()[2], five()[3],
                        five()[4])));
        assertThrows(IllegalArgumentException.class,
                () -> KeySlots.load(file(work("dup"), five()[0], five()[0], five()[2],
                        five()[3], five()[4])));
        assertThrows(IllegalArgumentException.class,
                () -> KeySlots.load(file(work("ascii"), "sk-aaaaaaaaaaaaaaaa", "sk-bbbbbbbbbbbbbbbb",
                        "key-中文", five()[3], five()[4])));
        assertThrows(IllegalArgumentException.class,
                () -> KeySlots.load(file(work("shape"), "not-a-key-aaaaaaaa", five()[1],
                        five()[2], five()[3], five()[4])));
        assertThrows(IllegalArgumentException.class,
                () -> KeySlots.load(file(work("short"), "sk-short", five()[1], five()[2],
                        five()[3], five()[4])));
    }

    @Test
    @DisplayName("超过 16 KiB 的文件拒绝且不读取全部内容")
    void rejectsOversizedFile() throws Exception {
        Path dir = work("big");
        Path file = dir.resolve("keys.txt");
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 5; index++) {
            builder.append("sk-").append(String.valueOf((char) ('a' + index)).repeat(4000))
                    .append('\n');
        }
        Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> KeySlots.load(file));
    }

    @Test
    @DisplayName("文件不存在时给出固定文案且不含路径细节")
    void missingFile() throws Exception {
        Path missing = work("missing").resolve("nope.txt");
        IOException failure = assertThrows(IOException.class, () -> KeySlots.load(missing));
        assertFalse(failure.getMessage().contains("nope"));
    }

    @Test
    @DisplayName("toString 只列槽位标识")
    void toStringHasNoSecret() throws Exception {
        KeySlots slots = KeySlots.load(file(work("tostring"), five()));
        String rendered = slots.toString();
        assertFalse(rendered.contains("sk-"));
        assertTrue(rendered.contains("key-1"));
    }
}
