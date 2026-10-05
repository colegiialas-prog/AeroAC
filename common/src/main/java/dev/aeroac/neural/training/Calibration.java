package dev.aeroac.neural.training;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platt and temperature scaling fitted on the calibration fold (ml/aeroml/evaluation/calibration.py).
 * Platt is the default and falls back to temperature when its fit fails or does not improve
 * likelihood; both keep the ranking, so ROC-AUC and TPR at every FPR are unchanged.
 */
final class Calibration {
    static final double MIN_TEMPERATURE = 0.05;
    static final double MAX_TEMPERATURE = 20.0;
    private static final double EPSILON = 1.0E-12;

    /** sigmoid(slope * logit + bias); temperature is slope = 1/T, bias = 0. */
    record Scaler(String method, double slope, double bias, int fittedOn, double nllBefore, double nllAfter,
                  double eceBefore, double eceAfter) {
        double apply(double logit) { return sigmoid(slope * logit + bias); }

        boolean improved() { return nllAfter <= nllBefore + 1e-9; }

        Map<String, Object> toJson() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("method", method);
            if ("platt".equals(method)) {
                data.put("slope", slope);
                data.put("bias", bias);
            } else {
                data.put("temperature", 1.0 / slope);
            }
            data.put("fittedOn", fittedOn);
            data.put("nllBefore", nllBefore);
            data.put("nllAfter", nllAfter);
            data.put("eceBefore", eceBefore);
            data.put("eceAfter", eceAfter);
            return data;
        }
    }

    private Calibration() { }

    static double logit(double probability) {
        double p = Math.min(1 - EPSILON, Math.max(EPSILON, probability));
        return Math.log(p / (1 - p));
    }

    static double sigmoid(double x) {
        if (x >= 0) return 1.0 / (1.0 + Math.exp(-x));
        double e = Math.exp(x);
        return e / (1.0 + e);
    }

    private static double nll(double[] logits, double[] targets, double slope, double bias) {
        double total = 0;
        for (int i = 0; i < logits.length; i++) {
            double z = slope * logits[i] + bias;
            total += Math.max(z, 0) - z * targets[i] + Math.log1p(Math.exp(-Math.abs(z)));
        }
        return total / logits.length;
    }

    static double ece(double[] probabilities, double[] labels) {
        int bins = 15;
        if (probabilities.length == 0) return Double.NaN;
        double error = 0;
        for (int b = 0; b < bins; b++) {
            double low = (double) b / bins, high = (double) (b + 1) / bins;
            int count = 0;
            double label = 0, predicted = 0;
            for (int i = 0; i < probabilities.length; i++) {
                double p = probabilities[i];
                boolean inside = b == 0 ? p <= high : p > low && p <= high;
                if (!inside) continue;
                count++;
                label += labels[i];
                predicted += p;
            }
            if (count > 0) error += (double) count / probabilities.length * Math.abs(label / count - predicted / count);
        }
        return error;
    }

    private static double[] apply(double[] logits, double slope, double bias) {
        double[] out = new double[logits.length];
        for (int i = 0; i < logits.length; i++) out[i] = sigmoid(slope * logits[i] + bias);
        return out;
    }

    static Scaler platt(double[] logits, double[] labels) {
        double positives = 0;
        for (double label : labels) positives += label;
        double negatives = labels.length - positives;
        if (positives == 0 || negatives == 0) throw new IllegalArgumentException("calibration needs both classes");
        double[] targets = new double[labels.length];
        for (int i = 0; i < labels.length; i++) targets[i] = labels[i] == 1 ? (positives + 1) / (positives + 2) : 1 / (negatives + 2);
        double slope = 1, bias = 0, current = nll(logits, targets, slope, bias);
        for (int iteration = 0; iteration < 100; iteration++) {
            double g0 = 0, g1 = 0, h00 = 1e-9, h01 = 0, h11 = 1e-9;
            for (int i = 0; i < logits.length; i++) {
                double p = sigmoid(slope * logits[i] + bias);
                double r = p - targets[i];
                double w = Math.max(p * (1 - p), 1e-12);
                g0 += r * logits[i];
                g1 += r;
                h00 += w * logits[i] * logits[i];
                h01 += w * logits[i];
                h11 += w;
            }
            double det = h00 * h11 - h01 * h01;
            if (!(Math.abs(det) > 0) || !Double.isFinite(det)) break;
            double s0 = (h11 * g0 - h01 * g1) / det, s1 = (h00 * g1 - h01 * g0) / det;
            double scale = 1, candidateSlope = slope, candidateBias = bias, candidate = current;
            boolean accepted = false;
            while (scale > 1e-6) {
                candidateSlope = slope - scale * s0;
                candidateBias = bias - scale * s1;
                candidate = nll(logits, targets, candidateSlope, candidateBias);
                if (candidate <= current) { accepted = true; break; }
                scale *= 0.5;
            }
            if (!accepted) break;
            double previous = current;
            slope = candidateSlope;
            bias = candidateBias;
            current = candidate;
            if (Math.abs(previous - current) < 1e-12 && Math.max(Math.abs(scale * s0), Math.abs(scale * s1)) < 1e-9) break;
        }
        if (!Double.isFinite(slope) || !Double.isFinite(bias) || slope <= 0) {
            throw new IllegalArgumentException("calibration would reverse or flatten the ranking");
        }
        slope = Math.min(1 / MIN_TEMPERATURE, Math.max(1 / MAX_TEMPERATURE, slope));
        return new Scaler("platt", slope, bias, logits.length, nll(logits, labels, 1, 0), nll(logits, labels, slope, bias),
                ece(apply(logits, 1, 0), labels), ece(apply(logits, slope, bias), labels));
    }

    static Scaler temperature(double[] logits, double[] labels) {
        double low = MIN_TEMPERATURE, high = MAX_TEMPERATURE, phi = (Math.sqrt(5) - 1) / 2;
        double left = high - phi * (high - low), right = low + phi * (high - low);
        double valueLeft = nll(logits, labels, 1 / left, 0), valueRight = nll(logits, labels, 1 / right, 0);
        for (int i = 0; i < 80; i++) {
            if (valueLeft < valueRight) {
                high = right; right = left; valueRight = valueLeft;
                left = high - phi * (high - low);
                valueLeft = nll(logits, labels, 1 / left, 0);
            } else {
                low = left; left = right; valueLeft = valueRight;
                right = low + phi * (high - low);
                valueRight = nll(logits, labels, 1 / right, 0);
            }
        }
        double t = (low + high) / 2;
        return new Scaler("temperature", 1 / t, 0, logits.length, nll(logits, labels, 1, 0), nll(logits, labels, 1 / t, 0),
                ece(apply(logits, 1, 0), labels), ece(apply(logits, 1 / t, 0), labels));
    }

    /** Platt when it fits and improves likelihood; temperature otherwise (fit_scaler). */
    static Scaler fit(double[] logits, double[] labels) {
        boolean both = false, zero = false;
        for (double label : labels) { if (label == 1) both = true; else zero = true; }
        if (!both || !zero) throw new IllegalArgumentException("calibration needs both classes");
        try {
            Scaler platt = platt(logits, labels);
            if (platt.improved()) return platt;
        } catch (IllegalArgumentException fallback) {
            // fall through to temperature, as the Python fit_scaler does
        }
        return temperature(logits, labels);
    }

    /** Per-head calibration as written into the manifest: {method, heads, scalers, priors}. */
    static Map<String, Object> toJson(List<String> heads, Scaler[] scalers, double[] priors) {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean allTemperature = true;
        for (Scaler scaler : scalers) allTemperature &= "temperature".equals(scaler.method());
        data.put("method", allTemperature ? "per-head-temperature" : "per-head");
        data.put("heads", heads);
        Map<String, Object> byHead = new LinkedHashMap<>();
        Map<String, Object> priorByHead = new LinkedHashMap<>();
        for (int i = 0; i < heads.size(); i++) {
            byHead.put(heads.get(i), scalers[i].toJson());
            priorByHead.put(heads.get(i), priors[i]);
        }
        data.put("scalers", byHead);
        data.put("priors", priorByHead);
        return data;
    }
}
