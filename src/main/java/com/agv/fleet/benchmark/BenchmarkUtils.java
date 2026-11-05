package com.agv.fleet.benchmark;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility helper for benchmarks to gather machine environment metadata,
 * format console output tables, and persist results to CSV files in docs/benchmarks/.
 */
public final class BenchmarkUtils {

    private BenchmarkUtils() {}

    public static String getCpuInfo() {
        String proc = System.getenv("PROCESSOR_IDENTIFIER");
        int cores = Runtime.getRuntime().availableProcessors();
        if (proc != null && !proc.isBlank()) {
            return proc.trim() + " (" + cores + " cores)";
        }
        return System.getProperty("os.arch") + " (" + cores + " cores)";
    }

    public static String getRamInfo() {
        long maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long totalMb = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        return String.format("%d MB allocated (Max JVM: %d MB)", totalMb, maxMb);
    }

    public static String getJdkInfo() {
        return System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
    }

    public static String getOsInfo() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")";
    }

    /**
     * Prints system environment metadata banner to standard output.
     */
    public static void printSystemInfoBanner() {
        System.out.println("================================================================================");
        System.out.println("                     BENCHMARK SYSTEM ENVIRONMENT                               ");
        System.out.println("================================================================================");
        System.out.printf("  CPU: %s%n", getCpuInfo());
        System.out.printf("  RAM: %s%n", getRamInfo());
        System.out.printf("  JDK: %s%n", getJdkInfo());
        System.out.printf("  OS : %s%n", getOsInfo());
        System.out.printf("  Timestamp: %s%n", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println("================================================================================");
    }

    /**
     * Writes benchmark results into a CSV file inside the docs/benchmarks/ directory.
     *
     * @param fileName relative file name, e.g. "latency_benchmark.csv"
     * @param headers  CSV column headers
     * @param rows     data rows
     */
    public static File writeCsv(String fileName, List<String> headers, List<List<String>> rows) {
        File dir = new File("docs/benchmarks");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File csvFile = new File(dir, fileName);

        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile, StandardCharsets.UTF_8))) {
            writer.println("# System Configuration");
            writer.println("# CPU: " + getCpuInfo());
            writer.println("# RAM: " + getRamInfo());
            writer.println("# JDK: " + getJdkInfo());
            writer.println("# OS: " + getOsInfo());
            writer.println("# Date: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            writer.println(String.join(",", headers));

            for (List<String> row : rows) {
                writer.println(String.join(",", row));
            }
        } catch (IOException e) {
            System.err.println("Failed to write benchmark CSV to " + csvFile.getAbsolutePath() + ": " + e.getMessage());
        }

        return csvFile;
    }

    /**
     * Prints an ASCII formatted table to standard console.
     *
     * @param title   title of benchmark table
     * @param headers column names
     * @param rows    row contents
     */
    public static void printTable(String title, List<String> headers, List<List<String>> rows) {
        int cols = headers.size();
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) {
            widths[i] = headers.get(i).length();
        }
        for (List<String> row : rows) {
            for (int i = 0; i < cols && i < row.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }

        System.out.println("\n--- " + title + " ---");

        // Format string
        StringBuilder formatBuilder = new StringBuilder();
        StringBuilder separatorBuilder = new StringBuilder();
        for (int i = 0; i < cols; i++) {
            formatBuilder.append("| %-").append(widths[i]).append("s ");
            separatorBuilder.append("+").append("-".repeat(widths[i] + 2));
        }
        formatBuilder.append("|\n");
        separatorBuilder.append("+");
        String rowFormat = formatBuilder.toString();
        String separator = separatorBuilder.toString();

        System.out.println(separator);
        System.out.printf(rowFormat, (Object[]) headers.toArray(new String[0]));
        System.out.println(separator);
        for (List<String> row : rows) {
            List<String> paddedRow = new ArrayList<>(row);
            while (paddedRow.size() < cols) {
                paddedRow.add("");
            }
            System.out.printf(rowFormat, (Object[]) paddedRow.toArray(new String[0]));
        }
        System.out.println(separator);
    }
}
