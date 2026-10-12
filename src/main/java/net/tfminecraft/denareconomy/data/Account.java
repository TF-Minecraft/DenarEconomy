package net.tfminecraft.denareconomy.data;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A balance in denars. Balances handed out by DenarEconomy are read-only snapshots of the ledger:
 * their mutators throw {@link UnsupportedOperationException}. Post changes through
 * {@code net.tfminecraft.denareconomy.api.Denars}.
 */
public class Account {
	private BigDecimal amount;
	private boolean taxable = true;
	private boolean readOnly;
	
	public Account(double amount) {
		this.amount = BigDecimal.valueOf(amount);
		this.amount = this.amount.setScale(2, RoundingMode.HALF_UP);
	}
	
	public Account(double amount, boolean tax) {
		this.amount = BigDecimal.valueOf(amount);
		this.amount = this.amount.setScale(2, RoundingMode.HALF_UP);
		this.taxable = tax;
	}
	
	/** A read-only copy of a ledger balance. */
	public static Account snapshot(long cents, boolean taxable) {
		Account account = new Account(0, taxable);
		account.amount = BigDecimal.valueOf(cents, 2);
		account.readOnly = true;
		return account;
	}

	public double getBal() {
		return amount.doubleValue();
	}
	
	public void setBal(double amount) {
		writable();
		this.amount = BigDecimal.valueOf(amount);
	}
	
	public void change(double a) {
		writable();
		BigDecimal change = BigDecimal.valueOf(a);
		amount = amount.add(change);
		amount = amount.setScale(2, RoundingMode.HALF_UP);
	}

	/** Whether a transfer amount is positive, finite and an exact number of cents. */
	public static boolean isValidTransferAmount(BigDecimal value) {
		return value != null && value.signum() > 0 && Double.isFinite(value.doubleValue())
			&& value.stripTrailingZeros().scale() <= 2;
	}

	/**
	 * Moves the same exact amount between accounts without rounding either balance.
	 * Invalid amounts and insufficient funds leave both accounts unchanged.
	 */
	public boolean transferTo(Account destination, BigDecimal value) {
		if (destination == null || !isValidTransferAmount(value)) return false;
		writable();
		destination.writable();
		BigDecimal transfer = value.setScale(2, RoundingMode.UNNECESSARY);
		if (amount.compareTo(transfer) < 0) return false;
		if (destination == this) return true;
		BigDecimal remaining = amount.subtract(transfer);
		BigDecimal received = destination.amount.add(transfer);
		amount = remaining;
		destination.amount = received;
		return true;
	}

	public boolean isTaxable(){
		return taxable;
	}

	private void writable() {
		if (readOnly) {
			throw new UnsupportedOperationException("Balances live in the DenarEconomy ledger; post changes through Denars");
		}
	}
}
