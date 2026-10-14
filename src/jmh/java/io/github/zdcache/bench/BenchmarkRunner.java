package io.github.zdcache.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

public final class BenchmarkRunner {
    private BenchmarkRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: BenchmarkRunner <plan.properties> <results.json>");
        BenchmarkPlan plan = BenchmarkPlan.read(Path.of(args[0]));
        Path results = Path.of(args[1]);
        Files.createDirectories(results.getParent());
        List<RunResult> all = new ArrayList<>();
        for (String benchmark : BenchmarkPlan.BENCHMARKS) {
            all.addAll(new Runner(options(benchmark, plan.settings(benchmark)).build()).run());
        }
        ResultFormatFactory.getInstance(ResultFormatType.JSON, results.toString()).writeOut(all);
        System.out.println("Results of " + all.size() + " measurements written to " + results.toAbsolutePath());
    }

    private static ChainedOptionsBuilder options(String benchmark, BenchmarkPlan.Settings settings) {
        ChainedOptionsBuilder options = new OptionsBuilder()
            .include("io\\.github\\.zdcache\\.bench\\." + benchmark + "\\.")
            .forks(settings.forks())
            .warmupIterations(settings.warmupIterations())
            .warmupTime(TimeValue.milliseconds(settings.warmupTime().toMillis()))
            .measurementIterations(settings.measurementIterations())
            .measurementTime(TimeValue.milliseconds(settings.measurementTime().toMillis()));
        if (!settings.sizes().isEmpty()) options.param("size", settings.sizes().toArray(String[]::new));
        if (!settings.subjects().isEmpty()) options.param("subject", settings.subjects().toArray(String[]::new));
        return options;
    }
}
