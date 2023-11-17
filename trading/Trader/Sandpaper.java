package Trader;

import api.TradingConstants;
import client.OrderStatus;

import java.time.Duration;
import java.time.LocalDateTime;
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

        LocalDateTime lt1 = LocalDateTime.now();
        LocalDateTime lt2 = LocalDateTime.now().minusSeconds(10);

        pr(Duration.between(lt2, lt1).getSeconds());

    }

}
