package fi.dy.masa.litematica.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;

/**
 * Utility class for splitting large schematics into smaller chunks.
 * Splits schematics into configurable chunk sizes (default 16x16x16) and saves them
 * to a subfolder with proper offsets so they can be placed at the same origin to recreate
 * the original structure.
 */
public class SchematicSplitter
{
    /**
     * Splits a schematic into smaller chunks and saves them to a subfolder.
     * Delegates to either fixed chunk size or KD-tree inventory-aware algorithm based on config.
     *
     * @param originalSchematic The schematic to split
     * @param originalFile The original file path (used to determine output directory)
     * @param fileName The base filename without extension
     * @return true if splitting was successful, false otherwise
     */
    public static boolean splitAndSaveSchematic(LitematicaSchematic originalSchematic, Path originalFile, String fileName)
    {
        if (!Configs.Generic.AUTO_SPLIT_SCHEMATICS.getBooleanValue())
        {
            return true; // Not an error, just disabled
        }

        SplitMode mode = (SplitMode) Configs.Generic.SPLIT_MODE.getOptionListValue();

        if (mode == SplitMode.KD_INVENTORY_AWARE)
        {
            return KDTreeSplitter.splitAndSaveSchematic(originalSchematic, originalFile, fileName);
        }
        else
        {
            return splitFixedChunkSize(originalSchematic, originalFile, fileName);
        }
    }

