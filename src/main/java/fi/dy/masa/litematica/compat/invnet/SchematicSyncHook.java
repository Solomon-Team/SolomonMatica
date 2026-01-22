package fi.dy.masa.litematica.compat.invnet;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.KDTreeSplitter.BoxBounds;
import fi.dy.masa.litematica.util.KDTreeSplitter.LeafResult;
import fi.dy.masa.litematica.util.KDTreeSplitter.SplitMetrics;

/**
 * Hook for synchronizing schematics with the SolomonBE backend via SolomonInvNetMod.
 * Handles:
 * - Sending split results to backend
 * - Downloading schematics from backend
 * - Responding to server-initiated load requests
 */
public class SchematicSyncHook
{
    private static final Gson GSON = new Gson();
    private static boolean initialized = false;
    private static Object callbackInstance = null;

    /**
     * Initializes the sync hook. Should be called during mod initialization.
     */
    public static void initialize()
    {
        if (initialized)
        {
            return;
        }

        // Check for SolomonInvNetMod
        InventoryNetworkCompat.checkForInventoryNetwork();

        if (!InventoryNetworkCompat.hasInventoryNetwork())
        {
            Litematica.LOGGER.info("SchematicSyncHook: SolomonInvNetMod not available, sync disabled");
            return;
        }

        // Register callback for load_schematic messages
        registerLoadSchematicCallback();

        initialized = true;
        Litematica.LOGGER.info("SchematicSyncHook initialized successfully");
    }

    /**
     * Registers a callback with SolomonInvNetMod to handle load_schematic requests.
     */
    private static void registerLoadSchematicCallback()
    {
        Object api = InventoryNetworkCompat.getApi();
        if (api == null)
        {
            Litematica.LOGGER.warn("Cannot register callback: API not available");
            return;
        }

        try
        {
            // Create a proxy for the LoadSchematicCallback interface
            Class<?> callbackInterface = Class.forName(
                "com.BookKeeper.InventoryNetwork.api.SchematicSyncApi$LoadSchematicCallback");

            callbackInstance = java.lang.reflect.Proxy.newProxyInstance(
                callbackInterface.getClassLoader(),
                new Class<?>[] { callbackInterface },
                (proxy, method, args) -> {
                    if (method.getName().equals("onLoadSchematic"))
                    {
                        String schematicId = (String) args[0];
                        int x = (Integer) args[1];
                        int y = (Integer) args[2];
                        int z = (Integer) args[3];
                        String requestId = (String) args[4];
                        handleLoadSchematic(schematicId, x, y, z, requestId);
                    }
                    return null;
                }
            );

            Method registerMethod = api.getClass().getMethod("registerLoadSchematicCallback", callbackInterface);
            registerMethod.invoke(api, callbackInstance);

            Litematica.LOGGER.info("Registered LoadSchematicCallback with SolomonInvNetMod");
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Failed to register LoadSchematicCallback", e);
        }
    }

