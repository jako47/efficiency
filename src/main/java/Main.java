import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    public static void main(String[] args) throws Exception {

        long start = System.currentTimeMillis();

        List<String> lines = Files.readAllLines(new File("input.csv").toPath());

        int done = 0;
        var duplicates = new AtomicInteger(0);

        lines.forEach(line -> {

            var cols1 = line.split(",");

            lines.forEach(line2 -> {

                var cols2 = line2.split(",");

                if (cols1[3].equals(cols2[3]) && cols1[5].equals(cols2[5]) && cols1[27].equals(cols2[27])) {
                    duplicates.getAndAdd(1);
                }

            });

        });

        System.out.println("Duplicates found: " + duplicates);
        System.out.println("Execution time: " + (System.currentTimeMillis() - start) + " ms");

    }

}
