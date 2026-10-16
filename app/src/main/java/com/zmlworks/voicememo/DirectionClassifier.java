package com.zmlworks.voicememo;

/**
 * Classifies the initial direction of a drag from the first few dp of movement.
 * Angle is measured from the horizontal axis (0° = pure horizontal, 90° = pure vertical).
 * Within {@code bandDeg} of horizontal = scroll, within {@code bandDeg} of vertical = select, otherwise a no-op.
 */
final class DirectionClassifier {
    private DirectionClassifier() {}

    enum Dir { UNDECIDED, HORIZONTAL, VERTICAL, DIAGONAL }

    /** Default band half-width in degrees. */
    static final double BAND_DEG = 30;
    /** Default decision distance in dp. */
    static final float DECIDE_DP = 14;

    static Dir classify(float dx, float dy, float decidePx, double bandDeg) {
        if (Float.isNaN(dx) || Float.isNaN(dy)) return Dir.UNDECIDED;
        if ((double) dx * dx + (double) dy * dy < (double) decidePx * decidePx) return Dir.UNDECIDED;
        double a = Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx)));
        if (a <= bandDeg) return Dir.HORIZONTAL;
        if (a >= 90 - bandDeg) return Dir.VERTICAL;
        return Dir.DIAGONAL;
    }

    static Dir classify(float dx, float dy, float decidePx) {
        return classify(dx, dy, decidePx, BAND_DEG);
    }
}
