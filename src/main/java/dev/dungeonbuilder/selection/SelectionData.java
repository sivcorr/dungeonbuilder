package dev.dungeonbuilder.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Datos de la selección realizada por un jugador dentro de una dimensión específica. */
public final class SelectionData {
    private final ResourceKey<Level> dimension;
    private final BlockPos firstCorner;
    private BlockPos secondCorner;

    public SelectionData(ResourceKey<Level> dimension, BlockPos firstCorner) {
        this.dimension = dimension;
        this.firstCorner = firstCorner.immutable();
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos firstCorner() {
        return firstCorner;
    }

    public BlockPos secondCorner() {
        return secondCorner;
    }

    public void setSecondCorner(BlockPos secondCorner) {
        this.secondCorner = secondCorner.immutable();
    }

    public boolean isComplete() {
        return secondCorner != null;
    }

    public BlockPos min() {
        if (!isComplete()) throw new IllegalStateException("La selección todavía no está completa");
        return new BlockPos(
                Math.min(firstCorner.getX(), secondCorner.getX()),
                Math.min(firstCorner.getY(), secondCorner.getY()),
                Math.min(firstCorner.getZ(), secondCorner.getZ())
        );
    }

    public BlockPos max() {
        if (!isComplete()) throw new IllegalStateException("La selección todavía no está completa");
        return new BlockPos(
                Math.max(firstCorner.getX(), secondCorner.getX()),
                Math.max(firstCorner.getY(), secondCorner.getY()),
                Math.max(firstCorner.getZ(), secondCorner.getZ())
        );
    }

    public int width() {
        return max().getX() - min().getX() + 1;
    }

    public int height() {
        return max().getY() - min().getY() + 1;
    }

    public int depth() {
        return max().getZ() - min().getZ() + 1;
    }

    public long volume() {
        return (long) width() * height() * depth();
    }
}