package fi.dy.masa.litematica.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;

/**
 * KD-Tree based schematic splitter that creates chunks based on inventory constraints.
 * Each chunk is sized so that it fits within a configurable number of inventory stacks
 * (default 27 = one player inventory) and maximum blocks (default 1728 = 27*64).
 */
public class KDTreeSplitter
{
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    /**
     * Region bounds in container coordinates (max exclusive).
     */
    public record BoxBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        public int sizeX() { return maxX - minX; }
        public int sizeY() { return maxY - minY; }
        public int sizeZ() { return maxZ - minZ; }
        public int volume() { return sizeX() * sizeY() * sizeZ(); }

        public int getMin(int axis)
        {
            return switch (axis) { case 0 -> minX; case 1 -> minY; default -> minZ; };
        }

        public int getMax(int axis)
        {
            return switch (axis) { case 0 -> maxX; case 1 -> maxY; default -> maxZ; };
        }

        public int getSize(int axis)
        {
            return switch (axis) { case 0 -> sizeX(); case 1 -> sizeY(); default -> sizeZ(); };
        }

        public BoxBounds splitLeft(int axis, int pos)
        {
            return switch (axis)
            {
                case 0 -> new BoxBounds(minX, minY, minZ, pos, maxY, maxZ);
                case 1 -> new BoxBounds(minX, minY, minZ, maxX, pos, maxZ);
                default -> new BoxBounds(minX, minY, minZ, maxX, maxY, pos);
            };
        }

