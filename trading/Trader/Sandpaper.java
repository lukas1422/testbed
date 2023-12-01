package Trader;

import client.OrderStatus;

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

        OrderStatus a = OrderStatus.Filled;
        pr(a.isFinished());
    }
}
