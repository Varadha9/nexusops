package com.nexusops.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a document body into retrieval chunks (D5): about {@value #TARGET} characters with {@value #OVERLAP} of
 * overlap, on paragraph boundaries first, then sentence boundaries; a sentence longer than the target is hard-split.
 *
 * <p>The body is cut into units: a paragraph that fits the target is one unit, a longer paragraph contributes its
 * sentences. Units are packed greedily up to the target. Each new chunk is seeded with the trailing units of the
 * previous one that fit in the overlap (at least the last sentence when the cut falls inside a paragraph), and may
 * then grow to {@value #MAX_LENGTH} to take its first new unit; a seed that would not fit is dropped. Every chunk is a
 * contiguous slice of the trimmed body, so its text is exactly what the author wrote.
 */
public final class Chunker {

    public static final int TARGET = 800;
    public static final int OVERLAP = 100;
    public static final int MAX_LENGTH = 900;

    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t\\x0B\\f\\r]*\\n\\s*");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+|\\s*\\n\\s*");

    private record Unit(int start, int end, int paragraph) {}

    private Chunker() {}

    public static List<String> chunks(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String text = body.strip();
        List<Unit> units = units(text);
        List<String> chunks = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        for (Unit unit : units) {
            if (current.isEmpty() || span(current.getFirst(), unit) <= TARGET) {
                current.add(unit);
                continue;
            }
            chunks.add(slice(text, current));
            // a hard-split piece already overlaps the piece before it
            List<Unit> seed = unit.start() < current.getLast().end() ? new ArrayList<>() : seed(current, unit);
            while (!seed.isEmpty() && span(seed.getFirst(), unit) > MAX_LENGTH) {
                seed.removeFirst();
            }
            current = seed;
            current.add(unit);
        }
        chunks.add(slice(text, current));
        return chunks;
    }

    /** Trailing units of {@code previous} within the overlap; at least one if {@code next} continues the paragraph. */
    private static List<Unit> seed(List<Unit> previous, Unit next) {
        Unit last = previous.getLast();
        int from = previous.size();
        while (from > 0 && span(previous.get(from - 1), last) <= OVERLAP) {
            from--;
        }
        if (from == previous.size() && last.paragraph() == next.paragraph()) {
            from = previous.size() - 1;
        }
        return new ArrayList<>(previous.subList(from, previous.size()));
    }

    private static List<Unit> units(String text) {
        List<Unit> units = new ArrayList<>();
        int paragraph = 0;
        for (int[] p : spans(text, 0, text.length(), PARAGRAPH_BREAK)) {
            if (p[1] - p[0] <= TARGET) {
                units.add(new Unit(p[0], p[1], paragraph));
            } else {
                for (int[] s : spans(text, p[0], p[1], SENTENCE_BREAK)) {
                    hardSplit(s[0], s[1], paragraph, units);
                }
            }
            paragraph++;
        }
        return units;
    }

    /** A sentence longer than the target becomes pieces of {@value #TARGET} overlapping by {@value #OVERLAP}. */
    private static void hardSplit(int start, int end, int paragraph, List<Unit> units) {
        int from = start;
        while (true) {
            int to = Math.min(from + TARGET, end);
            units.add(new Unit(from, to, paragraph));
            if (to == end) {
                return;
            }
            from = to - OVERLAP;
        }
    }

    /** Non-blank, trimmed spans of {@code text[start, end)} between matches of {@code separator}. */
    private static List<int[]> spans(String text, int start, int end, Pattern separator) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = separator.matcher(text).region(start, end);
        int from = start;
        while (m.find()) {
            addTrimmed(text, from, m.start(), spans);
            from = m.end();
        }
        addTrimmed(text, from, end, spans);
        return spans;
    }

    private static void addTrimmed(String text, int start, int end, List<int[]> spans) {
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (start < end) {
            spans.add(new int[] {start, end});
        }
    }

    private static int span(Unit first, Unit last) {
        return last.end() - first.start();
    }

    private static String slice(String text, List<Unit> units) {
        return text.substring(units.getFirst().start(), units.getLast().end());
    }
}
