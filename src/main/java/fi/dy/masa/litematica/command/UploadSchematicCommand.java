package fi.dy.masa.litematica.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.compat.invnet.InventoryNetworkCompat;
import fi.dy.masa.litematica.compat.invnet.SchematicSyncHook;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;

/**
 * Command to upload schematics to the SolomonBE backend.
 *
 * Usage:
 *   /uploadschematic <name>           - Upload schematic by name from schematics folder
 *   /uploadschematic file <path>      - Upload schematic by absolute file path
 *   /uploadschematic folder [path]    - Upload all schematics in folder (default: schematics folder)
 *   /uploadschematic list             - List available schematics in schematics folder
 */
public class UploadSchematicCommand
{
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher)
    {
        dispatcher.register(
            ClientCommandManager.literal("uploadschematic")
                // /uploadschematic list
                .then(ClientCommandManager.literal("list")
                    .executes(context -> executeList(context.getSource()))
                )
                // /uploadschematic folder [path]
                .then(ClientCommandManager.literal("folder")
                    .executes(context -> executeUploadFolder(context.getSource(), null))
                    .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                        .executes(context -> executeUploadFolder(
                            context.getSource(),
                            StringArgumentType.getString(context, "path")
                        ))
                    )
                )
                // /uploadschematic file <path>
                .then(ClientCommandManager.literal("file")
                    .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                        .executes(context -> executeUploadFile(
                            context.getSource(),
                            StringArgumentType.getString(context, "path")
                        ))
                    )
                )
                // /uploadschematic <name>
                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                    .executes(context -> executeUploadByName(
                        context.getSource(),
                        StringArgumentType.getString(context, "name")
                    ))
                )
        );
    }

    /**
     * List available schematics in the schematics folder.
     */
    private static int executeList(FabricClientCommandSource source)
    {
        Path schematicsDir = DataManager.getSchematicsBaseDirectory();

        try (Stream<Path> paths = Files.walk(schematicsDir, 2))
        {
            List<Path> schematics = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(LitematicaSchematic.FILE_EXTENSION))
                .collect(Collectors.toList());

            if (schematics.isEmpty())
            {
                source.sendFeedback(Text.literal("No schematics found in: " + schematicsDir));
                return 0;
            }

            source.sendFeedback(Text.literal("Available schematics (" + schematics.size() + "):"));
            for (Path schematic : schematics)
            {
                String relativePath = schematicsDir.relativize(schematic).toString();
                // Remove .litematic extension for cleaner display
                String name = relativePath.replace(LitematicaSchematic.FILE_EXTENSION, "");
                source.sendFeedback(Text.literal("  - " + name));
            }
            return 1;
        }
        catch (IOException e)
        {
            source.sendError(Text.literal("Error listing schematics: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Upload a schematic by name from the schematics folder.
     */
    private static int executeUploadByName(FabricClientCommandSource source, String name)
    {
        if (!checkApiAvailable(source))
        {
            return 0;
        }

        Path schematicsDir = DataManager.getSchematicsBaseDirectory();

        // Add extension if not present
        String fileName = name.endsWith(LitematicaSchematic.FILE_EXTENSION)
            ? name
            : name + LitematicaSchematic.FILE_EXTENSION;

        Path schematicPath = schematicsDir.resolve(fileName);

        if (!Files.exists(schematicPath))
        {
            // Try to find it in subdirectories
            try (Stream<Path> paths = Files.walk(schematicsDir, 3))
            {
                schematicPath = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName) ||
                                 p.toString().endsWith(fileName))
                    .findFirst()
                    .orElse(null);
            }
            catch (IOException e)
            {
                source.sendError(Text.literal("Error searching for schematic: " + e.getMessage()));
                return 0;
            }
        }

        if (schematicPath == null || !Files.exists(schematicPath))
        {
            source.sendError(Text.literal("Schematic not found: " + name));
            source.sendFeedback(Text.literal("Use '/uploadschematic list' to see available schematics"));
            return 0;
        }

        return uploadSchematic(source, schematicPath, name);
    }

    /**
     * Upload a schematic by absolute file path.
     */
    private static int executeUploadFile(FabricClientCommandSource source, String pathStr)
    {
        if (!checkApiAvailable(source))
        {
            return 0;
        }

        Path schematicPath = Path.of(pathStr);

        if (!Files.exists(schematicPath))
        {
            source.sendError(Text.literal("File not found: " + pathStr));
            return 0;
        }

        if (!schematicPath.toString().endsWith(LitematicaSchematic.FILE_EXTENSION))
        {
            source.sendError(Text.literal("Not a .litematic file: " + pathStr));
            return 0;
        }

        String name = schematicPath.getFileName().toString()
            .replace(LitematicaSchematic.FILE_EXTENSION, "");

        return uploadSchematic(source, schematicPath, name);
    }

    /**
     * Upload all schematics in a folder.
     */
    private static int executeUploadFolder(FabricClientCommandSource source, String pathStr)
    {
        if (!checkApiAvailable(source))
        {
            return 0;
        }

        Path folderPath = pathStr != null
            ? Path.of(pathStr)
            : DataManager.getSchematicsBaseDirectory();

        if (!Files.exists(folderPath) || !Files.isDirectory(folderPath))
        {
            source.sendError(Text.literal("Folder not found: " + folderPath));
            return 0;
        }

        try (Stream<Path> paths = Files.walk(folderPath, 1))
        {
            List<Path> schematics = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(LitematicaSchematic.FILE_EXTENSION))
                .collect(Collectors.toList());

            if (schematics.isEmpty())
            {
                source.sendFeedback(Text.literal("No schematics found in: " + folderPath));
                return 0;
            }

            source.sendFeedback(Text.literal("Uploading " + schematics.size() + " schematics..."));

            int[] successCount = {0};
            int[] failCount = {0};
            int total = schematics.size();

            for (Path schematic : schematics)
            {
                String name = schematic.getFileName().toString()
                    .replace(LitematicaSchematic.FILE_EXTENSION, "");

                SchematicSyncHook.uploadSchematicAsync(schematic, name)
                    .thenAccept(schematicId -> {
                        if (schematicId != null)
                        {
                            successCount[0]++;
                            source.sendFeedback(Text.literal("  Uploaded: " + name + " (ID: " + schematicId + ")"));
                        }
                        else
                        {
                            failCount[0]++;
                            source.sendError(Text.literal("  Failed: " + name));
                        }

                        // Report final summary when all done
                        if (successCount[0] + failCount[0] == total)
                        {
                            source.sendFeedback(Text.literal(
                                "Upload complete: " + successCount[0] + " succeeded, " + failCount[0] + " failed"
                            ));
                        }
                    });
            }

            return 1;
        }
        catch (IOException e)
        {
            source.sendError(Text.literal("Error reading folder: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Upload a single schematic file.
     */
    private static int uploadSchematic(FabricClientCommandSource source, Path schematicPath, String name)
    {
        source.sendFeedback(Text.literal("Uploading schematic: " + name + "..."));

        SchematicSyncHook.uploadSchematicAsync(schematicPath, name)
            .thenAccept(schematicId -> {
                if (schematicId != null)
                {
                    source.sendFeedback(Text.literal(
                        "Successfully uploaded '" + name + "' (ID: " + schematicId + ")"
                    ));
                }
                else
                {
                    source.sendError(Text.literal("Failed to upload schematic: " + name));
                }
            })
            .exceptionally(e -> {
                source.sendError(Text.literal("Error uploading schematic: " + e.getMessage()));
                Litematica.LOGGER.error("Error uploading schematic", e);
                return null;
            });

        return 1;
    }

    /**
     * Check if the SolomonInvNetMod API is available and connected.
     */
    private static boolean checkApiAvailable(FabricClientCommandSource source)
    {
        if (!InventoryNetworkCompat.hasInventoryNetwork())
        {
            source.sendError(Text.literal("SolomonInvNetMod is not installed"));
            source.sendFeedback(Text.literal("Install SolomonInvNetMod to enable schematic sync"));
            return false;
        }

        if (!InventoryNetworkCompat.isConnected())
        {
            source.sendError(Text.literal("Not connected to backend server"));
            source.sendFeedback(Text.literal("Make sure you're authenticated with the backend"));
            return false;
        }

        return true;
    }
}
