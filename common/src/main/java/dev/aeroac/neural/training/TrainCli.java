package dev.aeroac.neural.training;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Command-line entry for the Java trainer, for training off the server with nothing but Java:
 *
 * <pre>
 * java -cp AeroAC.jar dev.aeroac.neural.training.TrainCli &lt;dataset&gt; &lt;output&gt;
 *      [--preset flash|pro] [--epochs N] [--seed N] [--threads N]
 *      [--allow-synthetic] [--include-review] [--include-staff-reviews] [--no-augment]
 * </pre>
 *
 * Same arguments and defaults as {@code python -m aeroml.training.train}.
 */
public final class TrainCli {
    private TrainCli() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: TrainCli <dataset> <output> [--preset flash|pro] [--epochs N] [--seed N] [--threads N] "
                    + "[--allow-synthetic] [--include-review] [--include-staff-reviews] [--no-augment]");
            System.exit(2);
        }
        Path dataset = Path.of(args[0]), output = Path.of(args[1]);
        JavaTrainer.Settings settings = JavaTrainer.Settings.flash();
        boolean synthetic = false, review = false, staff = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--preset" -> settings = "pro".equals(args[++i]) ? JavaTrainer.Settings.pro()
                        .withEpochs(settings.epochs()).withSeed(settings.seed()).withThreads(settings.threads()) : settings;
                case "--epochs" -> settings = settings.withEpochs(Integer.parseInt(args[++i]));
                case "--seed" -> settings = settings.withSeed(Long.parseLong(args[++i]));
                case "--threads" -> settings = settings.withThreads(Integer.parseInt(args[++i]));
                case "--allow-synthetic" -> synthetic = true;
                case "--include-review" -> review = true;
                case "--include-staff-reviews" -> staff = true;
                case "--no-augment" -> settings = settings.withAugment(false);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        settings = settings.withData(synthetic, review, staff);
        long started = System.nanoTime();
        try {
            JavaTrainer.Result result = new JavaTrainer(settings, (stage, epoch, total, loss, validation, message) ->
                    System.out.println(String.format(Locale.ROOT, "[%s] %s", stage, message)), null).run(dataset, output);
            for (String warning : result.warnings()) System.out.println("ВНИМАНИЕ: " + warning);
            System.out.println(BundleWriter.JSON.toJson(result.evaluation()));
            System.out.printf(Locale.ROOT, "Готово за %.0f с: %s (%s)%n", (System.nanoTime() - started) / 1e9,
                    result.bundle(), result.modelVersion());
        } catch (TrainingException error) {
            System.err.println("Обучение остановлено: " + error.getMessage());
            System.exit(1);
        }
    }
}
