package net.tfminecraft.DenarEconomy.Managers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The denomination arithmetic behind withdrawals and change, with no Bukkit in sight so it can be
 * tested directly.
 *
 * <p>Everything is in whole cents. Repeated double subtraction used to drift and drop a coin on
 * roughly half of all amounts, always by 0.01.
 */
public final class CoinChange {

	private CoinChange() {
	}

	/** One configured denomination. Value is in cents; withdraw false means accepted but never paid out. */
	public record Denom(String item, long cents, boolean withdraw) {
	}

	/** No cap or floor. */
	public static final long NO_LIMIT = 0;

	/**
	 * How many of each denomination make up this many cents, largest first.
	 *
	 * @param sorted denominations in descending value order
	 * @param cents  the amount to pay
	 * @param maxCents largest coin allowed, or {@link #NO_LIMIT}
	 * @param minCents smallest coin allowed, or {@link #NO_LIMIT}
	 * @return counts per item id, or null when the allowed coins cannot make the amount exactly
	 */
	public static Map<String, Long> plan(List<Denom> sorted, long cents, long maxCents, long minCents) {
		if (cents <= 0) return new HashMap<>();
		Map<String, Long> counts = new HashMap<>();
		long remaining = cents;
		long max = maxCents > 0 ? maxCents : Long.MAX_VALUE;
		long min = Math.max(0, minCents);

		for (Denom d : sorted) {
			if (remaining <= 0) break;
			if (!d.withdraw()) continue;
			if (d.cents() <= 0 || d.cents() > max || d.cents() < min) continue;

			long count = remaining / d.cents();
			if (count <= 0) continue;
			remaining -= count * d.cents();
			counts.merge(d.item(), count, Long::sum);
		}

		// A cap or a floor means some amounts simply cannot be paid. Refuse rather than hand over
		// less than was asked for. Uncapped withdrawals stay lenient, as they always were.
		boolean limited = max < Long.MAX_VALUE || min > 0;
		if (remaining > 0 && limited) return null;
		return counts;
	}

	/**
	 * Strictly smaller coins worth the same as one coin of coinCents, or null when that cannot be
	 * done without going below minCents.
	 */
	public static Map<String, Long> breakInto(List<Denom> sorted, long coinCents, long minCents) {
		// A cap of 0 would read as "no cap", and the smallest possible coin has nothing below it.
		if (coinCents <= 1 || coinCents <= minCents) return null;
		// Strictly smaller, so a break always makes progress and can never loop.
		Map<String, Long> counts = plan(sorted, coinCents, coinCents - 1, minCents);
		if (counts == null || counts.isEmpty()) return null;
		return counts;
	}

	/** Total value of a plan, for asserting that change adds up. */
	public static long value(List<Denom> sorted, Map<String, Long> counts) {
		long total = 0;
		for (Denom d : sorted) {
			Long count = counts.get(d.item());
			if (count != null) total += d.cents() * count;
		}
		return total;
	}
}
