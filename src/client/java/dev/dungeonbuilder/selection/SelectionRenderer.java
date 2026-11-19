package dev.dungeonbuilder.selection;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Dibuja en el cliente la caja morada de selección mientras el jugador sostiene el Seleccionador. */
public final class SelectionRenderer {
    private static String dimensionId = "";
    private static BlockPos firstCorner;
    private static BlockPos secondCorner;

    private SelectionRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SelectionRenderer::onRenderWorld);
    }

    public static void update(boolean active, String dimId, BlockPos first, boolean hasSecond, BlockPos second) {
        if (!active || dimId == null || dimId.isEmpty() || first == null) {
            clear();
            return;
        }
        dimensionId = dimId;
        firstCorner = first.immutable();
        secondCorner = hasSecond && second != null ? second.immutable() : null;
    }

    public static void clear() {
        dimensionId = "";
        firstCorner = null;
        secondCorner = null;
    }

    private static void onRenderWorld(WorldRenderContext context) {
        if (firstCorner == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!SelectionManager.isHoldingSelector(mc.player)) {
            return;
        }
        if (!mc.level.dimension().location().toString().equals(dimensionId)) {
            return;
        }

        PoseStack poseStack = context.matrixStack();
        if (poseStack == null) {
            return;
        }

        Vec3 camPos = context.camera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lineConsumer = buffers.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        // Esquina 1 resaltada en lavanda claro
        AABB firstBox = new AABB(firstCorner).inflate(0.015);
        LevelRenderer.renderLineBox(poseStack, lineConsumer, firstBox, 0.84f, 0.70f, 1.0f, 0.98f);

        if (secondCorner != null) {
            int minX = Math.min(firstCorner.getX(), secondCorner.getX());
            int minY = Math.min(firstCorner.getY(), secondCorner.getY());
            int minZ = Math.min(firstCorner.getZ(), secondCorner.getZ());
            int maxX = Math.max(firstCorner.getX(), secondCorner.getX());
            int maxY = Math.max(firstCorner.getY(), secondCorner.getY());
            int maxZ = Math.max(firstCorner.getZ(), secondCorner.getZ());

            // Caja completa de selección en morado
            AABB bounds = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0).inflate(0.005);
            LevelRenderer.renderLineBox(poseStack, lineConsumer, bounds, 0.72f, 0.48f, 1.0f, 0.95f);

            // Esquina 2 resaltada en magenta suave
            AABB secondBox = new AABB(secondCorner).inflate(0.015);
            LevelRenderer.renderLineBox(poseStack, lineConsumer, secondBox, 0.94f, 0.67f, 0.99f, 0.98f);
        }

        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }
}