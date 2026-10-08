package dev.aeroac.neural.training;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The temporal ConvNet of ml/aeroml/models/tcn.py ("temporal-convnet-v1") with a hand-written
 * backward pass, so the plugin can train it without PyTorch.
 *
 * <pre>
 * project: Conv1d(F->W, k=1) -> GroupNorm -> GELU
 * blocks:  x = GELU(GN(conv2(dropout(GELU(GN(conv1(x)))))) + x), dilation 2^i, same padding
 * pool:    [attention-weighted sum over time, mean over time]
 * trunk:   Linear(2W->W) -> GELU -> dropout;  heads: Linear(W->1) each
 * </pre>
 *
 * Parameters live in one flat array in PyTorch state_dict order with PyTorch's names and shapes, so
 * {@link BundleWriter} writes exactly what LocalModelBundle and the Python tools read. Initialisation
 * is PyTorch's default (uniform +-1/sqrt(fan_in) for conv/linear, ones/zeros for GroupNorm).
 *
 * <p>A {@link Workspace} holds one sample's activations; each worker thread owns one, so forward and
 * backward allocate nothing per sample.
 */
public final class TrainableTcn {
    static final double GROUP_NORM_EPSILON = 1.0E-5;
    private static final double INV_SQRT2 = 1.0 / Math.sqrt(2.0);
    private static final double INV_SQRT_2PI = 1.0 / Math.sqrt(2.0 * Math.PI);

    /** One named tensor inside the flat parameter array. */
    public record Tensor(String name, int[] shape, int offset, int count) { }

    final int features, length, width, blocks, kernel, groups;
    final double dropout;
    final List<String> heads;
    final double[] params;
    private final List<Tensor> tensors = new ArrayList<>();
    // Offsets of each tensor.
    final int projW, projB, projGamma, projBeta;
    final int[] c1W, c1B, n1Gamma, n1Beta, c2W, c2B, n2Gamma, n2Beta;
    final int poolW, poolB, trunkW, trunkB;
    final int[] headW, headB;

    public TrainableTcn(int features, int length, int width, int blocks, int kernel, int groups, double dropout,
                        List<String> heads, long seed) {
        if (features < 1 || length < 1 || width < 1 || blocks < 0 || kernel < 1 || kernel % 2 == 0 || heads.isEmpty())
            throw new IllegalArgumentException("invalid architecture");
        this.features = features;
        this.length = length;
        this.width = width;
        this.blocks = blocks;
        this.kernel = kernel;
        this.groups = Math.min(groups, width);
        if (width % this.groups != 0) throw new IllegalArgumentException("width must divide into groups");
        this.dropout = dropout;
        this.heads = List.copyOf(heads);
        int[] cursor = {0};
        projW = add("project.0.weight", cursor, width, features, 1);
        projB = add("project.0.bias", cursor, width);
        projGamma = add("project.1.weight", cursor, width);
        projBeta = add("project.1.bias", cursor, width);
        c1W = new int[blocks]; c1B = new int[blocks]; n1Gamma = new int[blocks]; n1Beta = new int[blocks];
        c2W = new int[blocks]; c2B = new int[blocks]; n2Gamma = new int[blocks]; n2Beta = new int[blocks];
        for (int b = 0; b < blocks; b++) {
            String prefix = "blocks." + b + ".";
            c1W[b] = add(prefix + "first.weight", cursor, width, width, kernel);
            c1B[b] = add(prefix + "first.bias", cursor, width);
            n1Gamma[b] = add(prefix + "norm_first.weight", cursor, width);
            n1Beta[b] = add(prefix + "norm_first.bias", cursor, width);
            c2W[b] = add(prefix + "second.weight", cursor, width, width, kernel);
            c2B[b] = add(prefix + "second.bias", cursor, width);
            n2Gamma[b] = add(prefix + "norm_second.weight", cursor, width);
            n2Beta[b] = add(prefix + "norm_second.bias", cursor, width);
        }
        poolW = add("pool.score.weight", cursor, 1, width, 1);
        poolB = add("pool.score.bias", cursor, 1);
        trunkW = add("trunk.0.weight", cursor, width, 2 * width);
        trunkB = add("trunk.0.bias", cursor, width);
        headW = new int[this.heads.size()];
        headB = new int[this.heads.size()];
        for (int h = 0; h < headW.length; h++) {
            headW[h] = add("heads." + this.heads.get(h) + ".weight", cursor, 1, width);
            headB[h] = add("heads." + this.heads.get(h) + ".bias", cursor, 1);
        }
        params = new double[cursor[0]];
        initialise(new SplittableRandom(seed));
    }

