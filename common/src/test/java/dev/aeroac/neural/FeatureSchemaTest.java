package dev.aeroac.neural;

import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.neural.inference.ModelFeature;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.FrameField;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The model input order is a contract between this module, the training pipeline and the service.
 * If this test fails, the newest ml/schema/feature_schema_v*.json and ModelFeature disagree and every model
 * trained against one of them would silently read the other's channels.
 */
class FeatureSchemaTest {
    @Test void javaSchemaMatchesTheCanonicalManifest() throws Exception {
        JsonObject manifest = manifest();
        assertEquals(FeatureEncoder.FEATURE_SCHEMA_VERSION, manifest.get("featureSchemaVersion").getAsInt());
        assertEquals(CombatFrame.SCHEMA_VERSION, manifest.get("rawSchemaVersion").getAsInt());
        JsonArray rawFields = manifest.getAsJsonArray("rawFields");
        assertEquals(FrameField.COUNT, rawFields.size(), "raw field count");
        for (int i = 0; i < rawFields.size(); i++) {
            assertEquals(rawFields.get(i).getAsString(), FrameField.values()[i].name(), "raw field " + i);
        }
        JsonArray values = manifest.getAsJsonArray("values");
        assertEquals(ModelFeature.VALUE_COUNT, values.size(), "value channel count");
        for (int i = 0; i < values.size(); i++) {
            JsonObject declared = values.get(i).getAsJsonObject();
            ModelFeature feature = ModelFeature.VALUES[i];
            assertEquals(declared.get("name").getAsString(), feature.name(), "channel " + i);
            assertEquals(declared.get("nullable").getAsBoolean(), feature.nullable(), feature.name());
            JsonArray clip = declared.getAsJsonArray("clip");
            assertEquals(clip.get(0).getAsDouble(), feature.low(), feature.name() + " low");
            assertEquals(clip.get(1).getAsDouble(), feature.high(), feature.name() + " high");
            String source = declared.get("source").getAsString();
            if (source.startsWith("derived.")) assertTrue(feature.derived(), feature.name());
            else assertEquals(source, feature.field().name(), feature.name());
            String transform = declared.has("transform") ? declared.get("transform").getAsString() : "none";
            assertEquals(transform, feature.transform().wireName(), feature.name() + " transform");
        }
    }

    @Test void channelNamesPlaceEveryMaskAfterEveryValue() {
        String[] names = ModelFeature.channelNames();
        assertEquals(ModelFeature.FEATURE_COUNT, names.length);
        List<String> masks = new ArrayList<>();
        for (ModelFeature feature : ModelFeature.VALUES) {
            assertEquals(feature.name(), names[feature.ordinal()]);
            if (feature.nullable()) masks.add(feature.name() + "_MASK");
        }
        assertEquals(masks, Arrays.asList(names).subList(ModelFeature.VALUE_COUNT, names.length));
        assertEquals(ModelFeature.VALUE_COUNT + ModelFeature.MASK_COUNT, ModelFeature.FEATURE_COUNT);
    }

    @Test void modelNeverSeesIdentityPositionOrDeterministicCheckOutput() {
        for (ModelFeature feature : ModelFeature.VALUES) {
            FrameField field = feature.field();
            if (field == null) continue;
            assertFalse(field.name().contains("ENTITY_ID"), field.name());
            assertFalse(field.name().contains("TRANSACTION"), field.name());
            assertFalse(field.name().contains("EVIDENCE"), field.name());
            assertFalse(field.name().equals("PLAYER_X") || field.name().equals("PLAYER_Y") || field.name().equals("PLAYER_Z"), field.name());
            assertFalse(field.name().equals("SERVER_TICK") || field.name().equals("CLIENT_PROTOCOL_VERSION"), field.name());
        }
    }

    @Test void unknownValuesEncodeAsZeroWithAClearedMask() {
        CombatFrame frame = frame(1, builder -> { });
        float[] encoded = FeatureEncoder.encode(new CombatFrame[]{frame});
        assertEquals(ModelFeature.FEATURE_COUNT, encoded.length);
        int mask = ModelFeature.VALUE_COUNT;
        for (ModelFeature feature : ModelFeature.VALUES) {
            if (!feature.nullable()) continue;
            assertEquals(0f, encoded[feature.ordinal()], feature.name());
            assertEquals(0f, encoded[mask++], feature.name() + " mask");
        }
    }

