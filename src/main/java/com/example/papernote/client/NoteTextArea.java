package com.example.papernote.client;

import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Лёгкий многострочный редактор текста «чернилами по бумаге».
 * Перенос строк пересчитывается только при изменении текста или ширины.
 */
public class NoteTextArea {
    private static final int LINE_H = 9;
    private static final int INK = 0xFF2B2320;
    private static final int SELECTION = 0x663F6FD1;
    private static final int RULE = 0x40705A3C;

    private final Font font;
    private final int maxChars;
    private final boolean editable;

    private String text = "";
    private int cursor;
    private int anchor;

    private int x, y, w, h;
    private int scroll;
    private boolean dragging;

    private final List<int[]> lines = new ArrayList<>();
    private boolean layoutDirty = true;
    private int layoutW = -1;
    private Runnable onChange = () -> {};

    public NoteTextArea(Font font, int maxChars, boolean editable) {
        this.font = font;
        this.maxChars = maxChars;
        this.editable = editable;
    }

    // ------------------------------------------------------------ состояние

    public String getText() {
        return text;
    }

    public void setText(String value) {
        this.text = filter(value);
        if (text.length() > maxChars) text = text.substring(0, maxChars);
        cursor = anchor = text.length();
        layoutDirty = true;
        scroll = 0;
    }

    public void setOnChange(Runnable r) {
        this.onChange = r;
    }

