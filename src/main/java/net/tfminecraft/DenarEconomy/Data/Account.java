package net.tfminecraft.DenarEconomy.Data;

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

	public boolean isTaxable(){
		return taxable;
	}
}
