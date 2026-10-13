package net.tfminecraft.denareconomy.estate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.ledger.AccountIds;
import net.tfminecraft.denareconomy.ledger.Ledger;
import net.tfminecraft.denareconomy.managers.MoneyManager;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.GuildLookup;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;

/**
 * Moves a dead character's pouch and bank to the estate's money recipient. The same sweep runs for
 * every decided estate at the first tick and then hourly, so money credited after the decision
 * (wages, refunds, sales) follows it. Main thread only.
 */
public final class MoneyEstates implements EstateParticipant {
	public static final String ID = "denareconomy";
	static final long HOUR_TICKS = 20L * 60 * 60;

	/** The outcome last handed to the estate service for one death. */
	private record Answer(long revision, Outcome outcome) {
	}

	private final Logger log;
	private final Map<UUID, Answer> answered = new HashMap<>();

	MoneyEstates(Logger log) {
		this.log = log;
	}

	/** Registers the participant and schedules its sweeps. */
	static MoneyEstates register(Plugin plugin) {
		MoneyEstates estates = new MoneyEstates(plugin.getLogger());
		Bukkit.getServicesManager().register(EstateParticipant.class, estates, plugin, ServicePriority.Normal);
		Bukkit.getScheduler().runTaskTimer(plugin, estates::reconcile, 1L, HOUR_TICKS);
		return estates;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public Outcome settle(EstateDecision decision) {
		Outcome outcome = sweep(decision);
		answered.put(decision.deathId(), new Answer(decision.revision(), outcome));
		return outcome;
	}

	@Override
	public Holdings describe(UUID characterId) {
		Ledger ledger = DenarEconomy.getLedger();
		long pouch = ledger.balance(AccountIds.pouch(characterId));
		long bank = ledger.balance(AccountIds.bank(characterId));
		if (pouch == 0 && bank == 0) {
			return Holdings.NONE;
		}
		return new Holdings(Map.of("cents", cents(pouch, bank)), List.of("Money: " + MoneyManager.denars(pouch)
				+ " in the pouch, " + MoneyManager.denars(bank) + " in the bank"));
	}

	/**
	 * Sweeps every decided estate whose guard holds and reports each outcome that changed since
	 * the last one this process gave for that death.
	 */
	void reconcile() {
		for (EstateDecision decision : Estates.decided()) {
			try {
				if (!Estates.guardHolds(decision)) {
					continue;
				}
				Answer before = answered.get(decision.deathId());
				Outcome outcome = settle(decision);
				if (!new Answer(decision.revision(), outcome).equals(before)) {
					Estates.report(decision.deathId(), ID, decision.revision(), outcome);
				}
			} catch (RuntimeException e) {
				log.log(Level.WARNING, "Could not sweep the estate of death " + decision.deathId(), e);
			}
		}
	}

	private Outcome sweep(EstateDecision decision) {
		if (!Estates.guardHolds(decision)) {
			return Outcome.retry("The estate guard does not hold");
		}
		Optional<String> account = decision.money().account();
		if (account.isEmpty()) {
			// FROZEN: the money stays on the dead character's accounts for staff.
			return Outcome.done();
		}
		AccountIds.Parts parts;
		try {
			parts = AccountIds.parse(account.get());
		} catch (IllegalArgumentException e) {
			return Outcome.blocked("The money recipient " + account.get() + " is not a ledger account");
		}
		String recipient = account.get();
		if (parts.kind().equals("guild")) {
			UUID guild = UUID.fromString(parts.owner());
			GuildLookup lookup = Estates.guild(guild);
			if (lookup instanceof GuildLookup.Unavailable) {
				return Outcome.retry("SimpleFactions cannot say whether guild " + guild + " still exists");
			}
			if (lookup instanceof GuildLookup.Gone) {
				Optional<UUID> realm = decision.deathRealm()
						.filter(id -> decision.deathGuild().equals(Optional.of(guild)) && !id.equals(guild));
				if (realm.isEmpty()) {
					return Outcome.blocked("Guild " + guild + " is gone and has no realm to take its money");
				}
				GuildLookup realmLookup = Estates.guild(realm.get());
				if (realmLookup instanceof GuildLookup.Unavailable) {
					return Outcome.retry("SimpleFactions cannot say whether realm " + realm.get() + " still exists");
				}
				if (realmLookup instanceof GuildLookup.Gone) {
					return Outcome.blocked("Guild " + guild + " and its realm " + realm.get() + " are gone");
				}
				recipient = AccountIds.guild(realm.get());
			}
		}
		Ledger ledger = DenarEconomy.getLedger();
		Ledger.Sweep sweep = ledger.sweepEstate(decision.deathId(), decision.testator().characterId(), recipient);
		return switch (sweep.result()) {
			case APPLIED -> done(ledger, decision.deathId());
			case REFUSED -> Outcome.retry("The ledger refused the sweep " + sweep.opId().orElse("of an overflowing total"));
			case UNAVAILABLE -> Outcome.retry("The ledger or the character directory is unavailable");
			case CONFLICT -> Outcome.blocked("Sweep " + sweep.opId().orElse("") + " conflicts with a stored operation");
		};
	}

	/** Every applied sweep of this death so far: cents moved, postings and their operation IDs. */
	private static Outcome done(Ledger ledger, UUID deathId) {
		List<Ledger.EstatePosting> applied = ledger.estatePostings(deathId).stream()
				.filter(posting -> posting.result() == Result.APPLIED).toList();
		long cents = applied.stream().mapToLong(Ledger.EstatePosting::cents).sum();
		return Outcome.done(Map.of("cents", cents(cents, 0), "postings", applied.size()),
				applied.stream().map(Ledger.EstatePosting::opId).toList());
	}

	/** A count of cents for estate records, which hold ints: capped, never negative. */
	private static int cents(long a, long b) {
		return (int) Math.min(Integer.MAX_VALUE, Math.min(a, Integer.MAX_VALUE) + Math.min(b, Integer.MAX_VALUE));
	}
}
