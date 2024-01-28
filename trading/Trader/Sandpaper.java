package Trader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static api.TradingConstants.RELATIVEPATH;
import static utility.Utility.pr;

public class Sandpaper {

    public static void main(String[] args) throws IOException {


        Files.lines(Paths.get(RELATIVEPATH + "interestListUS")).map(l -> l.split(" "))
                .forEach(a -> pr(a));
    }
}
