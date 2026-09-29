package com.scarasol.citylines.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.DynamicOps;
import mcjty.lostcities.setup.CustomRegistries;
import mcjty.lostcities.worldgen.lost.regassets.MultiBuildingRE;
import mcjty.lostcities.worldgen.lost.regassets.PredefinedCityRE;
import mcjty.lostcities.worldgen.lost.regassets.WorldStyleRE;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.RegistryOps;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/** Content identity from datapack registries, never from the lazy asset-object cache. */
public final class GenerationFingerprint {
    private GenerationFingerprint() {
    }

    static String assets(ServerLevel level) {
        JsonObject inputs = new JsonObject();
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        inputs.add("worldstyles", registry(level.registryAccess().registryOrThrow(
                CustomRegistries.WORLDSTYLES_REGISTRY_KEY), WorldStyleRE.CODEC, ops));
        inputs.add("predefinedcities", registry(level.registryAccess().registryOrThrow(
                CustomRegistries.PREDEFINEDCITIES_REGISTRY_KEY), PredefinedCityRE.CODEC, ops));
        // Predefined multi-buildings use this registry to determine their occupied footprint.
        inputs.add("multibuildings", registry(level.registryAccess().registryOrThrow(
                CustomRegistries.MULTIBUILDINGS_REGISTRY_KEY), MultiBuildingRE.CODEC, ops));
        return digest(inputs);
    }

    private static <T> JsonObject registry(Registry<T> registry, Codec<T> codec, DynamicOps<JsonElement> ops) {
        JsonObject result = new JsonObject();
        registry.keySet().stream().sorted().forEach(key -> result.add(key.toString(),
                codec.encodeStart(ops, registry.get(key)).getOrThrow(false,
                        error -> { throw new IllegalStateException("Cannot fingerprint " + key + ": " + error); })));
        return result;
    }

    public static String digest(JsonElement value) {
        try {
            byte[] bytes = canonical(value).toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            value.getAsJsonObject().entrySet().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, element) -> result.add(key, canonical(element)));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(element -> result.add(canonical(element)));
            return result;
        }
        return value;
    }
}
