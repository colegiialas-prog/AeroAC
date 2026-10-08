package dev.aeroac.neural.training;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ranking metrics with the definitions of ml/aeroml/evaluation/metrics.py. */
public final class Metrics {
    public static final double[] FPR_TARGETS = {1e-3, 1e-4, 1e-5};

    private Metrics() { }

    /** Rank based; ties count half, so a constant scorer gets 0.5. NaN without both classes. */
    public static double rocAuc(int[] labels, double[] scores) {
        int n = labels.length, positives = 0;
        for (int label : labels) positives += label;
        int negatives = n - positives;
        if (positives == 0 || negatives == 0) return Double.NaN;
        Integer[] order = sortedAscending(scores);
        double[] ranks = new double[n];
        int index = 0;
        while (index < n) {
            int end = index;
            while (end + 1 < n && scores[order[end + 1]] == scores[order[index]]) end++;
            double rank = (index + end) / 2.0 + 1.0;
            for (int i = index; i <= end; i++) ranks[order[i]] = rank;
            index = end + 1;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) if (labels[i] == 1) sum += ranks[i];
        return (sum - positives * (positives + 1) / 2.0) / ((double) positives * negatives);
    }

    /** fpr, tpr, thresholds: points at each distinct score, highest first, starting at (0, 0, inf). */
    static double[][] rocCurve(int[] labels, double[] scores) {
        int n = labels.length;
        Integer[] order = sortedDescending(scores);
        int positives = 0;
        for (int label : labels) positives += label;
        int negatives = Math.max(n - positives, 1);
        positives = Math.max(positives, 1);
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{0, 0, Double.POSITIVE_INFINITY});
        int tp = 0, fp = 0;
        for (int i = 0; i < n; i++) {
            if (labels[order[i]] == 1) tp++; else fp++;
            if (i == n - 1 || scores[order[i + 1]] != scores[order[i]]) {
                points.add(new double[]{(double) fp / negatives, (double) tp / positives, scores[order[i]]});
            }
        }
        double[][] out = new double[3][points.size()];
        for (int i = 0; i < points.size(); i++) {
            out[0][i] = points.get(i)[0];
            out[1][i] = points.get(i)[1];
            out[2][i] = points.get(i)[2];
        }
        return out;
    }

    /** Standardised (McClish) partial AUC over FPR in [0, maxFpr]; 0.5 random, 1.0 perfect. */
    public static double partialAuc(int[] labels, double[] scores, double maxFpr) {
        if (!hasBothClasses(labels)) return Double.NaN;
        if (maxFpr >= 1) return rocAuc(labels, scores);
        double[][] curve = rocCurve(labels, scores);
        double[] fpr = curve[0], tpr = curve[1];
        int stop = 0;
        while (stop < fpr.length && fpr[stop] <= maxFpr) stop++;
        double area = 0;
        if (stop >= fpr.length) {
            for (int i = 1; i < fpr.length; i++) area += (fpr[i] - fpr[i - 1]) * (tpr[i] + tpr[i - 1]) / 2;
        } else {
            double x0 = fpr[stop - 1], x1 = fpr[stop], y0 = tpr[stop - 1], y1 = tpr[stop];
            double edge = x1 == x0 ? y1 : y0 + (y1 - y0) * (maxFpr - x0) / (x1 - x0);
            for (int i = 1; i < stop; i++) area += (fpr[i] - fpr[i - 1]) * (tpr[i] + tpr[i - 1]) / 2;
            area += (maxFpr - fpr[stop - 1]) * (edge + tpr[stop - 1]) / 2;
        }
        double minimum = 0.5 * maxFpr * maxFpr;
        return 0.5 * (1.0 + (area - minimum) / (maxFpr - minimum));
    }

    /** Average precision, step-wise over distinct scores. */
    public static double prAuc(int[] labels, double[] scores) {
        int positives = 0;
        for (int label : labels) positives += label;
        if (positives == 0) return Double.NaN;
        Integer[] order = sortedDescending(scores);
        double sum = 0;
        int tp = 0, previousTp = 0;
        for (int i = 0; i < labels.length; i++) {
            tp += labels[order[i]];
            if (i == labels.length - 1 || scores[order[i + 1]] != scores[order[i]]) {
                sum += (double) tp / (i + 1) * (tp - previousTp);
                previousTp = tp;
            }
        }
        return sum / positives;
    }

    /** {tpr, threshold}: the highest TPR whose FPR stays at or below the target. */
    public static double[] tprAtFpr(int[] labels, double[] scores, double target) {
        if (!hasBothClasses(labels)) return new double[]{Double.NaN, Double.POSITIVE_INFINITY};
        double[][] curve = rocCurve(labels, scores);
        int best = -1;
        for (int i = 0; i < curve[0].length; i++) if (curve[0][i] <= target + 1e-12) best = i;
        if (best < 0) return new double[]{0, Double.POSITIVE_INFINITY};
        return new double[]{curve[1][best], curve[2][best]};
    }

    static int negativesNeeded(double target) {
        return (int) Math.ceil(10 / target);
    }

    static double[] wilson(int successes, int trials) {
        if (trials <= 0) return new double[]{0, 1};
        double z = 1.96, p = (double) successes / trials;
        double denominator = 1 + z * z / trials;
        double centre = (p + z * z / (2 * trials)) / denominator;
        double spread = z * Math.sqrt(p * (1 - p) / trials + z * z / (4.0 * trials * trials)) / denominator;
        return new double[]{Math.max(0, centre - spread), Math.min(1, centre + spread)};
    }

    /** The evaluation report written into the bundle, with the keys the Python report uses. */
    public static Map<String, Object> evaluate(int[] labels, double[] scores, double legitHours) {
        int positives = 0;
        for (int label : labels) positives += label;
        int negatives = labels.length - positives;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("rocAuc", finite(rocAuc(labels, scores)));
        report.put("prAuc", finite(prAuc(labels, scores)));
        report.put("positives", positives);
        report.put("negatives", negatives);
        List<String> notes = new ArrayList<>();
        notes.add("TPR@FPR is a descriptive ROC curve, not a deployable threshold. Overlapping windows are correlated; window intervals do not establish player-level confidence.");
        Map<String, Object> tprs = new LinkedHashMap<>();
        Map<String, Object> alarms = new LinkedHashMap<>();
        for (double target : FPR_TARGETS) {
            double[] result = tprAtFpr(labels, scores, target);
            int required = negativesNeeded(target);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tpr", finite(result[0]));
            entry.put("threshold", finite(result[1]));
            entry.put("availableLegitNegatives", negatives);
            entry.put("requiredApproximateNegatives", required);
            entry.put("requestedFpr", target);
            boolean enough = negatives >= required;
            entry.put("status", enough ? "WINDOW COUNT SUFFICIENT; INDEPENDENCE STILL REQUIRES REVIEW"
                    : "INSUFFICIENT DATA TO CLAIM THIS FPR");
            entry.put("reliable", enough ? 1.0 : 0.0);
            if (!enough) {
                notes.add("FPR " + format(target) + " needs about " + required + " legit windows for a stable estimate; this evaluation has " + negatives + ".");
            }
            double[] interval = wilson(Double.isFinite(result[0]) ? (int) Math.round(result[0] * positives) : 0, positives);
            entry.put("tprCi95Low", interval[0]);
            entry.put("tprCi95High", interval[1]);
            tprs.put(format(target), entry);
            if (legitHours > 0 && Double.isFinite(result[1])) {
                int count = 0;
                for (int i = 0; i < labels.length; i++) if (labels[i] == 0 && scores[i] >= result[1]) count++;
                alarms.put(format(target), count / legitHours);
            }
        }
        report.put("tprAtFpr", tprs);
        report.put("falsePositivesPerLegitHour", alarms);
        report.put("medianDetectionSeconds", null);
        report.put("detectedFraction", null);
        report.put("notes", notes);
        return report;
    }

    /** Python's "%g" for the FPR keys: 0.001, 0.0001, 1e-05. */
    static String format(double value) {
        if (value >= 1e-4) return new java.math.BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString();
        return String.format(Locale.ROOT, "%.0e", value);
    }

    private static Object finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    static boolean hasBothClasses(int[] labels) {
        boolean zero = false, one = false;
        for (int label : labels) { if (label == 1) one = true; else zero = true; }
        return zero && one;
    }

    private static Integer[] sortedAscending(double[] scores) {
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(scores[a], scores[b]));
        return order;
    }

    private static Integer[] sortedDescending(double[] scores) {
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));
        return order;
    }
}
