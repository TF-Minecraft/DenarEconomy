package net.tfminecraft.denareconomy.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.accounts.OfflineModifier;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.database.Database;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** /deco give against the real offline account path: the saved file changes, nothing is cached. */
class GiveCommandOfflineTest {
  private static final Path NAMES = Path.of("plugins/DenarEconomy/Data/player-names.json");
  private final UUID id = UUID.randomUUID();
  private Path account;
  private Path namesBackup;
  private Object previousBook;
  private Object previousNames;
  private DenarEconomy previousPlugin;

  @BeforeEach
  void isolate() throws Exception {
    previousBook = field("book").get(null);
    previousNames = field("names").get(null);
    field("book").set(null, null);
    field("names").set(null, null);
    previousPlugin = DenarEconomy.plugin;
    if (Files.exists(NAMES)) {
      namesBackup = Files.createTempDirectory("denar-give-names").resolve("file");
      Files.move(NAMES, namesBackup);
    }
    account = Path.of("plugins/DenarEconomy/PlayerData", id + ".json");
  }

  @AfterEach
  void restore() throws Exception {
    field("book").set(null, previousBook);
    field("names").set(null, previousNames);
    DenarEconomy.plugin = previousPlugin;
    Files.deleteIfExists(account);
    Files.deleteIfExists(NAMES);
    if (namesBackup != null) {
      Files.move(namesBackup, NAMES);
      Files.delete(namesBackup.getParent());
    }
  }

  @Test
  void offlinePlayerIsPaidIntoTheirSavedAccount() {
    PlayerData saved = new PlayerData(id);
    saved.getPouch().setBal(1.1);
    saved.getBank().setBal(1);
    Database.savePlayerData(saved);
    CommandSender console = mock(CommandSender.class);
    Command deco = mock(Command.class);
    when(deco.getName()).thenReturn("deco");
    DenarEconomy.plugin = mock(DenarEconomy.class);
    when(DenarEconomy.plugin.getLogger()).thenReturn(mock(Logger.class));

    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        MockedStatic<DenarEconomy> economy = mockStatic(DenarEconomy.class);
        MockedStatic<MessageLoader> messages = mockStatic(MessageLoader.class)) {
      // No server and no player manager: Alex is known only by name and saved file.
      OfflineModifier.remember("Alex", id);
      CommandManager commands = new CommandManager();
      assertTrue(commands.onCommand(console, deco, "deco", new String[] {"give", "alex", "2.5"}));
      assertTrue(
          commands.onCommand(
              console, deco, "deco", new String[] {"give", "Alex", "0.04", "pouch"}));
      messages.verify(
          () -> MessageLoader.send(eq(console), eq("give.sent"), any(Object[].class)), times(2));
    }

    PlayerData reloaded = Database.loadPlayerData(id);
    assertEquals(3.5, reloaded.getBank().getBal());
    assertEquals(1.14, reloaded.getPouch().getBal());
  }

  private static Field field(String name) throws Exception {
    Field field = OfflineModifier.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }
}
