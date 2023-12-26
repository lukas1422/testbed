package Trader;

import client.OrderStatus;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;

import static Trader.TradingUtility.getESTLocalDateTimeNow;
import static java.lang.Math.pow;
import static utility.Utility.pr;

public class Sandpaper {

    public static void main(String[] args) {

        LinkedHashMap<Integer, Integer> m = new LinkedHashMap<>();
        m.put(1, 1);
        pr(m);
        pr(add(m));
        pr(m);

    }

    static void modify(LinkedHashMap<Integer, String> n) {
        n.put(2, "two");
    }

    static int add(LinkedHashMap<Integer, Integer> n) {
        n.put(1, 2);
        return n.get(1) + 3;
    }
}
