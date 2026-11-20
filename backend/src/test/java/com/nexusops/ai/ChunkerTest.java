package com.nexusops.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChunkerTest {

    @Test
    void aShortBodyIsOneChunkEqualToTheTrimmedBody() {
        assertThat(Chunker.chunks("  Open the rear tray.\nPull the paper out slowly.  \n"))
                .containsExactly("Open the rear tray.\nPull the paper out slowly.");
    }

    @Test
    void aBlankBodyHasNoChunks() {
        assertThat(Chunker.chunks("")).isEmpty();
        assertThat(Chunker.chunks("   \n\n\t ")).isEmpty();
        assertThat(Chunker.chunks(null)).isEmpty();
    }

    @Test
    void shortParagraphsArePackedWholeUpToTheTarget() {
        String body = IntStream.range(0, 30).mapToObj(i -> "Paragraph " + i + " is short and complete.")
                .collect(Collectors.joining("\n\n"));
        List<String> chunks = Chunker.chunks(body);
        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(900));
        // nothing is lost
        IntStream.range(0, 30).forEach(i -> assertThat(chunks).anySatisfy(
                c -> assertThat(c).contains("Paragraph " + i + " is short and complete.")));
    }

    @Test
    void longParagraphsAreSplitOnSentencesWithAtLeastOneSentenceOfOverlap() {
        List<String> sentences = new ArrayList<>();
        List<String> paragraphs = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            List<String> paragraph = new ArrayList<>();
            for (int s = 0; s < 25; s++) {
                String sentence = "Section " + p + " sentence " + s + " explains one more detail of the leave rules.";
                paragraph.add(sentence);
                sentences.add(sentence);
            }
            paragraphs.add(String.join(" ", paragraph));
        }
        List<String> chunks = Chunker.chunks(String.join("\n\n", paragraphs));

        assertThat(chunks).hasSizeGreaterThan(4).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(900));
        for (int i = 0; i + 1 < chunks.size(); i++) {
            String[] previous = chunks.get(i).split("(?<=\\.)\\s+");
            assertThat(chunks.get(i + 1)).as("chunk %d starts with the last sentence of chunk %d", i + 1, i)
                    .startsWith(previous[previous.length - 1]);
        }
        for (String sentence : sentences) {
            assertThat(chunks).as(sentence).anySatisfy(c -> assertThat(c).contains(sentence));
        }
        // chunks end on sentence boundaries
        assertThat(chunks).allSatisfy(c -> assertThat(c).endsWith("."));
    }

    @Test
    void aSingleOverlongSentenceIsHardSplitAt800With100Overlap() {
        String sentence = IntStream.range(0, 3000).mapToObj(i -> String.valueOf((char) ('a' + i % 26)))
                .collect(Collectors.joining());
        List<String> chunks = Chunker.chunks(sentence);
        assertThat(chunks).containsExactly(sentence.substring(0, 800), sentence.substring(700, 1500),
                sentence.substring(1400, 2200), sentence.substring(2100, 2900), sentence.substring(2800, 3000));
    }

    @Test
    void noChunkEverExceeds900Characters() {
        String body = Arrays.stream(new int[] {50, 120, 790, 30, 805, 99, 101, 640, 2500, 10, 700, 95, 400})
                .mapToObj(n -> "x".repeat(n - 1) + ".").collect(Collectors.joining(" "));
        List<String> chunks = Chunker.chunks(body + "\n\n" + body);
        assertThat(chunks).isNotEmpty().allSatisfy(c -> assertThat(c.length()).isBetween(1, 900));
    }
}
