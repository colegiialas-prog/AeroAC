package dev.aeroac.neural.inference.local;

import java.util.List;
import java.util.Map;

/**
 * The Flash/Pro temporal ConvNet, evaluated in the JVM. A line-for-line port of
 * ml/aeroml/models/tcn.py (architecture "temporal-convnet-v1") for one window at a time:
 *
 * <pre>
 * project: Conv1d(C->W, k=1) -> GroupNorm -> GELU
 * blocks:  x = GELU(GN(conv2(GELU(GN(conv1(x))))) + x), dilation 2^i, same padding
 * pool:    [attention-weighted sum over time, mean over time]
 * trunk:   Linear(2W->W) -> GELU;  heads: Linear(W->1) each
 * </pre>
 *
 * Dropout is identity at inference. Arithmetic is double precision over float32 weights, so the
 * logits match PyTorch to well inside the 1e-4 the golden test allows. Immutable and thread safe:
 * every call allocates its own activations.
 */
public final class TemporalConvNet {
    private static final double GROUP_NORM_EPSILON = 1.0E-5;

    private final int featureCount;
    private final int sequenceLength;
    private final int width;
    private final int kernelSize;
    private final int groups;
    private final List<String> heads;
    private final Conv project;
    private final Norm projectNorm;
    private final Block[] blocks;
    private final Conv poolScore;
    private final Dense trunk;
    private final Dense[] headLayers;

    private record Conv(float[] weight, float[] bias, int out, int in, int kernel, int dilation) { }
    private record Norm(float[] weight, float[] bias) { }
    private record Dense(float[] weight, float[] bias, int out, int in) { }
    private record Block(Conv first, Norm firstNorm, Conv second, Norm secondNorm) { }

    /**
     * @param tensors the state_dict by name, each flattened row-major, with its shape
     */
    public TemporalConvNet(int featureCount, int sequenceLength, int width, int blockCount, int kernelSize, int groups,
                           List<String> heads, Map<String, float[]> tensors, Map<String, int[]> shapes) {
        if (featureCount < 1 || sequenceLength < 1 || width < 1 || blockCount < 0 || kernelSize < 1
                || kernelSize % 2 == 0 || groups < 1 || heads.isEmpty()) {
            throw new IllegalArgumentException("invalid architecture");
        }
        this.featureCount = featureCount;
        this.sequenceLength = sequenceLength;
        this.width = width;
        this.kernelSize = kernelSize;
        this.groups = Math.min(groups, width);
        if (width % this.groups != 0) throw new IllegalArgumentException("width must divide into groups");
        this.heads = List.copyOf(heads);
        this.project = conv(tensors, shapes, "project.0", width, featureCount, 1, 1);
        this.projectNorm = norm(tensors, shapes, "project.1");
        this.blocks = new Block[blockCount];
        for (int i = 0; i < blockCount; i++) {
            String prefix = "blocks." + i + ".";
            int dilation = 1 << i;
            blocks[i] = new Block(conv(tensors, shapes, prefix + "first", width, width, kernelSize, dilation),
                    norm(tensors, shapes, prefix + "norm_first"),
                    conv(tensors, shapes, prefix + "second", width, width, kernelSize, dilation),
                    norm(tensors, shapes, prefix + "norm_second"));
        }
        this.poolScore = conv(tensors, shapes, "pool.score", 1, width, 1, 1);
        this.trunk = dense(tensors, shapes, "trunk.0", width, 2 * width);
        this.headLayers = new Dense[this.heads.size()];
        for (int i = 0; i < headLayers.length; i++) {
            headLayers[i] = dense(tensors, shapes, "heads." + this.heads.get(i), 1, width);
        }
    }

    public int featureCount() { return featureCount; }
    public int sequenceLength() { return sequenceLength; }
    public List<String> heads() { return heads; }

    /** Receptive field in samples, as ModelConfig.receptive_field computes it. */
    public int receptiveField() {
        int field = 1;
        for (int block = 0; block < blocks.length; block++) field += 2 * (kernelSize - 1) * (1 << block);
        return field;
    }

    /**
     * @param window row-major [t * featureCount + channel], already normalised
     * @return one raw logit per head, in {@link #heads()} order
     */
    public double[] logits(float[] window) {
        if (window == null || window.length != sequenceLength * featureCount) {
            throw new IllegalArgumentException("window must hold " + sequenceLength + " x " + featureCount + " values");
        }
        int length = sequenceLength;
        double[][] input = new double[featureCount][length];
        for (int t = 0; t < length; t++) {
            for (int c = 0; c < featureCount; c++) input[c][t] = window[t * featureCount + c];
        }
        double[][] x = convolve(project, input);
        groupNorm(projectNorm, x);
        gelu(x);
        for (Block block : blocks) {
            double[][] out = convolve(block.first, x);
            groupNorm(block.firstNorm, out);
            gelu(out);
            out = convolve(block.second, out);
            groupNorm(block.secondNorm, out);
            for (int c = 0; c < width; c++) for (int t = 0; t < length; t++) out[c][t] += x[c][t];
            gelu(out);
            x = out;
        }
        double[] score = convolve(poolScore, x)[0];
        double max = Double.NEGATIVE_INFINITY;
        for (double value : score) max = Math.max(max, value);
        double total = 0;
        double[] weights = new double[length];
        for (int t = 0; t < length; t++) { weights[t] = Math.exp(score[t] - max); total += weights[t]; }
        double[] pooled = new double[2 * width];
        for (int c = 0; c < width; c++) {
            double attended = 0, mean = 0;
            for (int t = 0; t < length; t++) { attended += x[c][t] * weights[t]; mean += x[c][t]; }
            pooled[c] = attended / total;
            pooled[width + c] = mean / length;
        }
        double[] features = apply(trunk, pooled);
        for (int i = 0; i < features.length; i++) features[i] = gelu(features[i]);
        double[] logits = new double[headLayers.length];
        for (int i = 0; i < headLayers.length; i++) logits[i] = apply(headLayers[i], features)[0];
        return logits;
    }

