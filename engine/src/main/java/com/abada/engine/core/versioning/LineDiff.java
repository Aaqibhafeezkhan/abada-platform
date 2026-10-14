package com.abada.engine.core.versioning;

import java.util.ArrayList;
import java.util.List;

/** A unified line diff of two sources (E16), small enough to read a definition change in a review. */
public final class LineDiff {
    private static final long MAX_CELLS = 4_000_000L;
    private static final int CONTEXT = 3;

    private LineDiff() {}

    /** Null when the sources are too large to compare; empty when they are identical. */
    public static String unified(String from, String to) {
        List<String> a = lines(from);
        List<String> b = lines(to);
        if ((long) a.size() * b.size() > MAX_CELLS) return null;
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> edits = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                edits.add(" " + a.get(i));
                i++;
                j++;
            } else if (j < b.size() && (i == a.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                edits.add("+" + b.get(j++));
            } else {
                edits.add("-" + a.get(i++));
            }
        }
        return hunks(edits);
    }

    /** Keeps changed lines with {@value #CONTEXT} lines of context and marks the gaps. */
    private static String hunks(List<String> edits) {
        boolean[] keep = new boolean[edits.size()];
        boolean changed = false;
        for (int k = 0; k < edits.size(); k++) {
            if (edits.get(k).charAt(0) == ' ') continue;
            changed = true;
            for (int c = Math.max(0, k - CONTEXT); c <= Math.min(edits.size() - 1, k + CONTEXT); c++) keep[c] = true;
        }
        if (!changed) return "";
        StringBuilder out = new StringBuilder();
        boolean gap = false;
        for (int k = 0; k < edits.size(); k++) {
            if (!keep[k]) {
                gap = true;
                continue;
            }
            if (gap && out.length() > 0) out.append("@@ ... @@\n");
            gap = false;
            out.append(edits.get(k)).append('\n');
        }
        return out.toString();
    }

    private static List<String> lines(String source) {
        if (source == null || source.isEmpty()) return List.of();
        return List.of(source.replace("\r\n", "\n").split("\n", -1));
    }
}
