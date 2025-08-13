package com.hellominers.statisticsupdater;

import ca.spottedleaf.dataconverter.minecraft.MCVersions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.kyori.adventure.key.Key;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.fixes.References;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.scheduler.BukkitRunnable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class FileBatchingTask extends BukkitRunnable {

    public static final String MINECRAFT_NAMESPACE = Key.MINECRAFT_NAMESPACE + Key.DEFAULT_SEPARATOR;

    public static int parseInt(String str) {
        try {
            return Integer.parseInt(str);
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    int batchSize = 15;
    final Stream<Path> filesStream;
    final Iterator<Path> filesIter;
    final IntFunction<String> itemUpdater, blockUpdater;
    final Logger logger;

    final DynamicOps<JsonElement> registryJsonOps = CraftRegistry.getMinecraftRegistry().createSerializationContext(JsonOps.INSTANCE);
    final int currentDataVersion = CraftMagicNumbers.INSTANCE.getDataVersion();

    int ticksLagged = 0;

    public FileBatchingTask(Path dir, IntFunction<String> itemUpdater, IntFunction<String> blockUpdater, Logger logger) throws IOException {
        this.filesStream = Files.list(dir);
        this.filesIter = filesStream.iterator();
        this.itemUpdater = itemUpdater;
        this.blockUpdater = blockUpdater;
        this.logger = logger;
    }

    @Override
    public void run() {
        DataFixer dataFixer = MinecraftServer.getServer().getFixerUpper();
        long startNanos = System.nanoTime();
        for (int filesUpdated = 0; filesIter.hasNext() && filesUpdated < batchSize; filesUpdated++) {
            Path statsFile = filesIter.next();
            JsonObject statsJson;
            try (JsonReader jsonReader = new JsonReader(new StringReader(Files.readString(statsFile)))) {
                jsonReader.setLenient(false);
                statsJson = Streams.parse(jsonReader).getAsJsonObject();
            }
            catch (JsonParseException e) {
                logger.warn("Stats file '{}' has invalid JSON, skipping.", statsFile, e);
                return;
            }
            catch (IOException e) {
                logger.warn("IO error while loading stats file '{}', skipping.", statsFile, e);
                return;
            }
            // If it has a data version or an updated "stats" block, it's new enough to hopefully not have number ids
            if (statsJson.get("DataVersion") != null || statsJson.get("stats") != null) {
                logger.info("Stats file '{}' is already up-to-date, skipping.", statsFile);
                return;
            }
            // Let MC do what it can with the updating, handles converting the "stats.useItem" format to a "stats" block with modern stat names
            JsonObject updatedStats = dataFixer.update(References.STATS, new Dynamic<>(registryJsonOps, statsJson), MCVersions.V15W32A, currentDataVersion).getValue().getAsJsonObject();

            // Pairs of statistic name -> values object
            for (Map.Entry<String, JsonElement> entry : updatedStats.getAsJsonObject("stats").entrySet()) {
                String statistic = entry.getKey();
                IntFunction<String> updater = switch (statistic) {
                    case "minecraft:crafted", "minecraft:used", "minecraft:broken", "minecraft:picked_up", "minecraft:dropped" -> itemUpdater;
                    case "minecraft:mined" -> blockUpdater;
                    default -> null;
                };
                if (updater == null) {
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
                    String updatedName = updater.apply(oldId);
                    if (updatedName == null) {
                        logger.warn("Ignoring unrecognized old ID '{}' in '{}' -> '{}'", oldId, statsFile, statistic);
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
                logger.error("Failed to write stats file '{}':", statsFile, e);
            }
        }
        if (!filesIter.hasNext()) {
            logger.info("""
                    
                    
                    
                    =============================================================
                    =============================================================
                    =============== Stats file updating complete! ===============
                    =============================================================
                    =============================================================
                    
                    
                    """);
            cancel();
            return;
        }
        long processTimeNanos = System.nanoTime() - startNanos;
        if (batchSize > 1 && processTimeNanos > 20_000_000) {
            ticksLagged++;
            if (ticksLagged > 5) {
                batchSize--;
                logger.warn("Server lag detected! Batch took {}ms to process, decreased batch size to {}.", processTimeNanos * 1.0E-6, batchSize);
                ticksLagged = 0;
                return;
            }
        }
        else {
            ticksLagged = 0;
        }
        logger.info("Batch took {}ms to process", processTimeNanos * 1.0E-6);
    }

    @Override
    public synchronized void cancel() throws IllegalStateException {
        filesStream.close();
        super.cancel();
    }
}
