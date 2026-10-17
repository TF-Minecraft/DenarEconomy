package net.tfminecraft.denareconomy.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.event.PlayerEarnMoneyEvent;
import net.tfminecraft.denareconomy.ledger.AccountIds;
import net.tfminecraft.denareconomy.ledger.Ledger;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;

/**
 * Money per character. Every balance is in whole cents and every change is a posting with an
 * operation ID: posting the same ID again returns the first result without applying anything, so
 * callers can retry safely. Use an ID derived from the event when the event can repeat, and store
 * it before any side effect that depends on it.
 *
 * <p>Account IDs: {@link #pouch}, {@link #bank}, {@link #guild}, {@link #guildTax},
 * {@link #MINT} and {@link #TAX_VOID}. Character and tax holding accounts never go below zero;
 * guild treasuries and system accounts may. A dead character's accounts refuse debits unless the
 * reason starts with {@code staff:}; DenarEconomy itself moves them to the character's estate.
 * Operation IDs and reasons starting {@code estate:} are reserved for that: every posting method
 * answers {@link Result#UNAVAILABLE} for them and records nothing.
 *
 * <p>Main thread only: every method except the account ID helpers throws
 * {@link IllegalStateException} elsewhere, before any lookup or tax policy runs.
 */
public final class Denars {
	public static final String MINT = AccountIds.MINT;
	public static final String TAX_VOID = AccountIds.TAX_VOID;

	private Denars() {
	}

	/** Whether the ledger is open and characters can be resolved. */
	public static boolean ready() {
		Ledger.requireMainThread();
		return DenarEconomy.getLedger().isOpen() && Characters.available();
	}

	/** Taxable payments wait while an installed faction provider starts or is unavailable. */
	public static boolean taxReady() {
		Ledger.requireMainThread();
		org.bukkit.plugin.Plugin factions = Bukkit.getPluginManager().getPlugin("SimpleFactions");
		return ready() && (factions == null || (factions.isEnabled()
				&& Bukkit.getServicesManager().load(TaxPolicy.class) != null));
	}

	/** The character the player is playing now, or empty when none is active. */
	public static Optional<CharacterRef> active(Player player) {
		Ledger.requireMainThread();
		return Characters.currentSession(player).map(CharacterSession::ref);
	}

	public static String pouch(CharacterRef character) {
		return AccountIds.pouch(character.characterId());
	}

	public static String bank(CharacterRef character) {
		return AccountIds.bank(character.characterId());
	}

	/** A guild treasury, by the guild's incarnation. */
	public static String guild(UUID incarnation) {
		return AccountIds.guild(incarnation);
	}

	/** Citizen tax held for a guild until its settlement. */
	public static String guildTax(UUID incarnation) {
		return AccountIds.guildTax(incarnation);
	}

	/** Balance in cents; zero when the ledger is unavailable. */
	public static long balance(String account) {
		Ledger.requireMainThread();
		return DenarEconomy.getLedger().balance(account);
	}

	/**
	 * Current held citizen tax per earning character, in cents. Excludes unattributed credits and
	 * earnings before the holding account's latest debit. Empty when the ledger is unavailable.
	 */
	public static Map<UUID, Long> taxContributors(UUID incarnation) {
		Ledger.requireMainThread();
		return DenarEconomy.getLedger().taxContributors(incarnation);
	}

	/** Posts legs that sum to zero, all or nothing. */
	public static Result post(String opId, String reason, List<Leg> legs) {
		Ledger.requireMainThread();
		return DenarEconomy.getLedger().post(opId, reason, legs);
	}

	/** Moves a positive amount between two accounts. */
	public static Result transfer(String opId, String reason, String from, String to, long cents) {
		Ledger.requireMainThread();
		requirePositive(cents);
		return post(opId, reason, List.of(new Leg(from, -cents), new Leg(to, cents)));
	}

	/** The stored result of an operation, or empty if it was never posted. */
	public static Optional<Result> status(String opId) {
		Ledger.requireMainThread();
		return DenarEconomy.getLedger().status(opId);
	}

	/** The tax on this earning under the registered {@link TaxPolicy}; zero without one. */
	public static long taxFor(CharacterRef earner, long grossCents) {
		Ledger.requireMainThread();
		return taxLeg(earner, grossCents).map(TaxLeg::cents).orElse(0L);
	}

	/**
	 * New money earned by a character, credited from the mint. When taxable, the tax goes to the
	 * policy's holding account in the same posting.
	 */
	public static Result earn(String opId, CharacterRef earner, String account, long cents, boolean taxable) {
		Ledger.requireMainThread();
		return pay(opId, "earn", MINT, earner, account, cents, taxable);
	}

	/** A sale: the buyer pays the seller, less tax on the seller's earning when taxable. */
	public static Result trade(String opId, String reason, String buyerAccount, CharacterRef seller,
			String sellerAccount, long cents, boolean taxable) {
		Ledger.requireMainThread();
		return pay(opId, reason, buyerAccount, seller, sellerAccount, cents, taxable);
	}

	private static Result pay(String opId, String reason, String from, CharacterRef earner, String to, long cents,
			boolean taxable) {
		requirePositive(cents);
		// Refused before any tax policy runs; the ledger would refuse it too.
		if (Ledger.reserved(opId) || Ledger.reserved(reason)) return Result.UNAVAILABLE;
		if (taxable && !taxReady()) return Result.UNAVAILABLE;
		List<Leg> legs = new ArrayList<>(List.of(new Leg(from, -cents)));
		Optional<TaxLeg> tax = taxable ? taxLeg(earner, cents) : Optional.empty();
		long taxCents = tax.map(TaxLeg::cents).orElse(0L);
		if (taxCents > cents) {
			throw new IllegalStateException("The tax policy asked for " + taxCents + " of " + cents);
		}
		legs.add(new Leg(to, cents - taxCents));
		tax.ifPresent(leg -> legs.add(new Leg(leg.account(), leg.cents())));
		Result result = DenarEconomy.getLedger().postEarning(opId, reason, legs, earner.characterId(), tax.orElse(null));
		if (taxable && result == Result.APPLIED) {
			Bukkit.getPluginManager().callEvent(new PlayerEarnMoneyEvent(
					Bukkit.getOfflinePlayer(earner.ownerId()).getName(), cents / 100.0, earner));
		}
		return result;
	}

	private static Optional<TaxLeg> taxLeg(CharacterRef earner, long grossCents) {
		TaxPolicy policy = Bukkit.getServicesManager().load(TaxPolicy.class);
		return policy == null ? Optional.empty() : policy.tax(earner, grossCents);
	}

	private static void requirePositive(long cents) {
		if (cents <= 0) {
			throw new IllegalArgumentException("Amount must be positive: " + cents);
		}
	}
}
