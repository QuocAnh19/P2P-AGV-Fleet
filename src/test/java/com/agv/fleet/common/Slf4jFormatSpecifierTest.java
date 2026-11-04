package com.agv.fleet.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class Slf4jFormatSpecifierTest {

    private static final Pattern INVALID_SLF4J_PATTERN = Pattern.compile("\\{\\.[0-9]+f\\}");

    @Test
    @DisplayName("Bug 5: Verify no files in src/ contain invalid SLF4J format specifiers like {.2f}")
    void testNoInvalidSlf4jFormatSpecifiersInSource() throws IOException {
        Path srcRoot = Paths.get("src");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(srcRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("Slf4jFormatSpecifierTest.java"))
                    .forEach(path -> {
                        try {
                            List<String> lines = Files.readAllLines(path);
                            for (int i = 0; i < lines.size(); i++) {
                                String line = lines.get(i);
                                Matcher matcher = INVALID_SLF4J_PATTERN.matcher(line);
                                if (matcher.find()) {
                                    violations.add(String.format("%s:%d -> %s", path, i + 1, line.trim()));
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed reading " + path, e);
                        }
                    });
        }

        assertThat(violations)
                .withFailMessage("Found invalid SLF4J format specifiers (e.g. {.2f}) in source files:\n" + String.join("\n", violations))
                .isEmpty();
    }
}