    private int add(String name, int[] cursor, int... shape) {
        int count = 1;
        for (int dimension : shape) count *= dimension;
        int offset = cursor[0];
        tensors.add(new Tensor(name, shape.clone(), offset, count));
        cursor[0] += count;
        return offset;
    }

    private void initialise(SplittableRandom random) {
        uniform(random, projW, width * features, features);
        uniform(random, projB, width, features);
        fill(projGamma, width, 1);
        for (int b = 0; b < blocks; b++) {
            uniform(random, c1W[b], width * width * kernel, width * kernel);
            uniform(random, c1B[b], width, width * kernel);
            fill(n1Gamma[b], width, 1);
            uniform(random, c2W[b], width * width * kernel, width * kernel);
            uniform(random, c2B[b], width, width * kernel);
            fill(n2Gamma[b], width, 1);
        }
        uniform(random, poolW, width, width);
        uniform(random, poolB, 1, width);
        uniform(random, trunkW, width * 2 * width, 2 * width);
        uniform(random, trunkB, width, 2 * width);
        for (int h = 0; h < headW.length; h++) {
            uniform(random, headW[h], width, width);
            uniform(random, headB[h], 1, width);
        }
    }

    private void uniform(SplittableRandom random, int offset, int count, int fanIn) {
        double bound = 1.0 / Math.sqrt(fanIn);
        for (int i = 0; i < count; i++) params[offset + i] = (random.nextDouble() * 2 - 1) * bound;
    }

    private void fill(int offset, int count, double value) {
        for (int i = 0; i < count; i++) params[offset + i] = value;
    }

    public List<Tensor> tensors() { return List.copyOf(tensors); }
    public int parameterCount() { return params.length; }
    public int receptiveField() {
        int field = 1;
        for (int b = 0; b < blocks; b++) field += 2 * (kernel - 1) * (1 << b);
        return field;
    }

    /** Per-thread activations for one sample. */
    public final class Workspace {
        final int n = width * length;
        final double[] input = new double[features * length];
        final double[] projPre = new double[n], projHat = new double[n], projAff = new double[n], projOut = new double[n];
        final double[] projRstd = new double[groups];
        final double[][] h1 = new double[blocks][n], hat1 = new double[blocks][n], aff1 = new double[blocks][n],
                drop1 = new double[blocks][n], d1 = new double[blocks][n], h2 = new double[blocks][n],
                hat2 = new double[blocks][n], sum = new double[blocks][n], out = new double[blocks][n];
        final double[][] rstd1 = new double[blocks][groups], rstd2 = new double[blocks][groups];
        final double[] score = new double[length], attention = new double[length], pooled = new double[2 * width];
        final double[] trunkPre = new double[width], trunkDrop = new double[width], trunkOut = new double[width];
        final double[] logits = new double[heads.size()];
        // Backward scratch.
        final double[] gA = new double[n], gB = new double[n], gC = new double[n];
        final double[] gPooled = new double[2 * width], gTrunk = new double[width], gWeights = new double[length];

        double[] last() { return blocks == 0 ? projOut : out[blocks - 1]; }
    }

    public Workspace workspace() { return new Workspace(); }

