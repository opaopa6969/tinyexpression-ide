package org.unlaxer.tinyexpression.ide;

import java.math.BigDecimal;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SimpleExpressionEvaluator}.
 *
 * Pins parser boundary behaviours (trailing garbage, unary minus, the
 * Long/BigDecimal return-type split) that the e2e tests never reach
 * directly. A refactor of the recursive-descent parser could silently
 * break any of these.
 */
public class SimpleExpressionEvaluatorTest {

    /**
     * After a successful parse the parser position must be at end-of-input;
     * trailing junk must be rejected. Guards the {@code pos < input.length()}
     * post-check that is easy to drop on refactor.
     */
    @Test
    public void testRejectsTrailingGarbage() {
        try {
            SimpleExpressionEvaluator.evaluate("1 + 2 abc");
            fail("expected IllegalArgumentException for trailing characters");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Unexpected character"));
        }
    }

    /**
     * Unary minus binds to the following primary, and the return type
     * splits on integral vs fractional value: an integral result comes
     * back as {@link Long} (via {@code longValueExact}), a fractional one
     * as {@link BigDecimal}. Both branches are untested.
     */
    @Test
    public void testUnaryMinusAndReturnTypeBoundary() {
        Object neg = SimpleExpressionEvaluator.evaluate("-(1 + 2)");
        assertEquals(-3L, neg);

        Object integral = SimpleExpressionEvaluator.evaluate("1.5 + 1.5");
        assertTrue("integral result should be Long, was " + integral.getClass(),
                integral instanceof Long);
        assertEquals(3L, integral);

        Object fractional = SimpleExpressionEvaluator.evaluate("10 / 4");
        assertTrue("fractional result should be BigDecimal, was " + fractional.getClass(),
                fractional instanceof BigDecimal);
        assertEquals(new BigDecimal("2.5"), fractional);
    }
}
