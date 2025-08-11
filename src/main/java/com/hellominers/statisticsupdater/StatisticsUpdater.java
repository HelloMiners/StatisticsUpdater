package com.hellominers.statisticsupdater;

import ca.spottedleaf.dataconverter.minecraft.MCDataConverter;
import ca.spottedleaf.dataconverter.minecraft.MCVersions;
import ca.spottedleaf.dataconverter.minecraft.converters.helpers.HelperBlockFlatteningV1450;
import ca.spottedleaf.dataconverter.minecraft.converters.helpers.HelperItemNameV102;
import ca.spottedleaf.dataconverter.minecraft.converters.itemstack.ConverterFlattenItemStack;
import ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry;
import ca.spottedleaf.dataconverter.types.MapType;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.kyori.adventure.key.Key;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.storage.LevelResource;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class StatisticsUpdater extends JavaPlugin {

    public static final String AIR = "minecraft:air";
    public static final String MINECRAFT_NAMESPACE = Key.MINECRAFT_NAMESPACE + Key.DEFAULT_SEPARATOR;

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
        DataFixer dataFixer = MinecraftServer.getServer().getFixerUpper();
        // Not sure if registry context is needed here but why not
        DynamicOps<JsonElement> jsonOps = CraftRegistry.getMinecraftRegistry().createSerializationContext(JsonOps.INSTANCE);
        try (Stream<Path> statsFiles = Files.list(statsDir)) {
            statsFiles.forEach(statsFile ->  {
                JsonObject statsJson;
                try (JsonReader jsonReader = new JsonReader(new StringReader(Files.readString(statsFile)))) {
                    jsonReader.setLenient(false);
                    statsJson = Streams.parse(jsonReader).getAsJsonObject();
                }
                catch (JsonParseException e) {
                    getSLF4JLogger().warn("Stats file {} has invalid JSON, skipping.", statsFile, e);
                    return;
                }
                catch (IOException e) {
                    getSLF4JLogger().warn("IO error while loading stats file {}, skipping.", statsFile, e);
                    return;
                }
                // If it has a data version or an updated "stats" block, it's new enough to hopefully not have number ids
                if (statsJson.get("DataVersion") != null || statsJson.get("stats") != null) {
                    getSLF4JLogger().info("Stats file {} is already up-to-date, skipping.", statsFile);
                    return;
                }
                // Let MC do what it can with the updating, handles converting the "stats.useItem" format to a "stats" block with modern stat names
                JsonObject updatedStats = dataFixer.update(References.STATS, new Dynamic<>(jsonOps, statsJson), MCVersions.V15W32A, currentDataVersion).getValue().getAsJsonObject();

                getSLF4JLogger().info(new GsonBuilder().setPrettyPrinting().create().toJson(updatedStats));
                // Pairs of statistic name -> values object
                for (Map.Entry<String, JsonElement> entry : updatedStats.getAsJsonObject("stats").entrySet()) {
                    String statistic = entry.getKey();
                    IntFunction<String> mapper = switch (statistic) {
                        case "minecraft:crafted", "minecraft:used", "minecraft:broken", "minecraft:picked_up", "minecraft:dropped" -> ITEM_MAPPING;
                        case "minecraft:mined" -> BLOCK_MAPPING;
                        default -> null;
                    };
                    if (mapper == null) {
                        continue;
                    }
                    JsonObject statValues = entry.getValue().getAsJsonObject();
                    JsonObject updatedStatValues = new JsonObject();
                    // Pairs of item/block types (or "minecraft:<legacy id>" in this case) to number values
                    for (Map.Entry<String, JsonElement> statEntry : statValues.entrySet()) {
                        int oldId = parseInt(statEntry.getKey().substring(MINECRAFT_NAMESPACE.length()));
                        if (oldId == -1) {
                            updatedStatValues.add(statEntry.getKey(), statEntry.getValue());
                            continue;
                        }
                        String updatedName = mapper.apply(oldId);
                        if (updatedName == null) {
                            getSLF4JLogger().warn("Ignoring unrecognized old ID {} in {}/{}", oldId, statsFile, statistic);
                            continue;
                        }
                        updatedStatValues.add(updatedName, statEntry.getValue());
                    }
                    entry.setValue(updatedStatValues);
                }
                updatedStats.addProperty("DataVersion", currentDataVersion);
                try {
                    Files.writeString(statsFile, updatedStats.toString());
                }
                catch (IOException e) {
                    getSLF4JLogger().error("Failed to write stats file {}:", statsFile);
                    getSLF4JLogger().error("", e);
                }
            });
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public static int parseInt(String str) {
        try {
            return Integer.parseInt(str);
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }
}