    /**
     * Forward pass of one window. {@code window} is row-major [t * features + c] and already
     * normalised. With a non-null {@code random}, dropout is active (training mode).
     */
    public double[] forward(Workspace w, float[] window, SplittableRandom random) {
        int T = length, C = width;
        for (int t = 0; t < T; t++) for (int c = 0; c < features; c++) w.input[c * T + t] = window[t * features + c];
        conv(projW, projB, w.input, features, w.projPre, C, 1, 1);
        groupNorm(projGamma, projBeta, w.projPre, w.projHat, w.projAff, w.projRstd);
        for (int i = 0; i < w.n; i++) w.projOut[i] = gelu(w.projAff[i]);
        double[] x = w.projOut;
        double keep = 1.0 - dropout;
        for (int b = 0; b < blocks; b++) {
            int dilation = 1 << b;
            conv(c1W[b], c1B[b], x, C, w.h1[b], C, kernel, dilation);
            groupNorm(n1Gamma[b], n1Beta[b], w.h1[b], w.hat1[b], w.aff1[b], w.rstd1[b]);
            double[] drop = w.drop1[b], d = w.d1[b], aff = w.aff1[b];
            for (int i = 0; i < w.n; i++) {
                double mask = random == null || dropout <= 0 ? 1.0 : (random.nextDouble() < dropout ? 0.0 : 1.0 / keep);
                drop[i] = mask;
                d[i] = gelu(aff[i]) * mask;
            }
            conv(c2W[b], c2B[b], d, C, w.h2[b], C, kernel, dilation);
            double[] normed = w.gA;
            groupNorm(n2Gamma[b], n2Beta[b], w.h2[b], w.hat2[b], normed, w.rstd2[b]);
            double[] s = w.sum[b], o = w.out[b];
            for (int i = 0; i < w.n; i++) {
                s[i] = normed[i] + x[i];
                o[i] = gelu(s[i]);
            }
            x = o;
        }
        // Attention pool.
        double max = Double.NEGATIVE_INFINITY;
        for (int t = 0; t < T; t++) {
            double value = params[poolB];
            for (int c = 0; c < C; c++) value += params[poolW + c] * x[c * T + t];
            w.score[t] = value;
            max = Math.max(max, value);
        }
        double total = 0;
        for (int t = 0; t < T; t++) { w.attention[t] = Math.exp(w.score[t] - max); total += w.attention[t]; }
        for (int t = 0; t < T; t++) w.attention[t] /= total;
        for (int c = 0; c < C; c++) {
            double attended = 0, mean = 0;
            for (int t = 0; t < T; t++) { double v = x[c * T + t]; attended += v * w.attention[t]; mean += v; }
            w.pooled[c] = attended;
            w.pooled[C + c] = mean / T;
        }
        for (int o = 0; o < C; o++) {
            double value = params[trunkB + o];
            int base = trunkW + o * 2 * C;
            for (int i = 0; i < 2 * C; i++) value += params[base + i] * w.pooled[i];
            w.trunkPre[o] = value;
            double mask = random == null || dropout <= 0 ? 1.0 : (random.nextDouble() < dropout ? 0.0 : 1.0 / keep);
            w.trunkDrop[o] = mask;
            w.trunkOut[o] = gelu(value) * mask;
        }
        for (int h = 0; h < w.logits.length; h++) {
            double value = params[headB[h]];
            for (int i = 0; i < C; i++) value += params[headW[h] + i] * w.trunkOut[i];
            w.logits[h] = value;
        }
        return w.logits;
    }