    /**
     * Handles a load_schematic request from the server.
     *
     * @param schematicId Schematic ID to download and load
     * @param x X coordinate to place the schematic
     * @param y Y coordinate to place the schematic
     * @param z Z coordinate to place the schematic
     * @param requestId Request ID for acknowledgment
     */
    private static void handleLoadSchematic(String schematicId, int x, int y, int z, String requestId)
    {
        Litematica.LOGGER.info("Handling load_schematic: id={}, pos=({}, {}, {}), requestId={}",
            schematicId, x, y, z, requestId);

        CompletableFuture.runAsync(() -> {
            try
            {
                // Download schematic to temp file
                Path tempDir = DataManager.getSchematicsBaseDirectory().resolve(".temp");
                Files.createDirectories(tempDir);
                Path targetPath = tempDir.resolve("remote_" + schematicId + LitematicaSchematic.FILE_EXTENSION);

                boolean downloaded = downloadSchematic(schematicId, targetPath);

                if (!downloaded)
                {
                    sendLoadSchematicAck(requestId, false, "Failed to download schematic");
                    return;
                }

                // Load and place on main thread
                MinecraftClient.getInstance().execute(() -> {
                    try
                    {
                        LitematicaSchematic schematic = LitematicaSchematic.createFromFile(
                            tempDir, "remote_" + schematicId);

                        if (schematic == null)
                        {
                            sendLoadSchematicAck(requestId, false, "Failed to parse schematic file");
                            return;
                        }

                        // Create placement at specified coordinates
                        BlockPos pos = new BlockPos(x, y, z);
                        SchematicPlacement placement = SchematicPlacement.createFor(
                            schematic, pos, schematic.getMetadata().getName(), true, true);

                        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
                        manager.addSchematicPlacement(placement, true);

                        Litematica.LOGGER.info("Successfully loaded schematic {} at ({}, {}, {})",
                            schematicId, x, y, z);
                        sendLoadSchematicAck(requestId, true, null);

                        // Clean up temp file
                        try
                        {
                            Files.deleteIfExists(targetPath);
                        }
                        catch (Exception e)
                        {
                            Litematica.LOGGER.debug("Failed to delete temp file: {}", targetPath);
                        }
                    }
                    catch (Exception e)
                    {
                        Litematica.LOGGER.error("Error loading schematic", e);
                        sendLoadSchematicAck(requestId, false, "Error loading schematic: " + e.getMessage());
                    }
                });
            }
            catch (Exception e)
            {
                Litematica.LOGGER.error("Error in handleLoadSchematic", e);
                sendLoadSchematicAck(requestId, false, "Error: " + e.getMessage());
            }
        });
    }

