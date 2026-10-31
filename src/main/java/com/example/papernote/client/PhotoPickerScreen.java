package com.example.papernote.client;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Окно выбора фото: сетка превью из папки papernote_photos. Клик — выбрать,
 * колесо — прокрутка, можно перетащить файл в окно. Превью грузятся лениво.
 */
public class PhotoPickerScreen extends Screen {
    private static final int CELL = 64;
    private static final int PAD = 3;

    private final Screen parent;
    private final Consumer<Path> onPick;

    private List<LocalPhotos.Entry> entries = List.of();
    private boolean scanning = true;
    private boolean started;

    private int cols, rowsVisible, gridX, gridY, scrollRow;

    public PhotoPickerScreen(Screen parent, Consumer<Path> onPick) {
        super(Component.translatable("gui.papernote.picker_title"));
        this.parent = parent;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        cols = Math.max(2, Math.min(6, (this.width - 40) / CELL));
        rowsVisible = Math.max(1, Math.min(4, (this.height - 120) / CELL));
        int gridW = cols * CELL;
        int gridH = rowsVisible * CELL;
        gridX = (this.width - gridW) / 2;
        gridY = (this.height - gridH) / 2;

        int by = gridY + gridH + 10;
        int bw = 100;
        int total = bw * 3 + 8;
        int bx = (this.width - total) / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.papernote.open_folder"),
                b -> Util.getPlatform().openFile(LocalPhotos.DIR.toFile())).bounds(bx, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.papernote.refresh"),
                b -> refresh()).bounds(bx + bw + 4, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.papernote.cancel"),
                b -> onClose()).bounds(bx + (bw + 4) * 2, by, bw, 20).build());

        if (!started) {
            started = true;
            refresh();
        }
        clampScroll();
    }

    private void refresh() {
        scanning = true;
        Thumbs.clear();
        LocalPhotos.scanAsync(list -> {
            entries = list;
            scanning = false;
            scrollRow = 0;
        });
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        Thumbs.clear(); // не держим текстуры превью, когда окно закрыто
    }

    private int totalRows() {
        return (entries.size() + cols - 1) / cols;
    }

    private void clampScroll() {
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, totalRows() - rowsVisible)));
    }

    private void pick(Path path) {
        this.minecraft.setScreen(parent);
        onPick.accept(path);
    }

    // ------------------------------------------------------------ отрисовка

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);

        int gridW = cols * CELL;
        int gridH = rowsVisible * CELL;
        g.fill(gridX - 10, gridY - 38, gridX + gridW + 10, gridY + gridH + 38, 0xE0161A20);
        g.fill(gridX - 10, gridY - 38, gridX + gridW + 10, gridY - 37, 0xFF5B6475);
        g.fill(gridX - 10, gridY + gridH + 37, gridX + gridW + 10, gridY + gridH + 38, 0xFF5B6475);

        g.drawCenteredString(this.font, this.title, this.width / 2, gridY - 32, 0xFFFFFFFF);
        g.drawCenteredString(this.font, Component.translatable("gui.papernote.picker_hint"),
                this.width / 2, gridY - 20, 0xFFA0A8B8);

        List<Component> tooltip = null;

        if (scanning) {
            g.drawCenteredString(this.font, Component.translatable("gui.papernote.scanning"),
                    this.width / 2, gridY + gridH / 2 - 4, 0xFFA0A8B8);
        } else if (entries.isEmpty()) {
            g.drawCenteredString(this.font, Component.translatable("gui.papernote.empty_folder"),
                    this.width / 2, gridY + gridH / 2 - 14, 0xFFE0C98A);
            g.drawCenteredString(this.font, Component.translatable("gui.papernote.empty_folder2"),
                    this.width / 2, gridY + gridH / 2 - 2, 0xFFA0A8B8);
        } else {
            int first = scrollRow * cols;
            int last = Math.min(entries.size(), first + cols * rowsVisible);
            for (int i = first; i < last; i++) {
                int idx = i - first;
                int cx = gridX + (idx % cols) * CELL;
                int cy = gridY + (idx / cols) * CELL;
                LocalPhotos.Entry e = entries.get(i);
                boolean hover = mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL;

                g.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, hover ? 0xFF3A4660 : 0xFF232933);
                if (hover) {
                    g.fill(cx, cy, cx + CELL, cy + 1, 0xFFE0C98A);
                    g.fill(cx, cy + CELL - 1, cx + CELL, cy + CELL, 0xFFE0C98A);
                    g.fill(cx, cy, cx + 1, cy + CELL, 0xFFE0C98A);
                    g.fill(cx + CELL - 1, cy, cx + CELL, cy + CELL, 0xFFE0C98A);
                }

                int boxW = CELL - PAD * 2;
                int boxH = CELL - PAD * 2 - 10;
                TexUtil.Tex t = Thumbs.get(e.path());
                if (t != null) {
                    float s = Math.min(boxW / (float) t.w(), boxH / (float) t.h());
                    int dw = Math.max(1, Math.round(t.w() * s));
                    int dh = Math.max(1, Math.round(t.h() * s));
                    g.blit(t.loc(), cx + PAD + (boxW - dw) / 2, cy + PAD + (boxH - dh) / 2, dw, dh,
                            0F, 0F, t.w(), t.h(), t.w(), t.h());
                } else {
                    String mark = Thumbs.failed(e.path()) ? "?" : "…";
                    g.drawCenteredString(this.font, mark, cx + CELL / 2, cy + PAD + boxH / 2 - 4, 0xFF7C8598);
                }
                g.drawString(this.font, this.font.plainSubstrByWidth(e.name(), CELL - 6),
                        cx + 3, cy + CELL - 11, 0xFFB8C0D0, false);

                if (hover) {
                    tooltip = new ArrayList<>();
                    tooltip.add(Component.literal(e.name()));
                    tooltip.add(Component.literal(humanSize(e.size())).withStyle(net.minecraft.ChatFormatting.GRAY));
                }
            }

            if (totalRows() > rowsVisible) {
                int barH = Math.max(10, gridH * rowsVisible / totalRows());
                int barY = gridY + (gridH - barH) * scrollRow / Math.max(1, totalRows() - rowsVisible);
                g.fill(gridX + gridW + 3, barY, gridX + gridW + 6, barY + barH, 0xFF8A93A6);
            }
        }

        super.render(g, mouseX, mouseY, partialTick);
        if (tooltip != null) {
            g.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    // ----------------------------------------------------------------- ввод

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0 || scanning) return false;
        int gridW = cols * CELL;
        int gridH = rowsVisible * CELL;
        if (mx < gridX || mx >= gridX + gridW || my < gridY || my >= gridY + gridH) return false;
        int idx = scrollRow * cols + (int) ((my - gridY) / CELL) * cols + (int) ((mx - gridX) / CELL);
        if (idx >= 0 && idx < entries.size()) {
            pick(entries.get(idx).path());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scrollRow -= (int) Math.signum(delta);
        clampScroll();
        return true;
    }

    /** Файл, брошенный в окно игры, копируется в папку фото и сразу выбирается. */
    @Override
    public void onFilesDrop(List<Path> paths) {
        for (Path p : paths) {
            if (!LocalPhotos.isImage(p)) continue;
            try {
                pick(LocalPhotos.importFile(p));
            } catch (Exception ignored) {
                // не удалось скопировать — просто игнорируем
            }
            return;
        }
    }
}