    /** Backward pass for the sample last run through {@code w}; adds into {@code grad}. */
    public void backward(Workspace w, double[] dLogits, double[] grad) {
        int T = length, C = width;
        // Heads and trunk.
        double[] gTrunk = w.gTrunk;
        java.util.Arrays.fill(gTrunk, 0);
        for (int h = 0; h < dLogits.length; h++) {
            double g = dLogits[h];
            if (g == 0) continue;
            grad[headB[h]] += g;
            for (int i = 0; i < C; i++) {
                grad[headW[h] + i] += g * w.trunkOut[i];
                gTrunk[i] += g * params[headW[h] + i];
            }
        }
        double[] gPooled = w.gPooled;
        java.util.Arrays.fill(gPooled, 0);
        for (int o = 0; o < C; o++) {
            double g = gTrunk[o] * w.trunkDrop[o] * geluPrime(w.trunkPre[o]);
            if (g == 0) continue;
            grad[trunkB + o] += g;
            int base = trunkW + o * 2 * C;
            for (int i = 0; i < 2 * C; i++) {
                grad[base + i] += g * w.pooled[i];
                gPooled[i] += g * params[base + i];
            }
        }
        // Attention pool -> gradient on the last block output.
        double[] x = w.last();
        double[] gx = w.gB;
        double[] gWeights = w.gWeights;
        java.util.Arrays.fill(gWeights, 0);
        for (int c = 0; c < C; c++) {
            double attended = gPooled[c], mean = gPooled[C + c] / T;
            for (int t = 0; t < T; t++) {
                gx[c * T + t] = attended * w.attention[t] + mean;
                gWeights[t] += attended * x[c * T + t];
            }
        }
        double dot = 0;
        for (int t = 0; t < T; t++) dot += w.attention[t] * gWeights[t];
        for (int t = 0; t < T; t++) {
            double gScore = w.attention[t] * (gWeights[t] - dot);
            grad[poolB] += gScore;
            for (int c = 0; c < C; c++) {
                grad[poolW + c] += gScore * x[c * T + t];
                gx[c * T + t] += params[poolW + c] * gScore;
            }
        }
        // Residual blocks, last to first. gx holds d(out of block b).
        for (int b = blocks - 1; b >= 0; b--) {
            int dilation = 1 << b;
            double[] input = b == 0 ? w.projOut : w.out[b - 1];
            double[] gSum = w.gA;
            for (int i = 0; i < w.n; i++) gSum[i] = gx[i] * geluPrime(w.sum[b][i]);
            // Residual branch keeps gSum for the input; the other branch goes through norm2/conv2.
            double[] gH2 = w.gC;
            groupNormBackward(n2Gamma[b], n2Beta[b], w.hat2[b], w.rstd2[b], gSum, gH2, grad);
            double[] gD1 = gx; // reuse: gx is no longer needed once gSum exists
            java.util.Arrays.fill(gD1, 0);
            convBackward(c2W[b], c2B[b], w.d1[b], C, gH2, C, kernel, dilation, gD1, grad);
            double[] gAff1 = gH2; // reuse
            for (int i = 0; i < w.n; i++) gAff1[i] = gD1[i] * w.drop1[b][i] * geluPrime(w.aff1[b][i]);
            double[] gH1 = gD1; // reuse
            groupNormBackward(n1Gamma[b], n1Beta[b], w.hat1[b], w.rstd1[b], gAff1, gH1, grad);
            // d(input) = residual + conv1 backward.
            double[] gInput = gAff1; // reuse
            System.arraycopy(gSum, 0, gInput, 0, w.n);
            convBackward(c1W[b], c1B[b], input, C, gH1, C, kernel, dilation, gInput, grad);
            // Move the result into gx for the next (earlier) block.
            System.arraycopy(gInput, 0, gx, 0, w.n);
        }
        // Projection.
        double[] gAff = w.gA;
        for (int i = 0; i < w.n; i++) gAff[i] = gx[i] * geluPrime(w.projAff[i]);
        double[] gPre = w.gC;
        groupNormBackward(projGamma, projBeta, w.projHat, w.projRstd, gAff, gPre, grad);
        convBackward(projW, projB, w.input, features, gPre, C, 1, 1, null, grad);
    }

    /** Conv1d with zero "same" padding; output[o * T + t]. */
    private void conv(int weight, int bias, double[] in, int inC, double[] out, int outC, int k, int dilation) {
        int T = length;
        int padding = dilation * (k - 1) / 2;
        for (int o = 0; o < outC; o++) {
            int row = o * T;
            double b = params[bias + o];
            for (int t = 0; t < T; t++) out[row + t] = b;
            for (int i = 0; i < inC; i++) {
                int source = i * T;
                int base = weight + (o * inC + i) * k;
                for (int j = 0; j < k; j++) {
                    double wv = params[base + j];
                    int shift = j * dilation - padding;
                    int from = Math.max(0, -shift), to = Math.min(T, T - shift);
                    for (int t = from; t < to; t++) out[row + t] += wv * in[source + t + shift];
                }
            }
        }
    }

