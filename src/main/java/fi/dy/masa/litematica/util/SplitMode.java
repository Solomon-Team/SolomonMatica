package fi.dy.masa.litematica.util;

import javax.annotation.Nonnull;
import net.minecraft.util.StringIdentifiable;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

public enum SplitMode implements IConfigOptionListEntry, StringIdentifiable
{
    FIXED_CHUNK_SIZE   ("fixed_chunk_size",    "litematica.gui.label.split_mode.fixed_chunk_size"),
    KD_INVENTORY_AWARE ("kd_inventory_aware",  "litematica.gui.label.split_mode.kd_inventory_aware");

    public static final StringIdentifiable.EnumCodec<SplitMode> CODEC = StringIdentifiable.createCodec(SplitMode::values);
    public static final ImmutableList<SplitMode> VALUES = ImmutableList.copyOf(values());
    private final String configString;
    private final String translationKey;

    SplitMode(String configString, String translationKey)
    {
        this.configString = configString;
        this.translationKey = translationKey;
    }

    @Override
    public @Nonnull String asString()
    {
        return this.configString;
    }

    @Override
    public String getStringValue()
    {
        return this.configString;
    }

    @Override
    public String getDisplayName()
    {
        return StringUtils.translate(this.translationKey);
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward)
    {
        int id = this.ordinal();

        if (forward)
        {
            if (++id >= values().length)
            {
                id = 0;
            }
        }
        else
        {
            if (--id < 0)
            {
                id = values().length - 1;
            }
        }

        return values()[id % values().length];
    }

    @Override
    public SplitMode fromString(String name)
    {
        return fromStringStatic(name);
    }

    public static SplitMode fromStringStatic(String name)
    {
        for (SplitMode val : VALUES)
        {
            if (val.configString.equalsIgnoreCase(name))
            {
                return val;
            }
        }

        return SplitMode.FIXED_CHUNK_SIZE;
    }
}
