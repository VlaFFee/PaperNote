package com.example.papernote.client;

import com.example.papernote.NoteData;
import com.example.papernote.PaperNoteMod;
import com.example.papernote.image.ImageUtil;
import com.example.papernote.net.Net;
import com.example.papernote.net.Packets;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.List;

/**
 * Окно записки. В творческом режиме можно писать и менять фото, во всех остальных режимах — только читать.
 * Права дополнительно проверяет сервер, так что подмена клиента ничего не даёт.
 */
public class NoteScreen extends Screen {
    private static final ResourceLocation PAPER = new ResourceLocation(PaperNoteMod.MODID, "textures/gui/paper.png");

    private static final int PW = 176;
    private static final int PH = 196;
    private static final int BTN_H = 20;
    private static final int GAP = 4;
    private static final int PHOTO_MAX_W = 150;
    private static final int PHOTO_MAX_H = 80;
    private static final long OPEN_MS = 280L;

    private final InteractionHand hand;
    private final boolean editable;
    private final NoteTextArea area;

    private String photo;
    private byte[] pendingBytes;
    private boolean preparing;
    private boolean dirty;
    private boolean discard;

    private long openedAt = -1L;
    private Component notice;
    private long noticeUntil;

    private Button photoBtn;
    private Button removeBtn;

    private int px, py;
    private int photoX, photoY, photoW, photoH;

    public NoteScreen(InteractionHand hand) {
        super(Component.translatable("gui.papernote.title"));
        Minecraft mc = Minecraft.getInstance();
        this.hand = hand;
        this.editable = mc.player != null && mc.player.isCreative();
        ItemStack stack = mc.player != null ? mc.player.getItemInHand(hand) : ItemStack.EMPTY;
        this.photo = NoteData.photo(stack);
        this.area = new NoteTextArea(mc.font, NoteData.MAX_TEXT, editable);
        this.area.setText(NoteData.text(stack));
        this.area.setOnChange(() -> dirty = true);
    }

    // ---------------------------------------------------------------- init