    /** Conv1d with zero "same" padding, dilation * (kernel - 1) / 2 on each side. */
    private static double[][] convolve(Conv conv, double[][] input) {
        int length = input[0].length;
        int padding = conv.dilation * (conv.kernel - 1) / 2;
        double[][] output = new double[conv.out][length];
        for (int o = 0; o < conv.out; o++) {
            double[] row = output[o];
            double bias = conv.bias[o];
            for (int t = 0; t < length; t++) row[t] = bias;
            for (int i = 0; i < conv.in; i++) {
                double[] source = input[i];
                int base = (o * conv.in + i) * conv.kernel;
                for (int k = 0; k < conv.kernel; k++) {
                    double w = conv.weight[base + k];
                    int shift = k * conv.dilation - padding;
                    int from = Math.max(0, -shift), to = Math.min(length, length - shift);
                    for (int t = from; t < to; t++) row[t] += w * source[t + shift];
                }
            }
        }
        return output;
    }

    private void groupNorm(Norm norm, double[][] x) {
        int perGroup = x.length / groups;
        int length = x[0].length;
        for (int g = 0; g < groups; g++) {
            double sum = 0;
            for (int c = g * perGroup; c < (g + 1) * perGroup; c++) for (int t = 0; t < length; t++) sum += x[c][t];
            double mean = sum / (perGroup * length);
            double squares = 0;
            for (int c = g * perGroup; c < (g + 1) * perGroup; c++) {
                for (int t = 0; t < length; t++) { double d = x[c][t] - mean; squares += d * d; }
            }
            double scale = 1.0 / Math.sqrt(squares / (perGroup * length) + GROUP_NORM_EPSILON);
            for (int c = g * perGroup; c < (g + 1) * perGroup; c++) {
                double gamma = norm.weight[c], beta = norm.bias[c];
                for (int t = 0; t < length; t++) x[c][t] = (x[c][t] - mean) * scale * gamma + beta;
            }
        }
    }

    private static double[] apply(Dense dense, double[] input) {
        double[] output = new double[dense.out];
        for (int o = 0; o < dense.out; o++) {
            double sum = dense.bias[o];
            int base = o * dense.in;
            for (int i = 0; i < dense.in; i++) sum += dense.weight[base + i] * input[i];
            output[o] = sum;
        }
        return output;
    }

    private static void gelu(double[][] x) {
        for (double[] row : x) for (int t = 0; t < row.length; t++) row[t] = gelu(row[t]);
    }

    /** PyTorch's default, exact GELU: x * Phi(x) = 0.5 x (1 + erf(x / sqrt 2)). */
    static double gelu(double x) {
        return 0.5 * x * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    /**
     * erf via the complementary error function's Chebyshev fit (Numerical Recipes erfcc), fractional
     * error below 1.2e-7 everywhere; far below the float32 noise already in the weights.
     */
    static double erf(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * z);
        double erfc = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? 1.0 - erfc : erfc - 1.0;
    }

    private static Conv conv(Map<String, float[]> tensors, Map<String, int[]> shapes, String name,
                             int out, int in, int kernel, int dilation) {
        return new Conv(tensor(tensors, shapes, name + ".weight", out, in, kernel),
                tensor(tensors, shapes, name + ".bias", out), out, in, kernel, dilation);
    }

    private Norm norm(Map<String, float[]> tensors, Map<String, int[]> shapes, String name) {
        return new Norm(tensor(tensors, shapes, name + ".weight", width), tensor(tensors, shapes, name + ".bias", width));
    }

    private static Dense dense(Map<String, float[]> tensors, Map<String, int[]> shapes, String name, int out, int in) {
        return new Dense(tensor(tensors, shapes, name + ".weight", out, in), tensor(tensors, shapes, name + ".bias", out), out, in);
    }

    private static float[] tensor(Map<String, float[]> tensors, Map<String, int[]> shapes, String name, int... shape) {
        float[] values = tensors.get(name);
        int[] declared = shapes.get(name);
        if (values == null || declared == null) throw new IllegalArgumentException("missing tensor " + name);
        if (!java.util.Arrays.equals(declared, shape)) {
            throw new IllegalArgumentException("tensor " + name + " has shape " + java.util.Arrays.toString(declared)
                    + ", expected " + java.util.Arrays.toString(shape));
        }
        return values;
    }
}
