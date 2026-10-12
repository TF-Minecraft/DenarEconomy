package net.tfminecraft.denareconomy.testing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.mockbukkit.mockbukkit.MockBukkit;

import net.tfminecraft.tlibs.character.CharacterIdentity;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;

/** A character provider for tests: sessions by player, characters by ID and reviewed heirs. */
public final class FakeIdentity implements CharacterIdentity {
  public final Map<UUID, CharacterSession> sessions = new HashMap<>();
  public final Map<UUID, CharacterInfo> characters = new HashMap<>();
  public final Map<UUID, CharacterRef> heirs = new HashMap<>();
  public boolean heirsReady;
  private long nextSession;

  /** Registers a new provider with the running MockBukkit server. */
  public static FakeIdentity register() {
    FakeIdentity identity = new FakeIdentity();
    Bukkit.getServicesManager()
        .register(
            CharacterIdentity.class, identity, MockBukkit.createMockPlugin(), ServicePriority.Normal);
    return identity;
  }

  /** Gives the player a new living character and makes it current. */
  public CharacterRef play(Player player) {
    CharacterRef ref = character(player.getUniqueId());
    start(player, ref);
    return ref;
  }

  /** A new living character of this account, not being played. */
  public CharacterRef character(UUID owner) {
    CharacterRef ref = new CharacterRef(owner, UUID.randomUUID());
    characters.put(ref.characterId(), info(ref, CharacterStatus.ALIVE));
    return ref;
  }

  public void start(Player player, CharacterRef ref) {
    sessions.put(player.getUniqueId(), new CharacterSession(ref, ++nextSession));
  }

  public void stop(Player player) {
    sessions.remove(player.getUniqueId());
  }

  public void kill(CharacterRef ref) {
    characters.put(ref.characterId(), info(ref, CharacterStatus.DEAD));
  }

  private static CharacterInfo info(CharacterRef ref, CharacterStatus status) {
    return new CharacterInfo(
        ref, "Char-" + ref.characterId().toString().substring(0, 4), status, null, Set.of(), 0, null);
  }

  @Override
  public Optional<CharacterSession> currentSession(Player player) {
    return Optional.ofNullable(sessions.get(player.getUniqueId()));
  }

  @Override
  public boolean isCurrent(CharacterSession session) {
    return sessions.containsValue(session);
  }

  @Override
  public Optional<CharacterInfo> info(UUID characterId) {
    return Optional.ofNullable(characters.get(characterId));
  }

  @Override
  public Optional<List<CharacterInfo>> characters(UUID ownerId) {
    return Optional.of(
        characters.values().stream().filter(c -> c.ref().ownerId().equals(ownerId)).toList());
  }

  @Override
  public Optional<CharacterInfo> lastPlayed(UUID ownerId) {
    return Optional.empty();
  }

  @Override
  public Optional<CharacterRef> legacyHeir(UUID ownerId) {
    return Optional.ofNullable(heirs.get(ownerId));
  }

  @Override
  public boolean legacyHeirsReady() {
    return heirsReady;
  }

  @Override
  public String displayName(Player player) {
    return player.getName();
  }
}
