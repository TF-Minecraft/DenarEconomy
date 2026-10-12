package net.tfminecraft.denareconomy.api;

import java.util.Optional;

import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * Decides the tax on a taxable earning. Register one in Bukkit's {@code ServicesManager}; without
 * one, earnings are untaxed. Called on the main thread inside {@link Denars#earn} and
 * {@link Denars#trade}, before the posting, so the tax leg commits with the earning.
 */
public interface TaxPolicy {

	/**
	 * The holding account and tax for this earning, or empty for none. The tax must not exceed
	 * {@code grossCents}.
	 */
	Optional<TaxLeg> tax(CharacterRef earner, long grossCents);
}
