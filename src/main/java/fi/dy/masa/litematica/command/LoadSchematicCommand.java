package fi.dy.masa.litematica.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import java.nio.file.Path;
import java.nio.file.Paths;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;

public class LoadSchematicCommand
{
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher)
    {
        // Command syntax: /loadschematic <x> <y> <z> <path>
        // Path is last because it uses greedyString() to capture paths with spaces/special chars
        dispatcher.register(
            ClientCommandManager.literal("loadschematic")
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String filePath = StringArgumentType.getString(context, "path");
                                    int x = IntegerArgumentType.getInteger(context, "x");
                                    int y = IntegerArgumentType.getInteger(context, "y");
                                    int z = IntegerArgumentType.getInteger(context, "z");
                                    return executeLoad(context.getSource(), filePath, x, y, z);
                                })
                            )
                        )
                    )
                )
        );
    }

    private static int executeLoad(FabricClientCommandSource source, String filePath, int x, int y, int z)
    {
        // 1. Load schematic from absolute path
        Path path = Paths.get(filePath);
        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);

        if (schematic == null)
        {
            source.sendError(Text.literal("Failed to load schematic: " + filePath));
            return 0;
        }

        // 2. Create placement at (x, y, z)
        BlockPos pos = new BlockPos(x, y, z);
        SchematicPlacement placement = SchematicPlacement.createFor(
            schematic,
            pos,
            schematic.getMetadata().getName(),
            true,  // enabled
            true   // renderingEnabled
        );

        // 3. Add to manager
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        manager.addSchematicPlacement(placement, true);
        manager.setSelectedSchematicPlacement(placement);

        source.sendFeedback(Text.literal("Loaded schematic '" + schematic.getMetadata().getName() + "' at " + x + ", " + y + ", " + z));
        return 1;
    }
}
