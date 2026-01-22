package fi.dy.masa.litematica.compat.invnet;

import net.fabricmc.loader.api.FabricLoader;
import fi.dy.masa.litematica.Litematica;

/**
 * Compatibility checker for SolomonInvNetMod.
 * Handles detection and API access for the inventory network mod.
 */
public class InventoryNetworkCompat
{
    public static final String MOD_ID = "solomoninvnet";
    private static boolean hasInventoryNetwork = false;
    private static Object apiInstance = null;
    private static boolean checked = false;

    /**
     * Checks if SolomonInvNetMod is loaded and initializes the API reference.
     * This should be called once during mod initialization.
     */
    public static void checkForInventoryNetwork()
    {
        if (checked)
        {
            return;
        }
        checked = true;

        hasInventoryNetwork = FabricLoader.getInstance().isModLoaded(MOD_ID);

        if (hasInventoryNetwork)
        {
            Litematica.LOGGER.info("SolomonInvNetMod detected - schematic sync features enabled");
            tryInitializeApi();
        }
        else
        {
            Litematica.LOGGER.info("SolomonInvNetMod not detected - schematic sync features disabled");
        }
    }

    /**
     * Attempts to get the API instance from SolomonInvNetMod via reflection.
     */
    private static void tryInitializeApi()
    {
        try
        {
            Class<?> providerClass = Class.forName("com.BookKeeper.InventoryNetwork.api.SchematicSyncApiProvider");
            java.lang.reflect.Method getApiMethod = providerClass.getMethod("getApi");
            apiInstance = getApiMethod.invoke(null);

            if (apiInstance != null)
            {
                Litematica.LOGGER.info("Successfully obtained SchematicSyncApi instance");
            }
            else
            {
                Litematica.LOGGER.warn("SchematicSyncApi instance is null - mod may not be fully initialized yet");
            }
        }
        catch (ClassNotFoundException e)
        {
            Litematica.LOGGER.warn("Could not find SchematicSyncApiProvider class");
            hasInventoryNetwork = false;
        }
        catch (Exception e)
        {
            Litematica.LOGGER.error("Error initializing SchematicSyncApi", e);
            hasInventoryNetwork = false;
        }
    }

    /**
     * Refreshes the API instance. Call this if the initial check returned null.
     */
    public static void refreshApi()
    {
        if (hasInventoryNetwork && apiInstance == null)
        {
            tryInitializeApi();
        }
    }

    /**
     * Checks if SolomonInvNetMod is available.
     *
     * @return true if the mod is loaded, false otherwise
     */
    public static boolean hasInventoryNetwork()
    {
        return hasInventoryNetwork;
    }

    /**
     * Gets the SchematicSyncApi instance.
     *
     * @return The API instance, or null if not available
     */
    public static Object getApi()
    {
        // Try to refresh if null
        if (hasInventoryNetwork && apiInstance == null)
        {
            tryInitializeApi();
        }
        return apiInstance;
    }

    /**
     * Sets the API instance directly (for testing or special cases).
     *
     * @param api The API instance to use
     */
    public static void setApi(Object api)
    {
        apiInstance = api;
    }

    /**
     * Checks if the API is connected to the backend.
     *
     * @return true if connected, false otherwise
     */
    public static boolean isConnected()
    {
        if (apiInstance == null)
        {
            return false;
        }

        try
        {
            java.lang.reflect.Method isConnectedMethod = apiInstance.getClass().getMethod("isConnected");
            return (Boolean) isConnectedMethod.invoke(apiInstance);
        }
        catch (Exception e)
        {
            Litematica.LOGGER.debug("Error checking connection status", e);
            return false;
        }
    }
}
