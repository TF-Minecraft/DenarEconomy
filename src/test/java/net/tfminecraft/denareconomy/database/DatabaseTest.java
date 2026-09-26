package net.tfminecraft.denareconomy.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.denareconomy.managers.PlayerManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class DatabaseTest {
  private static final Path DIRECTORY = Path.of("plugins/DenarEconomy/PlayerData");
  private Path backup;

  @BeforeEach
  void preserveExistingData() throws Exception {
    if (Files.exists(DIRECTORY)) {
      backup = Files.createTempDirectory("denar-database-backup").resolve("data");
      Files.move(DIRECTORY, backup);
    }
  }

  @AfterEach
  void restoreExistingData() throws Exception {
    if (Files.exists(DIRECTORY)) {
      try (var paths = Files.walk(DIRECTORY)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
    if (backup != null) {
      Files.move(backup, DIRECTORY);
      Files.delete(backup.getParent());
    }
  }

  @Test
  void legacyPublicConstructionDoesNotCreateOrOverwriteStorage() throws Exception {
    new Database();
    assertFalse(Files.exists(DIRECTORY));

    UUID id = UUID.randomUUID();
    Database.savePlayerData(account(id, 12.34, 56.78));
    Path file = DIRECTORY.resolve(id + ".json");
    byte[] saved = Files.readAllBytes(file);
    new Database();
    assertArrayEquals(saved, Files.readAllBytes(file));
  }

  @Test
  void savesAndReloadsBothBalancesAndCreatesMissingAccounts() {
    UUID id = UUID.randomUUID();
    assertFalse(Database.hasPlayerData(id));
    PlayerData empty = Database.loadPlayerData(id);
    assertEquals(id, empty.getId());
    assertEquals(0, empty.getPouch().getBal());
    assertEquals(0, empty.getBank().getBal());
    assertFalse(Database.hasPlayerData(id), "Reading an unknown account must not create a file");

    PlayerData data = account(id, 12.34, 56.78);
    Database.savePlayerData(data);
    assertTrue(Database.hasPlayerData(id));
    assertEquals(12.34, Database.getPlayerBalance(id, Accounts.POUCH));
    assertEquals(56.78, Database.getPlayerBalance(id, Accounts.BANK));
    assertEquals(id, Database.loadPlayerData(id).getId());
    assertFalse(Database.loadPlayerData(id).getBank().isTaxable());

    data.getPouch().setBal(99.12);
    Database.savePlayerData(data);
    assertEquals(99.12, Database.getPlayerBalance(id, Accounts.POUCH));
  }

  @Test
  void unreadableAccountNeverBecomesAZeroBalanceSessionOrOverwritesSavings() throws Exception {
    UUID id = UUID.randomUUID();
    Database.savePlayerData(account(id, 12.34, 100));
    Path file = DIRECTORY.resolve(id + ".json");
    byte[] original = Files.readAllBytes(file);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.getFileStore(file).supportsFileAttributeView("posix"));
    var permissions = Files.getPosixFilePermissions(file);
    PlayerManager manager = new PlayerManager();
    try {
      Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_WRITE));
      assumeFalse(Files.isReadable(file), "Privileged users can bypass POSIX permissions");
      UncheckedIOException failure =
          assertThrows(UncheckedIOException.class, () -> manager.add(id));
      assertTrue(failure.getMessage().contains(id.toString()));
      assertFalse(manager.exists(id));
      manager.save(id);
    } finally {
      Files.setPosixFilePermissions(file, permissions);
    }
    assertArrayEquals(original, Files.readAllBytes(file));
    assertEquals(100, manager.get(id).getBank().getBal());
  }

  @Test
  void inaccessibleParentIsAnErrorRatherThanAMissingAccount() throws Exception {
    UUID id = UUID.randomUUID();
    Database.savePlayerData(account(id, 12.34, 100));
    Path file = DIRECTORY.resolve(id + ".json");
    byte[] original = Files.readAllBytes(file);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.getFileStore(DIRECTORY).supportsFileAttributeView("posix"));
    var permissions = Files.getPosixFilePermissions(DIRECTORY);
    PlayerManager manager = new PlayerManager();
    try {
      Files.setPosixFilePermissions(DIRECTORY, Set.of(PosixFilePermission.OWNER_READ));
      assumeFalse(Files.isExecutable(DIRECTORY), "Privileged users can bypass POSIX permissions");
      assertThrows(UncheckedIOException.class, () -> Database.hasPlayerData(id));
      assertThrows(UncheckedIOException.class, () -> Database.loadPlayerData(id));
      assertThrows(UncheckedIOException.class, () -> manager.add(id));
      assertFalse(manager.exists(id));
    } finally {
      Files.setPosixFilePermissions(DIRECTORY, permissions);
    }
    manager.save(id);
    assertArrayEquals(original, Files.readAllBytes(file));
    assertEquals(100, manager.get(id).getBank().getBal());
  }

  @Test
  void failedSaveKeepsUpdatedSessionAndRetriesAfterStorageRecovers() throws Exception {
    UUID id = UUID.randomUUID();
    Database.savePlayerData(account(id, 12.34, 100));
    Path file = DIRECTORY.resolve(id + ".json");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.getFileStore(file).supportsFileAttributeView("posix"));
    var permissions = Files.getPosixFilePermissions(DIRECTORY);
    PlayerManager manager = new PlayerManager();
    PlayerData live = manager.get(id);
    live.getBank().setBal(200);
    try {
      Files.setPosixFilePermissions(
          DIRECTORY, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
      assumeFalse(Files.isWritable(DIRECTORY), "Privileged users can bypass POSIX permissions");
      UncheckedIOException failure =
          assertThrows(UncheckedIOException.class, () -> manager.save(id));
      assertTrue(failure.getMessage().contains(id.toString()));
      assertSame(live, manager.peek(id));
      assertEquals(200, live.getBank().getBal());
      assertEquals(100, Database.loadPlayerData(id).getBank().getBal());
      manager.add(
          id); // Rejoining must keep the unsaved balance rather than reload stale disk data.
      assertSame(live, manager.peek(id));
    } finally {
      Files.setPosixFilePermissions(DIRECTORY, permissions);
    }
    manager.save(id);
    assertFalse(manager.exists(id));
    assertEquals(200, Database.loadPlayerData(id).getBank().getBal());
  }

  @ParameterizedTest
  @ValueSource(strings = {"write", "move", "cleanup"})
  void interruptedReplacementPreservesOriginalAccount(String phase) throws Exception {
    UUID id = UUID.randomUUID();
    Database.savePlayerData(account(id, 12.34, 100));
    Path file = DIRECTORY.resolve(id + ".json");
    byte[] original = Files.readAllBytes(file);
    List<Path> temporaryFiles = new ArrayList<>();
    IOException failure =
        phase.equals("move")
            ? new AtomicMoveNotSupportedException("temporary", "account", "test filesystem")
            : new IOException("disk full while writing");
    IOException cleanupFailure = new IOException("cleanup denied");
    try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      if (phase.equals("move")) {
        files
            .when(
                () ->
                    Files.move(
                        any(Path.class),
                        eq(file),
                        eq(StandardCopyOption.ATOMIC_MOVE),
                        eq(StandardCopyOption.REPLACE_EXISTING)))
            .thenThrow(failure);
      } else {
        files
            .when(() -> Files.writeString(any(Path.class), any(CharSequence.class)))
            .thenAnswer(
                invocation -> {
                  Path temporary = invocation.getArgument(0);
                  temporaryFiles.add(temporary);
                  Files.write(
                      temporary, "incomplete".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                  throw failure;
                });
      }
      if (phase.equals("cleanup")) {
        files.when(() -> Files.deleteIfExists(any(Path.class))).thenThrow(cleanupFailure);
      }
      UncheckedIOException thrown =
          assertThrows(
              UncheckedIOException.class, () -> Database.savePlayerData(account(id, 12.34, 200)));
      assertSame(failure, thrown.getCause());
      if (phase.equals("cleanup"))
        assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
    }
    assertArrayEquals(original, Files.readAllBytes(file));
    assertEquals(100, Database.loadPlayerData(id).getBank().getBal());
    for (Path temporary : temporaryFiles) {
      assertEquals(phase.equals("cleanup"), Files.exists(temporary));
      Files.deleteIfExists(temporary);
    }
    try (var paths = Files.list(DIRECTORY)) {
      assertEquals(List.of(file), paths.toList());
    }
  }

  @Test
  void sumsAccountsRanksDescendingLimitsAndUsesUuidWhenNameMissing() throws Exception {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Database.savePlayerData(account(first, 10, 20));
    Database.savePlayerData(account(second, 2, 3));
    Files.writeString(DIRECTORY.resolve("ignored.txt"), "not json");
    assertEquals(12, Database.getTotalAmount(Accounts.POUCH));
    assertEquals(23, Database.getTotalAmount(Accounts.BANK));

    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      OfflinePlayer named = mock(OfflinePlayer.class);
      when(named.getName()).thenReturn("RichPlayer");
      OfflinePlayer unnamed = mock(OfflinePlayer.class);
      bukkit.when(() -> Bukkit.getOfflinePlayer(first)).thenReturn(named);
      bukkit.when(() -> Bukkit.getOfflinePlayer(second)).thenReturn(unnamed);
      var all = Database.getTopBalances(5);
      assertEquals(2, all.size());
      assertEquals("RichPlayer", all.get(0).getName());
      assertEquals(30, all.get(0).getTotal());
      assertEquals(second.toString().substring(0, 8), all.get(1).getName());
      assertEquals(5, all.get(1).getTotal());
      assertEquals(1, Database.getTopBalances(1).size());
      assertEquals("RichPlayer", Database.getTopBalances(1).get(0).getName());
      assertTrue(Database.getTopBalances(0).isEmpty());
      assertEquals(2, Database.getTopBalances(2).size());
    }
  }

  @Test
  void missingEmptyAndNonDirectoryStorageHaveNoTotals() throws Exception {
    assertEquals(0, Database.getTotalAmount(Accounts.POUCH));
    assertTrue(Database.getTopBalances(10).isEmpty());
    Files.createDirectories(DIRECTORY);
    assertEquals(0, Database.getTotalAmount(Accounts.BANK));
    assertTrue(Database.getTopBalances(10).isEmpty());
    Files.delete(DIRECTORY);
    Files.writeString(DIRECTORY, "blocked by a regular file");
    assertEquals(0, Database.getTotalAmount(Accounts.POUCH));
    assertTrue(Database.getTopBalances(10).isEmpty());
  }

  @Test
  void unreadableDirectoryHasNoTotals() throws Exception {
    Files.createDirectories(DIRECTORY);
    var store = Files.getFileStore(DIRECTORY);
    org.junit.jupiter.api.Assumptions.assumeTrue(store.supportsFileAttributeView("posix"));
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(DIRECTORY);
    try {
      Files.setPosixFilePermissions(DIRECTORY, Set.of());
      assumeFalse(Files.isReadable(DIRECTORY), "Privileged users can bypass POSIX permissions");
      assertEquals(0, Database.getTotalAmount(Accounts.POUCH));
      assertTrue(Database.getTopBalances(10).isEmpty());
    } finally {
      Files.setPosixFilePermissions(DIRECTORY, permissions);
    }
  }

  @Test
  void ioFailuresPropagateAndInvalidEntriesDoNotHideValidAccounts() throws Exception {
    UUID blocked = UUID.randomUUID();
    Files.createDirectories(DIRECTORY.resolve(blocked + ".json"));
    Files.writeString(DIRECTORY.resolve("empty.json"), "null");
    UUID valid = UUID.randomUUID();
    Database.savePlayerData(account(valid, 3, 4));
    PrintStream original = System.err;
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(errors);
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      System.setErr(capture);
      assertThrows(
          UncheckedIOException.class, () -> Database.savePlayerData(account(blocked, 8, 9)));
      assertThrows(UncheckedIOException.class, () -> Database.loadPlayerData(blocked));
      assertEquals(3, Database.getTotalAmount(Accounts.POUCH));
      OfflinePlayer player = mock(OfflinePlayer.class);
      when(player.getName()).thenReturn("Valid");
      bukkit.when(() -> Bukkit.getOfflinePlayer(valid)).thenReturn(player);
      var top = Database.getTopBalances(10);
      assertEquals(1, top.size());
      assertEquals("Valid", top.get(0).getName());
      assertEquals(7, top.get(0).getTotal());
      assertFalse(errors.toString().isBlank());
    } finally {
      System.setErr(original);
    }
  }

  private static PlayerData account(UUID id, double pouch, double bank) {
    PlayerData data = new PlayerData(id);
    data.getPouch().setBal(pouch);
    data.getBank().setBal(bank);
    return data;
  }
}
