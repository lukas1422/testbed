package Trader;

import api.TradingConstants;
import client.OrderStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;

import static api.TradingConstants.f1;
import static utility.TradingUtility.*;
import static utility.Utility.pr;
import static utility.Utility.str;

public class Sandpaper {

    public static void main(String[] args) {

        Sandpaper s = new Sandpaper();
        Map<String, ConcurrentHashMap<Integer, String>> m = new ConcurrentSkipListMap<>();
        ConcurrentHashMap<Integer, String> o1 = new ConcurrentHashMap<>();
        o1.put(1, "order1");
        m.put("a", o1);

        pr(m);
        o1.remove(1);
        pr(m.isEmpty(), m.get("a").isEmpty());


    }

}
