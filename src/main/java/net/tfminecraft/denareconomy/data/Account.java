package net.tfminecraft.denareconomy.data;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class Account {
	private BigDecimal amount;
	private boolean taxable = true;
	
	public Account(double amount) {
		this.amount = BigDecimal.valueOf(amount);
		this.amount = this.amount.setScale(2, RoundingMode.HALF_UP);
	}
	
	public Account(double amount, boolean tax) {
		this.amount = BigDecimal.valueOf(amount);
		this.amount = this.amount.setScale(2, RoundingMode.HALF_UP);
		this.taxable = tax;
	}
	
	public double getBal() {
		return amount.doubleValue();
	}
	
	public void setBal(double amount) {
		this.amount = BigDecimal.valueOf(amount);
	}
	
	public void change(double a) {
		BigDecimal change = BigDecimal.valueOf(a);
		amount = amount.add(change);
		amount = amount.setScale(2, RoundingMode.HALF_UP);
	}

	/** Apply a change and restore the exact prior balance if its persistence action fails. */
	public void change(double delta, Runnable persist) {
		BigDecimal previous = amount;
		change(delta);
		try {
			persist.run();
		} catch (RuntimeException failure) {
			amount = previous;
			throw failure;
		}
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
}
