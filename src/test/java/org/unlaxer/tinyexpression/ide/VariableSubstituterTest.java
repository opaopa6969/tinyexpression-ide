package org.unlaxer.tinyexpression.ide;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link VariableSubstituter}.
 *
 * Pins single-pass substitution boundary behaviours that are easy to
 * regress (prefix matching, literal treatment of $-bearing values) but
 * are not exercised by the e2e {@link McpEndpointTest}.
 */
public class VariableSubstituterTest {

    /**
     * A {@code $var} token must only match a whole identifier; {@code $x}
     * must not be substituted inside {@code $xy}. The javadoc promises this
     * (the matcher requires a non-identifier char after the name), but it
     * is not asserted anywhere — a regex tweak would silently break it.
     */
    @Test
    public void testSubstituteDoesNotMatchPrefixVariable() {
        Map<String, String> vars = new HashMap<>();
        vars.put("x", "99");

        assertEquals("99", VariableSubstituter.substitute("$x", vars));
        assertEquals("$xy", VariableSubstituter.substitute("$xy", vars));
        assertEquals("99 + $xy", VariableSubstituter.substitute("$x + $xy", vars));
    }

    /**
     * Variable values are inserted verbatim: {@code $} and {@code \} in a
     * value must NOT be interpreted as regex back-references by
     * {@link java.util.regex.Matcher#appendReplacement}. This relies on
     * {@link java.util.regex.Matcher#quoteReplacement}; dropping it would
     * make a value like {@code $1} throw "group 1 not found".
     */
    @Test
    public void testSubstituteTreatsValueAsLiteral() {
        Map<String, String> vars = new HashMap<>();
        vars.put("x", "$1");
        vars.put("y", "\\");

        assertEquals("$1 + \\", VariableSubstituter.substitute("$x + $y", vars));
    }
}
