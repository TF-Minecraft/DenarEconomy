package net.tfminecraft.denareconomy.testing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.mockbukkit.mockbukkit.MockBukkit;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateFallback;
import net.tfminecraft.tlibs.character.estate.EstateService;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.GuildLookup;
import net.tfminecraft.tlibs.character.estate.GuildRef;
import net.tfminecraft.tlibs.character.estate.Outcome;
import net.tfminecraft.tlibs.character.estate.Recipient;

/**
 * The estate service RPCharacters provides and the guild fallback SimpleFactions provides, for
 * tests. Guilds not listed are GONE.
 */
public final class FakeEstates implements EstateService, EstateFallback {
  public record Report(UUID deathId, String participantId, long revision, Outcome outcome) {}

  public final Map<UUID, EstateDecision> decisions = new HashMap<>();
  public final Map<UUID, EstateState> states = new HashMap<>();
  public final Map<UUID, GuildLookup> guilds = new HashMap<>();
  public final List<Report> reports = new ArrayList<>();
  public final List<UUID> guildCalls = new ArrayList<>();
  /** Thrown by guild lookups when set. */
  public RuntimeException failure;
  /** Thrown by reports when set. */
  public RuntimeException reportFailure;
  /** Runs at each guild lookup when set. */
  public Runnable onGuild;
  /** The answer to every death guild question. */
  public DeathGuild death = DeathGuild.UNAVAILABLE;

  /** Registers this as both the estate service and the guild fallback. */
  public static FakeEstates register() {
    FakeEstates estates = new FakeEstates();
    estates.registerService();
    estates.registerFallback();
    return estates;
  }

  public void registerService() {
    Bukkit.getServicesManager()
        .register(EstateService.class, this, MockBukkit.createMockPlugin(), ServicePriority.Normal);
  }

  public void registerFallback() {
    Bukkit.getServicesManager()
        .register(EstateFallback.class, this, MockBukkit.createMockPlugin(), ServicePriority.Normal);
  }

  public void unregisterService() {
    Bukkit.getServicesManager().unregister(EstateService.class, this);
  }

  public void unregisterFallback() {
    Bukkit.getServicesManager().unregister(EstateFallback.class, this);
  }

  /** A decided estate for this death sending the money to {@code money}. */
  public EstateDecision decide(
      CharacterRef testator,
      UUID deathId,
      Recipient money,
      Optional<UUID> deathGuild,
      Optional<UUID> deathRealm,
      long revision) {
    EstateDecision decision =
        new EstateDecision(
            deathId,
            testator,
            Optional.of(Instant.EPOCH),
            Instant.EPOCH,
            Optional.empty(),
            deathGuild,
            deathRealm,
            money,
            Recipient.FROZEN,
            Recipient.FROZEN,
            Recipient.SENDER,
            revision);
    decisions.put(deathId, decision);
    states.put(testator.characterId(), EstateState.DECIDED);
    return decision;
  }

  public void live(UUID guild) {
    guilds.put(
        guild,
        GuildLookup.live(
            new GuildRef(guild, "Guild", Optional.empty(), Optional.empty(), "guild:" + guild)));
  }

  @Override
  public Optional<EstateDecision> decision(UUID deathId) {
    return Optional.ofNullable(decisions.get(deathId));
  }

  @Override
  public List<EstateDecision> decided() {
    return decisions.values().stream()
        .filter(d -> states.get(d.testator().characterId()) == EstateState.DECIDED)
        .toList();
  }

  @Override
  public Optional<EstateState> stateOf(UUID characterId) {
    return Optional.ofNullable(states.get(characterId));
  }

  @Override
  public void report(UUID deathId, String participantId, long revision, Outcome outcome) {
    if (reportFailure != null) {
      throw reportFailure;
    }
    reports.add(new Report(deathId, participantId, revision, outcome));
  }

  @Override
  public DeathGuild deathGuild(UUID characterId, UUID deathId) {
    return death;
  }

  @Override
  public GuildLookup guild(UUID incarnation) {
    guildCalls.add(incarnation);
    if (onGuild != null) {
      onGuild.run();
    }
    if (failure != null) {
      throw failure;
    }
    return guilds.getOrDefault(incarnation, GuildLookup.GONE);
  }

  @Override
  public GuildLookup guildByName(String name) {
    return GuildLookup.UNAVAILABLE;
  }
}