    @Test void valuesAreClippedIntoTheDeclaredRangeButStayKnown() {
        CombatFrame frame = frame(1, values -> {
            values[FrameField.DELTA_YAW.ordinal()] = 4000;
            values[FrameField.DISTANCE_TO_TARGET.ordinal()] = -3;
            values[FrameField.PING_MS.ordinal()] = 99_999;
        });
        float[] encoded = FeatureEncoder.encode(new CombatFrame[]{frame});
        assertEquals(180f, encoded[ModelFeature.DELTA_YAW.ordinal()]);
        assertEquals(0f, encoded[ModelFeature.DISTANCE_TO_TARGET.ordinal()]);
        assertEquals(1000f, encoded[ModelFeature.PING_MS.ordinal()]);
        assertEquals(1f, encoded[maskIndex(ModelFeature.DELTA_YAW)]);
        assertEquals(1f, encoded[maskIndex(ModelFeature.PING_MS)]);
    }

    @Test void targetSwitchSentinelIsUnknownNotZero() {
        CombatFrame absent = frame(1, values -> values[FrameField.TICKS_SINCE_TARGET_SWITCH.ordinal()] = -1);
        float[] encoded = FeatureEncoder.encode(new CombatFrame[]{absent});
        assertEquals(0f, encoded[ModelFeature.TICKS_SINCE_TARGET_SWITCH.ordinal()]);
        assertEquals(0f, encoded[maskIndex(ModelFeature.TICKS_SINCE_TARGET_SWITCH)]);
        CombatFrame present = frame(1, values -> values[FrameField.TICKS_SINCE_TARGET_SWITCH.ordinal()] = 0);
        float[] known = FeatureEncoder.encode(new CombatFrame[]{present});
        assertEquals(1f, known[maskIndex(ModelFeature.TICKS_SINCE_TARGET_SWITCH)]);
    }

    @Test void derivedDeltasNeedTheSameTargetInsideTheWindow() {
        CombatFrame first = tracking(1, 7, 10, 2, 4.0);
        CombatFrame second = tracking(2, 7, 14, 3, 3.5);
        CombatFrame switched = tracking(3, 9, 20, 1, 3.0);
        float[] encoded = FeatureEncoder.encode(new CombatFrame[]{first, second, switched});
        int stride = ModelFeature.FEATURE_COUNT;
        // Index 0 has no predecessor inside the window, so every window-local derivation is unknown.
        assertEquals(0f, encoded[maskIndex(ModelFeature.TARGET_ANGULAR_VELOCITY_YAW)]);
        assertEquals(1f, encoded[stride + maskIndex(ModelFeature.TARGET_ANGULAR_VELOCITY_YAW)]);
        assertEquals(4f, encoded[stride + ModelFeature.TARGET_ANGULAR_VELOCITY_YAW.ordinal()], 1.0E-5);
        assertEquals(1f, encoded[stride + ModelFeature.TARGET_ANGULAR_VELOCITY_PITCH.ordinal()], 1.0E-5);
        assertEquals(-0.5f, encoded[stride + ModelFeature.TARGET_RADIAL_SPEED.ordinal()], 1.0E-5);
        // A target switch breaks the derivation even though both samples have a target.
        assertEquals(0f, encoded[2 * stride + maskIndex(ModelFeature.TARGET_ANGULAR_VELOCITY_YAW)]);
        assertEquals(0f, encoded[2 * stride + maskIndex(ModelFeature.TARGET_RADIAL_SPEED)]);
    }

    @Test void yawDerivationWrapsAcrossTheDiscontinuity() {
        CombatFrame first = tracking(1, 7, 179, 0, 4.0);
        CombatFrame second = tracking(2, 7, -179, 0, 4.0);
        float[] encoded = FeatureEncoder.encode(new CombatFrame[]{first, second});
        assertEquals(2f, encoded[ModelFeature.FEATURE_COUNT + ModelFeature.TARGET_ANGULAR_VELOCITY_YAW.ordinal()], 1.0E-4);
    }

