package dev.dungeonbuilder.preview;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.dungeonbuilder.DungeonBuilder;
import dev.dungeonbuilder.network.ModNetworking;
import dev.dungeonbuilder.network.ModNetworking.PlaceChunkPayload;
import dev.dungeonbuilder.network.ModNetworking.PlaceServerStructurePayload;
import dev.dungeonbuilder.storage.LocalLibrary;
import dev.dungeonbuilder.structure.DungeonStructure;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Gestiona la previsualización en holograma usando VBOs cacheados por sub-chunks y frustum culling. */
public final class PreviewManager {
    private static final int CHUNK_SIZE = 32;
    private static final double MAX_CHUNK_RENDER_DIST_SQR = 400.0 * 400.0;
    private static final int MAX_SPECIAL_BOXES = 512;

    private static final Map<String, byte[]> SERVER_BYTES_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 16;
                }
            }
    );

    private static KeyMapping rotateKey;
    private static KeyMapping moveHereKey;
    private static KeyMapping nudgeForwardKey;
    private static KeyMapping nudgeBackwardKey;
    private static KeyMapping nudgeLeftKey;
    private static KeyMapping nudgeRightKey;
    private static KeyMapping nudgeUpKey;
    private static KeyMapping nudgeDownKey;
    private static KeyMapping confirmKey;
    private static KeyMapping cancelKey;

    private static ActivePreview active;

    private PreviewManager() {}

    public static void cacheServerBytes(String structureId, byte[] bytes) {
        if (structureId != null && bytes != null) {
            SERVER_BYTES_CACHE.put(structureId, bytes);
        }
    }

    public static byte[] getCachedServerBytes(String structureId) {
        return SERVER_BYTES_CACHE.get(structureId);
    }

    public static void removeCachedServerBytes(String structureId) {
        SERVER_BYTES_CACHE.remove(structureId);
    }

    public static void clearServerCache() {
        SERVER_BYTES_CACHE.clear();
    }

    private record GhostBlock(
            int rawX,
            int rawY,
            int rawZ,
            BlockState rawState,
            int rawVisibleMask,
            boolean isFluid,
            boolean fluidHasSameAbove
    ) {}

    private record RotatedEntry(
            int relX,
            int relY,
            int relZ,
            BlockState state,
            int visibleMask,
            boolean isFluid,
            boolean fluidHasSameAbove,
            boolean isEntityBlock
    ) {}

    private static final class ChunkMesh implements AutoCloseable {
        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;
        final VertexBuffer vbo;
        final List<BlockPos> specialRelPositions;

        ChunkMesh(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                  VertexBuffer vbo, List<BlockPos> specialRelPositions) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.vbo = vbo;
            this.specialRelPositions = specialRelPositions;
        }

        @Override
        public void close() {
            if (vbo != null) {
                vbo.close();
            }
        }
    }

    private static final class ActivePreview implements AutoCloseable {
        final String structureId;
        final boolean isServerStructure;
        final byte[] compressedBytes;
        final DungeonStructure structure;
        final List<GhostBlock> rawSurfaceBlocks;
        BlockPos origin;
        int rotationSteps;
        final List<ChunkMesh> chunks = new ArrayList<>();

        ActivePreview(String structureId, boolean isServerStructure, byte[] compressedBytes,
                      DungeonStructure structure, BlockPos origin, List<GhostBlock> rawSurfaceBlocks) {
            this.structureId = structureId;
            this.isServerStructure = isServerStructure;
            this.compressedBytes = compressedBytes;
            this.structure = structure;
            this.origin = origin.immutable();
            this.rotationSteps = 0;
            this.rawSurfaceBlocks = rawSurfaceBlocks;
            rebuildGpuChunks();
        }

        Rotation rotation() {
            return DungeonStructure.rotationFromSteps(rotationSteps);
        }

        void rebuildGpuChunks() {
            closeChunks();

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }

            Rotation rot = rotation();
            BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
            Map<BlockState, BakedModel> modelCache = new IdentityHashMap<>();
            Map<Long, List<RotatedEntry>> grouped = new HashMap<>();

            for (GhostBlock gb : rawSurfaceBlocks) {
                BlockPos rel = structure.rotateLocalPos(gb.rawX(), gb.rawY(), gb.rawZ(), rot);
                BlockState rotState = rot == Rotation.NONE ? gb.rawState() : gb.rawState().rotate(rot);
                boolean isEntityBlock = !gb.isFluid() && rotState.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED;
                int rotMask = rotateFaceMask(gb.rawVisibleMask(), rot);

                int cx = Math.floorDiv(rel.getX(), CHUNK_SIZE);
                int cy = Math.floorDiv(rel.getY(), CHUNK_SIZE);
                int cz = Math.floorDiv(rel.getZ(), CHUNK_SIZE);
                long key = packChunkKey(cx, cy, cz);

                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(new RotatedEntry(
                        rel.getX(), rel.getY(), rel.getZ(),
                        rotState, rotMask,
                        gb.isFluid(), gb.fluidHasSameAbove(), isEntityBlock
                ));
            }

            RandomSource random = RandomSource.create(42L);
            PoseStack localPose = new PoseStack();
            BlockPos.MutableBlockPos worldPos = new BlockPos.MutableBlockPos();

            for (Map.Entry<Long, List<RotatedEntry>> entry : grouped.entrySet()) {
                List<RotatedEntry> list = entry.getValue();
                if (list.isEmpty()) {
                    continue;
                }

                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int maxY = Integer.MIN_VALUE;
                int maxZ = Integer.MIN_VALUE;
                for (RotatedEntry e : list) {
                    if (e.relX < minX) minX = e.relX;
                    if (e.relY < minY) minY = e.relY;
                    if (e.relZ < minZ) minZ = e.relZ;
                    if (e.relX + 1 > maxX) maxX = e.relX + 1;
                    if (e.relY + 1 > maxY) maxY = e.relY + 1;
                    if (e.relZ + 1 > maxZ) maxZ = e.relZ + 1;
                }

                List<BlockPos> specials = new ArrayList<>();
                VertexBuffer vbo = null;

                try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(256 * 1024)) {
                    BufferBuilder builder = new BufferBuilder(
                            byteBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

                    for (RotatedEntry e : list) {
                        if (e.isEntityBlock) {
                            specials.add(new BlockPos(e.relX, e.relY, e.relZ));
                            continue;
                        }

                        worldPos.set(origin.getX() + e.relX, origin.getY() + e.relY, origin.getZ() + e.relZ);
                        localPose.pushPose();
                        localPose.translate(e.relX - minX, e.relY - minY, e.relZ - minZ);
                        PoseStack.Pose pose = localPose.last();

                        if (e.isFluid) {
                            renderFluidTranslucent(mc, e.state, worldPos, pose, builder, e.visibleMask, e.fluidHasSameAbove);
                        } else if (e.state.getRenderShape() == RenderShape.MODEL) {
                            BakedModel model = modelCache.computeIfAbsent(e.state, dispatcher::getBlockModel);
                            renderModelTranslucent(mc, e.state, worldPos, model, pose, builder, random, e.visibleMask);
                        }

                        localPose.popPose();
                    }

                    MeshData mesh = builder.build();
                    if (mesh != null) {
                        vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                        vbo.bind();
                        vbo.upload(mesh);
                        VertexBuffer.unbind();
                    }
                }

                if (vbo != null || !specials.isEmpty()) {
                    chunks.add(new ChunkMesh(minX, minY, minZ, maxX, maxY, maxZ, vbo, specials));
                }
            }
        }

        void closeChunks() {
            for (ChunkMesh chunk : chunks) {
                chunk.close();
            }
            chunks.clear();
        }

        @Override
        public void close() {
            closeChunks();
        }
    }

    public static void register() {
        rotateKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.dungeonbuilder.preview_rotate",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.dungeonbuilder.main"
        ));
        moveHereKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.dungeonbuilder.preview_move_here",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.dungeonbuilder.main"
        ));
        nudgeForwardKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.dungeonbuilder.preview_nudge_forward",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UP,
                "category.dungeonbuilder.main"
        ));
        nudgeBackwardKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.dungeonbuilder.preview_nudge_backward",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_DOWN,
                "category.dungeonbuilder.main"
        ));
        nudgeLeftKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.dungeonbuilder.preview_nudge_left",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT,
                "category.dungeonbuilder.main"
        ));
        nudgeRightKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.dungeonbuilder.preview_nudge_right",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT,
                "category.dungeonbuilder.main"
        ));
        nudgeUpKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.dungeonbuilder.preview_nudge_up",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_PAGE_UP,
                "category.dungeonbuilder.main"
        ));
        nudgeDownKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.dungeonbuilder.preview_nudge_down",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_PAGE_DOWN,
                "category.dungeonbuilder.main"
        ));
        confirmKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.dungeonbuilder.preview_confirm",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_ENTER,
                "category.dungeonbuilder.main"
        ));
        cancelKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.dungeonbuilder.preview_cancel",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                "category.dungeonbuilder.main"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(PreviewManager::onClientTick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(PreviewManager::onRenderWorld);
        HudRenderCallback.EVENT.register(PreviewManager::onRenderHud);
    }

    public static boolean isActive() {
        return active != null;
    }

    public static void clear() {
        if (active != null) {
            active.close();
            active = null;
        }
    }

    public static void startPreview(String structureId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        try {
            clear();
            byte[] bytes = LocalLibrary.readStructureBytes(structureId);
            DungeonStructure structure = DungeonStructure.fromCompressedBytes(
                    bytes, mc.level.holderLookup(Registries.BLOCK));
            List<GhostBlock> surface = extractSurfaceBlocks(structure);
            active = new ActivePreview(structureId, false, bytes, structure, mc.player.blockPosition(), surface);

            mc.player.displayClientMessage(
                    Component.literal("DungeonBuilder ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal("» ").withStyle(ChatFormatting.GRAY))
                            .append(Component.translatable("message.dungeonbuilder.preview_started",
                                            Component.literal(structure.name()).withStyle(ChatFormatting.YELLOW))
                                    .withStyle(ChatFormatting.WHITE)),
                    true
            );
        } catch (Exception e) {
            clear();
            DungeonBuilder.LOGGER.error("No se pudo iniciar la previsualización de {}", structureId, e);
            mc.player.displayClientMessage(
                    Component.translatable("message.dungeonbuilder.error_preview_load")
                            .withStyle(ChatFormatting.RED),
                    false
            );
        }
    }

    public static void startServerPreview(String structureId, byte[] bytes) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        try {
            clear();
            cacheServerBytes(structureId, bytes);
            DungeonStructure structure = DungeonStructure.fromCompressedBytes(
                    bytes, mc.level.holderLookup(Registries.BLOCK));
            List<GhostBlock> surface = extractSurfaceBlocks(structure);
            active = new ActivePreview(structureId, true, bytes, structure, mc.player.blockPosition(), surface);

            mc.player.displayClientMessage(
                    Component.literal("DungeonBuilder ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal("» ").withStyle(ChatFormatting.GRAY))
                            .append(Component.translatable("message.dungeonbuilder.preview_started",
                                            Component.literal(structure.name()).withStyle(ChatFormatting.YELLOW))
                                    .withStyle(ChatFormatting.WHITE)),
                    true
            );
        } catch (Exception e) {
            clear();
            DungeonBuilder.LOGGER.error("No se pudo iniciar la previsualización del servidor para {}", structureId, e);
            mc.player.displayClientMessage(
                    Component.translatable("message.dungeonbuilder.error_preview_load")
                            .withStyle(ChatFormatting.RED),
                    false
            );
        }
    }

    public static void placeLocalStructureNow(String structureId, BlockPos origin, int rotationSteps) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        try {
            byte[] bytes = LocalLibrary.readStructureBytes(structureId);
            sendChunks(bytes, origin, rotationSteps);
        } catch (Exception e) {
            DungeonBuilder.LOGGER.error("Error leyendo estructura {} para colocar", structureId, e);
            mc.player.displayClientMessage(
                    Component.translatable("message.dungeonbuilder.error_place_failed")
                            .withStyle(ChatFormatting.RED),
                    false
            );
        }
    }

    private static void sendChunks(byte[] bytes, BlockPos origin, int rotationSteps) {
        UUID transferId = UUID.randomUUID();
        int chunkSize = ModNetworking.UPLOAD_CHUNK_SIZE;
        int totalChunks = Math.max(1, (int) Math.ceil((double) bytes.length / chunkSize));

        for (int i = 0; i < totalChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(bytes.length, start + chunkSize);
            byte[] slice = Arrays.copyOfRange(bytes, start, end);
            ClientPlayNetworking.send(new PlaceChunkPayload(
                    transferId, i, totalChunks, origin, Math.floorMod(rotationSteps, 4), slice
            ));
        }
    }

    private static void nudgeOrigin(Minecraft mc, int dx, int dy, int dz) {
        if (active == null) {
            return;
        }
        active.origin = active.origin.offset(dx, dy, dz).immutable();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.25F));
    }

    private static void onClientTick(Minecraft mc) {
        if (active == null) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            clear();
            return;
        }
        if (mc.screen != null) {
            return;
        }

        Direction facing = mc.player.getDirection();

        while (rotateKey.consumeClick()) {
            int step = Screen.hasShiftDown() ? 3 : 1;
            active.rotationSteps = (active.rotationSteps + step) % 4;
            active.rebuildGpuChunks();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.1F));
            mc.player.displayClientMessage(
                    Component.translatable("message.dungeonbuilder.preview_rotated",
                                    (active.rotationSteps * 90) + "°")
                            .withStyle(ChatFormatting.AQUA),
                    true
            );
        }

        while (moveHereKey.consumeClick()) {
            active.origin = mc.player.blockPosition().immutable();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            mc.player.displayClientMessage(
                    Component.translatable("message.dungeonbuilder.preview_moved",
                                    active.origin.getX() + ", " + active.origin.getY() + ", " + active.origin.getZ())
                            .withStyle(ChatFormatting.AQUA),
                    true
            );
        }

        while (nudgeUpKey.consumeClick()) {
            nudgeOrigin(mc, 0, 1, 0);
        }

        while (nudgeDownKey.consumeClick()) {
            nudgeOrigin(mc, 0, -1, 0);
        }

        while (nudgeForwardKey.consumeClick()) {
            if (Screen.hasShiftDown()) {
                nudgeOrigin(mc, 0, 1, 0);
            } else {
                nudgeOrigin(mc, facing.getStepX(), 0, facing.getStepZ());
            }
        }

        while (nudgeBackwardKey.consumeClick()) {
            if (Screen.hasShiftDown()) {
                nudgeOrigin(mc, 0, -1, 0);
            } else {
                nudgeOrigin(mc, -facing.getStepX(), 0, -facing.getStepZ());
            }
        }

        while (nudgeLeftKey.consumeClick()) {
            Direction left = facing.getCounterClockWise();
            nudgeOrigin(mc, left.getStepX(), 0, left.getStepZ());
        }

        while (nudgeRightKey.consumeClick()) {
            Direction right = facing.getClockWise();
            nudgeOrigin(mc, right.getStepX(), 0, right.getStepZ());
        }

        while (confirmKey.consumeClick()) {
            boolean isServer = active.isServerStructure;
            String id = active.structureId;
            byte[] bytes = active.compressedBytes;
            BlockPos origin = active.origin;
            int rotSteps = active.rotationSteps;
            clear();
            if (isServer) {
                ClientPlayNetworking.send(new PlaceServerStructurePayload(id, origin, Math.floorMod(rotSteps, 4)));
            } else {
                sendChunks(bytes, origin, rotSteps);
            }
            return;
        }

        while (cancelKey.consumeClick()) {
            clear();
            mc.player.displayClientMessage(
                    Component.translatable("message.dungeonbuilder.preview_cancelled")
                            .withStyle(ChatFormatting.YELLOW),
                    true
            );
            return;
        }
    }

    private static void onRenderWorld(WorldRenderContext context) {
        ActivePreview preview = active;
        if (preview == null) {
            return;
        }
        PoseStack poseStack = context.matrixStack();
        if (poseStack == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        Vec3 camPos = context.camera().getPosition();
        Rotation rot = preview.rotation();
        BlockPos origin = preview.origin;
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();

        Frustum frustum = context.frustum();
        Matrix4f projMatrix = context.projectionMatrix();
        Matrix4f baseView = new Matrix4f(RenderSystem.getModelViewMatrix());
        Matrix4f chunkModelView = new Matrix4f();

        List<BlockPos> visibleSpecials = new ArrayList<>();

        RenderType translucent = RenderType.translucent();
        translucent.setupRenderState();
        RenderSystem.depthMask(false);
        ShaderInstance shader = RenderSystem.getShader();

        if (shader != null) {
            if (shader.CHUNK_OFFSET != null) {
                shader.CHUNK_OFFSET.set(0.0f, 0.0f, 0.0f);
            }

            for (ChunkMesh chunk : preview.chunks) {
                double wMinX = ox + chunk.minX;
                double wMinY = oy + chunk.minY;
                double wMinZ = oz + chunk.minZ;
                double wMaxX = ox + chunk.maxX;
                double wMaxY = oy + chunk.maxY;
                double wMaxZ = oz + chunk.maxZ;

                double cx = (wMinX + wMaxX) * 0.5 - camPos.x;
                double cy = (wMinY + wMaxY) * 0.5 - camPos.y;
                double cz = (wMinZ + wMaxZ) * 0.5 - camPos.z;
                if (cx * cx + cy * cy + cz * cz > MAX_CHUNK_RENDER_DIST_SQR) {
                    continue;
                }

                if (frustum != null && !frustum.isVisible(new AABB(wMinX, wMinY, wMinZ, wMaxX, wMaxY, wMaxZ))) {
                    continue;
                }

                if (chunk.vbo != null) {
                    poseStack.pushPose();
                    poseStack.translate(wMinX - camPos.x, wMinY - camPos.y + 0.002, wMinZ - camPos.z);
                    baseView.mul(poseStack.last().pose(), chunkModelView);
                    chunk.vbo.bind();
                    chunk.vbo.drawWithShader(chunkModelView, projMatrix, shader);
                    poseStack.popPose();
                }

                if (!chunk.specialRelPositions.isEmpty() && visibleSpecials.size() < MAX_SPECIAL_BOXES) {
                    for (BlockPos rel : chunk.specialRelPositions) {
                        visibleSpecials.add(origin.offset(rel));
                        if (visibleSpecials.size() >= MAX_SPECIAL_BOXES) {
                            break;
                        }
                    }
                }
            }
            VertexBuffer.unbind();
        }
        RenderSystem.depthMask(true);
        translucent.clearRenderState();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lineConsumer = buffers.getBuffer(RenderType.lines());

        int rW = preview.structure.rotatedWidth(rot);
        int rH = preview.structure.height();
        int rD = preview.structure.rotatedDepth(rot);

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        AABB bounds = new AABB(ox, oy, oz, ox + rW, oy + rH, oz + rD);
        LevelRenderer.renderLineBox(poseStack, lineConsumer, bounds, 0.20f, 0.85f, 1.0f, 0.90f);

        AABB originBox = new AABB(origin).inflate(0.02);
        LevelRenderer.renderLineBox(poseStack, lineConsumer, originBox, 1.0f, 0.85f, 0.25f, 1.0f);

        for (BlockPos sp : visibleSpecials) {
            LevelRenderer.renderLineBox(poseStack, lineConsumer, new AABB(sp).deflate(0.06), 1.0f, 0.78f, 0.15f, 0.85f);
        }

        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static void renderModelTranslucent(
            Minecraft mc,
            BlockState state,
            BlockPos worldPos,
            BakedModel model,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            RandomSource random,
            int visibleMask
    ) {
        final float holoAlpha = 0.46f;

        for (Direction dir : Direction.values()) {
            if ((visibleMask & (1 << dir.ordinal())) == 0) {
                continue;
            }
            random.setSeed(42L);
            List<BakedQuad> quads = model.getQuads(state, dir, random);
            emitQuads(mc, state, worldPos, pose, consumer, quads, holoAlpha);
        }

        random.setSeed(42L);
        List<BakedQuad> generalQuads = model.getQuads(state, null, random);
        emitQuads(mc, state, worldPos, pose, consumer, generalQuads, holoAlpha);
    }

    private static void emitQuads(
            Minecraft mc,
            BlockState state,
            BlockPos worldPos,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            List<BakedQuad> quads,
            float alpha
    ) {
        for (BakedQuad quad : quads) {
            float r = 0.80f;
            float g = 0.96f;
            float b = 1.00f;
            if (quad.isTinted() && mc.level != null) {
                try {
                    int tint = mc.getBlockColors().getColor(state, mc.level, worldPos, quad.getTintIndex());
                    if (tint != -1) {
                        r *= ((tint >> 16) & 0xFF) / 255.0f;
                        g *= ((tint >> 8) & 0xFF) / 255.0f;
                        b *= (tint & 0xFF) / 255.0f;
                    }
                } catch (Exception ignored) {
                }
            }
            consumer.putBulkData(pose, quad, r, g, b, alpha, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
    }

    private static void renderFluidTranslucent(
            Minecraft mc,
            BlockState state,
            BlockPos worldPos,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int visibleMask,
            boolean hasSameAbove
    ) {
        FluidState fluidState = state.getFluidState();
        if (fluidState.isEmpty()) {
            return;
        }
        FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluidState.getType());
        if (handler == null) {
            return;
        }
        TextureAtlasSprite[] sprites = handler.getFluidSprites(mc.level, worldPos, fluidState);
        if (sprites == null || sprites.length == 0 || sprites[0] == null) {
            return;
        }

        TextureAtlasSprite sprite = sprites[0];
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();

        int color = handler.getFluidColor(mc.level, worldPos, fluidState);
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = 0.50f;

        float topY = hasSameAbove ? 1.0f : 0.875f;

        if ((visibleMask & (1 << Direction.UP.ordinal())) != 0) {
            emitFluidFace(pose, consumer,
                    0, topY, 0, u0, v0,
                    0, topY, 1, u0, v1,
                    1, topY, 1, u1, v1,
                    1, topY, 0, u1, v0,
                    0, 1, 0, r, g, b, a);
        }
        if ((visibleMask & (1 << Direction.DOWN.ordinal())) != 0) {
            emitFluidFace(pose, consumer,
                    0, 0, 1, u0, v0,
                    0, 0, 0, u0, v1,
                    1, 0, 0, u1, v1,
                    1, 0, 1, u1, v0,
                    0, -1, 0, r, g, b, a);
        }
        if ((visibleMask & (1 << Direction.NORTH.ordinal())) != 0) {
            emitFluidFace(pose, consumer,
                    1, topY, 0, u0, v0,
                    1, 0, 0, u0, v1,
                    0, 0, 0, u1, v1,
                    0, topY, 0, u1, v0,
                    0, 0, -1, r, g, b, a);
        }
        if ((visibleMask & (1 << Direction.SOUTH.ordinal())) != 0) {
            emitFluidFace(pose, consumer,
                    0, topY, 1, u0, v0,
                    0, 0, 1, u0, v1,
                    1, 0, 1, u1, v1,
                    1, topY, 1, u1, v0,
                    0, 0, 1, r, g, b, a);
        }
        if ((visibleMask & (1 << Direction.WEST.ordinal())) != 0) {
            emitFluidFace(pose, consumer,
                    0, topY, 0, u0, v0,
                    0, 0, 0, u0, v1,
                    0, 0, 1, u1, v1,
                    0, topY, 1, u1, v0,
                    -1, 0, 0, r, g, b, a);
        }
        if ((visibleMask & (1 << Direction.EAST.ordinal())) != 0) {
            emitFluidFace(pose, consumer,
                    1, topY, 1, u0, v0,
                    1, 0, 1, u0, v1,
                    1, 0, 0, u1, v1,
                    1, topY, 0, u1, v0,
                    1, 0, 0, r, g, b, a);
        }
    }

    private static void emitFluidFace(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            float x4, float y4, float z4, float u4, float v4,
            float nx, float ny, float nz,
            float r, float g, float b, float a
    ) {
        consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setUv(u1, v1).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setUv(u2, v2).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x3, y3, z3).setColor(r, g, b, a).setUv(u3, v3).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x4, y4, z4).setColor(r, g, b, a).setUv(u4, v4).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
    }

    private static void onRenderHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        ActivePreview preview = active;
        if (preview == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }

        Rotation rot = preview.rotation();
        int rW = preview.structure.rotatedWidth(rot);
        int rH = preview.structure.height();
        int rD = preview.structure.rotatedDepth(rot);

        Component line1 = Component.translatable("hud.dungeonbuilder.preview.header",
                        preview.structure.name(), rW, rH, rD, (preview.rotationSteps * 90) + "°")
                .copy()
                .append(Component.literal("  •  Pos: (" + preview.origin.getX()
                        + ", " + preview.origin.getY() + ", " + preview.origin.getZ() + ")"));

        Component line2 = Component.literal("[" + rotateKey.getTranslatedKeyMessage().getString() + "] Rotar (±Shift)  •  ["
                + moveHereKey.getTranslatedKeyMessage().getString() + "] Traer  •  [Flechas] Mover  •  [Shift+↑/↓ o Re/AvPág] ±Y  •  ["
                + confirmKey.getTranslatedKeyMessage().getString() + "] Colocar  •  ["
                + cancelKey.getTranslatedKeyMessage().getString() + "] Cancelar");

        int w1 = mc.font.width(line1);
        int w2 = mc.font.width(line2);
        int boxW = Math.min(guiGraphics.guiWidth() - 16, Math.max(w1, w2) + 18);
        int boxH = 30;
        int x = (guiGraphics.guiWidth() - boxW) / 2;
        int y = 8;

        guiGraphics.fill(x, y, x + boxW, y + boxH, 0xCC111622);
        guiGraphics.renderOutline(x, y, boxW, boxH, 0xFF38BDF8);
        guiGraphics.drawCenteredString(mc.font,
                mc.font.plainSubstrByWidth(line1.getString(), Math.max(1, boxW - 10)),
                guiGraphics.guiWidth() / 2, y + 5, 0xFF7DD3FC);
        guiGraphics.drawCenteredString(mc.font,
                mc.font.plainSubstrByWidth(line2.getString(), Math.max(1, boxW - 10)),
                guiGraphics.guiWidth() / 2, y + 17, 0xFFE2E8F0);
    }

    private static List<GhostBlock> extractSurfaceBlocks(DungeonStructure s) {
        int w = s.width();
        int h = s.height();
        int d = s.depth();
        List<GhostBlock> list = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    BlockState state = s.getRawBlockState(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }

                    FluidState fluid = state.getFluidState();
                    boolean isFluidOnly = !fluid.isEmpty() && state.getRenderShape() == RenderShape.INVISIBLE;
                    boolean fluidHasSameAbove = false;
                    if (isFluidOnly && y + 1 < h) {
                        fluidHasSameAbove = !s.getRawBlockState(x, y + 1, z).getFluidState().isEmpty();
                    }

                    int mask = 0;
                    for (Direction dir : Direction.values()) {
                        int nx = x + dir.getStepX();
                        int ny = y + dir.getStepY();
                        int nz = z + dir.getStepZ();
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h || nz < 0 || nz >= d) {
                            mask |= (1 << dir.ordinal());
                        } else {
                            BlockState neighbor = s.getRawBlockState(nx, ny, nz);
                            if (isFluidOnly) {
                                if (neighbor.getFluidState().isEmpty() && !neighbor.canOcclude()) {
                                    mask |= (1 << dir.ordinal());
                                }
                            } else if (!neighbor.canOcclude()) {
                                mask |= (1 << dir.ordinal());
                            }
                        }
                    }

                    if (mask != 0 || (!isFluidOnly && state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED)) {
                        list.add(new GhostBlock(x, y, z, state, mask, isFluidOnly, fluidHasSameAbove));
                    }
                }
            }
        }
        return list;
    }

    private static int rotateFaceMask(int rawMask, Rotation rot) {
        if (rot == Rotation.NONE) {
            return rawMask;
        }
        int out = 0;
        for (Direction dir : Direction.values()) {
            if ((rawMask & (1 << dir.ordinal())) != 0) {
                Direction rotated = rot.rotate(dir);
                out |= (1 << rotated.ordinal());
            }
        }
        return out;
    }

    private static long packChunkKey(int cx, int cy, int cz) {
        return (((long) cx & 0x1FFFFFL) << 42)
                | (((long) cz & 0x1FFFFFL) << 21)
                | ((long) cy & 0x1FFFFFL);
    }
}