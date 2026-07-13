package com.auctiontracker.core;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JPA entity. Money fields are whole rupees. */
@Entity
@Table(name = "team")
public class Team {

    @Id
    private UUID teamId;

    @Column(nullable = false)
    private String name;

    private String ownerName;
    private long startingPurse;
    private long remainingPurse;
    private int maxSquadSize;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "team_min_per_role", joinColumns = @JoinColumn(name = "team_id"))
    @MapKeyColumn(name = "role")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "min_count")
    private Map<PlayerRole, Integer> minPerRole = new EnumMap<>(PlayerRole.class);

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "team_squad", joinColumns = @JoinColumn(name = "team_id"))
    @Column(name = "player_id")
    private List<UUID> squadPlayerIds = new ArrayList<>();

    @Version
    private long version;

    public static Team register(String name, String ownerName, long startingPurse, int maxSquadSize,
                                Map<PlayerRole, Integer> minPerRole) {
        Team t = new Team();
        t.teamId = UUID.randomUUID();
        t.name = name;
        t.ownerName = ownerName;
        t.startingPurse = startingPurse;
        t.remainingPurse = startingPurse;
        t.maxSquadSize = maxSquadSize;
        if (minPerRole != null) {
            t.minPerRole.putAll(minPerRole);
        }
        return t;
    }

    public int squadSize() {
        return squadPlayerIds.size();
    }

    public UUID getTeamId() { return teamId; }
    public void setTeamId(UUID teamId) { this.teamId = teamId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public long getStartingPurse() { return startingPurse; }
    public void setStartingPurse(long startingPurse) { this.startingPurse = startingPurse; }

    public long getRemainingPurse() { return remainingPurse; }
    public void setRemainingPurse(long remainingPurse) { this.remainingPurse = remainingPurse; }

    public int getMaxSquadSize() { return maxSquadSize; }
    public void setMaxSquadSize(int maxSquadSize) { this.maxSquadSize = maxSquadSize; }

    public Map<PlayerRole, Integer> getMinPerRole() { return minPerRole; }
    public void setMinPerRole(Map<PlayerRole, Integer> minPerRole) { this.minPerRole = minPerRole; }

    public List<UUID> getSquadPlayerIds() { return squadPlayerIds; }
    public void setSquadPlayerIds(List<UUID> squadPlayerIds) { this.squadPlayerIds = squadPlayerIds; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
