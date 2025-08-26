import java.io.FileReader;
import java.util.HashSet;
import java.util.Scanner;

public class Main2 {

    public static void main(String[] args) throws Exception {

        long start = System.currentTimeMillis();

        var set = new HashSet<String>(1000);
        var duplicates = 0;

        try (var fr = new FileReader("input.csv");
             var scanner = new Scanner(fr)) {

            while (scanner.hasNextLine()) {

                var cols = scanner.nextLine().split(",");
                var hash = cols[3] + "_" + cols[5] + "_" + cols[27];

                if (!set.add(hash)) {
                    duplicates++;
                }

            }

        }

        System.out.println("Duplicates found: " + duplicates);
        System.out.println("Execution time: " + (System.currentTimeMillis() - start) + " ms");

    }

}
