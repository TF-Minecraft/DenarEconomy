package net.tfminecraft.denareconomy.ledger;

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
import net.tfminecraft.denareconomy.managers.MoneyManager;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
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
 *
 * <p>It lives beside {@link Ledger} so that the sweep stays package-private.
 */
public final class MoneyEstates implements EstateParticipant {
	public static final String ID = "denareconomy";
	static final long HOUR_TICKS = 20L * 60 * 60;

	/** The outcome last reported to the estate service for one death. */
	private record Answer(long revision, Outcome outcome) {
	}

	/** Where the money goes now, or the outcome that stops the sweep. */
	private record Target(String account, Outcome stop) {
		static Target to(String account) {
			return new Target(account, null);
		}

		static Target stop(Outcome outcome) {
			return new Target(null, outcome);
		}
	}

	private final Logger log;
	private final Map<UUID, Answer> reported = new HashMap<>();

	MoneyEstates(Logger log) {
		this.log = log;
	}

	/** Registers the participant and schedules its sweeps. */
	public static MoneyEstates register(Plugin plugin) {
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
		if (!Estates.guardHolds(decision)) {
			return Outcome.retry("The estate guard does not hold");
		}
		Optional<String> account = decision.money().account();
		if (account.isEmpty()) {
			// FROZEN: the money stays on the dead character's accounts for staff.
			return Outcome.done();
		}
		Ledger ledger = DenarEconomy.getLedger();
		UUID testator = decision.testator().characterId();
		boolean empty = ledger.balance(AccountIds.pouch(testator)) == 0 && ledger.balance(AccountIds.bank(testator)) == 0;
		if (!ledger.isOpen()) {
			return unavailable();
		}
		if (empty) {
			// Nothing to move, so the recipient does not matter: no guild check can block or delay it.
			return done(ledger, decision.deathId());
		}
		Target target = target(decision, account.get());
		if (target.stop() != null) {
			return target.stop();
		}
		Ledger.Sweep sweep;
		try {
			sweep = ledger.sweepEstate(decision.deathId(), decision.testator().characterId(), target.account());
		} catch (IllegalArgumentException e) {
			return notAnAccount(target.account());
		}
		return switch (sweep.result()) {
			case APPLIED -> done(ledger, decision.deathId());
			case REFUSED -> Outcome.retry("The ledger refused the sweep " + sweep.opId().orElse("of an overflowing total"));
			case UNAVAILABLE -> unavailable();
			case CONFLICT -> Outcome.blocked("Sweep " + sweep.opId().orElse("") + " conflicts with a stored operation");
		};
	}

	@Override
	public Holdings describe(UUID characterId) {
		Ledger ledger = DenarEconomy.getLedger();
		long pouch = ledger.balance(AccountIds.pouch(characterId));
		long bank = ledger.balance(AccountIds.bank(characterId));
		if (pouch == 0 && bank == 0) {
			return Holdings.NONE;
		}
		return new Holdings(Map.of("cents", add(cap(pouch), bank)), List.of("Money: " + MoneyManager.denars(pouch)
				+ " in the pouch, " + MoneyManager.denars(bank) + " in the bank"));
	}

	/**
	 * Sweeps every decided estate whose guard holds and reports each outcome that differs from the
	 * last one reported for that death. One failing estate does not stop the others, and an outcome
	 * counts as reported only once the estate service took it.
	 */
	void reconcile() {
		for (EstateDecision decision : Estates.decided()) {
			try {
				if (!Estates.guardHolds(decision)) {
					continue;
				}
				Answer answer = new Answer(decision.revision(), settle(decision));
				if (!answer.equals(reported.get(decision.deathId()))
						&& Estates.report(decision.deathId(), ID, decision.revision(), answer.outcome())) {
					reported.put(decision.deathId(), answer);
				}
			} catch (RuntimeException e) {
				log.log(Level.WARNING, "Could not sweep the estate of death " + decision.deathId(), e);
			}
		}
	}

	/** The account to sweep to: a character's own account, or a guild treasury that still exists. */
	private static Target target(EstateDecision decision, String account) {
		AccountIds.Parts parts;
		try {
			parts = AccountIds.parse(account);
		} catch (IllegalArgumentException e) {
			return Target.stop(notAnAccount(account));
		}
		if (parts.kind().equals("char")) {
			return Target.to(account);
		}
		if (!parts.kind().equals("guild")) {
			return Target.stop(Outcome.blocked("The money recipient " + account + " is not a character or guild"));
		}
		UUID guild = UUID.fromString(parts.owner());
		GuildLookup lookup = Estates.guild(guild);
		if (lookup instanceof GuildLookup.Unavailable) {
			return Target.stop(Outcome.retry("SimpleFactions cannot say whether guild " + guild + " still exists"));
		}
		if (lookup instanceof GuildLookup.Live) {
			return Target.to(account);
		}
		Optional<UUID> realm = Optional.empty();
		if (decision.deathGuild().equals(Optional.of(guild))) {
			realm = decision.deathRealm();
			if (realm.isEmpty()) {
				// Unknown at the decision; SimpleFactions may know it now.
				DeathGuild died = Estates.deathGuild(decision.testator().characterId(), decision.deathId());
				if (died instanceof DeathGuild.Unavailable) {
					return Target.stop(Outcome.retry("SimpleFactions cannot say which realm guild " + guild + " was in"));
				}
				if (died instanceof DeathGuild.Guild found && found.incarnation().equals(guild)) {
					realm = found.realm();
				}
			}
		}
		realm = realm.filter(id -> !id.equals(guild));
		if (realm.isEmpty()) {
			return Target.stop(Outcome.blocked("Guild " + guild + " is gone and has no realm to take its money"));
		}
		GuildLookup realmLookup = Estates.guild(realm.get());
		if (realmLookup instanceof GuildLookup.Live live) {
			return Target.to(live.guild().treasuryAccount());
		}
		if (realmLookup instanceof GuildLookup.Unavailable) {
			return Target.stop(Outcome.retry("SimpleFactions cannot say whether realm " + realm.get() + " still exists"));
		}
		return Target.stop(Outcome.blocked("Guild " + guild + " and its realm " + realm.get() + " are gone"));
	}

	private static Outcome unavailable() {
		return Outcome.retry("The ledger or the character directory is unavailable");
	}

	private static Outcome notAnAccount(String account) {
		return Outcome.blocked("The money recipient " + account + " is not a ledger account");
	}

	/** Every applied sweep of this death so far: cents moved, postings and their operation IDs. */
	private static Outcome done(Ledger ledger, UUID deathId) {
		List<Ledger.EstatePosting> applied = ledger.estatePostings(deathId).stream()
				.filter(posting -> posting.result() == Result.APPLIED).toList();
		int cents = 0;
		for (Ledger.EstatePosting posting : applied) {
			cents = add(cents, posting.cents());
		}
		return Outcome.done(Map.of("cents", cents, "postings", applied.size()),
				applied.stream().map(Ledger.EstatePosting::opId).toList());
	}

	/** Cents for estate counts, which hold ints: added without overflow and capped. */
	private static int add(int total, long cents) {
		return (int) Math.min(Integer.MAX_VALUE, (long) total + cap(cents));
	}

	private static int cap(long cents) {
		return (int) Math.min(Integer.MAX_VALUE, cents);
	}
}
