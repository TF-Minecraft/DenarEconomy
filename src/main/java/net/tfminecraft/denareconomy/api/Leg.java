package net.tfminecraft.denareconomy.api;

import java.util.Objects;

/** One account's share of a posting, in cents: negative debits, positive credits. */
public record Leg(String account, long cents) {

	public Leg {
		Objects.requireNonNull(account, "account");
	}
}
