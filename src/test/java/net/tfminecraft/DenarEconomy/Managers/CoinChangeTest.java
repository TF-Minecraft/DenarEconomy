package net.tfminecraft.DenarEconomy.Managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.tfminecraft.DenarEconomy.Managers.CoinChange.Denom;

class CoinChangeTest {

	/** The live set: pouch 100, stack 10, handful 5, gold coin 1, ingot 1 (never paid out), silver below. */
	private static final List<Denom> LIVE = List.of(
			new Denom("m.currency.pouch_of_coins", 10000, true),
			new Denom("m.currency.stack_of_coins", 1000, true),
			new Denom("m.currency.handful_of_coins", 500, true),
			new Denom("m.currency.gold_coin", 100, true),
			new Denom("v.gold_ingot", 100, false),
			new Denom("m.currency.silver_coin10", 10, true),
			new Denom("m.currency.silver_coin5", 5, true),
			new Denom("m.currency.silver_coin", 1, true));

	private static final long DENAR = 100;

	@Test
	void payingUncappedUsesLargestFirst() {
		Map<String, Long> plan = CoinChange.plan(LIVE, 12634, CoinChange.NO_LIMIT, CoinChange.NO_LIMIT);
		assertEquals(12634, CoinChange.value(LIVE, plan));
		assertEquals(1L, plan.get("m.currency.pouch_of_coins"));
		assertEquals(2L, plan.get("m.currency.stack_of_coins"));
		assertEquals(1L, plan.get("m.currency.handful_of_coins"));
		assertEquals(1L, plan.get("m.currency.gold_coin"));
	}

	@Test
	void neverPaysOutACoinMarkedNoWithdraw() {
		Map<String, Long> plan = CoinChange.plan(LIVE, 300, CoinChange.NO_LIMIT, CoinChange.NO_LIMIT);
		assertEquals(3L, plan.get("m.currency.gold_coin"));
		assertNull(plan.get("v.gold_ingot"));
	}

	@Test
	void breakingAPouchGivesTenStacks() {
		Map<String, Long> change = CoinChange.breakInto(LIVE, 10000, DENAR);
		assertEquals(Map.of("m.currency.stack_of_coins", 10L), change);
		assertEquals(10000, CoinChange.value(LIVE, change));
	}

	@Test
	void breakingAStackGivesTwoHandfuls() {
		Map<String, Long> change = CoinChange.breakInto(LIVE, 1000, DENAR);
		assertEquals(Map.of("m.currency.handful_of_coins", 2L), change);
	}

	@Test
	void breakingAHandfulGivesFiveDenars() {
		Map<String, Long> change = CoinChange.breakInto(LIVE, 500, DENAR);
		assertEquals(Map.of("m.currency.gold_coin", 5L), change);
	}

	@Test
	void aDenarWillNotBreakWhenSilverIsUnstakeable() {
		assertNull(CoinChange.breakInto(LIVE, DENAR, DENAR));
	}

	@Test
	void aDenarBreaksIntoSilverWhenNoFloorIsSet() {
		Map<String, Long> change = CoinChange.breakInto(LIVE, DENAR, CoinChange.NO_LIMIT);
		assertEquals(DENAR, CoinChange.value(LIVE, change));
		assertEquals(10L, change.get("m.currency.silver_coin10"));
	}

	@Test
	void theSmallestCoinCannotBreak() {
		assertNull(CoinChange.breakInto(LIVE, 1, CoinChange.NO_LIMIT));
	}

	@Test
	void aCapThatCannotBePaidExactlyIsRefused() {
		// 3 denars of change from coins no smaller than a handful: the handful does not divide it.
		assertNull(CoinChange.plan(LIVE, 300, 500, 500));
	}

	@Test
	void aFloorThatCannotBePaidExactlyIsRefused() {
		// 7 denars with nothing below a handful leaves 2 denars stranded.
		assertNull(CoinChange.plan(LIVE, 700, CoinChange.NO_LIMIT, 500));
	}

	@Test
	void uncappedPaymentStaysLenientWhenItCannotFinish() {
		// No floor, no cap: the 1 cent coin always closes the gap, so a plan always exists.
		Map<String, Long> plan = CoinChange.plan(LIVE, 137, CoinChange.NO_LIMIT, CoinChange.NO_LIMIT);
		assertEquals(137, CoinChange.value(LIVE, plan));
	}

	@Test
	void nothingToPayIsAnEmptyPlanNotAFailure() {
		Map<String, Long> plan = CoinChange.plan(LIVE, 0, CoinChange.NO_LIMIT, CoinChange.NO_LIMIT);
		assertTrue(plan.isEmpty());
	}

	@Test
	void breakingIsValueNeutralAcrossTheWholeStakeableSet() {
		for (long coin : new long[] { 10000, 1000, 500 }) {
			Map<String, Long> change = CoinChange.breakInto(LIVE, coin, DENAR);
			assertEquals(coin, CoinChange.value(LIVE, change), "break of " + coin);
		}
	}
}
