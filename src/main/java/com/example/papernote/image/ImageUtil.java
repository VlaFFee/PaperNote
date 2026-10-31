package com.example.papernote.image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;

/**
 * Работа с картинками без AWT-окон: чтение с аппаратным прореживанием (быстро для больших фото),
 * масштабирование «усреднением по площади», кодирование в JPEG, SHA-1.
 */
public final class ImageUtil {
    static {
        System.setProperty("java.awt.headless", "true");
        ImageIO.setUseCache(false);
    }

    /** Максимальный размер стороны фото, которое уходит на сервер. */
    public static final int UPLOAD_MAX_DIM = 384;
    /** Жёсткий предел размера JPEG при загрузке с клиента. */
    public static final int UPLOAD_MAX_BYTES = 200 * 1024;
    private static final long MAX_SOURCE_PIXELS = 120_000_000L;

    public record Decoded(int w, int h, int[] argb) {}
    public record Prepared(String hash, byte[] jpeg) {}

    private ImageUtil() {}

    // ---------------------------------------------------------------- чтение

    public static Decoded read(Path path, int maxDim) {
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            return read(in, maxDim);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Decoded read(byte[] data, int maxDim) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            return read(in, maxDim);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Decoded read(ImageInputStream in, int maxDim) throws Exception {
        if (in == null) return null;
        Iterator<ImageReader> it = ImageIO.getImageReaders(in);
        if (!it.hasNext()) return null;
        ImageReader reader = it.next();
        try {
            reader.setInput(in, true, true);
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            if (w <= 0 || h <= 0 || (long) w * h > MAX_SOURCE_PIXELS) return null;
            ImageReadParam param = reader.getDefaultReadParam();
            int sub = Math.max(1, Math.max(w, h) / (maxDim * 2));
            if (sub > 1) param.setSourceSubsampling(sub, sub, 0, 0);
            BufferedImage img = reader.read(0, param);
            int iw = img.getWidth();
            int ih = img.getHeight();
            int[] px = img.getRGB(0, 0, iw, ih, null, 0, iw);
            return fit(new Decoded(iw, ih, px), maxDim);
        } finally {
            reader.dispose();
        }
    }

    /** Размеры из заголовка файла, без полной распаковки. null — если это не картинка. */
    public static int[] dimensions(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (in == null) return null;
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return null;
            ImageReader reader = it.next();
            try {
                reader.setInput(in, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------- масштабирование

    public static Decoded fit(Decoded d, int maxDim) {
        int m = Math.max(d.w(), d.h());
        if (m <= maxDim) return d;
        float s = maxDim / (float) m;
        int nw = Math.max(1, Math.round(d.w() * s));
        int nh = Math.max(1, Math.round(d.h() * s));
        return boxScale(d, nw, nh);
    }

    private static Decoded boxScale(Decoded d, int nw, int nh) {
        int[] out = new int[nw * nh];
        int[] src = d.argb();
        for (int y = 0; y < nh; y++) {
            int y0 = (int) ((long) y * d.h() / nh);
            int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * d.h() / nh));
            for (int x = 0; x < nw; x++) {
                int x0 = (int) ((long) x * d.w() / nw);
                int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * d.w() / nw));
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    int row = yy * d.w();
                    for (int xx = x0; xx < x1; xx++) {
                        int p = src[row + xx];
                        a += p >>> 24;
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        n++;
                    }
                }
                out[y * nw + x] = ((int) (a / n) << 24) | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
            }
        }
        return new Decoded(nw, nh, out);
    }

    // ---------------------------------------------------------------- JPEG

    public static byte[] encodeJpeg(Decoded d, float quality) {
        try {
            int[] rgb = new int[d.argb().length];
            for (int i = 0; i < rgb.length; i++) {
                int p = d.argb()[i];
                int a = p >>> 24;
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (a < 255) { // прозрачное → на белый фон
                    r = (r * a + 255 * (255 - a)) / 255;
                    g = (g * a + 255 * (255 - a)) / 255;
                    b = (b * a + 255 * (255 - a)) / 255;
                }
                rgb[i] = (r << 16) | (g << 8) | b;
            }
            BufferedImage bi = new BufferedImage(d.w(), d.h(), BufferedImage.TYPE_INT_RGB);
            bi.setRGB(0, 0, d.w(), d.h(), rgb, 0, d.w());

            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try {
                ImageWriteParam wp = writer.getDefaultWriteParam();
                wp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                wp.setCompressionQuality(quality);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(bi, null, null), wp);
                }
                return bos.toByteArray();
            } finally {
                writer.dispose();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Читает файл с диска, уменьшает и кодирует в JPEG, влезающий в лимит. */
    public static Prepared prepareUpload(Path path) {
        Decoded d = read(path, UPLOAD_MAX_DIM);
        if (d == null) return null;
        float[] qualities = {0.85f, 0.7f, 0.55f, 0.4f};
        for (int attempt = 0; attempt < 5; attempt++) {
            for (float q : qualities) {
                byte[] b = encodeJpeg(d, q);
                if (b != null && b.length <= UPLOAD_MAX_BYTES) {
                    return new Prepared(sha1(b), b);
                }
            }
            d = fit(d, Math.max(32, (int) (Math.max(d.w(), d.h()) * 0.75f)));
        }
        return null;
    }

    // ---------------------------------------------------------------- хэш

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