    private void convBackward(int weight, int bias, double[] in, int inC, double[] gOut, int outC, int k, int dilation,
                              double[] gIn, double[] grad) {
        int T = length;
        int padding = dilation * (k - 1) / 2;
        for (int o = 0; o < outC; o++) {
            int row = o * T;
            double gb = 0;
            for (int t = 0; t < T; t++) gb += gOut[row + t];
            grad[bias + o] += gb;
            for (int i = 0; i < inC; i++) {
                int source = i * T;
                int base = weight + (o * inC + i) * k;
                for (int j = 0; j < k; j++) {
                    int shift = j * dilation - padding;
                    int from = Math.max(0, -shift), to = Math.min(T, T - shift);
                    double gw = 0;
                    double wv = params[base + j];
                    if (gIn != null) {
                        for (int t = from; t < to; t++) {
                            double g = gOut[row + t];
                            gw += g * in[source + t + shift];
                            gIn[source + t + shift] += wv * g;
                        }
                    } else {
                        for (int t = from; t < to; t++) gw += gOut[row + t] * in[source + t + shift];
                    }
                    grad[base + j] += gw;
                }
            }
        }
    }

    private void groupNorm(int gamma, int beta, double[] in, double[] hat, double[] out, double[] rstd) {
        int T = length, per = width / groups, count = per * T;
        for (int g = 0; g < groups; g++) {
            int from = g * per * T, to = from + count;
            double sum = 0;
            for (int i = from; i < to; i++) sum += in[i];
            double mean = sum / count;
            double squares = 0;
            for (int i = from; i < to; i++) { double d = in[i] - mean; squares += d * d; }
            double scale = 1.0 / Math.sqrt(squares / count + GROUP_NORM_EPSILON);
            rstd[g] = scale;
            for (int c = g * per; c < (g + 1) * per; c++) {
                double gm = params[gamma + c], bt = params[beta + c];
                for (int t = 0; t < T; t++) {
                    int i = c * T + t;
                    hat[i] = (in[i] - mean) * scale;
                    out[i] = hat[i] * gm + bt;
                }
            }
        }
    }

    private void groupNormBackward(int gamma, int beta, double[] hat, double[] rstd, double[] gOut, double[] gIn, double[] grad) {
        int T = length, per = width / groups, count = per * T;
        for (int g = 0; g < groups; g++) {
            double sumG = 0, sumGHat = 0;
            for (int c = g * per; c < (g + 1) * per; c++) {
                double gm = params[gamma + c];
                double gGamma = 0, gBeta = 0;
                for (int t = 0; t < T; t++) {
                    int i = c * T + t;
                    gGamma += gOut[i] * hat[i];
                    gBeta += gOut[i];
                    double gHat = gOut[i] * gm;
                    sumG += gHat;
                    sumGHat += gHat * hat[i];
                }
                grad[gamma + c] += gGamma;
                grad[beta + c] += gBeta;
            }
            double scale = rstd[g] / count;
            for (int c = g * per; c < (g + 1) * per; c++) {
                double gm = params[gamma + c];
                for (int t = 0; t < T; t++) {
                    int i = c * T + t;
                    gIn[i] = scale * (count * gOut[i] * gm - sumG - hat[i] * sumGHat);
                }
            }
        }
    }

    /** Exact GELU, x * Phi(x), with the same erf as the inference port. */
    static double gelu(double x) {
        return 0.5 * x * (1.0 + erf(x * INV_SQRT2));
    }

    static double geluPrime(double x) {
        return 0.5 * (1.0 + erf(x * INV_SQRT2)) + x * Math.exp(-0.5 * x * x) * INV_SQRT_2PI;
    }

    /** Numerical Recipes erfcc, fractional error below 1.2e-7; identical to TemporalConvNet.erf. */
    static double erf(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * z);
        double erfc = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? 1.0 - erfc : erfc - 1.0;
    }
}
