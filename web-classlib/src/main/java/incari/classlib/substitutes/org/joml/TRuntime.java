package incari.classlib.substitutes.org.joml;

import java.text.NumberFormat;

/**
 * JOML's {@code Runtime}, written fresh for the browser build. JOML's own looks up
 * {@code java.lang.Float} and friends with {@code Class.forName} in its static initializer, to find
 * out whether the JDK is newer than 1.3; TeaVM doesn't find those by name, so the first
 * {@code org.joml.Math} call (vertex sorting, matrices) failed. Every method it probes for exists
 * here, so the answers are constants.
 *
 * <p>Number formatting is only for JOML's {@code toString}: plain {@code NumberFormat} output.
 */
@SuppressWarnings("TypeName") // The name is JOML's class, as the class library maps it.
public final class TRuntime {

    public static final boolean HAS_floatToRawIntBits = true;
    public static final boolean HAS_doubleToRawLongBits = true;
    public static final boolean HAS_Long_rotateLeft = true;
    /**
     * JOML uses {@code Math.fma} only when asked to ({@code joml.useMathFma}), which the game never
     * is, on the desktop either. A constant, not the property: read at run time it kept TeaVM from
     * folding the branch and inlining {@code org.joml.Math.fma}, which every vector and matrix
     * transform calls (the lobby's largest self time, ADR 0027). Hot path.
     */
    public static final boolean HAS_Math_fma = false;

    private TRuntime() {}

    public static int floatToIntBits(float value) {
        return Float.floatToRawIntBits(value);
    }

    public static long doubleToLongBits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    public static String formatNumbers(String text) {
        return text;
    }

    public static String format(double value, NumberFormat format) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.toString(value);
        }
        return format.format(value);
    }

    public static boolean equals(float a, float b, float delta) {
        return Float.floatToIntBits(a) == Float.floatToIntBits(b) || Math.abs(a - b) <= delta;
    }

    public static boolean equals(double a, double b, double delta) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b) || Math.abs(a - b) <= delta;
    }
}
