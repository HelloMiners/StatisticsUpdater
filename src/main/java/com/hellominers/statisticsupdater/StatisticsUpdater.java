package com.hellominers.statisticsupdater;

import ca.spottedleaf.dataconverter.minecraft.MCDataConverter;
import ca.spottedleaf.dataconverter.minecraft.MCVersions;
import ca.spottedleaf.dataconverter.minecraft.converters.helpers.HelperBlockFlatteningV1450;
import ca.spottedleaf.dataconverter.minecraft.converters.helpers.HelperItemNameV102;
import ca.spottedleaf.dataconverter.minecraft.converters.itemstack.ConverterFlattenItemStack;
import ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry;
import ca.spottedleaf.dataconverter.types.MapType;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.IntFunction;

public class StatisticsUpdater extends JavaPlugin {

    public static final String AIR = "minecraft:air";

    @Override
    public void onEnable() {
        int currentDataVersion = CraftMagicNumbers.INSTANCE.getDataVersion();
        // Map pre-flattening ids to modern names directly, to avoid going through the update process for every stat.
        IntFunction<String> BLOCK_MAPPING;
        Int2ObjectMap<String> ITEM_MAPPING;
        try {
            Field blockDefaultsField = HelperBlockFlatteningV1450.class.getDeclaredField("BLOCK_DEFAULTS");
            blockDefaultsField.setAccessible(true);
            MapType<String>[] data = (MapType<String>[]) blockDefaultsField.get(null);
            // Maps {Name:<name>,...} data to our id -> name mapping, and excludes air (not relevant for stats)
            String[] fullBlocksMapping = new String[data.length];
            for (int i = 0; i < data.length; i++) {
                MapType<String> mapData = data[i];
                if (mapData == null) {
                    continue;
                }
                String name = mapData.getString("Name", "");
                if (name.equals("%%FILTER_ME%%") || name.equals(AIR)) {
                    continue;
                }
                fullBlocksMapping[i] = MCDataConverter.convert(MCTypeRegistry.BLOCK_NAME, name, MCVersions.V1_12_2, currentDataVersion).toString();
            }
            // Strip the tons of "air" entries at the end
            int lastNonAirIndex = fullBlocksMapping.length - 1;
            while (lastNonAirIndex >= 0 && fullBlocksMapping[lastNonAirIndex] == null) {
                lastNonAirIndex--;
            }
            String[] blockMapping = Arrays.copyOfRange(fullBlocksMapping, 0, lastNonAirIndex + 1);
            BLOCK_MAPPING = id -> id >= 0 && id < blockMapping.length ? blockMapping[id] : null;

            Field itemNamesField = HelperItemNameV102.class.getDeclaredField("ITEM_NAMES");
            itemNamesField.setAccessible(true);
            Int2ObjectMap<String> originalItemMapping = (Int2ObjectMap<String>) itemNamesField.get(null);
            ITEM_MAPPING = new Int2ObjectOpenHashMap<>(originalItemMapping.size());
            // The names in ITEM_NAMES are from back then and need updating (e.g. "speckled_melon" needs to become "glistering_melon_slice")
            // Also excludes invalid items (e.g. "water" or "fire").
            for (Int2ObjectMap.Entry<String> entry : originalItemMapping.int2ObjectEntrySet()) {
                String name = entry.getValue();
                String flattenedName = ConverterFlattenItemStack.flattenItem(name, 0);
                String updatedName = MCDataConverter.convert(MCTypeRegistry.ITEM_NAME, flattenedName != null ? flattenedName : name, MCVersions.V1_12_2, currentDataVersion).toString();
                ResourceLocation updatedId = ResourceLocation.tryParse(updatedName);
                if (updatedId != null && BuiltInRegistries.ITEM.containsKey(updatedId)) {
                    ITEM_MAPPING.put(entry.getIntKey(), updatedName);
                }
            }
        }
        catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        Path statsDir = MinecraftServer.getServer().getWorldPath(LevelResource.PLAYER_STATS_DIR);
        try {
            new FileBatchingTask(statsDir, ITEM_MAPPING, BLOCK_MAPPING, this).runTaskTimer(this, 0L, 1L);
        }
        catch (IOException e) {
            getSLF4JLogger().error("Failed to initialize file batching task", e);
        }
    }
}
