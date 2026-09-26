package net.tfminecraft.denareconomy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.tfminecraft.denareconomy.drop.Drop;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.loaders.*;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationTest {
  @TempDir Path dir;
  DenarEconomy previous;
  List<Coin> previousCoins;
  java.util.Map<Material, Drop> previousDrops;
  double previousFortune;
  Object previousMessages;
  Object previousPrefix;

  private static java.lang.reflect.Field messageField(String name) throws Exception {
    var field = MessageLoader.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  @BeforeEach
  void setup() throws Exception {
    previous = DenarEconomy.plugin;
    previousCoins = new ArrayList<>(CoinLoader.coins);
    previousDrops = new java.util.HashMap<>(DropLoader.drops);
    previousFortune = DropLoader.fortuneStrength;
    previousMessages = messageField("config").get(null);
    previousPrefix = messageField("prefix").get(null);
    DenarEconomy.plugin = mock(DenarEconomy.class);
    when(DenarEconomy.plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
  }

  @AfterEach
  void cleanup() throws Exception {
    DenarEconomy.plugin = previous;
    CoinLoader.coins.clear();
    CoinLoader.coins.addAll(previousCoins);
    DropLoader.drops.clear();
    DropLoader.drops.putAll(previousDrops);
    DropLoader.fortuneStrength = previousFortune;
    messageField("config").set(null, previousMessages);
    messageField("prefix").set(null, previousPrefix);
  }

  Path yaml(String text) throws Exception {
    return Files.writeString(dir.resolve("config.yml"), text);
  }

  @Test
  void coinConfigurationReloadsSortsAndFindsWithdrawableCentValues() throws Exception {
    new CoinLoader()
        .loadCoins(
            yaml("""
                 small:
                   item: v.iron_nugget
                   value: 0.01
                 large:
                   item: v.gold_nugget
                   value: 1.0
                 material:
                   item: v.diamond
                   value: 5
                   withdraw: false
                 """)
                .toFile());
    assertEquals(3, CoinLoader.get().size());
    Coin small = CoinLoader.getByString("SMALL");
    assertEquals("small", small.getId());
    assertTrue(small.canWithdraw());
    assertEquals("v.iron_nugget", small.getItem());
    assertEquals(.01, small.getValue());
    assertNull(CoinLoader.getByString("missing"));
    assertEquals(
        List.of(5., 1., .01), CoinLoader.getSortedCoins().stream().map(Coin::getValue).toList());
    assertSame(small, CoinLoader.getWithdrawableByValue(.014));
    assertNull(CoinLoader.getWithdrawableByValue(5));
    small.setId("renamed");
    small.setItem("v.stone");
    small.setValue(null);
    assertEquals("renamed", small.getId());
    assertEquals("v.stone", small.getItem());
    assertNull(CoinLoader.getWithdrawableByValue(.01));
    new CoinLoader().loadCoins(dir.resolve("missing.yml").toFile());
    assertTrue(CoinLoader.get().isEmpty());
    new CoinLoader().loadCoins(yaml("bad: [").toFile());
    assertTrue(CoinLoader.get().isEmpty());
  }

  @Test
  void dropsUseDefaultsAndSkipNonSections() throws Exception {
    new DropLoader()
        .load(
            yaml("""
                 fortune-strength: 0.75
                 metadata: ignored
                 wheat:
                   chance: 1
                   amount: 1.0-2.0
                 unknown-block: {}
                 """)
                .toFile());
    assertEquals(.75, DropLoader.fortuneStrength);
    assertEquals(2, DropLoader.get().size());
    assertNull(DropLoader.getByBlock(Material.AIR));
    Drop wheat = DropLoader.getByBlock(Material.WHEAT);
    assertEquals("wheat", wheat.getId());
    assertEquals(Material.WHEAT, wheat.getBlock());
    assertEquals(1, wheat.getChance());
    for (int n = 0; n < 30; n++) {
      double amount = wheat.getAmount();
      assertTrue(amount >= 1 && amount <= 2);
      assertEquals(Math.round(amount * 100) / 100., amount);
    }
    Drop fallback = DropLoader.getByBlock(Material.BEDROCK);
    assertEquals(.5, fallback.getChance());
    assertTrue(fallback.getAmount() >= .3);
    new DropLoader().load(dir.resolve("missing.yml").toFile());
    assertTrue(DropLoader.get().isEmpty());
    assertEquals(.33, DropLoader.fortuneStrength);
    new DropLoader().load(yaml("bad: [").toFile());
    assertTrue(DropLoader.get().isEmpty());
  }

  @Test
  void messagesMergeDefaultsSubstituteAndSend() throws Exception {
    when(DenarEconomy.plugin.getResource("messages.yml"))
        .thenAnswer(
            inv ->
                new java.io.ByteArrayInputStream(
                    "fallback: '&aBundled'\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    new MessageLoader()
        .load(yaml("prefix: '&6Bank'\nhello: '%prefix% &a%name% %amount%'\n").toFile());
    assertEquals(
        "§6Bank §aAlex 5.0", MessageLoader.get("hello", "name", "Alex", "amount", 5., "unpaired"));
    assertEquals("§aBundled", MessageLoader.get("fallback"));
    assertEquals("missing", MessageLoader.get("missing"));
    CommandSender recipient = mock(CommandSender.class);
    MessageLoader.send(recipient, "hello", "name", "Alex", "amount", 5);
    verify(recipient).sendMessage("§6Bank §aAlex 5");
    new MessageLoader().load(dir.resolve("absent.yml").toFile());
    assertEquals("§aBundled", MessageLoader.get("fallback"));
    when(DenarEconomy.plugin.getResource("messages.yml")).thenReturn(null);
    new MessageLoader().load(yaml("plain: hello").toFile());
    assertEquals("hello", MessageLoader.get("plain"));
  }
}
