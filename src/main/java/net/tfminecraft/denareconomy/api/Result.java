package net.tfminecraft.denareconomy.api;

/** What became of a posting. */
public enum Result {
	/** Every leg was applied, now or by an earlier call with the same operation ID. */
	APPLIED,
	/** Nothing changed: an account would go below zero, a frozen account was debited or a sum overflowed. Retrying the same operation ID gives REFUSED again. */
	REFUSED,
	/** The operation ID was already used with different legs or reason. The stored result stands. */
	CONFLICT,
	/** The ledger is closed or failed, or the operation is reserved for estates. Nothing was recorded. */
	UNAVAILABLE
}
