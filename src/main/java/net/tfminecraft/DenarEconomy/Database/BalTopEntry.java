package net.tfminecraft.DenarEconomy.Database;

public class BalTopEntry {
    private final String name;
    private final double total;

    public BalTopEntry(String name, double total) {
        this.name = name;
        this.total = total;
    }

    public String getName() {
        return name;
    }

    public double getTotal() {
        return total;
    }
}