    @Test void angularRadiusAndErrorRatioUseTheCompensatedBox() {
        CombatFrame frame = frame(1, values -> {
            values[FrameField.TARGET_PRESENT.ordinal()] = 1;
            values[FrameField.DISTANCE_TO_TARGET.ordinal()] = 3;
            values[FrameField.TARGET_MIN_X.ordinal()] = 0;
            values[FrameField.TARGET_MAX_X.ordinal()] = 0.6;
            values[FrameField.TARGET_MIN_Z.ordinal()] = 0;
            values[FrameField.TARGET_MAX_Z.ordinal()] = 0.6;
            values[FrameField.AIM_ERROR_TOTAL.ordinal()] = 5.7106;
        });
        float[] encoded = FeatureEncoder.encode(new CombatFrame[]{frame});
        double expected = Math.toDegrees(Math.atan2(0.3, 3));
        assertEquals(expected, encoded[ModelFeature.TARGET_ANGULAR_RADIUS.ordinal()], 1.0E-4);
        assertEquals(1.0, encoded[ModelFeature.AIM_ERROR_RATIO.ordinal()], 1.0E-3);
    }

    @Test void alignmentIsUnknownWhenEitherRotationIsStill() {
        CombatFrame first = tracking(1, 7, 10, 0, 4.0);
        CombatFrame still = tracking(2, 7, 10, 0, 4.0);
        float[] encoded = FeatureEncoder.encode(new CombatFrame[]{first, still});
        assertEquals(0f, encoded[ModelFeature.FEATURE_COUNT + maskIndex(ModelFeature.ROTATION_TARGET_ALIGNMENT)]);
    }

    @Test void emptyOrHolePunchedWindowsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FeatureEncoder.encode(new CombatFrame[0]));
        assertThrows(IllegalArgumentException.class, () -> FeatureEncoder.encode(null));
        assertThrows(IllegalArgumentException.class, () -> FeatureEncoder.encode(new CombatFrame[]{null}));
    }

    static int maskIndex(ModelFeature feature) {
        int index = ModelFeature.VALUE_COUNT;
        for (ModelFeature candidate : ModelFeature.VALUES) {
            if (candidate == feature) return index;
            if (candidate.nullable()) index++;
        }
        throw new IllegalArgumentException(feature.name());
    }

    static CombatFrame frame(long tick, java.util.function.Consumer<double[]> fill) {
        double[] values = new double[FrameField.COUNT];
        Arrays.fill(values, Double.NaN);
        values[FrameField.SEGMENT_START.ordinal()] = 0;
        values[FrameField.TARGET_PRESENT.ordinal()] = 0;
        values[FrameField.TARGET_SWITCH.ordinal()] = 0;
        fill.accept(values);
        return new CombatFrame(tick, tick * 50_000_000L, values);
    }

    /** A sample that is tracking entity {@code id} with the given required aim and distance. */
    private static CombatFrame tracking(long tick, int id, double targetYaw, double targetPitch, double distance) {
        return frame(tick, values -> {
            values[FrameField.TARGET_PRESENT.ordinal()] = 1;
            values[FrameField.TARGET_ENTITY_ID.ordinal()] = id;
            values[FrameField.TARGET_YAW.ordinal()] = targetYaw;
            values[FrameField.TARGET_PITCH.ordinal()] = targetPitch;
            values[FrameField.DISTANCE_TO_TARGET.ordinal()] = distance;
            values[FrameField.TARGET_SWITCH.ordinal()] = 0;
            values[FrameField.DELTA_YAW.ordinal()] = 0;
            values[FrameField.DELTA_PITCH.ordinal()] = 0;
        });
    }

    static JsonObject manifest() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        assertNotNull(root, "repository root not found from " + Path.of("").toAbsolutePath());
        return new JsonParser().parse(Files.readString(newestManifest(root))).getAsJsonObject();
    }

    /** Newest schema version wins, so bumping the contract does not need a test edit. */
    static Path newestManifest(Path root) throws Exception {
        Path directory = root.resolve("ml/schema");
        assertTrue(Files.isDirectory(directory), "missing " + directory);
        try (var files = Files.list(directory)) {
            Path newest = files
                    .filter(path -> path.getFileName().toString().matches("feature_schema_v\\d+\\.json"))
                    .max(java.util.Comparator.comparingInt(FeatureSchemaTest::versionOf))
                    .orElse(null);
            assertNotNull(newest, "no feature_schema_v*.json under " + directory);
            return newest;
        }
    }

    static int versionOf(Path path) {
        return Integer.parseInt(path.getFileName().toString().replaceAll("\\D+", ""));
    }
}
