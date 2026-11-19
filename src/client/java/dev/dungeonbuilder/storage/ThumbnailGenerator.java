package dev.dungeonbuilder.storage;

import com.mojang.blaze3d.platform.NativeImage;
import dev.dungeonbuilder.DungeonBuilder;
import dev.dungeonbuilder.structure.DungeonStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Genera y cachea miniaturas isométricas PNG locales y en memoria para estructuras del servidor. */
public final class ThumbnailGenerator {
    public static final int THUMB_SIZE = 96;
    private static final Map<String, ResourceLocation> LOADED_TEXTURES = new ConcurrentHashMap<>();
    private static final Map<String, ResourceLocation> SERVER_TEXTURES = new ConcurrentHashMap<>();

    private ThumbnailGenerator() {}

    public static ResourceLocation getOrCreateTexture(String id) {
        ResourceLocation cached = LOADED_TEXTURES.get(id);
        if (cached != null) {
            return cached;
        }

        Path thumbPath = LocalLibrary.thumbnailFile(id);
        if (!Files.exists(thumbPath)) {
            ensureThumbnail(id);
        }
        if (!Files.exists(thumbPath)) {
            return null;
        }

        try (InputStream in = Files.newInputStream(thumbPath)) {
            NativeImage nativeImage = NativeImage.read(in);
            DynamicTexture dynTex = new DynamicTexture(nativeImage);
            ResourceLocation texId = DungeonBuilder.id("thumb/" + sanitizeForRl(id));
            Minecraft.getInstance().getTextureManager().register(texId, dynTex);
            LOADED_TEXTURES.put(id, texId);
            return texId;
        } catch (Exception e) {
            DungeonBuilder.LOGGER.warn("No se pudo cargar la miniatura {}", thumbPath, e);
            return null;
        }
    }

    public static ResourceLocation getCachedServerTexture(String id) {
        return SERVER_TEXTURES.get(id);
    }