        public BoxBounds splitRight(int axis, int pos)
        {
            return switch (axis)
            {
                case 0 -> new BoxBounds(pos, minY, minZ, maxX, maxY, maxZ);
                case 1 -> new BoxBounds(minX, pos, minZ, maxX, maxY, maxZ);
                default -> new BoxBounds(minX, minY, pos, maxX, maxY, maxZ);
            };
        }
    }

    /**
     * Metrics for a region: block counts, stack requirements, type tracking.
     */
    public static class SplitMetrics
    {
        public int blocksNonAir = 0;
        public int stacksNeeded = 0;
        public long typesBitset = 0L;
        public final Object2IntOpenHashMap<BlockState> blockCounts = new Object2IntOpenHashMap<>();

        public SplitMetrics() {}

        public boolean fitsConstraints(int maxStacks, int maxBlocks)
        {
            return stacksNeeded <= maxStacks && blocksNonAir <= maxBlocks;
        }

        public void addBlock(BlockState state, int stackSize)
        {
            blocksNonAir++;

            int oldCount = blockCounts.getOrDefault(state, 0);
            int newCount = oldCount + 1;
            blockCounts.put(state, newCount);

            // Update stacks needed (delta calculation)
            int oldStacks = (oldCount + stackSize - 1) / stackSize;
            int newStacks = (newCount + stackSize - 1) / stackSize;
            stacksNeeded += (newStacks - oldStacks);

            // Update type bitset (hash to 64 bits)
            int bitIndex = Math.abs(state.hashCode()) % 64;
            typesBitset |= (1L << bitIndex);
        }

        public static SplitMetrics combine(SplitMetrics a, SplitMetrics b, MaterialCache cache)
        {
            SplitMetrics result = new SplitMetrics();
            result.blocksNonAir = a.blocksNonAir + b.blocksNonAir;
            result.typesBitset = a.typesBitset | b.typesBitset;

            // Combine block counts and recalculate stacks
            result.blockCounts.putAll(a.blockCounts);
            for (Object2IntOpenHashMap.Entry<BlockState> entry : b.blockCounts.object2IntEntrySet())
            {
                BlockState state = entry.getKey();
                int count = entry.getIntValue();
                result.blockCounts.addTo(state, count);
            }

            // Recalculate total stacks
            result.stacksNeeded = 0;
            for (Object2IntOpenHashMap.Entry<BlockState> entry : result.blockCounts.object2IntEntrySet())
            {
                BlockState state = entry.getKey();
                int count = entry.getIntValue();
                int stackSize = getStackSize(state, cache);
                result.stacksNeeded += (count + stackSize - 1) / stackSize;
            }

            return result;
        }
    }

    /**
     * Result of splitting: bounds and metrics for a leaf node.
     */
    public record LeafResult(BoxBounds bounds, SplitMetrics metrics, int leafIndex) {}

    /**
     * Main entry point: splits a schematic using kd-tree algorithm.
     */
    public static boolean splitAndSaveSchematic(LitematicaSchematic originalSchematic, Path originalFile, String fileName)
    {
        int maxStacks = Configs.Generic.SPLIT_MAX_STACKS.getIntegerValue();
        int maxBlocks = Configs.Generic.SPLIT_MAX_BLOCKS.getIntegerValue();
        int candidatesPerAxis = Configs.Generic.SPLIT_CANDIDATES_PER_AXIS.getIntegerValue();

        try
        {
            // Create output directory
            Path parentDir = originalFile.getParent();
            String baseFileName = fileName.endsWith(LitematicaSchematic.FILE_EXTENSION)
                ? fileName.substring(0, fileName.length() - LitematicaSchematic.FILE_EXTENSION.length())
                : fileName;
            Path chunksDir = parentDir.resolve(baseFileName + "_chunks");
            Files.createDirectories(chunksDir);

            Litematica.LOGGER.info("Starting KD-Tree split for '{}' (maxStacks={}, maxBlocks={})",
                fileName, maxStacks, maxBlocks);

            int totalChunks = 0;
            Map<String, Box> regions = originalSchematic.getAreas();
            MaterialCache cache = MaterialCache.getInstance();

            for (Map.Entry<String, Box> entry : regions.entrySet())
            {
                String regionName = entry.getKey();
                Box box = entry.getValue();
                BlockPos regionSize = box.getSize();
                BlockPos regionPos = originalSchematic.getSubRegionPosition(regionName);

                if (regionSize == null || regionPos == null)
                {
                    Litematica.LOGGER.warn("Skipping region '{}' - missing size or position data", regionName);
                    continue;
                }

                // Get container
                LitematicaBlockStateContainer container = originalSchematic.getSubRegionContainer(regionName);
                if (container == null)
                {
                    Litematica.LOGGER.warn("Could not get block container for region '{}'", regionName);
                    continue;
                }

                // Calculate bounds (0-indexed, exclusive max)
                int sizeX = Math.abs(regionSize.getX());
                int sizeY = Math.abs(regionSize.getY());
                int sizeZ = Math.abs(regionSize.getZ());
                BoxBounds initialBounds = new BoxBounds(0, 0, 0, sizeX, sizeY, sizeZ);

                // Compute initial metrics
                SplitMetrics initialMetrics = computeMetrics(container, initialBounds, cache);

                Litematica.LOGGER.info("Region '{}' size {}x{}x{}, {} non-air blocks, {} stacks needed",
                    regionName, sizeX, sizeY, sizeZ, initialMetrics.blocksNonAir, initialMetrics.stacksNeeded);

                // Perform kd-tree split
                List<LeafResult> leaves = new ArrayList<>();
                splitRecursive(container, initialBounds, initialMetrics, maxStacks, maxBlocks,
                    candidatesPerAxis, cache, leaves, 0);

                Litematica.LOGGER.info("Region '{}' split into {} leaves", regionName, leaves.size());

                // Calculate min corner for offset calculation
                BlockPos posEndRel = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).add(regionPos);
                BlockPos posMinRel = PositionUtils.getMinCorner(regionPos, posEndRel);

                // Create and save each leaf schematic
                for (LeafResult leaf : leaves)
                {
                    if (leaf.metrics.blocksNonAir == 0)
                    {
                        continue; // Skip empty leaves
                    }

                    LitematicaSchematic leafSchematic = createLeafSchematic(
                        originalSchematic, regionName, regionPos, regionSize, posMinRel,
                        leaf, container);

                    if (leafSchematic != null)
                    {
                        String leafFileName = String.format("%s_%s_kd%d",
                            baseFileName, regionName, leaf.leafIndex);

                        if (leafSchematic.writeToFile(chunksDir, leafFileName, true))
                        {
                            totalChunks++;

                            if (Configs.Generic.SPLIT_GENERATE_MATERIAL_LISTS.getBooleanValue())
                            {
                                generateMaterialList(leafSchematic, chunksDir, leafFileName, leaf);
                            }
                        }
                        else
                        {
                            Litematica.LOGGER.error("Failed to write leaf: {}", leafFileName);
                        }
                    }
                }
            }

            if (totalChunks > 0)
            {
                InfoUtils.showGuiOrInGameMessage(MessageType.SUCCESS,
                    "litematica.message.schematic_kd_split_complete", totalChunks, chunksDir.getFileName());
                Litematica.LOGGER.info("Successfully split schematic into {} inventory-sized chunks in '{}'",
                    totalChunks, chunksDir);
                return true;
            }
            else
            {
                Litematica.LOGGER.warn("No chunks were created during KD-Tree split");
                return false;
            }
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Error during KD-Tree split of '{}'", fileName, e);
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR,
                "litematica.error.schematic_kd_split_failed", fileName);
            return false;
        }
    }

    /**
     * Recursively splits a region using kd-tree algorithm.
     */
    private static void splitRecursive(
        LitematicaBlockStateContainer container,
        BoxBounds bounds,
        SplitMetrics metrics,
        int maxStacks,
        int maxBlocks,
        int candidatesPerAxis,
        MaterialCache cache,
        List<LeafResult> leaves,
        int depth)
    {
        // Base case: fits constraints
        if (metrics.fitsConstraints(maxStacks, maxBlocks))
        {
            if (metrics.blocksNonAir > 0)
            {
                leaves.add(new LeafResult(bounds, metrics, leaves.size()));
            }
            return;
        }

        // Base case: cannot split further (1x1x1 or smaller in any dimension)
        if (bounds.sizeX() <= 1 && bounds.sizeY() <= 1 && bounds.sizeZ() <= 1)
        {
            if (metrics.blocksNonAir > 0)
            {
                Litematica.LOGGER.warn("Leaf at ({},{},{}) exceeds constraints: {} stacks, {} blocks",
                    bounds.minX, bounds.minY, bounds.minZ, metrics.stacksNeeded, metrics.blocksNonAir);
                leaves.add(new LeafResult(bounds, metrics, leaves.size()));
            }
            return;
        }

        // Prevent infinite recursion
        if (depth > 100)
        {
            Litematica.LOGGER.error("Max recursion depth exceeded at ({},{},{})",
                bounds.minX, bounds.minY, bounds.minZ);
            if (metrics.blocksNonAir > 0)
            {
                leaves.add(new LeafResult(bounds, metrics, leaves.size()));
            }
            return;
        }

        // Choose split axis (longest dimension)
        int axis = chooseSplitAxis(bounds);
        int axisSize = bounds.getSize(axis);

        // Cannot split if axis size is 1
        if (axisSize <= 1)
        {
            // Try another axis
            for (int tryAxis = 0; tryAxis < 3; tryAxis++)
            {
                if (tryAxis != axis && bounds.getSize(tryAxis) > 1)
                {
                    axis = tryAxis;
                    axisSize = bounds.getSize(tryAxis);
                    break;
                }
            }

            if (axisSize <= 1)
            {
                // Cannot split at all
                if (metrics.blocksNonAir > 0)
                {
                    leaves.add(new LeafResult(bounds, metrics, leaves.size()));
                }
                return;
            }
        }

        // Generate candidate split positions
        List<Integer> candidates = generateSplitCandidates(axis, bounds, candidatesPerAxis);

        if (candidates.isEmpty())
        {
            if (metrics.blocksNonAir > 0)
            {
                leaves.add(new LeafResult(bounds, metrics, leaves.size()));
            }
            return;
        }

        // Evaluate each candidate
        BoxBounds bestLeftBounds = null;
        BoxBounds bestRightBounds = null;
        SplitMetrics bestLeftMetrics = null;
        SplitMetrics bestRightMetrics = null;
        double bestCost = Double.MAX_VALUE;

        for (int pos : candidates)
        {
            BoxBounds leftBounds = bounds.splitLeft(axis, pos);
            BoxBounds rightBounds = bounds.splitRight(axis, pos);

            // Skip degenerate splits
            if (leftBounds.volume() == 0 || rightBounds.volume() == 0)
            {
                continue;
            }

            SplitMetrics leftMetrics = computeMetrics(container, leftBounds, cache);
            SplitMetrics rightMetrics = computeMetrics(container, rightBounds, cache);

            double cost = evaluateSplitCost(leftMetrics, rightMetrics, maxStacks, maxBlocks);

            if (cost < bestCost)
            {
                bestCost = cost;
                bestLeftBounds = leftBounds;
                bestRightBounds = rightBounds;
                bestLeftMetrics = leftMetrics;
                bestRightMetrics = rightMetrics;
            }
        }

        // Recurse on best split
        if (bestLeftBounds != null && bestRightBounds != null)
        {
            splitRecursive(container, bestLeftBounds, bestLeftMetrics, maxStacks, maxBlocks,
                candidatesPerAxis, cache, leaves, depth + 1);
            splitRecursive(container, bestRightBounds, bestRightMetrics, maxStacks, maxBlocks,
                candidatesPerAxis, cache, leaves, depth + 1);
        }
        else
        {
            // Fallback: accept as leaf
            if (metrics.blocksNonAir > 0)
            {
                leaves.add(new LeafResult(bounds, metrics, leaves.size()));
            }
        }
    }

    /**
     * Chooses split axis based on longest dimension.
     */
    private static int chooseSplitAxis(BoxBounds bounds)
    {
        int sizeX = bounds.sizeX();
        int sizeY = bounds.sizeY();
        int sizeZ = bounds.sizeZ();

        if (sizeX >= sizeY && sizeX >= sizeZ) return 0;
        if (sizeY >= sizeZ) return 1;
        return 2;
    }

    /**
     * Generates candidate split positions along an axis.
     */
    private static List<Integer> generateSplitCandidates(int axis, BoxBounds bounds, int K)
    {
        int min = bounds.getMin(axis);
        int max = bounds.getMax(axis);
        int size = max - min;

        if (size <= 1)
        {
            return Collections.emptyList();
        }

        TreeSet<Integer> candidates = new TreeSet<>();

        // Always include mid, quartiles
        int mid = min + size / 2;
        int q1 = min + size / 4;
        int q3 = min + 3 * size / 4;

        if (mid > min && mid < max) candidates.add(mid);
        if (q1 > min && q1 < max && q1 != mid) candidates.add(q1);
        if (q3 > min && q3 < max && q3 != mid) candidates.add(q3);

        // Fill with evenly spaced positions
        int step = Math.max(1, size / (K + 1));
        for (int i = 1; i <= K && candidates.size() < K; i++)
        {
            int pos = min + i * step;
            if (pos > min && pos < max)
            {
                candidates.add(pos);
            }
        }

        return new ArrayList<>(candidates);
    }

    /**
     * Evaluates the cost of a split (lower is better).
     */
    private static double evaluateSplitCost(SplitMetrics left, SplitMetrics right, int maxStacks, int maxBlocks)
    {
        double cost = 0.0;

        // Overflow penalties (critical)
        int leftStackOverflow = Math.max(0, left.stacksNeeded - maxStacks);
        int rightStackOverflow = Math.max(0, right.stacksNeeded - maxStacks);
        int leftBlockOverflow = Math.max(0, left.blocksNonAir - maxBlocks);
        int rightBlockOverflow = Math.max(0, right.blocksNonAir - maxBlocks);

        cost += 10.0 * (leftStackOverflow + rightStackOverflow);
        cost += 2.0 * (leftBlockOverflow + rightBlockOverflow);

        // Imbalance penalty (prefer balanced splits)
        int total = left.blocksNonAir + right.blocksNonAir;
        if (total > 0)
        {
            double imbalance = Math.abs(left.blocksNonAir - right.blocksNonAir) / (double) total;
            cost += 1.0 * imbalance;
        }

        return cost;
    }

    /**
     * Computes metrics for a region by scanning all blocks.
     */
    private static SplitMetrics computeMetrics(LitematicaBlockStateContainer container, BoxBounds bounds, MaterialCache cache)
    {
        SplitMetrics metrics = new SplitMetrics();

        for (int y = bounds.minY; y < bounds.maxY; y++)
        {
            for (int z = bounds.minZ; z < bounds.maxZ; z++)
            {
                for (int x = bounds.minX; x < bounds.maxX; x++)
                {
                    BlockState state = container.get(x, y, z);
                    if (state != null && !state.isAir())
                    {
                        int stackSize = getStackSize(state, cache);
                        metrics.addBlock(state, stackSize);
                    }
                }
            }
        }

        return metrics;
    }

    /**
     * Gets the stack size for a block state.
     */
    private static int getStackSize(BlockState state, MaterialCache cache)
    {
        try
        {
            ItemStack buildItem = cache.getRequiredBuildItemForState(state);
            if (buildItem != null && !buildItem.isEmpty())
            {
                return buildItem.getMaxCount();
            }
        }
        catch (Exception e)
        {
            // Fall back to default
        }
        return 64;
    }

    /**
     * Creates a leaf schematic from bounds.
     */
    @Nullable
    private static LitematicaSchematic createLeafSchematic(
        LitematicaSchematic original,
        String regionName,
        BlockPos regionPos,
        BlockPos regionSize,
        BlockPos posMinRel,
        LeafResult leaf,
        LitematicaBlockStateContainer originalContainer)
    {
        try
        {
            BoxBounds bounds = leaf.bounds;
            int sizeX = bounds.sizeX();
            int sizeY = bounds.sizeY();
            int sizeZ = bounds.sizeZ();

            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0)
            {
                return null;
            }

            // Calculate the offset for this leaf relative to schematic origin
            // bounds.minX/Y/Z are container coordinates (0-indexed starting positions)
            // posMinRel is the min corner of the region relative to schematic origin
            // Same approach as original SchematicSplitter: pos1 = posMinRel + startPosition
            BlockPos pos1 = posMinRel.add(bounds.minX, bounds.minY, bounds.minZ);
            BlockPos pos2 = pos1.add(sizeX - 1, sizeY - 1, sizeZ - 1);

            // Create area selection
            AreaSelection leafArea = new AreaSelection();
            leafArea.setName(original.getMetadata().getName() + "_kd" + leaf.leafIndex);

            Box leafBox = new Box(pos1, pos2, regionName);
            leafArea.addSubRegionBox(leafBox, true);

            // CRITICAL: Set origin to BlockPos.ORIGIN so all chunks align
            leafArea.setExplicitOrigin(BlockPos.ORIGIN);

            // Create empty schematic
            LitematicaSchematic leafSchematic = LitematicaSchematic.createEmptySchematic(
                leafArea, original.getMetadata().getAuthor());

            if (leafSchematic == null)
            {
                return null;
            }

            // Set metadata
            leafSchematic.getMetadata().setDescription(String.format(
                "KD-Tree chunk %d of %s (bounds: %d,%d,%d to %d,%d,%d)",
                leaf.leafIndex, original.getMetadata().getName(),
                bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, bounds.maxY, bounds.maxZ));
            leafSchematic.getMetadata().setTimeCreated(System.currentTimeMillis());
            leafSchematic.getMetadata().setTimeModified(System.currentTimeMillis());

            // Get chunk container
            LitematicaBlockStateContainer chunkContainer = leafSchematic.getSubRegionContainer(regionName);
            if (chunkContainer == null)
            {
                Litematica.LOGGER.warn("Could not get chunk container for region '{}'", regionName);
                return null;
            }

            // Copy blocks
            int blockCount = 0;
            for (int y = 0; y < sizeY; y++)
            {
                for (int z = 0; z < sizeZ; z++)
                {
                    for (int x = 0; x < sizeX; x++)
                    {
                        BlockState state = originalContainer.get(bounds.minX + x, bounds.minY + y, bounds.minZ + z);
                        if (state != null)
                        {
                            chunkContainer.set(x, y, z, state);
                            if (!state.isAir())
                            {
                                blockCount++;
                            }
                        }
                    }
                }
            }

            // Copy tile entities
            Map<BlockPos, NbtCompound> originalTileEntities = original.getBlockEntityMapForRegion(regionName);
            Map<BlockPos, NbtCompound> chunkTileEntities = leafSchematic.getBlockEntityMapForRegion(regionName);

            if (originalTileEntities != null && !originalTileEntities.isEmpty() && chunkTileEntities != null)
            {
                for (Map.Entry<BlockPos, NbtCompound> entry : originalTileEntities.entrySet())
                {
                    BlockPos pos = entry.getKey();

                    if (pos.getX() >= bounds.minX && pos.getX() < bounds.maxX &&
                        pos.getY() >= bounds.minY && pos.getY() < bounds.maxY &&
                        pos.getZ() >= bounds.minZ && pos.getZ() < bounds.maxZ)
                    {
                        BlockPos localPos = new BlockPos(
                            pos.getX() - bounds.minX,
                            pos.getY() - bounds.minY,
                            pos.getZ() - bounds.minZ);

                        NbtCompound tileEntityNbt = entry.getValue().copy();
                        tileEntityNbt.putInt("x", localPos.getX());
                        tileEntityNbt.putInt("y", localPos.getY());
                        tileEntityNbt.putInt("z", localPos.getZ());

                        chunkTileEntities.put(localPos, tileEntityNbt);
                    }
                }
            }

            // Copy entities
            List<LitematicaSchematic.EntityInfo> originalEntities = original.getEntityListForRegion(regionName);
            List<LitematicaSchematic.EntityInfo> chunkEntities = leafSchematic.getEntityListForRegion(regionName);

            if (originalEntities != null && !originalEntities.isEmpty() && chunkEntities != null)
            {
                for (LitematicaSchematic.EntityInfo entityInfo : originalEntities)
                {
                    double ex = entityInfo.posVec.x;
                    double ey = entityInfo.posVec.y;
                    double ez = entityInfo.posVec.z;

                    if (ex >= bounds.minX && ex < bounds.maxX &&
                        ey >= bounds.minY && ey < bounds.maxY &&
                        ez >= bounds.minZ && ez < bounds.maxZ)
                    {
                        Vec3d localPos = new Vec3d(
                            ex - bounds.minX,
                            ey - bounds.minY,
                            ez - bounds.minZ);

                        NbtCompound entityNbt = entityInfo.nbt.copy();

                        NbtList posList = new NbtList();
                        posList.add(NbtDouble.of(localPos.x));
                        posList.add(NbtDouble.of(localPos.y));
                        posList.add(NbtDouble.of(localPos.z));
                        entityNbt.put("Pos", posList);

                        chunkEntities.add(new LitematicaSchematic.EntityInfo(localPos, entityNbt));
                    }
                }
            }

            leafSchematic.getMetadata().setTotalBlocks(blockCount);

            return leafSchematic;
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Error creating leaf schematic for index {}", leaf.leafIndex, e);
            return null;
        }
    }

    /**
     * Generates a material list for a leaf schematic.
     */
    private static void generateMaterialList(LitematicaSchematic leafSchematic, Path chunksDir,
                                            String leafFileName, LeafResult leaf)
    {
        try
        {
            List<MaterialListEntry> materialList = MaterialListUtils.createMaterialListFor(leafSchematic);

            if (materialList.isEmpty())
            {
                return;
            }

            Collections.sort(materialList, Comparator.comparing(e -> e.getStack().getName().getString()));

            Path materialListFile = chunksDir.resolve(leafFileName + "_materials.txt");

            try (BufferedWriter writer = Files.newBufferedWriter(materialListFile))
            {
                writer.write("=".repeat(60));
                writer.newLine();
                writer.write(String.format("Material List for KD-Tree Chunk %d", leaf.leafIndex));
                writer.newLine();
                writer.write(String.format("Bounds: (%d,%d,%d) to (%d,%d,%d)",
                    leaf.bounds.minX, leaf.bounds.minY, leaf.bounds.minZ,
                    leaf.bounds.maxX, leaf.bounds.maxY, leaf.bounds.maxZ));
                writer.newLine();
                writer.write(String.format("Blocks: %d, Stacks needed: %d",
                    leaf.metrics.blocksNonAir, leaf.metrics.stacksNeeded));
                writer.newLine();
                writer.write("=".repeat(60));
                writer.newLine();
                writer.newLine();

                int totalItems = 0;
                int totalStacks = 0;

                for (MaterialListEntry entry : materialList)
                {
                    int count = entry.getCountTotal();
                    int stackSize = entry.getStack().getMaxCount();
                    totalItems += count;
                    totalStacks += (count + stackSize - 1) / stackSize;
                }

                writer.write(String.format("Total Items: %,d", totalItems));
                writer.newLine();
                writer.write(String.format("Total Stacks: %,d (%.1f double chests)",
                    totalStacks, totalStacks / 54.0));
                writer.newLine();
                writer.newLine();
                writer.write("-".repeat(60));
                writer.newLine();
                writer.newLine();

                for (MaterialListEntry entry : materialList)
                {
                    String itemName = entry.getStack().getName().getString();
                    int count = entry.getCountTotal();
                    int stackSize = entry.getStack().getMaxCount();
                    int stacks = count / stackSize;
                    int remainder = count % stackSize;

                    StringBuilder line = new StringBuilder();
                    line.append(String.format("%-40s : %,6d items", itemName, count));

                    if (stacks > 0)
                    {
                        line.append(String.format("  (%d stacks", stacks));
                        if (remainder > 0)
                        {
                            line.append(String.format(" + %d", remainder));
                        }
                        line.append(")");
                    }

                    writer.write(line.toString());
                    writer.newLine();
                }

                writer.newLine();
                writer.write("=".repeat(60));
                writer.newLine();
                writer.write("Generated by Litematica KD-Tree Splitter");
                writer.newLine();
                writer.write("=".repeat(60));
            }
        }
        catch (IOException e)
        {
            Litematica.LOGGER.error("Failed to generate material list for leaf {}", leaf.leafIndex, e);
        }
    }
}
