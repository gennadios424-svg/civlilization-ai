package com.civilizationai;

import com.mojang.serialization.Codec;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;

/**
 * World-persistent memory for Civilization AI citizens.
 * The codec stores a compact string map so memories can evolve without
 * requiring a brittle, version-specific NBT schema for every AI field.
 */
public final class CivilizationMemoryData extends SavedData {
    public static final Codec<CivilizationMemoryData> CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .xmap(CivilizationMemoryData::new, data -> data.values);

    public static final SavedDataType<CivilizationMemoryData> TYPE =
            new SavedDataType<>(
                    "civilization_ai_memory",
                    CivilizationMemoryData::new,
                    CODEC,
                    DataFixTypes.LEVEL
            );

    private final Map<String, String> values;

    public CivilizationMemoryData() {
        this.values = new HashMap<>();
    }

    private CivilizationMemoryData(Map<String, String> values) {
        this.values = new HashMap<>(values);
    }

    public String get(String key) {
        return values.get(key);
    }

    public void put(String key, String value) {
        if (value == null) values.remove(key);
        else values.put(key, value);
        setDirty();
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(values);
    }
}