    public static ResourceLocation getOrCreateServerTexture(
            String id,
            byte[] compressedBytes,
            HolderGetter<Block> blockLookup
    ) {
        ResourceLocation cached = SERVER_TEXTURES.get(id);
        if (cached != null) {
            return cached;
        }

        try {
            DungeonStructure structure = DungeonStructure.fromCompressedBytes(compressedBytes, blockLookup);
            BufferedImage img = renderIsometric(structure);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);

            NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(baos.toByteArray()));
            DynamicTexture dynTex = new DynamicTexture(nativeImage);
            ResourceLocation texId = DungeonBuilder.id("server_thumb/" + sanitizeForRl(id));
            Minecraft.getInstance().getTextureManager().register(texId, dynTex);
            SERVER_TEXTURES.put(id, texId);
            return texId;
        } catch (Exception e) {
            DungeonBuilder.LOGGER.warn("No se pudo generar miniatura del servidor para {}", id, e);
            return null;
        }
    }

    public static void invalidate(String id) {
        ResourceLocation texId = LOADED_TEXTURES.remove(id);
        if (texId != null) {
            Minecraft.getInstance().getTextureManager().release(texId);
        }
    }

    public static void invalidateServer(String id) {
        ResourceLocation texId = SERVER_TEXTURES.remove(id);
        if (texId != null) {
            Minecraft.getInstance().getTextureManager().release(texId);
        }
    }

    public static void clearServerCache() {
        for (ResourceLocation texId : SERVER_TEXTURES.values()) {
            Minecraft.getInstance().getTextureManager().release(texId);
        }
        SERVER_TEXTURES.clear();
    }

    public static void ensureThumbnail(String id) {
        Path structurePath = LocalLibrary.structureFile(id);
        if (!Files.exists(structurePath)) {
            return;
        }
        try {
            DungeonStructure structure = DungeonStructure.fromPath(structurePath);
            BufferedImage img = renderIsometric(structure);
            Path out = LocalLibrary.thumbnailFile(id);
            Files.createDirectories(out.getParent());
            ImageIO.write(img, "PNG", out.toFile());
        } catch (IOException e) {
            DungeonBuilder.LOGGER.warn("No se pudo generar miniatura para {}", id, e);
        }
    }

    private static BufferedImage renderIsometric(DungeonStructure structure) {
        BufferedImage img = new BufferedImage(THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        int rawW = structure.width();
        int rawH = structure.height();
        int rawD = structure.depth();

        int maxDim = Math.max(rawW, Math.max(rawH, rawD));
        int step = Math.max(1, (int) Math.ceil(maxDim / 36.0));

        int sW = Math.max(1, (int) Math.ceil((double) rawW / step));
        int sH = Math.max(1, (int) Math.ceil((double) rawH / step));
        int sD = Math.max(1, (int) Math.ceil((double) rawD / step));

        double isoSpanX = (sW + sD) * 0.866;
        double isoSpanY = (sW + sD) * 0.5 + sH * 1.0;
        double scale = Math.min((THUMB_SIZE - 14.0) / Math.max(1.0, isoSpanX),
                (THUMB_SIZE - 14.0) / Math.max(1.0, isoSpanY));

        double dx = scale * 0.866;
        double dy = scale * 0.5;
        double dz = scale * 1.0;

        double minIsoX = -sD * dx;
        double maxIsoX = sW * dx;
        double minIsoY = -sH * dz;
        double maxIsoY = (sW + sD) * dy;

        double offsetX = (THUMB_SIZE - (maxIsoX + minIsoX)) / 2.0;
        double offsetY = (THUMB_SIZE - (maxIsoY + minIsoY)) / 2.0;

        for (int sy = 0; sy < sH; sy++) {
            int y = Math.min(rawH - 1, sy * step);
            for (int sx = 0; sx < sW; sx++) {
                int x = Math.min(rawW - 1, sx * step);
                for (int sz = 0; sz < sD; sz++) {
                    int z = Math.min(rawD - 1, sz * step);
                    BlockState state = sampleNonAir(structure, x, y, z, step);
                    if (state == null || state.isAir()) {
                        continue;
                    }

                    int mapCol = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
                    if (mapCol == 0) {
                        mapCol = 0x7E8A97;
                    }

                    double cx = offsetX + (sx - sz) * dx;
                    double cy = offsetY + (sx + sz) * dy - sy * dz;

                    drawIsoCube(g, cx, cy, dx, dy, dz, mapCol);
                }
            }
        }

        g.dispose();
        return img;
    }

    private static BlockState sampleNonAir(DungeonStructure s, int bx, int by, int bz, int step) {
        BlockState center = s.getRawBlockState(bx, by, bz);
        if (!center.isAir() || step == 1) {
            return center;
        }
        int maxX = Math.min(s.width(), bx + step);
        int maxY = Math.min(s.height(), by + step);
        int maxZ = Math.min(s.depth(), bz + step);
        for (int y = maxY - 1; y >= by; y--) {
            for (int x = bx; x < maxX; x++) {
                for (int z = bz; z < maxZ; z++) {
                    BlockState candidate = s.getRawBlockState(x, y, z);
                    if (!candidate.isAir()) {
                        return candidate;
                    }
                }
            }
        }
        return center;
    }

    private static void drawIsoCube(Graphics2D g, double cx, double cy,
                                    double dx, double dy, double dz, int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int gr = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        Color topColor = shade(r, gr, b, 1.12f);
        Color leftColor = shade(r, gr, b, 0.88f);
        Color rightColor = shade(r, gr, b, 0.70f);

        int x0 = (int) Math.round(cx);
        int xL = (int) Math.round(cx - dx);
        int xR = (int) Math.round(cx + dx);

        int yTop = (int) Math.round(cy - dz - dy);
        int yMidTop = (int) Math.round(cy - dz);
        int yBottomTop = (int) Math.round(cy - dz + dy);
        int yMidBot = (int) Math.round(cy);
        int yBottom = (int) Math.round(cy + dy);

        Polygon top = new Polygon(
                new int[]{x0, xR, x0, xL},
                new int[]{yTop, yMidTop, yBottomTop, yMidTop},
                4
        );
        g.setColor(topColor);
        g.fillPolygon(top);

        Polygon left = new Polygon(
                new int[]{xL, x0, x0, xL},
                new int[]{yMidTop, yBottomTop, yBottom, yMidBot},
                4
        );
        g.setColor(leftColor);
        g.fillPolygon(left);

        Polygon right = new Polygon(
                new int[]{x0, xR, xR, x0},
                new int[]{yBottomTop, yMidTop, yMidBot, yBottom},
                4
        );
        g.setColor(rightColor);
        g.fillPolygon(right);
    }

    private static Color shade(int r, int g, int b, float factor) {
        int nr = Math.max(0, Math.min(255, Math.round(r * factor)));
        int ng = Math.max(0, Math.min(255, Math.round(g * factor)));
        int nb = Math.max(0, Math.min(255, Math.round(b * factor)));
        return new Color(nr, ng, nb, 255);
    }

    private static String sanitizeForRl(String raw) {
        return raw.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
    }
}