    @Override
    protected void init() {
        px = (this.width - PW) / 2;
        int total = PH + GAP + BTN_H;
        py = Math.max(2, (this.height - total) / 2);
        int by = py + PH + GAP;

        if (editable) {
            int bw = (PW - 6) / 4;
            photoBtn = addRenderableWidget(Button.builder(Component.translatable("gui.papernote.photo"),
                    b -> openPicker()).bounds(px, by, bw, BTN_H).build());
            removeBtn = addRenderableWidget(Button.builder(Component.translatable("gui.papernote.remove"),
                    b -> removePhoto()).bounds(px + bw + 2, by, bw, BTN_H).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.papernote.done"),
                    b -> onClose()).bounds(px + (bw + 2) * 2, by, bw, BTN_H).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.papernote.cancel"),
                    b -> {
                        discard = true;
                        onClose();
                    }).bounds(px + (bw + 2) * 3, by, bw, BTN_H).build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("gui.papernote.close"),
                    b -> onClose()).bounds(px + (PW - 80) / 2, by, 80, BTN_H).build());
        }

        if (openedAt < 0) {
            openedAt = Util.getMillis();
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0F));
        }
    }

    @Override
    public void onClose() {
        if (editable && dirty && !discard) {
            send();
        }
        super.onClose();
    }

    private void send() {
        if (pendingBytes != null && !photo.isEmpty()) {
            Net.sendUpload(photo, pendingBytes);
            pendingBytes = null;
        }
        Net.CHANNEL.sendToServer(new Packets.Save(hand.ordinal(), area.getText(), photo));
    }

    // ---------------------------------------------------------------- фото

    private void openPicker() {
        if (!editable || preparing) return;
        this.minecraft.setScreen(new PhotoPickerScreen(this, this::onPhotoPicked));
    }

    private void removePhoto() {
        if (!editable || photo.isEmpty()) return;
        photo = "";
        pendingBytes = null;
        dirty = true;
    }

    private void onPhotoPicked(Path path) {
        preparing = true;
        showNotice(Component.translatable("gui.papernote.processing"), 600_000L);
        Async.IO.execute(() -> {
            ImageUtil.Prepared prep = ImageUtil.prepareUpload(path);
            Minecraft.getInstance().execute(() -> {
                preparing = false;
                if (prep == null) {
                    showNotice(Component.translatable("gui.papernote.bad_image"), 4000L);
                    return;
                }
                notice = null;
                PhotoCache.putLocal(prep.hash(), prep.jpeg());
                photo = prep.hash();
                pendingBytes = prep.jpeg();
                dirty = true;
            });
        });
    }

    private void showNotice(Component c, long ms) {
        notice = c;
        noticeUntil = Util.getMillis() + ms;
    }

    // ------------------------------------------------------------ раскладка

    private void layout() {
        int top = py + 10;
        photoW = 0;
        photoH = 0;
        if (!photo.isEmpty()) {
            PhotoCache.Entry e = PhotoCache.get(photo);
            if (e != null) {
                float s = Math.min(Math.min(PHOTO_MAX_W / (float) e.w(), PHOTO_MAX_H / (float) e.h()), 2.0F);
                photoW = Math.max(1, Math.round(e.w() * s)) + 6;
                photoH = Math.max(1, Math.round(e.h() * s)) + 6;
            } else {
                photoW = PHOTO_MAX_W;
                photoH = 50;
            }
        } else if (editable) {
            photoW = PHOTO_MAX_W;
            photoH = 24;
        }
        photoX = px + (PW - photoW) / 2;
        photoY = top;
        int textTop = top + (photoH > 0 ? photoH + 6 : 0);
        area.setBounds(px + 12, textTop, PW - 24, py + PH - 14 - textTop);

        if (editable) {
            removeBtn.active = !photo.isEmpty() && !preparing;
            photoBtn.active = !preparing;
        }
    }

    // ------------------------------------------------------------ отрисовка

    private float progress() {
        return openedAt < 0 ? 0F : Math.min(1F, (Util.getMillis() - openedAt) / (float) OPEN_MS);
    }

    private static float easeOutCubic(float t) {
        float u = 1F - t;
        return 1F - u * u * u;
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158F;
        float c3 = c1 + 1F;
        float u = t - 1F;
        return 1F + c3 * u * u * u + c1 * u * u;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        float p = progress();
        if (p < 1F) {
            renderOpening(g, p);
            return;
        }
        layout();
        renderPaper(g);
        renderPhoto(g);
        area.render(g, Component.translatable(editable ? "gui.papernote.placeholder_edit" : "gui.papernote.placeholder_read"));
        if (notice != null) {
            if (Util.getMillis() > noticeUntil) {
                notice = null;
            } else {
                g.drawCenteredString(this.font, notice, px + PW / 2, py + PH - 11, 0xFF8A3B2A);
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderOpening(GuiGraphics g, float p) {
        float cx = px + PW / 2F;
        float cy = py + PH / 2F;
        float sy = 0.05F + 0.95F * easeOutBack(p);
        float sx = 0.75F + 0.25F * easeOutCubic(p);
        float rot = -6F * (1F - easeOutCubic(p));
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(rot));
        pose.scale(sx, sy, 1F);
        pose.translate(-cx, -cy, 0);
        renderPaper(g);
        pose.popPose();
    }

    private void renderPaper(GuiGraphics g) {
        g.blit(PAPER, px, py, 0, 0, PW, PH, 256, 256);
    }

    private void renderPhoto(GuiGraphics g) {
        if (photoH <= 0) return;
        if (photo.isEmpty()) { // пустое место для фото (только творческий режим)
            g.fill(photoX, photoY, photoX + photoW, photoY + photoH, 0x22705A3C);
            drawDashedBorder(g, photoX, photoY, photoW, photoH, 0xFF9C8A68);
            g.drawCenteredString(this.font, Component.translatable("gui.papernote.add_photo"),
                    photoX + photoW / 2, photoY + (photoH - 8) / 2, 0xFF7A6747);
            return;
        }
        // тень и белая рамка «как у снимка»
        g.fill(photoX + 2, photoY + 2, photoX + photoW + 2, photoY + photoH + 2, 0x40000000);
        g.fill(photoX, photoY, photoX + photoW, photoY + photoH, 0xFFF8F4EA);

        PhotoCache.Entry e = PhotoCache.get(photo);
        if (e != null) {
            g.blit(e.loc(), photoX + 3, photoY + 3, photoW - 6, photoH - 6,
                    0F, 0F, e.w(), e.h(), e.w(), e.h());
        } else {
            g.fill(photoX + 3, photoY + 3, photoX + photoW - 3, photoY + photoH - 3, 0xFFD9D2BF);
            boolean failed = PhotoCache.state(photo) == PhotoCache.State.FAILED;
            g.drawCenteredString(this.font,
                    Component.translatable(failed ? "gui.papernote.photo_missing" : "gui.papernote.photo_loading"),
                    photoX + photoW / 2, photoY + (photoH - 8) / 2, 0xFF6B5E45);
        }
        // кусочек скотча сверху
        g.blit(PAPER, photoX + photoW / 2 - 12, photoY - 5, 180, 0, 24, 9, 256, 256);
    }

    private static void drawDashedBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        for (int i = 0; i < w; i += 4) {
            int len = Math.min(2, w - i);
            g.fill(x + i, y, x + i + len, y + 1, color);
            g.fill(x + i, y + h - 1, x + i + len, y + h, color);
        }
        for (int i = 0; i < h; i += 4) {
            int len = Math.min(2, h - i);
            g.fill(x, y + i, x + 1, y + i + len, color);
            g.fill(x + w - 1, y + i, x + w, y + i + len, color);
        }
    }

    // ----------------------------------------------------------------- ввод

    private boolean ready() {
        return progress() >= 1F;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!ready()) return true;
        if (super.mouseClicked(mx, my, button)) return true;
        if (editable && button == 0 && photoH > 0 && !preparing
                && mx >= photoX && mx < photoX + photoW && my >= photoY && my < photoY + photoH) {
            openPicker();
            return true;
        }
        return area.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (ready() && area.mouseDragged(mx, my)) return true;
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        area.mouseReleased();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (ready() && area.mouseScrolled(mx, my, delta)) return true;
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!ready()) return keyCode == 256 ? super.keyPressed(keyCode, scanCode, modifiers) : true;
        if (area.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (ready() && area.charTyped(c, modifiers)) return true;
        return super.charTyped(c, modifiers);
    }

    /** Перетаскивание файла прямо в окно игры. */
    @Override
    public void onFilesDrop(List<Path> paths) {
        if (!editable || preparing || paths.isEmpty()) return;
        for (Path p : paths) {
            if (!LocalPhotos.isImage(p)) continue;
            try {
                onPhotoPicked(LocalPhotos.importFile(p));
            } catch (Exception e) {
                showNotice(Component.translatable("gui.papernote.bad_image"), 4000L);
            }
            return;
        }
        showNotice(Component.translatable("gui.papernote.bad_image"), 4000L);
    }
}
