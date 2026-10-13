package net.tfminecraft.denareconomy.estate;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class EstateSupportTest {
  Plugin plugin;
  final List<LogRecord> logs = new ArrayList<>();

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
    plugin = MockBukkit.createMockPlugin();
    plugin.getLogger().addHandler(
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logs.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        });
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void anOlderTlibsWithoutTheEstateApiRegistersNothing() {
    assertFalse(EstateSupport.present("net.tfminecraft.tlibs.character.estate.Missing"));
    EstateSupport.start(plugin, "net.tfminecraft.tlibs.character.estate.Missing");
    assertNull(Bukkit.getServicesManager().load(EstateParticipant.class));
    assertTrue(logs.stream().anyMatch(r -> r.getMessage().contains("no estate API")));
  }

  @Test
  void theEstateApiRegistersTheMoneyParticipant() {
    assertTrue(EstateSupport.present(EstateSupport.CLASSES));
    EstateSupport.start(plugin);
    assertInstanceOf(
        MoneyEstates.class, Bukkit.getServicesManager().load(EstateParticipant.class));
  }
}
