package com.ssi.panzergeneral;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.*;

public class PgAssetManager {
    private final AssetManager assets;
    private final int[] defaultPalette = new int[256];
    private final Map<String, ShpArchive> shpCache = new HashMap<>();

    @Getter
    @ToString(exclude = {"bitmaps", "flippedBitmaps"})
    public static class ShpArchive {
        public final byte[] data;
        public final int count;
        public final int[] spriteOffsets;
        public final int[] paletteOffsets;
        private final Bitmap[] bitmaps;
        private final Bitmap[] flippedBitmaps;

        @Builder
        public ShpArchive(byte[] data, int count, int[] spriteOffsets, int[] paletteOffsets) {
            this.data = data;
            this.count = count;
            this.spriteOffsets = spriteOffsets;
            this.paletteOffsets = paletteOffsets;
            this.bitmaps = new Bitmap[count];
            this.flippedBitmaps = new Bitmap[count];
        }
    }

    public PgAssetManager(AssetManager assets) {
        this.assets = assets;
        loadWarpal();
    }

    public byte[] readAssetBytes(String relPath) throws IOException {
        try (InputStream is = assets.open("pgdata/" + relPath)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private void loadWarpal() {
        // Default fallback palette (SSI SHP uses cmd==1 skips for transparency; index 0 is opaque black)
        for (int i = 0; i < 256; i++) {
            defaultPalette[i] = 0xFF000000 | (i << 16) | (i << 8) | i;
        }
        try {
            byte[] lbm = readAssetBytes("dat/WARPAL.LBM");
            for (int i = 0; i < lbm.length - 8; i++) {
                if (lbm[i] == 'C' && lbm[i + 1] == 'M' && lbm[i + 2] == 'A' && lbm[i + 3] == 'P') {
                    int cmapStart = i + 8;
                    for (int c = 0; c < 256 && cmapStart + c * 3 + 2 < lbm.length; c++) {
                        int r = lbm[cmapStart + c * 3] & 0xFF;
                        int g = lbm[cmapStart + c * 3 + 1] & 0xFF;
                        int b = lbm[cmapStart + c * 3 + 2] & 0xFF;
                        defaultPalette[c] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    break;
                }
            }
        } catch (IOException ignored) {
        }
    }

    public synchronized ShpArchive getShp(String name) {
        String key = name.toUpperCase(Locale.ROOT);
        ShpArchive arch = shpCache.get(key);
        if (arch != null) return arch;
        try {
            byte[] data = readAssetBytes("dat/" + key);
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int count = bb.getInt(4);
            int[] sprOff = new int[count];
            int[] palOff = new int[count];
            for (int i = 0; i < count; i++) {
                sprOff[i] = bb.getInt(8 + i * 8);
                palOff[i] = bb.getInt(12 + i * 8);
            }
            arch = ShpArchive.builder()
                    .data(data)
                    .count(count)
                    .spriteOffsets(sprOff)
                    .paletteOffsets(palOff)
                    .build();
            shpCache.put(key, arch);
            return arch;
        } catch (Exception e) {
            return null;
        }
    }

    public Bitmap getSprite(String shpName, int index) {
        return getSprite(shpName, index, false);
    }

    public Bitmap getSprite(String shpName, int index, boolean flipHorizontal) {
        ShpArchive arch = getShp(shpName);
        if (arch == null || index < 0 || index >= arch.count) return null;
        if (flipHorizontal && arch.flippedBitmaps[index] != null) {
            return arch.flippedBitmaps[index];
        }
        if (!flipHorizontal && arch.bitmaps[index] != null) {
            return arch.bitmaps[index];
        }
        Bitmap bmp = decodeSprite(arch, index, false);
        arch.bitmaps[index] = bmp;
        if (flipHorizontal && bmp != null) {
            Bitmap flipped = decodeSprite(arch, index, true);
            arch.flippedBitmaps[index] = flipped;
            return flipped;
        }
        return bmp;
    }

    private Bitmap decodeSprite(ShpArchive arch, int index, boolean flipX) {
        byte[] data = arch.data;
        int sprOff = arch.spriteOffsets[index];
        int palOff = arch.paletteOffsets[index];
        if (sprOff <= 0 || sprOff + 24 > data.length) return null;

        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int h = bb.getShort(sprOff) + 1;
        int w = bb.getShort(sprOff + 2) + 1;
        int x1 = bb.getInt(sprOff + 8);
        int y1 = bb.getInt(sprOff + 12);
        int x2 = bb.getInt(sprOff + 16);
        int y2 = bb.getInt(sprOff + 20);

        if (w <= 0 || h <= 0 || w > 1024 || h > 1024) return null;

        int[] pal = Arrays.copyOf(defaultPalette, 256);
        if (palOff > 0 && palOff + 4 <= data.length) {
            int nColors = bb.getInt(palOff);
            int pPos = palOff + 4;
            if (nColors > 0 && nColors <= 256 && pPos + nColors * 4 <= data.length) {
                for (int i = 0; i < nColors; i++) {
                    int cIdx = data[pPos] & 0xFF;
                    int r6 = data[pPos + 1] & 0xFF;
                    int g6 = data[pPos + 2] & 0xFF;
                    int b6 = data[pPos + 3] & 0xFF;
                    int r = Math.min(255, (r6 << 2) | (r6 >> 4));
                    int g = Math.min(255, (g6 << 2) | (g6 >> 4));
                    int b = Math.min(255, (b6 << 2) | (b6 >> 4));
                    pal[cIdx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    pPos += 4;
                }
            }
        }

        int[] pixels = new int[w * h];
        int rows = y2 - y1 + 1;
        int pos = sprOff + 24;
        if (rows > 0 && x2 >= x1) {
            for (int r = 0; r < rows && pos < data.length; r++) {
                int py = y1 + r;
                int col = x1;
                while (pos < data.length) {
                    int cmd = data[pos++] & 0xFF;
                    if (cmd == 0) {
                        break;
                    } else if (cmd == 1) {
                        if (pos >= data.length) break;
                        col += (data[pos++] & 0xFF);
                    } else if ((cmd & 1) == 0) {
                        int cnt = cmd >> 1;
                        if (pos >= data.length) break;
                        int val = data[pos++] & 0xFF;
                        int argb = pal[val];
                        for (int k = 0; k < cnt; k++) {
                            if (py >= 0 && py < h && col >= 0 && col < w) {
                                int drawX = flipX ? (w - 1 - col) : col;
                                pixels[py * w + drawX] = argb;
                            }
                            col++;
                        }
                    } else {
                        int cnt = cmd >> 1;
                        for (int k = 0; k < cnt && pos < data.length; k++) {
                            int val = data[pos++] & 0xFF;
                            if (py >= 0 && py < h && col >= 0 && col < w) {
                                int drawX = flipX ? (w - 1 - col) : col;
                                pixels[py * w + drawX] = pal[val];
                            }
                            col++;
                        }
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    public String readBriefing(int brfIndex) {
        String fname = String.format(Locale.ROOT, "scnbrief/BDB%04d.BRF", brfIndex);
        try {
            byte[] b = readAssetBytes(fname);
            if (b.length < 4) return "";
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            int blocks = bb.getInt(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < blocks && 4 + (i + 1) * 200 <= b.length; i++) {
                int start = 4 + i * 200;
                int end = start;
                while (end < start + 200 && b[end] != 0) end++;
                String part = new String(b, start, end - start, StandardCharsets.US_ASCII).trim();
                if (!part.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(part);
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    public Bitmap getTrimmedSprite(String shpName, int index) {
        Bitmap src = getSprite(shpName, index, false);
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 16) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return src;
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    public Bitmap createUnitPortrait(int iconId, int unitClass, int outW, int outH) {
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        // 1. Background sky / ground gradient based on unit class
        boolean isAir = (unitClass == PgDatabase.CLASS_FIGHTER
                || unitClass == PgDatabase.CLASS_TAC_BOMBER
                || unitClass == PgDatabase.CLASS_LEVEL_BOMBER
                || unitClass == PgDatabase.CLASS_AIR_TRANSPORT);
        int topColor = isAir ? 0xFF283A4E : 0xFF2D3829;
        int botColor = isAir ? 0xFF1A2530 : 0xFF1B2218;
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, outH,
                topColor, botColor, android.graphics.Shader.TileMode.CLAMP));
        android.graphics.RectF rect = new android.graphics.RectF(0, 0, outW, outH);
        c.drawRoundRect(rect, 10f, 10f, p);
        p.setShader(null);

        // 2. Draw authentic terrain hex backdrop from TACMAP.SHP
        int terrainTile = isAir ? 28 : (unitClass == PgDatabase.CLASS_INFANTRY ? 120 : 4);
        Bitmap hexBmp = getSprite("TACMAP.SHP", terrainTile, false);
        if (hexBmp != null) {
            p.setAlpha(155);
            p.setFilterBitmap(false);
            float scale = Math.max((float) outW / hexBmp.getWidth(), (float) outH / hexBmp.getHeight()) * 1.15f;
            float dw = hexBmp.getWidth() * scale;
            float dh = hexBmp.getHeight() * scale;
            float dx = (outW - dw) * 0.5f;
            float dy = (outH - dh) * 0.5f;
            c.drawBitmap(hexBmp, null, new android.graphics.RectF(dx, dy, dx + dw, dy + dh), p);
            p.setAlpha(255);
        }

        // 3. Draw German Balkenkreuz national flag badge in top-left corner
        Bitmap flagBmp = getTrimmedSprite("FLAGS.SHP", 7);
        if (flagBmp != null) {
            float fw = Math.max(18f, outW * 0.16f);
            float fh = fw * ((float) flagBmp.getHeight() / Math.max(1, flagBmp.getWidth()));
            c.drawBitmap(flagBmp, null, new android.graphics.RectF(8f, 8f, 8f + fw, 8f + fh), p);
        }

        // 4. Draw crisp trimmed & scaled unit sprite centered in the frame
        Bitmap unitBmp = getTrimmedSprite("TACICONS.SHP", iconId);
        if (unitBmp != null) {
            p.setFilterBitmap(false);
            float availW = outW * 0.80f;
            float availH = outH * 0.76f;
            float scale = Math.min(availW / Math.max(1, unitBmp.getWidth()),
                    availH / Math.max(1, unitBmp.getHeight()));
            float uw = unitBmp.getWidth() * scale;
            float uh = unitBmp.getHeight() * scale;
            float ux = (outW - uw) * 0.5f;
            float uy = (outH - uh) * 0.52f;
            c.drawBitmap(unitBmp, null, new android.graphics.RectF(ux, uy, ux + uw, uy + uh), p);
        }

        // 5. Outer brass border
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(0xFFC5A059);
        c.drawRoundRect(new android.graphics.RectF(1f, 1f, outW - 1f, outH - 1f), 10f, 10f, p);
        return out;
    }
}