    /**
     * Splits a schematic using the fixed chunk size algorithm.
     *
     * @param originalSchematic The schematic to split
     * @param originalFile The original file path (used to determine output directory)
     * @param fileName The base filename without extension
     * @return true if splitting was successful, false otherwise
     */
    private static boolean splitFixedChunkSize(LitematicaSchematic originalSchematic, Path originalFile, String fileName)
    {
        int chunkSize = Configs.Generic.SPLIT_CHUNK_SIZE.getIntegerValue();

        try
        {
            // Create output directory for chunks
            Path parentDir = originalFile.getParent();
            String baseFileName = fileName.endsWith(LitematicaSchematic.FILE_EXTENSION)
                ? fileName.substring(0, fileName.length() - LitematicaSchematic.FILE_EXTENSION.length())
                : fileName;
            Path chunksDir = parentDir.resolve(baseFileName + "_chunks");
            Files.createDirectories(chunksDir);

            Litematica.LOGGER.info("Starting schematic split for '{}' into {}x{}x{} chunks", fileName, chunkSize, chunkSize, chunkSize);

            // Split each region in the schematic
            int totalChunks = 0;
            Map<String, Box> regions = originalSchematic.getAreas();

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

                // Calculate how many chunks we need in each dimension
                // Use absolute values to handle negative sizes, and ensure at least 1 chunk per dimension
                int sizeX = Math.abs(regionSize.getX());
                int sizeY = Math.abs(regionSize.getY());
                int sizeZ = Math.abs(regionSize.getZ());

                int chunksX = Math.max(1, (int) Math.ceil((double) sizeX / chunkSize));
                int chunksY = Math.max(1, (int) Math.ceil((double) sizeY / chunkSize));
                int chunksZ = Math.max(1, (int) Math.ceil((double) sizeZ / chunkSize));

                Litematica.LOGGER.info("Region '{}' size {}x{}x{} will create {}x{}x{} chunks ({} total)",
                    regionName, sizeX, sizeY, sizeZ,
                    chunksX, chunksY, chunksZ, chunksX * chunksY * chunksZ);

                // Create each chunk
                for (int chunkX = 0; chunkX < chunksX; chunkX++)
                {
                    for (int chunkY = 0; chunkY < chunksY; chunkY++)
                    {
                        for (int chunkZ = 0; chunkZ < chunksZ; chunkZ++)
                        {
                            LitematicaSchematic chunkSchematic = createChunkSchematic(
                                originalSchematic, regionName, regionPos, regionSize,
                                chunkX, chunkY, chunkZ, chunkSize);

                            if (chunkSchematic != null)
                            {
                                // Generate chunk filename with coordinates
                                String chunkFileName = String.format("%s_%s_x%d_y%d_z%d",
                                    baseFileName, regionName, chunkX, chunkY, chunkZ);

                                if (chunkSchematic.writeToFile(chunksDir, chunkFileName, true))
                                {
                                    totalChunks++;

                                    // Generate material list for this chunk if enabled
                                    if (Configs.Generic.SPLIT_GENERATE_MATERIAL_LISTS.getBooleanValue())
                                    {
                                        generateMaterialList(chunkSchematic, chunksDir, chunkFileName,
                                            chunkX, chunkY, chunkZ);
                                    }
                                }
                                else
                                {
                                    Litematica.LOGGER.error("Failed to write chunk: {}", chunkFileName);
                                }
                            }
                        }
                    }
                }
            }

            if (totalChunks > 0)
            {
                InfoUtils.showGuiOrInGameMessage(MessageType.SUCCESS,
                    "litematica.message.schematic_split_complete", totalChunks, chunksDir.getFileName());
                Litematica.LOGGER.info("Successfully split schematic into {} chunks in '{}'", totalChunks, chunksDir);
                return true;
            }
            else
            {
                Litematica.LOGGER.warn("No chunks were created during split operation");
                return false;
            }
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Error splitting schematic '{}'", fileName, e);
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.error.schematic_split_failed", fileName);
            return false;
        }
    }

    /**
     * Creates a single chunk schematic from a region of the original schematic.
     */
    @Nullable
    private static LitematicaSchematic createChunkSchematic(
        LitematicaSchematic original, String regionName, BlockPos regionPos, BlockPos regionSize,
        int chunkX, int chunkY, int chunkZ, int chunkSize)
    {
        try
        {
            // Calculate chunk bounds within the region
            int startX = chunkX * chunkSize;
            int startY = chunkY * chunkSize;
            int startZ = chunkZ * chunkSize;

            int endX = Math.min(startX + chunkSize, Math.abs(regionSize.getX()));
            int endY = Math.min(startY + chunkSize, Math.abs(regionSize.getY()));
            int endZ = Math.min(startZ + chunkSize, Math.abs(regionSize.getZ()));

            int sizeX = endX - startX;
            int sizeY = endY - startY;
            int sizeZ = endZ - startZ;

            // Skip empty chunks
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0)
            {
                return null;
            }

            // Calculate the min corner of the region relative to schematic origin
            // regionPos is pos1 - origin, but container indices start from MIN corner
            // We need to find the actual min corner when regionSize has negative components
            BlockPos posEndRel = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).add(regionPos);
            BlockPos posMinRel = PositionUtils.getMinCorner(regionPos, posEndRel);

            // Calculate the offset for this chunk relative to schematic origin
            // Each chunk is offset by (chunkIndex * chunkSize) from the region's min corner
            BlockPos chunkOffset = new BlockPos(
                posMinRel.getX() + chunkX * chunkSize,
                posMinRel.getY() + chunkY * chunkSize,
                posMinRel.getZ() + chunkZ * chunkSize
            );

            // Create an AreaSelection for the chunk
            AreaSelection chunkArea = new AreaSelection();
            chunkArea.setName(original.getMetadata().getName() + "_chunk");

            // Create a box with pos1 at chunk offset, pos2 at chunk offset + size - 1
            // This creates a standard positive-size box
            BlockPos pos1 = chunkOffset;
            BlockPos pos2 = chunkOffset.add(sizeX - 1, sizeY - 1, sizeZ - 1);
            Box chunkBox = new Box(pos1, pos2, regionName);
            chunkArea.addSubRegionBox(chunkBox, true);

            // Set the origin to match the original's origin
            // This is critical - all chunks share the same origin point
            chunkArea.setExplicitOrigin(BlockPos.ORIGIN);

            // Create the schematic from the area selection
            LitematicaSchematic chunkSchematic = LitematicaSchematic.createEmptySchematic(
                chunkArea, original.getMetadata().getAuthor());

            if (chunkSchematic == null)
            {
                return null;
            }

            // Copy metadata
            chunkSchematic.getMetadata().setDescription(String.format("Chunk [%d,%d,%d] of %s",
                chunkX, chunkY, chunkZ, original.getMetadata().getName()));
            chunkSchematic.getMetadata().setTimeCreated(System.currentTimeMillis());
            chunkSchematic.getMetadata().setTimeModified(System.currentTimeMillis());

            // Get the original region's block container
            LitematicaBlockStateContainer originalContainer = original.getSubRegionContainer(regionName);
            if (originalContainer == null)
            {
                Litematica.LOGGER.warn("Could not get block container for region '{}'", regionName);
                return null;
            }

            // Get the chunk's block container (should be created by createEmptySchematic)
            LitematicaBlockStateContainer chunkContainer = chunkSchematic.getSubRegionContainer(regionName);
            if (chunkContainer == null)
            {
                Litematica.LOGGER.warn("Could not get chunk container for region '{}'", regionName);
                return null;
            }

            // Copy blocks from original to chunk
            int blockCount = 0;
            for (int y = 0; y < sizeY; y++)
            {
                for (int z = 0; z < sizeZ; z++)
                {
                    for (int x = 0; x < sizeX; x++)
                    {
                        BlockState state = originalContainer.get(startX + x, startY + y, startZ + z);
                        if (state != null)
                        {
                            chunkContainer.set(x, y, z, state);
                            blockCount++;
                        }
                    }
                }
            }

            // Copy tile entities (block entities like chests, signs, etc.)
            Map<BlockPos, NbtCompound> originalTileEntities = original.getBlockEntityMapForRegion(regionName);
            Map<BlockPos, NbtCompound> chunkTileEntities = chunkSchematic.getBlockEntityMapForRegion(regionName);

            if (originalTileEntities != null && !originalTileEntities.isEmpty() && chunkTileEntities != null)
            {
                for (Map.Entry<BlockPos, NbtCompound> entry : originalTileEntities.entrySet())
                {
                    BlockPos pos = entry.getKey();

                    // Check if this tile entity is within our chunk bounds
                    if (pos.getX() >= startX && pos.getX() < endX &&
                        pos.getY() >= startY && pos.getY() < endY &&
                        pos.getZ() >= startZ && pos.getZ() < endZ)
                    {
                        // Translate position to chunk-local coordinates
                        BlockPos localPos = new BlockPos(
                            pos.getX() - startX,
                            pos.getY() - startY,
                            pos.getZ() - startZ);

                        // Copy the NBT data
                        NbtCompound tileEntityNbt = entry.getValue().copy();

                        // Update position in NBT to match new local coordinates
                        tileEntityNbt.putInt("x", localPos.getX());
                        tileEntityNbt.putInt("y", localPos.getY());
                        tileEntityNbt.putInt("z", localPos.getZ());

                        chunkTileEntities.put(localPos, tileEntityNbt);
                    }
                }
            }

            // Copy entities within the chunk bounds
            List<LitematicaSchematic.EntityInfo> originalEntities = original.getEntityListForRegion(regionName);
            List<LitematicaSchematic.EntityInfo> chunkEntities = chunkSchematic.getEntityListForRegion(regionName);

            if (originalEntities != null && !originalEntities.isEmpty() && chunkEntities != null)
            {
                for (LitematicaSchematic.EntityInfo entityInfo : originalEntities)
                {
                    double ex = entityInfo.posVec.x;
                    double ey = entityInfo.posVec.y;
                    double ez = entityInfo.posVec.z;

                    // Check if entity is within chunk bounds
                    if (ex >= startX && ex < endX &&
                        ey >= startY && ey < endY &&
                        ez >= startZ && ez < endZ)
                    {
                        // Translate entity position to chunk-local coordinates
                        net.minecraft.util.math.Vec3d localPos = new net.minecraft.util.math.Vec3d(
                            ex - startX,
                            ey - startY,
                            ez - startZ);

                        // Copy entity NBT
                        NbtCompound entityNbt = entityInfo.nbt.copy();

                        // Update position in NBT
                        net.minecraft.nbt.NbtList posList = new net.minecraft.nbt.NbtList();
                        posList.add(net.minecraft.nbt.NbtDouble.of(localPos.x));
                        posList.add(net.minecraft.nbt.NbtDouble.of(localPos.y));
                        posList.add(net.minecraft.nbt.NbtDouble.of(localPos.z));
                        entityNbt.put("Pos", posList);

                        chunkEntities.add(new LitematicaSchematic.EntityInfo(localPos, entityNbt));
                    }
                }
            }

            chunkSchematic.getMetadata().setTotalBlocks(blockCount);

            return chunkSchematic;
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Error creating chunk schematic at [{},{},{}]", chunkX, chunkY, chunkZ, e);
            return null;
        }
    }

    /**
     * Generates a material list text file for a chunk schematic.
     */
    private static void generateMaterialList(LitematicaSchematic chunkSchematic, Path chunksDir,
                                            String chunkFileName, int chunkX, int chunkY, int chunkZ)
    {
        try
        {
            // Create material list for the chunk
            List<MaterialListEntry> materialList = MaterialListUtils.createMaterialListFor(chunkSchematic);

            if (materialList.isEmpty())
            {
                return; // No materials needed
            }

            // Sort by item name for easier reading
            Collections.sort(materialList, new Comparator<MaterialListEntry>()
            {
                @Override
                public int compare(MaterialListEntry e1, MaterialListEntry e2)
                {
                    String name1 = e1.getStack().getName().getString();
                    String name2 = e2.getStack().getName().getString();
                    return name1.compareTo(name2);
                }
            });

            // Write to text file
            Path materialListFile = chunksDir.resolve(chunkFileName + "_materials.txt");

            try (BufferedWriter writer = Files.newBufferedWriter(materialListFile))
            {
                writer.write("=".repeat(60));
                writer.newLine();
                writer.write(String.format("Material List for Chunk [%d, %d, %d]", chunkX, chunkY, chunkZ));
                writer.newLine();
                writer.write(String.format("Schematic: %s", chunkSchematic.getMetadata().getName()));
                writer.newLine();
                writer.write("=".repeat(60));
                writer.newLine();
                writer.newLine();

                // Calculate totals
                int totalItems = 0;
                int totalStacks = 0;

                for (MaterialListEntry entry : materialList)
                {
                    int count = entry.getCountTotal();
                    totalItems += count;
                    totalStacks += (int) Math.ceil(count / 64.0);
                }

                writer.write(String.format("Total Items: %,d", totalItems));
                writer.newLine();
                writer.write(String.format("Total Stacks: %,d (%.1f full double chests)",
                    totalStacks, totalStacks / 54.0));
                writer.newLine();
                writer.newLine();
                writer.write("-".repeat(60));
                writer.newLine();
                writer.newLine();

                // Write each material entry
                for (MaterialListEntry entry : materialList)
                {
                    String itemName = entry.getStack().getName().getString();
                    int count = entry.getCountTotal();
                    int stacks = count / 64;
                    int remainder = count % 64;

                    // Format: "Item Name: X items (Y stacks + Z)"
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
                writer.write("Generated by Litematica Schematic Splitter");
                writer.newLine();
                writer.write("=".repeat(60));
            }

            Litematica.LOGGER.debug("Generated material list for chunk [{},{},{}]: {}",
                chunkX, chunkY, chunkZ, materialListFile.getFileName());
        }
        catch (IOException e)
        {
            Litematica.LOGGER.error("Failed to generate material list for chunk [{},{},{}]",
                chunkX, chunkY, chunkZ, e);
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Unexpected error generating material list for chunk [{},{},{}]",
                chunkX, chunkY, chunkZ, e);
        }
    }
}
