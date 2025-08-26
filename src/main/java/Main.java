import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {

        long start = System.currentTimeMillis();

        List<String> lines = Files.readAllLines(new File("input.csv").toPath());

        int done = 0;
        int duplicates = 0;

        for (int i = 0; i < lines.size(); i++) {

            var cols1 = lines.get(i).split(",");

            for (int j = i + 1; j < lines.size(); j++) {

                var cols2 = lines.get(j).split(",");

                if (cols1[3].equals(cols2[3]) && cols1[5].equals(cols2[5]) && cols1[27].equals(cols2[27])) {
                    duplicates++;
                }

            }

            // <editor-fold desc="Profiling">
            if (++done % 50 == 0) {
                System.out.println("Completed: " + done);

                int totalSecs = (int) ((((float) (System.currentTimeMillis() - start)) / done) * 227.930);
                int hours = totalSecs / 3600;
                int minutes = (totalSecs % 3600) / 60;
                int seconds = totalSecs % 60;

                System.out.println("Execution time: " + (System.currentTimeMillis() - start) + " ms, " +
                    "estimated execution time: " + String.format("%02d:%02d:%02d", hours, minutes, seconds));
            }
            // </editor-fold>
        }

        System.out.println("Duplicates found: " + duplicates);
        System.out.println("Execution time: " + (System.currentTimeMillis() - start) + " ms");

    }

}
