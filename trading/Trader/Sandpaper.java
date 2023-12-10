package Trader;

import client.OrderStatus;

import java.time.LocalDateTime;

import static Trader.TradingUtility.getESTLocalDateTimeNow;
import static java.lang.Math.pow;
import static utility.Utility.pr;

public class Sandpaper {

    public static void main(String[] args) {

//        Sandpaper s = new Sandpaper();
//
//        LocalDateTime lt1 = LocalDateTime.now();
//        LocalDateTime lt2 = LocalDateTime.now().minusSeconds(10);
//
//        pr(Duration.between(lt2, lt1).getSeconds()<11);
//
//    }
        LocalDateTime t = getESTLocalDateTimeNow();
        int id = (int) (t.getYear() * pow(10, 7) + t.getMonthValue() * pow(10, 5) + t.getDayOfMonth() * pow(10, 3) + 1);
        pr(t.getYear()*pow(10,7)+t.getMonthValue()*pow(10,5)+t.getDayOfMonth()*pow(10,3)+1);
    }
}
