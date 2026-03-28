package com.mark.knowledge.rag.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class Bm25Scorer {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    public List<Double> score(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        List<String> queryTokens = new ArrayList<>(new LinkedHashSet<>(tokenize(query)));
        if (queryTokens.isEmpty()) {
            return zeroScores(documents.size());
        }

        List<Map<String, Integer>> documentTermFrequencies = new ArrayList<>(documents.size());
        List<Integer> documentLengths = new ArrayList<>(documents.size());
        Map<String, Integer> documentFrequencies = new HashMap<>();

        for (String document : documents) {
            List<String> tokens = tokenize(document);
            Map<String, Integer> termFrequencies = buildTermFrequencies(tokens);

            documentTermFrequencies.add(termFrequencies);
            documentLengths.add(tokens.size());

            for (String queryToken : queryTokens) {
                if (termFrequencies.containsKey(queryToken)) {
                    documentFrequencies.merge(queryToken, 1, Integer::sum);
                }
            }
        }

        double averageDocumentLength = documentLengths.stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0.0);

        if (averageDocumentLength <= 0) {
            return zeroScores(documents.size());
        }

        List<Double> scores = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            scores.add(scoreDocument(
                queryTokens,
                documentTermFrequencies.get(i),
                documentLengths.get(i),
                averageDocumentLength,
                documentFrequencies,
                documents.size()
            ));
        }
        return scores;
    }

    List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        String normalizedText = text.toLowerCase(Locale.ROOT);
        StringBuilder latinToken = new StringBuilder();
        StringBuilder cjkToken = new StringBuilder();

        normalizedText.codePoints().forEach(codePoint -> {
            if (isLatinOrDigit(codePoint)) {
                flushCjkToken(cjkToken, tokens);
                latinToken.appendCodePoint(codePoint);
                return;
            }

            if (isCjk(codePoint)) {
                flushLatinToken(latinToken, tokens);
                cjkToken.appendCodePoint(codePoint);
                return;
            }

            flushLatinToken(latinToken, tokens);
            flushCjkToken(cjkToken, tokens);
        });

        flushLatinToken(latinToken, tokens);
        flushCjkToken(cjkToken, tokens);
        return tokens;
    }

    private double scoreDocument(
            List<String> queryTokens,
            Map<String, Integer> termFrequencies,
            int documentLength,
            double averageDocumentLength,
            Map<String, Integer> documentFrequencies,
            int documentCount) {
        if (documentLength <= 0) {
            return 0.0;
        }

        double documentNormalization = K1 * (1.0 - B + B * documentLength / averageDocumentLength);
        double score = 0.0;

        for (String queryToken : queryTokens) {
            int termFrequency = termFrequencies.getOrDefault(queryToken, 0);
            if (termFrequency == 0) {
                continue;
            }

            int documentFrequency = documentFrequencies.getOrDefault(queryToken, 0);
            if (documentFrequency == 0) {
                continue;
            }

            double idf = Math.log1p((documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5));
            double numerator = termFrequency * (K1 + 1.0);
            score += idf * (numerator / (termFrequency + documentNormalization));
        }

        return score;
    }

    private Map<String, Integer> buildTermFrequencies(List<String> tokens) {
        Map<String, Integer> termFrequencies = new HashMap<>();
        for (String token : tokens) {
            termFrequencies.merge(token, 1, Integer::sum);
        }
        return termFrequencies;
    }

    private List<Double> zeroScores(int size) {
        return new ArrayList<>(java.util.Collections.nCopies(size, 0.0));
    }

    private void flushLatinToken(StringBuilder latinToken, List<String> tokens) {
        if (latinToken.isEmpty()) {
            return;
        }
        tokens.add(latinToken.toString());
        latinToken.setLength(0);
    }

    private void flushCjkToken(StringBuilder cjkToken, List<String> tokens) {
        if (cjkToken.isEmpty()) {
            return;
        }

        int[] codePoints = cjkToken.toString().codePoints().toArray();
        if (codePoints.length == 1) {
            tokens.add(new String(codePoints, 0, 1));
        } else {
            for (int i = 0; i < codePoints.length - 1; i++) {
                tokens.add(new String(codePoints, i, 2));
            }
        }
        cjkToken.setLength(0);
    }

    private boolean isLatinOrDigit(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
            || (codePoint >= '0' && codePoint <= '9');
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
            || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA
            || script == Character.UnicodeScript.HANGUL;
    }
}
