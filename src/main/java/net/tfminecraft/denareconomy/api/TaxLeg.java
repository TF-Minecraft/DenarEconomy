package net.tfminecraft.denareconomy.api;

import java.util.Objects;

/** Tax on one earning: the account that holds it and the amount in cents. */
public record TaxLeg(String account, long cents) {

	public TaxLeg {
		Objects.requireNonNull(account, "account");
		if (cents < 0) {
			throw new IllegalArgumentException("Tax must not be negative");
		}
	}
}