    public void setBounds(int x, int y, int w, int h) {
        if (w != this.w) layoutDirty = true;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    private boolean inside(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------ раскладка

    private void ensureLayout() {
        if (!layoutDirty && layoutW == w) return;
        lines.clear();
        int pos = 0;
        int n = text.length();
        while (true) {
            int nl = text.indexOf('\n', pos);
            int end = nl < 0 ? n : nl;
            wrap(pos, end);
            if (nl < 0) break;
            pos = nl + 1;
        }
        layoutDirty = false;
        layoutW = w;
    }

    private void wrap(int s, int e) {
        if (s == e) {
            lines.add(new int[]{s, s});
            return;
        }
        int start = s;
        int width = 0;
        int lastSpace = -1;
        for (int i = s; i < e; i++) {
            char c = text.charAt(i);
            int cw = font.width(String.valueOf(c));
            if (c != ' ' && width + cw > w && i > start) {
                int brk = lastSpace >= start ? lastSpace + 1 : i;
                lines.add(new int[]{start, brk});
                start = brk;
                width = start < i ? font.width(text.substring(start, i)) : 0;
                lastSpace = -1;
                for (int k = start; k < i; k++) {
                    if (text.charAt(k) == ' ') lastSpace = k;
                }
            }
            if (c == ' ') lastSpace = i;
            width += cw;
        }
        lines.add(new int[]{start, e});
    }

    private int visibleLines() {
        return Math.max(1, h / LINE_H);
    }

    private void clampScroll() {
        int max = Math.max(0, lines.size() - visibleLines());
        scroll = Math.max(0, Math.min(scroll, max));
    }

    private int lineOf(int idx) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i)[0] <= idx) return i;
        }
        return 0;
    }

    private void ensureCursorVisible() {
        ensureLayout();
        int l = lineOf(cursor);
        int vis = visibleLines();
        if (l < scroll) scroll = l;
        else if (l >= scroll + vis) scroll = l - vis + 1;
        clampScroll();
    }

    // ------------------------------------------------------------ отрисовка

    public void render(GuiGraphics g, Component placeholder) {
        ensureLayout();
        clampScroll();
        int vis = visibleLines();
        int end = Math.min(lines.size(), scroll + vis);

        for (int i = 0; i < vis; i++) {
            int ly = y + i * LINE_H;
            g.fill(x, ly + 8, x + w, ly + 9, RULE);
        }

        if (text.isEmpty()) {
            g.drawString(font, placeholder, x, y, 0xFF9C8A68, false);
        }

        int selMin = Math.min(cursor, anchor);
        int selMax = Math.max(cursor, anchor);
        for (int i = scroll; i < end; i++) {
            int[] ln = lines.get(i);
            int ly = y + (i - scroll) * LINE_H;
            if (selMin != selMax) {
                int a = Math.max(selMin, ln[0]);
                int b = Math.min(selMax, ln[1]);
                if (a < b) {
                    int x0 = x + font.width(text.substring(ln[0], a));
                    int x1 = x0 + font.width(text.substring(a, b));
                    g.fill(x0, ly - 1, x1, ly + 8, SELECTION);
                }
            }
            if (ln[1] > ln[0]) {
                g.drawString(font, text.substring(ln[0], ln[1]), x, ly, INK, false);
            }
        }

        if (editable && (Util.getMillis() / 530L) % 2 == 0) {
            int cl = lineOf(cursor);
            if (cl >= scroll && cl < end) {
                int[] ln = lines.get(cl);
                int cx = x + font.width(text.substring(ln[0], Math.min(cursor, ln[1])));
                int ly = y + (cl - scroll) * LINE_H;
                g.fill(cx, ly - 1, cx + 1, ly + 9, INK);
            }
        }

        // тонкая полоса прокрутки, если текст не помещается
        if (lines.size() > vis) {
            int barH = Math.max(6, h * vis / lines.size());
            int barY = y + (h - barH) * scroll / Math.max(1, lines.size() - vis);
            g.fill(x + w + 2, barY, x + w + 4, barY + barH, 0x80705A3C);
        }
    }

    // ----------------------------------------------------------------- ввод

    private int indexAtX(int lineIdx, int px) {
        int[] ln = lines.get(lineIdx);
        int acc = 0;
        for (int i = ln[0]; i < ln[1]; i++) {
            int cw = font.width(String.valueOf(text.charAt(i)));
            if (px < acc + cw / 2) return i;
            acc += cw;
        }
        return lineEnd(lineIdx);
    }

    /** Позиция курсора «в конце строки» (для мягкого переноса — перед пробелом на стыке). */
    private int lineEnd(int lineIdx) {
        int[] ln = lines.get(lineIdx);
        boolean soft = lineIdx < lines.size() - 1 && lines.get(lineIdx + 1)[0] == ln[1];
        if (soft && ln[1] > ln[0] && text.charAt(ln[1] - 1) == ' ') return ln[1] - 1;
        return ln[1];
    }

    private int indexAt(double mx, double my) {
        ensureLayout();
        int li = scroll + (int) ((my - y) / LINE_H);
        li = Math.max(0, Math.min(li, lines.size() - 1));
        return indexAtX(li, (int) (mx - x));
    }

    private void moveCursor(int pos, boolean select) {
        cursor = Math.max(0, Math.min(pos, text.length()));
        if (!select) anchor = cursor;
        ensureCursorVisible();
    }

    private void moveLine(int dir, boolean select) {
        ensureLayout();
        int l = lineOf(cursor);
        int target = l + dir;
        if (target < 0) {
            moveCursor(0, select);
        } else if (target >= lines.size()) {
            moveCursor(text.length(), select);
        } else {
            int px = font.width(text.substring(lines.get(l)[0], cursor));
            moveCursor(indexAtX(target, px), select);
        }
    }

    private boolean hasSelection() {
        return cursor != anchor;
    }

    private void changed() {
        layoutDirty = true;
        ensureCursorVisible();
        onChange.run();
    }

    private void deleteRange(int a, int b) {
        if (a >= b) return;
        text = text.substring(0, a) + text.substring(b);
        cursor = anchor = a;
        changed();
    }

    private void insert(String raw) {
        String s = filter(raw);
        if (s.isEmpty()) return;
        if (hasSelection()) {
            int a = Math.min(cursor, anchor);
            int b = Math.max(cursor, anchor);
            text = text.substring(0, a) + text.substring(b);
            cursor = anchor = a;
        }
        int room = maxChars - text.length();
        if (room <= 0) {
            changed();
            return;
        }
        if (s.length() > room) s = s.substring(0, room);
        text = text.substring(0, cursor) + s + text.substring(cursor);
        cursor += s.length();
        anchor = cursor;
        changed();
    }

    private static String filter(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') sb.append('\n');
            else if (c == '\t') sb.append(' ');
            else if (c != '\r' && SharedConstants.isAllowedChatCharacter(c)) sb.append(c);
        }
        return sb.toString();
    }

    public boolean charTyped(char c, int mods) {
        if (!editable) return false;
        if (SharedConstants.isAllowedChatCharacter(c)) {
            insert(String.valueOf(c));
            return true;
        }
        return false;
    }

    public boolean keyPressed(int key, int scan, int mods) {
        if (!editable) return false;
        boolean shift = Screen.hasShiftDown();
        Minecraft mc = Minecraft.getInstance();

        if (Screen.isSelectAll(key)) {
            anchor = 0;
            cursor = text.length();
            return true;
        }
        if (Screen.isCopy(key)) {
            if (hasSelection()) {
                mc.keyboardHandler.setClipboard(text.substring(Math.min(cursor, anchor), Math.max(cursor, anchor)));
            }
            return true;
        }
        if (Screen.isCut(key)) {
            if (hasSelection()) {
                int a = Math.min(cursor, anchor);
                int b = Math.max(cursor, anchor);
                mc.keyboardHandler.setClipboard(text.substring(a, b));
                deleteRange(a, b);
            }
            return true;
        }
        if (Screen.isPaste(key)) {
            insert(mc.keyboardHandler.getClipboard());
            return true;
        }

        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> insert("\n");
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (hasSelection()) deleteRange(Math.min(cursor, anchor), Math.max(cursor, anchor));
                else if (cursor > 0) deleteRange(cursor - 1, cursor);
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (hasSelection()) deleteRange(Math.min(cursor, anchor), Math.max(cursor, anchor));
                else if (cursor < text.length()) deleteRange(cursor, cursor + 1);
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (hasSelection() && !shift) moveCursor(Math.min(cursor, anchor), false);
                else moveCursor(cursor - 1, shift);
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (hasSelection() && !shift) moveCursor(Math.max(cursor, anchor), false);
                else moveCursor(cursor + 1, shift);
            }
            case GLFW.GLFW_KEY_UP -> moveLine(-1, shift);
            case GLFW.GLFW_KEY_DOWN -> moveLine(1, shift);
            case GLFW.GLFW_KEY_HOME -> {
                ensureLayout();
                moveCursor(lines.get(lineOf(cursor))[0], shift);
            }
            case GLFW.GLFW_KEY_END -> {
                ensureLayout();
                moveCursor(lineEnd(lineOf(cursor)), shift);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !inside(mx, my)) return false;
        if (editable) {
            int idx = indexAt(mx, my);
            cursor = idx;
            if (!Screen.hasShiftDown()) anchor = idx;
            dragging = true;
            ensureCursorVisible();
        }
        return true;
    }

    public boolean mouseDragged(double mx, double my) {
        if (!dragging || !editable) return false;
        cursor = indexAt(mx, my);
        ensureCursorVisible();
        return true;
    }

    public void mouseReleased() {
        dragging = false;
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!inside(mx, my)) return false;
        ensureLayout();
        scroll -= (int) Math.signum(delta);
        clampScroll();
        return true;
    }
}
