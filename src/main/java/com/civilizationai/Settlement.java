package com.civilizationai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A claimed settlement with membership, professions and a shared resource ledger. */
public final class Settlement {
    private final UUID id = UUID.randomUUID();
    private final String name;
    private final ServerLevel level;
    private BlockPos center;
    private final Set<UUID> members = new HashSet<>();
    private final Map<UUID, Personality.Profession> professions = new HashMap<>();
    private final Map<String, Integer> stockpile = new HashMap<>();
    private int ageTicks;

    public Settlement(String name, ServerLevel level, BlockPos center) {
        this.name = name;
        this.level = level;
        this.center = center.immutable();
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public ServerLevel level() { return level; }
    public BlockPos center() { return center; }
    public Set<UUID> members() { return Set.copyOf(members); }
    public int ageTicks() { return ageTicks; }

    public void tick() { ageTicks++; }

    public void addMember(UUID citizen, Personality.Profession profession) {
        members.add(citizen);
        professions.put(citizen, profession);
    }

    public Personality.Profession professionOf(UUID citizen) {
        return professions.getOrDefault(citizen, Personality.Profession.GATHERER);
    }

    public void deposit(String resource, int amount) {
        if (amount > 0) stockpile.merge(resource, amount, Integer::sum);
    }

    public int stock(String resource) {
        return stockpile.getOrDefault(resource, 0);
    }

    public int withdraw(String resource, int amount) {
        int available = stock(resource);
        int taken = Math.min(Math.max(0, amount), available);
        if (taken > 0) stockpile.put(resource, available - taken);
        return taken;
    }

    public void moveCenter(BlockPos newCenter) {
        center = newCenter.immutable();
    }
}