    /**
     * Downloads a schematic from the backend.
     *
     * @param schematicId Schematic ID to download
     * @param targetPath Path to save the downloaded file
     * @return true if successful, false otherwise
     */
    @SuppressWarnings("unchecked")
    private static boolean downloadSchematic(String schematicId, Path targetPath)
    {
        Object api = InventoryNetworkCompat.getApi();
        if (api == null)
        {
            return false;
        }

        try
        {
            Method downloadMethod = api.getClass().getMethod("downloadSchematic", String.class, Path.class);
            CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) downloadMethod.invoke(api, schematicId, targetPath);
            return future.get();
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Error downloading schematic", e);
            return false;
        }
    }

    /**
     * Sends an acknowledgment for a load_schematic request.
     */
    private static void sendLoadSchematicAck(String requestId, boolean success, String error)
    {
        Object api = InventoryNetworkCompat.getApi();
        if (api == null)
        {
            return;
        }

        try
        {
            Method ackMethod = api.getClass().getMethod("sendLoadSchematicAck", String.class, boolean.class, String.class);
            ackMethod.invoke(api, requestId, success, error);
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Error sending load_schematic_ack", e);
        }
    }

    /**
     * Sends split results from KDTreeSplitter to the backend.
     *
     * @param schematicId Schematic ID the results belong to
     * @param leaves List of leaf results from the split operation
     * @param fileName Original schematic filename
     */
    @SuppressWarnings("unchecked")
    public static void sendSplitResultsToBackend(int schematicId, List<LeafResult> leaves, String fileName)
    {
        if (!InventoryNetworkCompat.hasInventoryNetwork())
        {
            Litematica.LOGGER.debug("Cannot send split results: SolomonInvNetMod not available");
            return;
        }

        Object api = InventoryNetworkCompat.getApi();
        if (api == null || !InventoryNetworkCompat.isConnected())
        {
            Litematica.LOGGER.debug("Cannot send split results: not connected to backend");
            return;
        }

        try
        {
            // Build JSON payload
            JsonObject payload = new JsonObject();
            payload.addProperty("schematic_name", fileName);
            payload.addProperty("total_leaves", leaves.size());

            JsonArray leavesArray = new JsonArray();
            for (LeafResult leaf : leaves)
            {
                JsonObject leafJson = new JsonObject();
                leafJson.addProperty("leaf_index", leaf.leafIndex());

                BoxBounds bounds = leaf.bounds();
                leafJson.addProperty("bounds_min_x", bounds.minX());
                leafJson.addProperty("bounds_min_y", bounds.minY());
                leafJson.addProperty("bounds_min_z", bounds.minZ());
                leafJson.addProperty("bounds_max_x", bounds.maxX());
                leafJson.addProperty("bounds_max_y", bounds.maxY());
                leafJson.addProperty("bounds_max_z", bounds.maxZ());

                SplitMetrics metrics = leaf.metrics();
                leafJson.addProperty("blocks_non_air", metrics.blocksNonAir);
                leafJson.addProperty("stacks_needed", metrics.stacksNeeded);

                // Material counts
                JsonObject materialCounts = new JsonObject();
                metrics.blockCounts.forEach((blockState, count) -> {
                    String blockName = blockState.getBlock().getTranslationKey();
                    materialCounts.addProperty(blockName, count);
                });
                leafJson.add("material_counts", materialCounts);

                leavesArray.add(leafJson);
            }
            payload.add("leaves", leavesArray);

            String jsonResults = GSON.toJson(payload);

            // Send via API
            Method sendMethod = api.getClass().getMethod("sendSplitResults", int.class, String.class);
            CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) sendMethod.invoke(api, schematicId, jsonResults);

            future.thenAccept(success -> {
                if (success)
                {
                    Litematica.LOGGER.info("Successfully sent split results for schematic {} ({} leaves)",
                        schematicId, leaves.size());
                }
                else
                {
                    Litematica.LOGGER.warn("Failed to send split results for schematic {}", schematicId);
                }
            });
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Error sending split results to backend", e);
        }
    }

    /**
     * Checks if sync features are available.
     *
     * @return true if SolomonInvNetMod is available and connected
     */
    public static boolean isSyncAvailable()
    {
        return InventoryNetworkCompat.hasInventoryNetwork() && InventoryNetworkCompat.isConnected();
    }

    /**
     * Upload a schematic file to the backend asynchronously.
     *
     * @param schematicPath Path to the schematic file
     * @param name Display name for the schematic
     * @return CompletableFuture resolving to schematic ID on success, or null on failure
     */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<String> uploadSchematicAsync(Path schematicPath, String name)
    {
        if (!InventoryNetworkCompat.hasInventoryNetwork())
        {
            Litematica.LOGGER.warn("Cannot upload schematic: SolomonInvNetMod not available");
            return CompletableFuture.completedFuture(null);
        }

        Object api = InventoryNetworkCompat.getApi();
        if (api == null || !InventoryNetworkCompat.isConnected())
        {
            Litematica.LOGGER.warn("Cannot upload schematic: not connected to backend");
            return CompletableFuture.completedFuture(null);
        }

        try
        {
            Method uploadMethod = api.getClass().getMethod("uploadSchematic", Path.class, String.class);
            CompletableFuture<String> future = (CompletableFuture<String>) uploadMethod.invoke(api, schematicPath, name);

            return future.thenApply(schematicId -> {
                if (schematicId != null)
                {
                    Litematica.LOGGER.info("Successfully uploaded schematic '{}' with ID: {}", name, schematicId);
                }
                else
                {
                    Litematica.LOGGER.warn("Failed to upload schematic '{}'", name);
                }
                return schematicId;
            });
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Error uploading schematic '{}'", name, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Download a schematic from the backend asynchronously.
     *
     * @param schematicId ID of the schematic to download
     * @param targetPath Path to save the downloaded schematic
     * @return CompletableFuture resolving to true on success, false on failure
     */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<Boolean> downloadSchematicAsync(String schematicId, Path targetPath)
    {
        if (!InventoryNetworkCompat.hasInventoryNetwork())
        {
            Litematica.LOGGER.warn("Cannot download schematic: SolomonInvNetMod not available");
            return CompletableFuture.completedFuture(false);
        }

        Object api = InventoryNetworkCompat.getApi();
        if (api == null || !InventoryNetworkCompat.isConnected())
        {
            Litematica.LOGGER.warn("Cannot download schematic: not connected to backend");
            return CompletableFuture.completedFuture(false);
        }

        try
        {
            Method downloadMethod = api.getClass().getMethod("downloadSchematic", String.class, Path.class);
            CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) downloadMethod.invoke(api, schematicId, targetPath);

            return future.thenApply(success -> {
                if (success)
                {
                    Litematica.LOGGER.info("Successfully downloaded schematic {} to {}", schematicId, targetPath);
                }
                else
                {
                    Litematica.LOGGER.warn("Failed to download schematic {}", schematicId);
                }
                return success;
            });
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Error downloading schematic {}", schematicId, e);
            return CompletableFuture.completedFuture(false);
        }
    }
}
