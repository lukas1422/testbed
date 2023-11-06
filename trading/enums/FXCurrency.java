package enums;

import java.util.HashMap;
import java.util.Map;

public enum FXCurrency {
    USD("USD"), CNY("CNY"), HKD("HKD"), CNH("CNH"), CAD("CAD");

    String currName;

    FXCurrency(String curr) {
        currName = curr;
    }

    private static final Map<String, FXCurrency> lookup = new HashMap<>();

    static {
        for (FXCurrency c : FXCurrency.values()) {
            lookup.put(c.getCurrName(), c);
        }
    }

    public static FXCurrency get(String curr) {
        if (curr.equalsIgnoreCase("CNH")) {
            return lookup.get("CNY");
        }

        if (lookup.containsKey(curr)) {
            return lookup.get(curr);
        }
        throw new IllegalArgumentException(" cannot find ticker ");
    }

    String getCurrName() {
        return currName;
    }
}
