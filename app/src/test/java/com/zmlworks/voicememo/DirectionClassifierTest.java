package com.zmlworks.voicememo;

import static com.zmlworks.voicememo.DirectionClassifier.Dir.*;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DirectionClassifierTest {
    static final float D = 42; // 14 dp @ 3x

    static DirectionClassifier.Dir at(double deg, double len) {
        double r = Math.toRadians(deg);
        return DirectionClassifier.classify((float) (Math.cos(r) * len), (float) (Math.sin(r) * len), D);
    }

    @Test public void belowDecisionDistanceIsUndecided() {
        assertEquals(UNDECIDED, DirectionClassifier.classify(0, 0, D));
        assertEquals(UNDECIDED, DirectionClassifier.classify(30, 29, D));
        assertEquals(UNDECIDED, at(0, 41.9));
        assertEquals(HORIZONTAL, at(0, 42.1));
    }

    @Test public void bandsInAllFourQuadrants() {
        for (int q = 0; q < 4; q++) {
            double b = q * 90;
            // horizontal axis at 0° / 180°, vertical at 90° / 270°
            boolean horizAxis = q % 2 == 0;
            for (double d = -29.5; d <= 29.5; d += 0.5) {
                assertEquals("deg " + (b + d), horizAxis ? HORIZONTAL : VERTICAL, at(b + d, 60));
            }
            for (double d = 31; d <= 59; d += 0.5) {
                assertEquals("deg " + (b + d), DIAGONAL, at(b + d, 60));
            }
        }
    }

    @Test public void boundariesAndExtremes() {
        assertEquals(HORIZONTAL, DirectionClassifier.classify(100, 0, D));
        assertEquals(HORIZONTAL, DirectionClassifier.classify(-100, 1, D));
        assertEquals(VERTICAL, DirectionClassifier.classify(0, -100, D));
        assertEquals(VERTICAL, DirectionClassifier.classify(2, 100, D));
        assertEquals(DIAGONAL, DirectionClassifier.classify(70, 70, D));
        assertEquals(DIAGONAL, DirectionClassifier.classify(-70, 70, D));
        assertEquals(UNDECIDED, DirectionClassifier.classify(Float.NaN, 100, D));
        // custom band
        assertEquals(HORIZONTAL, DirectionClassifier.classify(100, 70, D, 40)); // ~35°
        assertEquals(DIAGONAL, DirectionClassifier.classify(100, 70, D, 30));
    }
}
