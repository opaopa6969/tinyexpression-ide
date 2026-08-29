package org.unlaxer.tinyexpression.ide;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-pass variable substitution for TinyExpression formulas.
 * <p>
 * Replaces every {@code $varName} token in the formula with the corresponding
 * value from the supplied map in <em>one</em> pass, so that values containing
 * {@code $}-tokens are treated as literals and never re-interpreted in a
 * subsequent pass. This avoids the double-substitution bug that arises when
 * variables are substituted sequentially via {@link String#replaceAll}.
 * <p>
 * Variable names follow the Java identifier pattern
 * {@code [A-Za-z][A-Za-z0-9_]*}; the matcher only replaces a token when the
 * character following {@code $varName} is not an identifier character, so that
 * {@code $x} does not match inside {@code $xy}.
 */
public final class VariableSubstituter {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$([A-Za-z][A-Za-z0-9_]*)");

    private VariableSubstituter() {}

    /**
     * Substitute variables in {@code formula} using {@code variables}.
     *
     * @param formula   the formula containing {@code $varName} tokens
     * @param variables map of variable name to raw string value; values are
     *                  inserted as literals (never re-interpreted)
     * @return the formula with all known {@code $varName} tokens replaced;
     *         unknown tokens are left as-is
     */
    public static String substitute(String formula, Map<String, String> variables) {
        if (formula == null || variables == null || variables.isEmpty()) {
            return formula;
        }
        Matcher m = VAR_PATTERN.matcher(formula);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            String value = variables.get(name);
            if (value != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
