package com.civilizationai;

import java.util.Random;

/** Stable behavioural traits assigned once when an AI citizen is created. */
public record Personality(
        float curiosity,
        float industriousness,
        float sociability,
        float caution,
        float cooperation
) {
    public static Personality random(Random random) {
        return new Personality(
                range(random), range(random), range(random), range(random), range(random)
        );
    }

    private static float range(Random random) {
        return 0.2F + random.nextFloat() * 0.8F;
    }

    public String greeting() {
        if (sociability > 0.75F) return "Hello! It's good to meet you.";
        if (curiosity > 0.75F) return "I wonder what is beyond those hills.";
        if (industriousness > 0.75F) return "There is work to do. Let's get started.";
        if (caution > 0.75F) return "Let's find somewhere safe before nightfall.";
        return "A new day in this world.";
    }

    public Profession preferredProfession() {
        if (industriousness > 0.78F) return Profession.BUILDER;
        if (cooperation > 0.78F) return Profession.FARMER;
        if (curiosity > 0.78F) return Profession.SCOUT;
        return Profession.GATHERER;
    }

    public enum Profession {
        GATHERER, BUILDER, FARMER, SCOUT
    }
}
